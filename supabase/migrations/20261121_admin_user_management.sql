-- ============================================================
-- StudyHub - Admin User Management
-- RPC: admin_list_users
-- ============================================================

create or replace function public.admin_list_users(
    p_search text default null,
    p_limit integer default 50,
    p_offset integer default 0
)
returns table (
    id uuid,
    email text,
    username text,
    display_name text,
    avatar_url text,
    role text,
    is_banned boolean,
    created_at timestamptz
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
    -- Xác thực người gọi phải là Admin
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

    -- Giới hạn phân trang
    if v_limit is null or v_limit < 1 or v_limit > 100 then
        v_limit := 50;
    end if;

    if v_offset is null or v_offset < 0 then
        v_offset := 0;
    end if;

    return query
    select
        p.id,
        u.email::text,
        p.username::text,
        p.display_name::text,
        p.avatar_url::text,
        p.role::text,
        p.is_banned,
        p.created_at
    from public.profiles p
    join auth.users u on u.id = p.id
    where
        v_search is null
        or btrim(v_search) = ''
        or u.email ilike '%' || btrim(v_search) || '%'
        or p.username ilike '%' || btrim(v_search) || '%'
        or p.display_name ilike '%' || btrim(v_search) || '%'
    order by p.created_at desc, p.id
    limit v_limit
    offset v_offset;
end;
$$;

-- Không cho anon hoặc PUBLIC gọi
revoke all on function public.admin_list_users(text, integer, integer)
from public, anon;

-- Chỉ tài khoản đã đăng nhập được gọi
-- Bên trong RPC tiếp tục kiểm tra Admin
grant execute on function public.admin_list_users(text, integer, integer)
to authenticated;

notify pgrst, 'reload schema';