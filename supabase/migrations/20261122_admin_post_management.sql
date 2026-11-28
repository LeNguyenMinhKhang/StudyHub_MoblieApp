-- ============================================================
-- StudyHub - Admin Post Management
-- RPC: admin_list_posts
-- RPC: admin_get_post_detail
-- RPC: admin_delete_post
-- ============================================================

-- 1. LIST POSTS: search + pagination + statistics
create or replace function public.admin_list_posts(
    p_search text default null,
    p_limit integer default 20,
    p_offset integer default 0
)
returns table (
    id uuid,
    author_id uuid,
    subject_id uuid,
    post_type text,
    title text,
    content text,
    created_at timestamptz,
    updated_at timestamptz,
    author_username text,
    author_display_name text,
    author_email text,
    author_avatar_url text,
    subject_name text,
    comment_count bigint,
    like_count bigint,
    file_count bigint
)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_search text := p_search;
    v_limit integer := p_limit;
    v_offset integer := p_offset;
begin
    if auth.uid() is null or not exists (
        select 1
        from public.profiles p
        where p.id = auth.uid()
          and p.role = 'admin'
          and not p.is_banned
    ) then
        raise exception 'admin access required'
            using errcode = '42501';
    end if;

    if v_limit is null or v_limit < 1 or v_limit > 100 then
        v_limit := 20;
    end if;

    if v_offset is null or v_offset < 0 then
        v_offset := 0;
    end if;

    return query
    select
        p.id,
        p.author_id,
        p.subject_id,
        p.type::text,
        p.title::text,
        p.content::text,
        p.created_at,
        p.updated_at,
        pr.username::text,
        pr.display_name::text,
        u.email::text,
        pr.avatar_url::text,
        s.name::text,
        (select count(*) from public.comments c where c.post_id = p.id),
        (select count(*) from public.likes l where l.post_id = p.id),
        (select count(*) from public.post_files pf where pf.post_id = p.id)
    from public.posts p
    left join public.profiles pr on pr.id = p.author_id
    left join auth.users u on u.id = p.author_id
    left join public.subjects s on s.id = p.subject_id
    where
        v_search is null
        or btrim(v_search) = ''
        or p.title ilike '%' || btrim(v_search) || '%'
        or coalesce(p.content, '') ilike '%' || btrim(v_search) || '%'
        or coalesce(pr.username, '') ilike '%' || btrim(v_search) || '%'
        or coalesce(pr.display_name, '') ilike '%' || btrim(v_search) || '%'
        or coalesce(u.email, '') ilike '%' || btrim(v_search) || '%'
    order by p.created_at desc, p.id
    limit v_limit
    offset v_offset;
end;
$$;


-- 2. POST DETAIL: post + author + subject + files + comments
create or replace function public.admin_get_post_detail(
    p_post_id uuid
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_result jsonb;
begin
    if auth.uid() is null or not exists (
        select 1
        from public.profiles p
        where p.id = auth.uid()
          and p.role = 'admin'
          and not p.is_banned
    ) then
        raise exception 'admin access required'
            using errcode = '42501';
    end if;

    select jsonb_build_object(
        'post', (
            select to_jsonb(p)
            from public.posts p
            where p.id = p_post_id
        ),
        'author', (
            select jsonb_build_object(
                'id', pr.id,
                'username', pr.username,
                'display_name', pr.display_name,
                'avatar_url', pr.avatar_url,
                'email', u.email
            )
            from public.posts p
            left join public.profiles pr on pr.id = p.author_id
            left join auth.users u on u.id = p.author_id
            where p.id = p_post_id
        ),
        'subject', (
            select to_jsonb(s)
            from public.posts p
            left join public.subjects s on s.id = p.subject_id
            where p.id = p_post_id
        ),
        'files', coalesce((
            select jsonb_agg(to_jsonb(pf) order by pf.created_at)
            from public.post_files pf
            where pf.post_id = p_post_id
        ), '[]'::jsonb),
        'comments', coalesce((
            select jsonb_agg(
                jsonb_build_object(
                    'comment', to_jsonb(c),
                    'author', jsonb_build_object(
                        'id', pr.id,
                        'username', pr.username,
                        'display_name', pr.display_name,
                        'avatar_url', pr.avatar_url
                    ),
                    'reaction_count', (
                        select count(*)
                        from public.comment_reactions cr
                        where cr.comment_id = c.id
                    )
                )
                order by c.created_at
            )
            from public.comments c
            left join public.profiles pr on pr.id = c.user_id
            where c.post_id = p_post_id
        ), '[]'::jsonb),
        'like_count', (
            select count(*) from public.likes l where l.post_id = p_post_id
        ),
        'comment_count', (
            select count(*) from public.comments c where c.post_id = p_post_id
        )
    )
    into v_result;

    if v_result->'post' is null or v_result->'post' = 'null'::jsonb then
        return null;
    end if;

    return v_result;
end;
$$;


-- 3. DELETE POST: Admin only
-- Related database records are deleted by FK ON DELETE CASCADE.
-- Storage objects are intentionally not deleted by this RPC.
create or replace function public.admin_delete_post(
    p_post_id uuid
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_deleted_count integer;
begin
    if auth.uid() is null or not exists (
        select 1
        from public.profiles p
        where p.id = auth.uid()
          and p.role = 'admin'
          and not p.is_banned
    ) then
        raise exception 'admin access required'
            using errcode = '42501';
    end if;

    delete from public.posts
    where id = p_post_id;

    get diagnostics v_deleted_count = row_count;

    return v_deleted_count > 0;
end;
$$;


-- Permissions: authenticated users may call RPC,
-- but each function independently verifies Admin role.
revoke all on function public.admin_list_posts(text, integer, integer)
from public, anon;

revoke all on function public.admin_get_post_detail(uuid)
from public, anon;

revoke all on function public.admin_delete_post(uuid)
from public, anon;

grant execute on function public.admin_list_posts(text, integer, integer)
to authenticated;

grant execute on function public.admin_get_post_detail(uuid)
to authenticated;

grant execute on function public.admin_delete_post(uuid)
to authenticated;

notify pgrst, 'reload schema';
