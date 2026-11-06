-- Inbox producers, preferences, visibility, pagination, read/open and grants.
-- Runs as supabase_admin inside one transaction that is always rolled back.
\set ON_ERROR_STOP 1
begin;

-- Fixture posts owned by B: public, friends-only and private.
insert into public.posts(id, author_id, title, type) values
  ('20000000-0000-0000-0000-000000000001', nt.uid('b'), 'public', 'question:public'),
  ('20000000-0000-0000-0000-000000000002', nt.uid('b'), 'friends', 'none:friends'),
  ('20000000-0000-0000-0000-000000000003', nt.uid('b'), 'private', 'private');

do $$
declare a uuid := nt.uid('a'); b uuid := nt.uid('b'); c uuid := nt.uid('c');
  like_id uuid; like2 uuid; first_note uuid; cmt uuid; reply uuid; jobs_before bigint; n record;
begin
  -- Like by A on B's public post: one inbox row + delivery job, nothing for A.
  jobs_before := nt.jobs(b);
  insert into public.likes(post_id, user_id) values ('20000000-0000-0000-0000-000000000001', a) returning id into like_id;
  select * into n from public.notifications where source_kind = 'like' and source_id = like_id;
  assert n.user_id = b and n.actor_id = a and n.type = 'like' and n.event_key = 'like:' || like_id
     and n.template_version = 1 and not n.is_read, 'like notification shape';
  assert nt.jobs(b) = jobs_before + 1, 'like enqueues exactly one job';
  assert nt.inbox(a) = 0, 'actor not notified';
  first_note := n.id;

  -- Retrying the same source event does not create a second inbox row.
  perform studyhub_private.create_notification(b, a, 'like', 'like', like_id, '20000000-0000-0000-0000-000000000001');
  assert (select count(*) from public.notifications where source_id = like_id) = 1, 'idempotent producer';

  -- Self like: no notification.
  insert into public.likes(post_id, user_id) values ('20000000-0000-0000-0000-000000000001', b);
  assert nt.inbox(b, 'like') = 1, 'self like ignored';

  -- Unlike removes the notification; re-like is a new incarnation.
  delete from public.likes where id = like_id;
  assert not exists (select 1 from public.notifications where id = first_note), 'unlike removes notification';
  insert into public.likes(post_id, user_id) values ('20000000-0000-0000-0000-000000000001', a) returning id into like2;
  assert (select count(*) from public.notifications where source_id = like2 and user_id = b) = 1, 're-like notifies once';
  assert not exists (select 1 from public.notifications where id = first_note), 'old notification not recreated';

  -- Comment + reply notify only the post owner; edits create nothing.
  insert into public.comments(post_id, user_id, content) values ('20000000-0000-0000-0000-000000000001', c, 'c root')
    returning id into cmt;
  insert into public.comments(post_id, user_id, parent_id, content)
    values ('20000000-0000-0000-0000-000000000001', a, cmt, 'reply to c') returning id into reply;
  assert nt.inbox(b, 'comment') = 2, 'post owner gets comment and reply';
  assert nt.inbox(c) = 0, 'parent comment author not fanned out';
  update public.comments set content = 'edited' where id = reply;
  assert nt.inbox(b, 'comment') = 2, 'edit creates nothing';
  delete from public.comments where id = reply;
  assert not exists (select 1 from public.notifications where source_id = reply), 'deleted comment removes notification';

  -- Source visibility: A is not B's friend, so friends/private posts do not notify.
  insert into public.likes(post_id, user_id) values ('20000000-0000-0000-0000-000000000002', a);
  insert into public.likes(post_id, user_id) values ('20000000-0000-0000-0000-000000000003', a);
  assert (select count(*) from public.notifications
          where post_id in ('20000000-0000-0000-0000-000000000002','20000000-0000-0000-0000-000000000003')) = 0,
    'inaccessible source posts do not notify';
end $$;

