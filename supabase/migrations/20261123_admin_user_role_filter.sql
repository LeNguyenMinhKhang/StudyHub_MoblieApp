-- ============================================================
-- StudyHub - Admin User Management
-- Add server-side role filtering
-- ============================================================

create or replace function public.admin_list_users(
    p_search text default null,
    p_limit integer default 50,
    p_offset integer default 0,
    p_role text default null
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
    v_search text := nullif(btrim(p_search), '');
    v_role text := nullif(lower(btrim(p_role)), '');
    v_limit integer := p_limit;
    v_offset integer := p_offset;
begin
    -- Admin authorization
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

    -- Validate role filter
    if v_role is not null
       and v_role not in ('user', 'admin') then
        raise exception 'invalid role filter';
    end if;

    -- Validate pagination
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
        (v_role is null or lower(p.role::text) = v_role)
        and (
            v_search is null
            or u.email ilike '%' || v_search || '%'
            or p.username ilike '%' || v_search || '%'
            or p.display_name ilike '%' || v_search || '%'
        )
    order by p.created_at desc, p.id
    limit v_limit
    offset v_offset;
end;
$$;

-- Remove old 3-parameter function to avoid RPC overload ambiguity
drop function if exists public.admin_list_users(text, integer, integer);

-- Security permissions
revoke all on function public.admin_list_users(text, integer, integer, text)
from public, anon;

grant execute on function public.admin_list_users(text, integer, integer, text)
to authenticated;

notify pgrst, 'reload schema';