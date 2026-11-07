-- ==============================================================================
-- Migration: Unified search (OpenSpec change add-unified-search)
-- Accent-insensitive, typo-tolerant search for profiles, posts, subjects and
-- non-image post files through authenticated-only RPCs that enforce post
-- visibility, bans and two-way blocks on the server.
-- Requires the friendship helpers from 20261027_friendship_rpc.sql.
-- ==============================================================================

begin;

do $$
begin
    if to_regprocedure('studyhub_private.friend_actor()') is null
       or to_regprocedure('studyhub_private.friend_peer_available(uuid,uuid)') is null then
        raise exception 'search preflight failed: friendship helpers are missing';
    end if;
end;
$$;

create schema if not exists extensions;
create extension if not exists pg_trgm with schema extensions;
create extension if not exists unaccent with schema extensions;

do $$
begin
    if exists (
        select 1 from pg_extension e join pg_namespace n on n.oid = e.extnamespace
        where e.extname in ('pg_trgm', 'unaccent') and n.nspname <> 'extensions'
    ) then
        raise exception 'search preflight failed: pg_trgm and unaccent must live in schema extensions';
    end if;
end;
$$;

-- ------------------------------------------------------------------------------
-- Normalization helpers (IMMUTABLE so they can back expression indexes).
-- unaccent itself is STABLE; the dictionary-qualified call is pinned here.
-- Reindex the *_trgm_idx indexes after upgrading the unaccent rules.
-- ------------------------------------------------------------------------------
create or replace function studyhub_private.search_norm(p_value text)
returns text
language sql
immutable
strict
parallel safe
set search_path = ''
as $$
    select btrim(regexp_replace(
        regexp_replace(
            lower(extensions.unaccent('extensions.unaccent'::regdictionary, normalize(p_value, NFC))),
            '[_./-]+', ' ', 'g'),
        '\s+', ' ', 'g'))
$$;

-- Same expression as search_norm after dropping the extension. It is inlined
-- rather than calling search_norm by name: index expressions run as the
-- writing role, which has no USAGE on schema studyhub_private.
create or replace function studyhub_private.search_file_norm(p_value text)
returns text
language sql
immutable
strict
parallel safe
set search_path = ''
as $$
    select btrim(regexp_replace(
        regexp_replace(
            lower(extensions.unaccent('extensions.unaccent'::regdictionary,
                normalize(regexp_replace(p_value, '\.[A-Za-z0-9]{1,8}$', ''), NFC))),
            '[_./-]+', ' ', 'g'),
        '\s+', ' ', 'g'))
$$;

