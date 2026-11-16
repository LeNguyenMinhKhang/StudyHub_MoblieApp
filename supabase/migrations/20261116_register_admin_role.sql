-- =====================================================================
-- Migration: Support assigning admin role upon registration from app admin
-- File: supabase/migrations/20261116_register_admin_role.sql
-- =====================================================================

create or replace function public.register_admin_role()
returns void
language plpgsql
security definer
set search_path = 'public', 'auth', 'pg_temp'
as $$
begin
    if auth.uid() is not null then
        update public.profiles
        set role = 'admin'
        where id = auth.uid();
    end if;
end;
$$;

grant execute on function public.register_admin_role() to authenticated;

notify pgrst, 'reload schema';