-- Friendship producers via RPCs with source ids.
do $$
declare a uuid := nt.uid('a'); b uuid := nt.uid('b'); c uuid := nt.uid('c'); snap jsonb; rel uuid; req public.notifications;
begin
  perform nt.act_as(a);
  snap := public.send_friend_request(b);
  perform nt.reset_role();
  rel := (snap->>'friendship_id')::uuid;
  select * into req from public.notifications where source_kind = 'friendship' and source_id = rel and type = 'friend_request';
  assert req.user_id = b and req.event_key = 'friend_request:' || rel, 'request notification with source id';
  perform nt.act_as(a); perform public.send_friend_request(b); perform nt.reset_role();
  assert (select count(*) from public.notifications where source_id = rel and type = 'friend_request') = 1, 'repeat send silent';

  perform nt.act_as(b); perform public.accept_friend_request(rel); perform nt.reset_role();
  assert (select is_read from public.notifications where id = req.id), 'accept marks request read';
  assert (select count(*) from public.notifications where user_id = a and type = 'friend_accept' and source_id = rel) = 1,
    'requester gets one friend_accept';
  -- Legacy request row from A to B (no source) is also marked read, as before.
  assert (select is_read from public.notifications where id = '10000000-0000-0000-0000-000000000001'), 'legacy row cleanup kept';

  -- Now friends: A's like on the friends-only post notifies.
  delete from public.likes where post_id = '20000000-0000-0000-0000-000000000002' and user_id = a;
  insert into public.likes(post_id, user_id) values ('20000000-0000-0000-0000-000000000002', a);
  assert exists (select 1 from public.notifications where post_id = '20000000-0000-0000-0000-000000000002' and user_id = b),
    'friends-only post notifies a friend';

  -- Cancel removes the matching pending request notification.
  perform nt.act_as(c); snap := public.send_friend_request(b); perform nt.reset_role();
  rel := (snap->>'friendship_id')::uuid;
  assert exists (select 1 from public.notifications where source_id = rel), 'C request notifies';
  perform nt.act_as(c); perform public.cancel_friend_request(rel); perform nt.reset_role();
  assert not exists (select 1 from public.notifications where source_id = rel), 'cancel removes request notification';

  -- Decline keeps history but marks it read.
  perform nt.act_as(c); snap := public.send_friend_request(b); perform nt.reset_role();
  rel := (snap->>'friendship_id')::uuid;
  perform nt.act_as(b); perform public.decline_friend_request(rel); perform nt.reset_role();
  assert (select is_read from public.notifications where source_id = rel), 'decline marks read';
end $$;

-- Preferences: default on, own-user only, opt-out suppresses without failing, no backfill.
do $$
declare a uuid := nt.uid('a'); b uuid := nt.uid('b'); c uuid := nt.uid('c'); prefs jsonb; before bigint; jobs_before bigint; snap jsonb;
begin
  perform nt.act_as(b);
  prefs := public.get_my_notification_preferences();
  assert prefs = '{"comment": true, "friendship": true, "like": true}'::jsonb, 'defaults enabled: ' || prefs;
  prefs := public.set_my_notification_preferences('like', false);
  assert prefs->>'like' = 'false' and prefs->>'comment' = 'true', 'like disabled';
  perform nt.expect_error($q$select public.set_my_notification_preferences('push', true)$q$, 'F0001');
  perform nt.expect_error($q$select public.set_my_notification_preferences('like', null)$q$, 'F0001');
  perform nt.expect_error('select * from studyhub_private.notification_preferences', '42501');
  perform nt.reset_role();

  before := nt.inbox(b, 'like'); jobs_before := nt.jobs(b);
  delete from public.likes where post_id = '20000000-0000-0000-0000-000000000001' and user_id = c;
  insert into public.likes(post_id, user_id) values ('20000000-0000-0000-0000-000000000001', c);
  assert exists (select 1 from public.likes where post_id = '20000000-0000-0000-0000-000000000001' and user_id = c),
    'business action succeeds when suppressed';
  assert nt.inbox(b, 'like') = before, 'opt-out suppresses like';
  assert nt.jobs(b) = jobs_before, 'no delivery job for suppressed event';
  assert before > 0, 'history still present';

  perform nt.act_as(b); perform public.set_my_notification_preferences('like', true); perform nt.reset_role();
  assert nt.inbox(b, 'like') = before, 're-enable does not backfill';

  -- Friendship opt-out: request still created, notification suppressed (cross-change exception).
  perform nt.act_as(c); perform public.set_my_notification_preferences('friendship', false); perform nt.reset_role();
  perform nt.act_as(a); snap := public.send_friend_request(c); perform nt.reset_role();
  assert snap->>'state' is not null and exists (select 1 from public.friendships where id = (snap->>'friendship_id')::uuid),
    'request created';
  assert nt.inbox(c, 'friend_request') = 0, 'friendship opt-out suppresses request';

  perform nt.act_as(null);
  perform nt.expect_error('select public.get_my_notification_preferences()', '42501');
  perform nt.reset_role();
