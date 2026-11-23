
-- ============================================================
-- StudyHub - Admin User Actions + Posts by Author
-- 1. admin_list_posts: thêm p_author_id
-- 2. admin_ban_user: chỉ Ban tài khoản role user
-- 3. admin_unban_user: chỉ Unban tài khoản role user
-- ============================================================

-- ------------------------------------------------------------
-- 1. ADMIN LIST POSTS: hỗ trợ lọc theo tác giả
-- ------------------------------------------------------------

-- Xóa phiên bản 3 tham số cũ để tránh RPC overload
drop function if exists public.admin_list_posts(text, integer, integer);

create or replace function public.admin_list_posts(
    p_search text default null,
    p_limit integer default 20,
    p_offset integer default 0,
    p_author_id uuid default null
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
    -- Kiểm tra quyền Admin
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

    -- Chuẩn hóa phân trang
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
        (p_author_id is null or p.author_id = p_author_id)
        and (
            v_search is null
            or btrim(v_search) = ''
            or p.title ilike '%' || btrim(v_search) || '%'
            or coalesce(p.content, '') ilike '%' || btrim(v_search) || '%'
            or coalesce(pr.username, '') ilike '%' || btrim(v_search) || '%'
            or coalesce(pr.display_name, '') ilike '%' || btrim(v_search) || '%'
            or coalesce(u.email, '') ilike '%' || btrim(v_search) || '%'
        )
    order by p.created_at desc, p.id
    limit v_limit
    offset v_offset;
end;
$$;

revoke all on function public.admin_list_posts(text, integer, integer, uuid)
from public, anon;

grant execute on function public.admin_list_posts(text, integer, integer, uuid)
to authenticated;


-- ------------------------------------------------------------
-- 2. BAN USER: chỉ được Ban tài khoản role user
-- ------------------------------------------------------------

create or replace function public.admin_ban_user(
    p_user_id uuid
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_role text;
begin
    -- Xác thực Admin hiện tại
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

    -- Không cho tự thao tác lên chính tài khoản Admin
    if p_user_id = auth.uid() then
        raise exception 'cannot ban yourself'
            using errcode = '42501';
    end if;

    -- Chỉ cho phép tài khoản role user
    select p.role
    into v_role
    from public.profiles p
    where p.id = p_user_id
    for update;

    if not found then
        raise exception 'user not found'
            using errcode = 'P0002';
    end if;

    if v_role <> 'user' then
        raise exception 'only regular users can be banned'
            using errcode = '42501';
    end if;

    update public.profiles
    set is_banned = true
    where id = p_user_id
      and role = 'user';

    return found;
end;
$$;

revoke all on function public.admin_ban_user(uuid)
from public, anon;

grant execute on function public.admin_ban_user(uuid)
to authenticated;


-- ------------------------------------------------------------
-- 3. UNBAN USER: chỉ được Unban tài khoản role user
-- ------------------------------------------------------------

create or replace function public.admin_unban_user(
    p_user_id uuid
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_role text;
begin
    -- Xác thực Admin hiện tại
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

    if p_user_id = auth.uid() then
        raise exception 'cannot unban yourself'
            using errcode = '42501';
    end if;

    -- Chỉ cho phép tài khoản role user
    select p.role
    into v_role
    from public.profiles p
    where p.id = p_user_id
    for update;

    if not found then
        raise exception 'user not found'
            using errcode = 'P0002';
    end if;

    if v_role <> 'user' then
        raise exception 'only regular users can be unbanned'
            using errcode = '42501';
    end if;

    update public.profiles
    set is_banned = false
    where id = p_user_id
      and role = 'user';

    return found;
end;
$$;

revoke all on function public.admin_unban_user(uuid)
from public, anon;

grant execute on function public.admin_unban_user(uuid)
to authenticated;

-- Refresh Supabase API schema cache
notify pgrst, 'reload schema';
