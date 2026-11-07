-- Disposable-database fixtures for notification tests. Never run against hosted projects.
-- Users: A=...a1, B=...b2, C=...c3, D=...d4 (D is used for ban scenarios).
\set ON_ERROR_STOP 1
begin;
insert into auth.users (id, instance_id, aud, role, email, raw_user_meta_data, created_at, updated_at)
select u.id, '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated',
       u.name || '@notification.test', jsonb_build_object('username', 'nt_' || u.name, 'display_name', 'User ' || upper(u.name)),
       now(), now()
from (values ('00000000-0000-0000-0000-0000000000a1'::uuid, 'a'),
             ('00000000-0000-0000-0000-0000000000b2'::uuid, 'b'),
             ('00000000-0000-0000-0000-0000000000c3'::uuid, 'c'),
             ('00000000-0000-0000-0000-0000000000d4'::uuid, 'd')) u(id, name)
on conflict (id) do nothing;

-- Test helper: impersonate a user the same way PostgREST does.
create schema if not exists nt;
create or replace function nt.act_as(uid uuid) returns void language plpgsql as $$
begin
  if uid is null then
    perform set_config('request.jwt.claims', '', true);
    execute 'set local role anon';
  else
    perform set_config('request.jwt.claims', json_build_object('sub', uid, 'role', 'authenticated')::text, true);
    execute 'set local role authenticated';
  end if;
end;
$$;
create or replace function nt.reset_role() returns void language plpgsql as $$
begin
  execute 'reset role';
  perform set_config('request.jwt.claims', '', true);
end;
$$;
-- Runs stmt and requires it to fail with the expected SQLSTATE.
create or replace function nt.expect_error(stmt text, expected text) returns void language plpgsql as $$
begin
  begin
    execute stmt;
  exception when others then
    if sqlstate <> expected then
      raise exception 'expected % but got % (%) for: %', expected, sqlstate, sqlerrm, stmt;
    end if;
    return;
  end;
  raise exception 'expected % but statement succeeded: %', expected, stmt;
end;
$$;
create or replace function nt.uid(name text) returns uuid language sql immutable as $$
  select case name when 'a' then '00000000-0000-0000-0000-0000000000a1'
                   when 'b' then '00000000-0000-0000-0000-0000000000b2'
                   when 'c' then '00000000-0000-0000-0000-0000000000c3'
                   when 'd' then '00000000-0000-0000-0000-0000000000d4' end::uuid;
$$;
create or replace function nt.inbox(recipient uuid, kind text default null) returns bigint language sql as $$
  select count(*) from public.notifications where user_id = recipient and (kind is null or type = kind);
$$;
create or replace function nt.jobs(recipient uuid) returns bigint language sql as $$
  select count(*) from studyhub_private.notification_delivery_jobs where recipient_id = recipient;
$$;
create or replace function nt.set_pref_as(uid uuid, cat text, enabled boolean) returns jsonb language plpgsql as $$
declare r jsonb;
begin
  perform nt.act_as(uid);
  r := public.set_my_notification_preferences(cat, enabled);
  perform nt.reset_role();
  return r;
end;
$$;
create or replace function nt.mark_all_as(uid uuid) returns integer language plpgsql as $$
declare r integer;
begin
  perform nt.act_as(uid);
  r := public.mark_notifications_read(null);
  perform nt.reset_role();
  return r;
end;
$$;
grant usage on schema nt to anon, authenticated;
grant execute on all functions in schema nt to anon, authenticated;
commit;