end $$;

-- Pagination: 45 equal timestamps, deterministic tie-breaker, filters, global unread count.
do $$
declare c uuid := nt.uid('c'); a uuid := nt.uid('a'); page jsonb; seen uuid[] := '{}'; cur jsonb; pages int := 0; ids uuid[];
begin
  delete from public.notifications where user_id = c;
  insert into public.notifications(user_id, actor_id, type, is_read, created_at)
    select c, a, 'friend_accept', g % 3 = 0, '2026-11-01T00:00:00Z' from generate_series(1, 45) g;
  perform nt.act_as(c);
  loop
    page := public.list_my_notifications_v2('all', 20,
      (cur->>'created_at')::timestamptz, (cur->>'id')::uuid);
    pages := pages + 1;
    select array_agg((x->>'id')::uuid) into ids from jsonb_array_elements(page->'items') x;
    assert not (seen && ids), 'no duplicate across pages';
    seen := seen || ids;
    assert (page->>'unread_count')::int = 30, 'global unread count on every page';
    cur := page->'next_cursor';
    exit when cur is null or jsonb_typeof(cur) = 'null';
  end loop;
  assert cardinality(seen) = 45 and pages = 3, format('45 rows once in 3 pages, got %s in %s', cardinality(seen), pages);

  page := public.list_my_notifications_v2('unread', 50);
  assert jsonb_array_length(page->'items') = 30 and (page->>'unread_count')::int = 30, 'unread filter';
  assert (select bool_and(not (x->>'is_read')::boolean) from jsonb_array_elements(page->'items') x), 'unread only';
  page := public.list_my_notifications_v2('all', 500);
  assert jsonb_array_length(page->'items') = 45 and page->'next_cursor' = 'null'::jsonb, 'oversized limit accepted, capped at 50';
  assert (select array_agg(k order by k) from jsonb_object_keys(page->'items'->0) k)
       = array['actor','created_at','id','is_read','opened_at','post_id','source_id','source_kind','template_version','type'],
    'v2 item keys';
  page := public.list_my_notifications(20);
  assert (select array_agg(k order by k) from jsonb_object_keys(page->'items'->0) k)
       = array['actor','created_at','id','is_read','post_id','type'], 'legacy item keys preserved';

  perform nt.expect_error($q$select public.list_my_notifications_v2('read', 20)$q$, 'F0001');
  perform nt.expect_error($q$select public.list_my_notifications_v2('all', 0)$q$, 'F0001');
  perform nt.expect_error($q$select public.list_my_notifications_v2('all', 20, now(), null)$q$, 'F0001');
  perform nt.expect_error($q$select public.list_my_notifications_v2('all', 20, 'infinity', gen_random_uuid())$q$, 'F0001');
  perform nt.reset_role();
end $$;

-- Read/open semantics, ownership and visibility changes.
do $$
declare a uuid := nt.uid('a'); b uuid := nt.uid('b'); c uuid := nt.uid('c'); d uuid := nt.uid('d');
  target uuid; first_open timestamptz; changed int; d_note uuid;