create or replace function studyhub_private.search_like_escape(p_value text)
returns text
language sql
immutable
strict
parallel safe
set search_path = ''
as $$
    select replace(replace(replace(p_value, '\', '\\'), '%', '\%'), '_', '\_')
$$;

-- Validates paging input and returns the normalized query and page window.
create or replace function studyhub_private.search_input(
    p_query text, p_limit integer, p_offset integer,
    out q text, out q_like text, out q_raw text, out page_size integer)
language plpgsql
immutable
set search_path = ''
as $$
begin
    if p_query is null or p_limit is null or p_offset is null
       or p_limit <= 0 or p_offset < 0 or char_length(p_query) > 100 then
        raise exception 'invalid search input' using errcode = 'F0001';
    end if;
    q := coalesce(studyhub_private.search_norm(p_query), '');
    q_like := studyhub_private.search_like_escape(q);
    q_raw := lower(btrim(p_query));
    page_size := greatest(least(p_limit, 50, 100 - p_offset), 0);
end;
$$;

-- Mirrors PostTypeUtils.parse: "<category>:<visibility>" or a legacy single value.
-- SQL-standard body: names are bound at creation (no search_path exposure) and
-- the planner can inline it into the search queries.
create or replace function studyhub_private.post_visibility(p_type text)
returns text
language sql
immutable
parallel safe
return case pg_catalog.lower(pg_catalog.btrim(
        case when pg_catalog.strpos(coalesce(p_type, ''), ':') > 0 then pg_catalog.split_part(p_type, ':', 2)
             else coalesce(p_type, '') end, E' \t\r\n'))
    when 'friends' then 'friends'
    when 'private' then 'private'
    else 'public'
end;

-- ------------------------------------------------------------------------------
-- Trigram expression indexes. Queries must use the same expressions.
-- ------------------------------------------------------------------------------
create index if not exists profiles_username_trgm_idx
    on public.profiles using gin (studyhub_private.search_norm(username) extensions.gin_trgm_ops);
create index if not exists profiles_display_name_trgm_idx
    on public.profiles using gin (studyhub_private.search_norm(display_name) extensions.gin_trgm_ops);
create index if not exists posts_title_trgm_idx
    on public.posts using gin (studyhub_private.search_norm(title) extensions.gin_trgm_ops);
create index if not exists posts_content_trgm_idx
    on public.posts using gin (studyhub_private.search_norm(content) extensions.gin_trgm_ops);
create index if not exists subjects_name_trgm_idx
    on public.subjects using gin (studyhub_private.search_norm(name) extensions.gin_trgm_ops);
create index if not exists post_files_file_name_trgm_idx
    on public.post_files using gin (studyhub_private.search_file_norm(file_name) extensions.gin_trgm_ops);

-- ------------------------------------------------------------------------------
-- RPCs. Ranking: tier (1 prefix, 2 contains, 3 fuzzy), exact-diacritic match,
-- word similarity, tab secondary key, id. Pages are capped at 50 items and
-- the first 100 ranked results.
-- ------------------------------------------------------------------------------
create or replace function public.search_profiles(p_query text, p_limit integer default 20, p_offset integer default 0)
returns jsonb
language plpgsql
security definer
set search_path = ''
set pg_trgm.word_similarity_threshold = '0.5'
as $$
declare
    v_actor uuid := studyhub_private.friend_actor();
    v_q text; v_like text; v_raw text; v_size integer; v_contains text; v_fuzzy boolean;
    v_result jsonb;
begin
    select i.q, i.q_like, i.q_raw, i.page_size into v_q, v_like, v_raw, v_size
    from studyhub_private.search_input(p_query, p_limit, p_offset) i;
    if char_length(v_q) < 2 or v_size = 0 then
        return jsonb_build_object('items', '[]'::jsonb, 'has_more', false);
    end if;
    v_contains := '%' || v_like || '%';
    -- Trigrams ignore symbols, so queries such as "100%" or "c++" stay literal.
    v_fuzzy := char_length(v_q) >= 4 and v_q !~ '[^[:alnum:][:space:]]';

    with hits as (
        select p.id, p.username, p.display_name, p.avatar_url,
               studyhub_private.search_norm(p.username) as n_user,
               studyhub_private.search_norm(p.display_name) as n_display
        from public.profiles p
        where not p.is_banned
          and (p.id = v_actor or not exists (
                select 1 from public.blocks b
                where (b.blocker_id = v_actor and b.blocked_id = p.id)
                   or (b.blocker_id = p.id and b.blocked_id = v_actor)))
          and (studyhub_private.search_norm(p.username) like v_contains escape '\'
               or studyhub_private.search_norm(p.display_name) like v_contains escape '\'
               or (v_fuzzy and v_q operator(extensions.<%) studyhub_private.search_norm(p.username))
               or (v_fuzzy and v_q operator(extensions.<%) studyhub_private.search_norm(p.display_name)))
    ), ordered as (
        select h.*, row_number() over (order by
            case when h.n_user like v_like || '%' escape '\' or h.n_display like v_like || '%' escape '\' then 1
                 when h.n_user like v_contains escape '\' or h.n_display like v_contains escape '\' then 2
                 else 3 end,
            (strpos(lower(h.username), v_raw) > 0 or strpos(lower(h.display_name), v_raw) > 0) desc,
            greatest(extensions.word_similarity(v_q, h.n_user), extensions.word_similarity(v_q, h.n_display)) desc,
            h.username, h.id) as rn
        from hits h
    )
    select jsonb_build_object(
        'items', coalesce(jsonb_agg(jsonb_build_object(
                'id', o.id, 'username', o.username, 'display_name', o.display_name, 'avatar_url', o.avatar_url)
            order by o.rn) filter (where o.rn > p_offset and o.rn <= p_offset + v_size), '[]'::jsonb),
        'has_more', coalesce(bool_or(o.rn > p_offset + v_size), false) and p_offset + v_size < 100)
    into v_result
    from ordered o
    where o.rn <= p_offset + v_size + 1;
    return v_result;
end;
$$;

create or replace function public.search_subjects(p_query text, p_limit integer default 20, p_offset integer default 0)
returns jsonb
language plpgsql
security definer
set search_path = ''
set pg_trgm.word_similarity_threshold = '0.5'
as $$
declare
    v_actor uuid := studyhub_private.friend_actor();
    v_q text; v_like text; v_raw text; v_size integer; v_contains text; v_fuzzy boolean;
    v_result jsonb;
begin
    select i.q, i.q_like, i.q_raw, i.page_size into v_q, v_like, v_raw, v_size
    from studyhub_private.search_input(p_query, p_limit, p_offset) i;
    if char_length(v_q) < 2 or v_size = 0 then
        return jsonb_build_object('items', '[]'::jsonb, 'has_more', false);
    end if;
    v_contains := '%' || v_like || '%';
    -- Trigrams ignore symbols, so queries such as "100%" or "c++" stay literal.
    v_fuzzy := char_length(v_q) >= 4 and v_q !~ '[^[:alnum:][:space:]]';

    with hits as (
        select s.id, s.name, s.description, s.is_active, s.created_at,
               studyhub_private.search_norm(s.name) as n_name,
               studyhub_private.search_norm(s.description) as n_desc
        from public.subjects s
        where s.is_active
          and (studyhub_private.search_norm(s.name) like v_contains escape '\'
               or studyhub_private.search_norm(s.description) like v_contains escape '\'
               or (v_fuzzy and v_q operator(extensions.<%) studyhub_private.search_norm(s.name)))
    ), ordered as (
        select h.*, row_number() over (order by
            case when h.n_name like v_like || '%' escape '\' or h.n_desc like v_like || '%' escape '\' then 1
                 when h.n_name like v_contains escape '\' or h.n_desc like v_contains escape '\' then 2
                 else 3 end,
            (strpos(lower(h.name), v_raw) > 0 or strpos(lower(coalesce(h.description, '')), v_raw) > 0) desc,
            extensions.word_similarity(v_q, h.n_name) desc,
            h.name, h.id) as rn
        from hits h
    )
    select jsonb_build_object(
        'items', coalesce(jsonb_agg(jsonb_build_object(
                'id', o.id, 'name', o.name, 'description', o.description,
                'is_active', o.is_active, 'created_at', o.created_at)
            order by o.rn) filter (where o.rn > p_offset and o.rn <= p_offset + v_size), '[]'::jsonb),
        'has_more', coalesce(bool_or(o.rn > p_offset + v_size), false) and p_offset + v_size < 100)
    into v_result
    from ordered o
    where o.rn <= p_offset + v_size + 1;
    return v_result;
end;
$$;

create or replace function public.search_posts(p_query text, p_limit integer default 20, p_offset integer default 0)
returns jsonb
language plpgsql
security definer
set search_path = ''
set pg_trgm.word_similarity_threshold = '0.5'
as $$
declare
    v_actor uuid := studyhub_private.friend_actor();
    v_q text; v_like text; v_raw text; v_size integer; v_contains text; v_fuzzy boolean;
    v_result jsonb;
begin
    select i.q, i.q_like, i.q_raw, i.page_size into v_q, v_like, v_raw, v_size
    from studyhub_private.search_input(p_query, p_limit, p_offset) i;
    if char_length(v_q) < 2 or v_size = 0 then
        return jsonb_build_object('items', '[]'::jsonb, 'has_more', false);
    end if;
    v_contains := '%' || v_like || '%';
    -- Trigrams ignore symbols, so queries such as "100%" or "c++" stay literal.
    v_fuzzy := char_length(v_q) >= 4 and v_q !~ '[^[:alnum:][:space:]]';

    -- Visibility: the author sees everything; others need an author who is not
    -- banned, no block either way, and public visibility or an accepted
    -- friendship. Friend and block sets are computed once per call.
    with viewer_friends as materialized (
        select case when f.requester_id = v_actor then f.addressee_id else f.requester_id end as peer_id
        from public.friendships f
        where f.status = 'accepted' and (f.requester_id = v_actor or f.addressee_id = v_actor)
    ), blocked as materialized (
        select b.blocked_id as peer_id from public.blocks b where b.blocker_id = v_actor
        union
        select b.blocker_id from public.blocks b where b.blocked_id = v_actor
    ), hits as (
        select p.id, p.title, p.content, p.created_at,
               studyhub_private.search_norm(p.title) as n_title,
               studyhub_private.search_norm(p.content) as n_content
        from public.posts p
        join public.profiles a on a.id = p.author_id
        where (studyhub_private.search_norm(p.title) like v_contains escape '\'
               or studyhub_private.search_norm(p.content) like v_contains escape '\'
               or (v_fuzzy and v_q operator(extensions.<%) studyhub_private.search_norm(p.title)))
          and (p.author_id = v_actor
               or (not a.is_banned
                   and not exists (select 1 from blocked x where x.peer_id = p.author_id)
                   and case studyhub_private.post_visibility(p.type)
                       when 'public' then true
                       when 'friends' then exists (select 1 from viewer_friends vf where vf.peer_id = p.author_id)
                       else false
                   end))
    ), ordered as (
        select h.*, row_number() over (order by
            case when h.n_title like v_like || '%' escape '\' or h.n_content like v_like || '%' escape '\' then 1
                 when h.n_title like v_contains escape '\' or h.n_content like v_contains escape '\' then 2
                 else 3 end,
            (strpos(lower(h.title), v_raw) > 0 or strpos(lower(coalesce(h.content, '')), v_raw) > 0) desc,
            extensions.word_similarity(v_q, h.n_title) desc,
            h.created_at desc, h.id) as rn
        from hits h
    )
    -- Build the row JSON only for the returned page.
    select jsonb_build_object(
        'items', coalesce(jsonb_agg(to_jsonb(p) order by o.rn)
            filter (where o.rn > p_offset and o.rn <= p_offset + v_size), '[]'::jsonb),
        'has_more', coalesce(bool_or(o.rn > p_offset + v_size), false) and p_offset + v_size < 100)
    into v_result
    from ordered o
    join public.posts p on p.id = o.id
    where o.rn <= p_offset + v_size + 1;
    return v_result;
end;
$$;

create or replace function public.search_documents(p_query text, p_limit integer default 20, p_offset integer default 0)
returns jsonb
language plpgsql
security definer
set search_path = ''
set pg_trgm.word_similarity_threshold = '0.5'
as $$
declare
    v_actor uuid := studyhub_private.friend_actor();
    v_q text; v_like text; v_raw text; v_size integer; v_contains text; v_fuzzy boolean;
    v_result jsonb;
begin
    select i.q, i.q_like, i.q_raw, i.page_size into v_q, v_like, v_raw, v_size
    from studyhub_private.search_input(p_query, p_limit, p_offset) i;
    if char_length(v_q) < 2 or v_size = 0 then
        return jsonb_build_object('items', '[]'::jsonb, 'has_more', false);
    end if;
    v_contains := '%' || v_like || '%';
    -- Trigrams ignore symbols, so queries such as "100%" or "c++" stay literal.
    v_fuzzy := char_length(v_q) >= 4 and v_q !~ '[^[:alnum:][:space:]]';

    -- Candidates come from two index-friendly branches (file name, post title);
    -- the parent post then gets the same visibility rules as search_posts.
    with viewer_friends as materialized (
        select case when f.requester_id = v_actor then f.addressee_id else f.requester_id end as peer_id
        from public.friendships f
        where f.status = 'accepted' and (f.requester_id = v_actor or f.addressee_id = v_actor)
    ), blocked as materialized (
        select b.blocked_id as peer_id from public.blocks b where b.blocker_id = v_actor
        union
        select b.blocker_id from public.blocks b where b.blocked_id = v_actor
    ), candidates as (
        select f.id from public.post_files f
        where studyhub_private.search_file_norm(f.file_name) like v_contains escape '\'
           or (v_fuzzy and v_q operator(extensions.<%) studyhub_private.search_file_norm(f.file_name))
        union
        select f.id from public.posts p join public.post_files f on f.post_id = p.id
        where studyhub_private.search_norm(p.title) like v_contains escape '\'
    ), hits as (
        select f.id, f.file_name, f.file_type, f.file_size, f.created_at,
               p.id as post_id, p.title as post_title, a.display_name as author_display_name,
               studyhub_private.search_file_norm(f.file_name) as n_file,
               studyhub_private.search_norm(p.title) as n_title
        from candidates c
        join public.post_files f on f.id = c.id
        join public.posts p on p.id = f.post_id
        join public.profiles a on a.id = p.author_id
        where not (coalesce(f.file_type, '') ilike 'image/%'
                   or f.file_name ~* '\.(png|jpe?g|webp|gif|bmp|heic)$')
          and (p.author_id = v_actor
               or (not a.is_banned
                   and not exists (select 1 from blocked x where x.peer_id = p.author_id)
                   and case studyhub_private.post_visibility(p.type)
                       when 'public' then true
                       when 'friends' then exists (select 1 from viewer_friends vf where vf.peer_id = p.author_id)
                       else false
                   end))
    ), ordered as (
        select h.*, row_number() over (order by
            case when h.n_file like v_like || '%' escape '\' or h.n_title like v_like || '%' escape '\' then 1
                 when h.n_file like v_contains escape '\' or h.n_title like v_contains escape '\' then 2
                 else 3 end,
            (strpos(lower(h.file_name), v_raw) > 0 or strpos(lower(h.post_title), v_raw) > 0) desc,
            extensions.word_similarity(v_q, h.n_file) desc,
            h.created_at desc, h.id) as rn
        from hits h
    )
    select jsonb_build_object(
        'items', coalesce(jsonb_agg(jsonb_build_object(
                'id', o.id, 'file_name', o.file_name, 'file_type', o.file_type, 'file_size', o.file_size,
                'created_at', o.created_at, 'post_id', o.post_id, 'post_title', o.post_title,
                'author_display_name', o.author_display_name)
            order by o.rn) filter (where o.rn > p_offset and o.rn <= p_offset + v_size), '[]'::jsonb),
        'has_more', coalesce(bool_or(o.rn > p_offset + v_size), false) and p_offset + v_size < 100)
    into v_result
    from ordered o
    where o.rn <= p_offset + v_size + 1;
    return v_result;
end;
$$;

-- ------------------------------------------------------------------------------
-- Grants. Helpers stay private; index expressions run with the writer's
-- privileges, so writers of indexed tables need EXECUTE on the normalizers.
-- ------------------------------------------------------------------------------
revoke all on function
    studyhub_private.search_norm(text), studyhub_private.search_file_norm(text),
    studyhub_private.search_like_escape(text), studyhub_private.search_input(text, integer, integer),
    studyhub_private.post_visibility(text)
    from public, anon, authenticated;
grant execute on function studyhub_private.search_norm(text), studyhub_private.search_file_norm(text)
    to authenticated, service_role;

revoke all on function
    public.search_profiles(text, integer, integer), public.search_subjects(text, integer, integer),
    public.search_posts(text, integer, integer), public.search_documents(text, integer, integer)
    from public, anon, authenticated;
grant execute on function
    public.search_profiles(text, integer, integer), public.search_subjects(text, integer, integer),
    public.search_posts(text, integer, integer), public.search_documents(text, integer, integer)
    to authenticated;

insert into supabase_migrations.schema_migrations (version, name)
values ('20261106_unified_search', 'unified_search')
on conflict (version) do nothing;

commit;
