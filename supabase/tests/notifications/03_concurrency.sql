-- Multi-session races via dblink. Uses committed rows (other sessions must see them) and
-- removes them at the end. Run as supabase_admin on a disposable database only.
\set ON_ERROR_STOP 1
create extension if not exists dblink schema extensions;

delete from public.posts where id = '40000000-0000-0000-0000-000000000001';
insert into public.posts(id, author_id, title, type)
  values ('40000000-0000-0000-0000-000000000001', nt.uid('b'), 'race', 'question:public');
select nt.set_pref_as(nt.uid('b'), 'like', true);
select extensions.dblink_connect('c1', 'host=127.0.0.1 dbname=postgres user=supabase_admin');
select extensions.dblink_connect('c2', 'host=127.0.0.1 dbname=postgres user=supabase_admin');

-- A. Opt-out commits first; a concurrent like waits on the recipient lock and is suppressed.
select extensions.dblink_exec('c1', $q$begin$q$);
select * from extensions.dblink('c1', $q$select nt.set_pref_as(nt.uid('b'), 'like', false)::text$q$) as t(r text);
select extensions.dblink_send_query('c2', $q$insert into public.likes(post_id, user_id)
  values ('40000000-0000-0000-0000-000000000001', nt.uid('a')) returning id::text$q$);
select pg_sleep(0.5);
do $$ begin assert extensions.dblink_is_busy('c2') = 1, 'like blocked behind preference change'; end $$;
select extensions.dblink_exec('c1', 'commit');
select * from extensions.dblink_get_result('c2') as t(id text);
select * from extensions.dblink_get_result('c2') as t(id text);
do $$ begin
  assert exists (select 1 from public.likes where post_id = '40000000-0000-0000-0000-000000000001'), 'like committed';
  assert not exists (select 1 from public.notifications where post_id = '40000000-0000-0000-0000-000000000001'),
    'serialized after opt-out: suppressed';
end $$;
delete from public.likes where post_id = '40000000-0000-0000-0000-000000000001';
select nt.set_pref_as(nt.uid('b'), 'like', true);

-- B. Like commits first; a concurrent opt-out waits and does not remove the earlier notification.
select extensions.dblink_exec('c1', $q$begin$q$);
select * from extensions.dblink('c1', $q$insert into public.likes(post_id, user_id)
  values ('40000000-0000-0000-0000-000000000001', nt.uid('a')) returning id::text$q$) as t(id text);
select extensions.dblink_send_query('c2', $q$select nt.set_pref_as(nt.uid('b'), 'like', false)::text$q$);
select pg_sleep(0.5);
do $$ begin assert extensions.dblink_is_busy('c2') = 1, 'preference change blocked behind producer'; end $$;
select extensions.dblink_exec('c1', 'commit');
select * from extensions.dblink_get_result('c2') as t(r text);
select * from extensions.dblink_get_result('c2') as t(r text);
do $$ begin
  assert (select count(*) from public.notifications where post_id = '40000000-0000-0000-0000-000000000001') = 1,
    'serialized before opt-out: notified once';
end $$;
delete from public.likes where post_id = '40000000-0000-0000-0000-000000000001';
select nt.set_pref_as(nt.uid('b'), 'like', true);

-- C. Concurrent duplicate processing of one source event -> one inbox row.
select extensions.dblink_exec('c1', $q$begin$q$);
select * from extensions.dblink('c1', $q$select studyhub_private.create_notification(nt.uid('b'), nt.uid('a'), 'comment', 'comment',
  '40000000-0000-0000-0000-0000000000cc', '40000000-0000-0000-0000-000000000001')::text$q$) as t(r text);
select extensions.dblink_send_query('c2', $q$select studyhub_private.create_notification(nt.uid('b'), nt.uid('a'), 'comment', 'comment',
  '40000000-0000-0000-0000-0000000000cc', '40000000-0000-0000-0000-000000000001')::text$q$);
select pg_sleep(0.3);
select extensions.dblink_exec('c1', 'commit');
select * from extensions.dblink_get_result('c2') as t(r text);
select * from extensions.dblink_get_result('c2') as t(r text);
do $$ begin
  assert (select count(*) from public.notifications where source_id = '40000000-0000-0000-0000-0000000000cc') = 1,
    'concurrent duplicates collapse to one row';
end $$;

-- D. Mark-all races with an arrival: the uncommitted arrival stays unread.
select extensions.dblink_exec('c1', $q$begin$q$);
select * from extensions.dblink('c1', $q$insert into public.likes(post_id, user_id)
  values ('40000000-0000-0000-0000-000000000001', nt.uid('c')) returning id::text$q$) as t(id text);
select nt.mark_all_as(nt.uid('b')) as marked;
select extensions.dblink_exec('c1', 'commit');
do $$ begin
  assert (select not is_read from public.notifications n join public.likes l on l.id = n.source_id
          where l.post_id = '40000000-0000-0000-0000-000000000001' and l.user_id = nt.uid('c')),
    'arrival after mark-all snapshot remains unread';
  assert (select is_read from public.notifications where source_id = '40000000-0000-0000-0000-0000000000cc'),
    'existing row marked read';
end $$;

-- E. Overlapping worker runs: the second returns immediately without claiming.
select extensions.dblink_exec('c1', $q$begin$q$);
select * from extensions.dblink('c1', $q$select studyhub_private.dispatch_notifications()::text$q$) as t(r text);
do $$ begin
  assert (select r from extensions.dblink('c2', $q$select studyhub_private.dispatch_notifications()::text$q$) as t(r text))
       = '{"locked": true}', 'concurrency guard';
end $$;
select extensions.dblink_exec('c1', 'rollback');

-- Cleanup committed rows.
select extensions.dblink_disconnect('c1');
select extensions.dblink_disconnect('c2');
delete from public.posts where id = '40000000-0000-0000-0000-000000000001';
delete from public.notifications where source_id = '40000000-0000-0000-0000-0000000000cc';
delete from studyhub_private.notification_delivery_jobs where recipient_id in (nt.uid('a'), nt.uid('b'), nt.uid('c'));
delete from studyhub_private.notification_dispatch_state;
select nt.set_pref_as(nt.uid('b'), 'like', true);
select 'PASS 03_concurrency' as result;
