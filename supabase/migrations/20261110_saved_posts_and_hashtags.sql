-- =====================================================================
-- Migration: 20261110_saved_posts_and_hashtags
-- Adds:
-- 1. Indexes on saved_posts and post_hashtags for fast retrieval.
-- 2. RPC public.toggle_saved_post(p_post_id uuid) for atomic bookmarking.
-- 3. Updates public.search_posts to match posts by their hashtags.
-- =====================================================================

begin;

-- 1. Indexes
create index if not exists post_hashtags_post_id_idx on public.post_hashtags(post_id);
create index if not exists post_hashtags_hashtag_id_idx on public.post_hashtags(hashtag_id);
create index if not exists saved_posts_user_id_idx on public.saved_posts(user_id);
create index if not exists saved_posts_post_id_idx on public.saved_posts(post_id);

-- 2. RPC toggle_saved_post
create or replace function public.toggle_saved_post(p_post_id uuid)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := auth.uid();
    v_exists boolean;
begin
    if v_user_id is null then
        raise exception 'not authenticated' using errcode = '42501';
    end if;

    if p_post_id is null then
        raise exception 'invalid post id' using errcode = '22023';
    end if;

    if not exists (
        select 1 from public.posts p
        where p.id = p_post_id
    ) then
        raise exception 'post not found' using errcode = 'P0002';
    end if;

    select exists (
        select 1 from public.saved_posts sp
        where sp.user_id = v_user_id and sp.post_id = p_post_id
    ) into v_exists;

    if v_exists then
        delete from public.saved_posts sp
        where sp.user_id = v_user_id and sp.post_id = p_post_id;
        return false;
    else
        insert into public.saved_posts (user_id, post_id)
        values (v_user_id, p_post_id)
        on conflict (user_id, post_id) do nothing;
        return true;
    end if;
end;
$$;

revoke all on function public.toggle_saved_post(uuid) from public, anon;
grant execute on function public.toggle_saved_post(uuid) to authenticated;

-- 3. Cập nhật search_posts để tìm kiếm bài viết theo hashtag
create or replace function public.search_posts(p_query text, p_limit integer default 20, p_offset integer default 0)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_actor uuid := studyhub_private.friend_actor();
    v_q text; v_like text; v_raw text; v_size integer; v_contains text; v_fuzzy boolean;
    v_tag_clean text; v_tag_like text; v_tag_contains text;
    v_result jsonb;
begin
    begin
        perform set_config('pg_trgm.word_similarity_threshold', '0.5', true);
    exception when others then
        null;
    end;
    select i.q, i.q_like, i.q_raw, i.page_size into v_q, v_like, v_raw, v_size
    from studyhub_private.search_input(p_query, p_limit, p_offset) i;
    if char_length(v_q) < 2 or v_size = 0 then
        return jsonb_build_object('items', '[]'::jsonb, 'has_more', false);
    end if;

    v_contains := '%' || v_like || '%';
    v_fuzzy := char_length(v_q) >= 4 and v_q !~ '[^[:alnum:][:space:]]';

    -- Chuẩn hóa từ khóa hashtag nếu người dùng tìm kiếm có chứa dấu '#' ở đầu
    v_tag_clean := ltrim(v_q, '# ');
    if v_tag_clean = '' then
        v_tag_clean := v_q;
    end if;
    v_tag_like := studyhub_private.search_like_escape(v_tag_clean);
    v_tag_contains := '%' || v_tag_like || '%';

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
               studyhub_private.search_norm(p.content) as n_content,
               exists (
                   select 1 from public.post_hashtags ph
                   join public.hashtags ht on ht.id = ph.hashtag_id
                   where ph.post_id = p.id
                     and (lower(ht.name) like v_tag_like || '%' escape '\')
               ) as tag_prefix_match,
               exists (
                   select 1 from public.post_hashtags ph
                   join public.hashtags ht on ht.id = ph.hashtag_id
                   where ph.post_id = p.id
                     and (lower(ht.name) like v_tag_contains escape '\'
                          or studyhub_private.search_norm(ht.name) like v_tag_contains escape '\')
               ) as tag_contains_match
        from public.posts p
        join public.profiles a on a.id = p.author_id
        where (studyhub_private.search_norm(p.title) like v_contains escape '\'
               or studyhub_private.search_norm(p.content) like v_contains escape '\'
               or (v_fuzzy and v_q operator(extensions.<%) studyhub_private.search_norm(p.title))
               or exists (
                   select 1 from public.post_hashtags ph
                   join public.hashtags ht on ht.id = ph.hashtag_id
                   where ph.post_id = p.id
                     and (lower(ht.name) like v_tag_contains escape '\'
                          or studyhub_private.search_norm(ht.name) like v_tag_contains escape '\')
               ))
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
            case when h.n_title like v_like || '%' escape '\' or h.n_content like v_like || '%' escape '\' or h.tag_prefix_match then 1
                 when h.n_title like v_contains escape '\' or h.n_content like v_contains escape '\' or h.tag_contains_match then 2
                 else 3 end,
            (strpos(lower(h.title), v_raw) > 0 or strpos(lower(coalesce(h.content, '')), v_raw) > 0) desc,
            extensions.word_similarity(v_q, h.n_title) desc,
            h.created_at desc, h.id) as rn
        from hits h
    )
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

revoke all on function public.search_posts(text, integer, integer) from public, anon;
grant execute on function public.search_posts(text, integer, integer) to authenticated;

notify pgrst, 'reload schema';
commit;