begin
  select id into target from public.notifications where user_id = b and type = 'like' and not is_read limit 1;
  perform nt.act_as(c);
  perform nt.expect_error(format('select public.open_my_notification(%L)', target), 'F0004');
  assert public.mark_notifications_read(array[target]) = 0, 'C cannot mark B notification';
  perform nt.reset_role();
  assert not (select is_read from public.notifications where id = target), 'still unread after C attempts';

  perform nt.act_as(b);
  perform public.open_my_notification(target);
  perform nt.reset_role();
  select opened_at into first_open from public.notifications where id = target;
  assert first_open is not null and (select is_read from public.notifications where id = target), 'open marks read + opened';
  perform pg_sleep(0.01);
  perform nt.act_as(b); perform public.open_my_notification(target); perform nt.reset_role();
  assert (select opened_at from public.notifications where id = target) = first_open, 'first open recorded once';

  -- Mark-all does not count as opening.
  perform nt.act_as(b); changed := public.mark_notifications_read(null); perform nt.reset_role();
  assert changed > 0, 'mark-all changed rows';
  assert (select count(*) from public.notifications where user_id = b and opened_at is not null) = 1, 'mark-all is not open';
  perform nt.act_as(b);
  perform nt.expect_error('select public.mark_notifications_read(array[]::uuid[])', 'F0001');
  perform nt.reset_role();

  -- Banned actor: hidden from rows and count, producer suppressed.
  insert into public.notifications(user_id, actor_id, type) values (b, d, 'friend_accept') returning id into d_note;
  update public.profiles set is_banned = true where id = d;
  perform nt.act_as(b);
  assert not exists (select 1 from jsonb_array_elements(public.list_my_notifications_v2('all', 50)->'items') x
                     where (x->>'id')::uuid = d_note), 'banned actor hidden';
  assert (public.list_my_notifications_v2('unread', 1)->>'unread_count')::int = 0, 'banned actor excluded from count';
  perform nt.expect_error(format('select public.open_my_notification(%L)', d_note), 'F0004');
  perform nt.reset_role();
  assert exists (select 1 from studyhub_private.notification_invalidations where scope = 'actor' and source_id = d),
    'ban queues invalidation';
  insert into public.likes(post_id, user_id) values ('20000000-0000-0000-0000-000000000001', d);
  assert not exists (select 1 from public.notifications where actor_id = d and type = 'like'), 'banned actor produces nothing';
  update public.profiles set is_banned = false where id = d;

  -- Block hides existing rows in both list and count, and suppresses new ones.
  insert into public.blocks(blocker_id, blocked_id) values (b, a);
  perform nt.act_as(b);
  assert not exists (select 1 from jsonb_array_elements(public.list_my_notifications_v2('all', 50)->'items') x
                     where x->'actor'->>'id' = a::text), 'blocked actor hidden';
  perform nt.reset_role();
  insert into public.comments(post_id, user_id, content) values ('20000000-0000-0000-0000-000000000001', a, 'blocked');
  assert not exists (select 1 from public.notifications n join public.comments cm on cm.id = n.source_id
                     where cm.content = 'blocked'), 'blocked actor produces nothing';
  delete from public.blocks where blocker_id = b and blocked_id = a;

  -- Post deletion removes its notifications.
  delete from public.posts where id = '20000000-0000-0000-0000-000000000001';
  assert not exists (select 1 from public.notifications where post_id = '20000000-0000-0000-0000-000000000001'),
    'post deletion removes notifications';
end $$;

-- Direct table access is closed for anon/authenticated; helpers are not executable.
do $$
begin
  perform nt.act_as(nt.uid('b'));
  perform nt.expect_error('select * from public.notifications', '42501');
  perform nt.expect_error($q$insert into public.notifications(user_id, actor_id, type) values (auth.uid(), auth.uid(), 'like')$q$, '42501');
  perform nt.expect_error('update public.notifications set is_read = true', '42501');
  perform nt.expect_error('delete from public.notifications', '42501');
  perform nt.expect_error($q$select studyhub_private.create_notification(auth.uid(), auth.uid(), 'like', 'like', gen_random_uuid())$q$, '42501');
  perform nt.expect_error('select studyhub_private.dispatch_notifications()', '42501');
  perform nt.expect_error('select * from studyhub_private.notification_delivery_jobs', '42501');
  perform nt.reset_role();
  perform nt.act_as(null);
  perform nt.expect_error('select * from public.notifications', '42501');
  perform nt.expect_error($q$select public.list_my_notifications_v2('all', 20)$q$, '42501');
  perform nt.expect_error('select public.mark_notifications_read(null)', '42501');
  perform nt.reset_role();
end $$;

-- Regression: notification migrations must not strip other features' grants on
-- studyhub_private (unified search indexes call search_norm as the writing user).
do $$
begin
  if to_regprocedure('studyhub_private.search_norm(text)') is not null then
    assert has_function_privilege('authenticated', 'studyhub_private.search_norm(text)', 'execute'),
      'search_norm grant must survive notification migrations';
    perform nt.act_as(nt.uid('a'));
    insert into public.posts(author_id, title, content, type) values (nt.uid('a'), 'Bài thử', 'nội dung', 'none:public');
    perform nt.reset_role();
  end if;
  assert not has_function_privilege('authenticated',
    'studyhub_private.create_notification(uuid,uuid,text,text,uuid,uuid)', 'execute'), 'notification helpers stay private';
end $$;

select 'PASS 01_center' as result;
rollback;
