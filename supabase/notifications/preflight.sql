-- Read-only metadata and aggregate counts; no user content or credentials.
select jsonb_build_object(
  'server_version', current_setting('server_version'),
  'cron', (select jsonb_agg(to_jsonb(e)) from
    (select name, default_version, installed_version from pg_available_extensions where name = 'pg_cron') e),
  'notifications_count', (select count(*) from public.notifications),
  'notification_columns', (select jsonb_agg(to_jsonb(c)) from
    (select column_name, data_type from information_schema.columns where table_schema='public' and table_name='notifications') c),
  'effective_grants', (select jsonb_agg(jsonb_build_object(
    'role', r, 'table', t, 'select', has_table_privilege(r,t,'SELECT'),
    'insert', has_table_privilege(r,t,'INSERT'), 'update', has_table_privilege(r,t,'UPDATE'),
    'delete', has_table_privilege(r,t,'DELETE')))
    from unnest(array['anon','authenticated']) r
    cross join unnest(array['public.notifications','public.friendships','public.posts','public.likes','public.comments']) t),
  'policies', (select jsonb_agg(to_jsonb(p)) from
    (select schemaname, tablename, policyname, roles, cmd, qual, with_check from pg_policies
      where (schemaname='public' and tablename in ('notifications','friendships','posts','likes','comments'))
      or (schemaname='realtime' and tablename='messages')) p),
  'functions', (select jsonb_agg(to_jsonb(f)) from
    (select n.nspname, p.proname, pg_get_function_identity_arguments(p.oid) as arguments,
      p.prosecdef, p.proconfig, md5(pg_get_functiondef(p.oid)) as definition_hash
      from pg_proc p join pg_namespace n on n.oid=p.pronamespace
      where p.proname in ('send_friend_request','friend_mutate','list_my_notifications','mark_notifications_read','send','topic')
      and n.nspname in ('public','studyhub_private','realtime')) f),
  'migrations', (select jsonb_agg(to_jsonb(m)) from
    (select version, name from supabase_migrations.schema_migrations order by version) m)
) as preflight;
