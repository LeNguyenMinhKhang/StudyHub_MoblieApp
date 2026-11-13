-- Outbox atomicity, worker publication confirmation, retry/dead-letter, invalidations,
-- retention, health and Realtime topic authorization. Run as supabase_admin (fault
-- injection needs it); everything is rolled back, including injected triggers.
\set ON_ERROR_STOP 1
begin;

insert into public.posts(id, author_id, title, type) values
  ('30000000-0000-0000-0000-000000000001', nt.uid('b'), 'delivery', 'question:public');

create function nt.reset_delivery() returns void language sql as $$
  delete from studyhub_private.notification_delivery_jobs;
  delete from studyhub_private.notification_dispatch_state;
  delete from studyhub_private.notification_invalidations;
$$;
create function nt.messages(dispatch uuid) returns bigint language sql as $$
  select count(*) from realtime.messages where payload->>'dispatch_id' = dispatch::text;
$$;
create function nt.fail_insert() returns trigger language plpgsql as $$
begin raise exception 'injected realtime insert failure'; end $$;
create function nt.mangle_event() returns trigger language plpgsql as $$
begin new.event := 'something_else'; return new; end $$;
create function nt.mangle_private() returns trigger language plpgsql as $$
begin new.private := false; return new; end $$;
create function nt.duplicate_row() returns trigger language plpgsql as $$
begin
  if pg_trigger_depth() = 1 then
    insert into realtime.messages(payload, event, topic, private, extension)
      values (new.payload, new.event, new.topic, new.private, new.extension);
  end if;
  return null;
end $$;

-- 1. Atomicity: inbox + job commit together; job failure rolls back the like.
do $$
declare a uuid := nt.uid('a'); b uuid := nt.uid('b');
begin
  perform nt.reset_delivery();
  alter table studyhub_private.notification_delivery_jobs add constraint nt_fail check (recipient_id <> nt.uid('b'));
  begin
    insert into public.likes(post_id, user_id) values ('30000000-0000-0000-0000-000000000001', a);
    raise exception 'like should have failed';
  exception when check_violation then null;
  end;
  assert not exists (select 1 from public.likes where post_id = '30000000-0000-0000-0000-000000000001'),
    'business row rolled back with outbox failure';
  alter table studyhub_private.notification_delivery_jobs drop constraint nt_fail;

  -- Statement-level outbox: mark-all over many rows yields one job per recipient.
  insert into public.notifications(user_id, actor_id, type) select b, a, 'friend_accept' from generate_series(1, 30);
  assert nt.jobs(b) = 1, 'bulk insert -> one job';
  update public.notifications set is_read = true where user_id = b and not is_read;
  assert nt.jobs(b) = 2, 'bulk update -> one more job';
  update public.notifications set is_read = true where user_id = b;
  assert nt.jobs(b) = 2, 'no-op update enqueues nothing';
end $$;

-- 2. Confirmed grouped publication with a minimal payload.
do $$
declare b uuid := nt.uid('b'); c uuid := nt.uid('c'); r jsonb; dispatch uuid; msg realtime.messages; late uuid;
begin
  perform nt.reset_delivery();
  perform studyhub_private.enqueue_notification(b);
  perform studyhub_private.enqueue_notification(b);
  perform studyhub_private.enqueue_notification(b);
  perform studyhub_private.enqueue_notification(c);
  r := studyhub_private.dispatch_notifications();
  assert (r->>'claimed')::int = 4 and (r->>'published')::int = 4, 'published: ' || r;
  assert (select count(distinct last_dispatch_id) from studyhub_private.notification_delivery_jobs where recipient_id = b) = 1,
    'group shares one dispatch id';
  select last_dispatch_id into dispatch from studyhub_private.notification_delivery_jobs where recipient_id = b limit 1;
  assert dispatch <> (select last_dispatch_id from studyhub_private.notification_delivery_jobs where recipient_id = c limit 1),
    'recipients get distinct dispatch ids';
  assert not exists (select 1 from studyhub_private.notification_delivery_jobs where id = dispatch), 'dispatch id is not a job id';
  select * into msg from realtime.messages where payload->>'dispatch_id' = dispatch::text;
  assert msg.topic = 'notifications:' || b and msg.event = 'notifications_changed' and msg.private and msg.extension = 'broadcast',
    'broadcast record shape';
  assert (select array_agg(k order by k) from jsonb_object_keys(msg.payload) k) = array['dispatch_id','id','schema_version'],
    'minimal payload: ' || msg.payload;
  assert (select bool_and(status = 'published' and attempts = 1 and published_at is not null)
          from studyhub_private.notification_delivery_jobs), 'all claimed jobs published';

  -- Rate limit: a new job within 5 s is deferred (not dropped, not published).
  perform studyhub_private.enqueue_notification(b);
  select id into late from studyhub_private.notification_delivery_jobs where status = 'pending';
  r := studyhub_private.dispatch_notifications();
  assert (r->>'deferred')::int = 1 and (r->>'published')::int = 0, 'deferred: ' || r;
  assert (select status = 'pending' and attempts = 0
            and next_attempt_at = (select last_published_at from studyhub_private.notification_dispatch_state where user_id = b)
                                  + interval '5 seconds'
          from studyhub_private.notification_delivery_jobs where id = late), 'deferred to interval boundary';
end $$;

-- 3. realtime.send swallows the INSERT error (WARNING only) -> retry, fresh ids, dead-letter, requeue.
do $$
declare b uuid := nt.uid('b'); r jsonb; job uuid; ids uuid[] := '{}'; inbox_before bigint; unread_before bigint; j record;
begin
  perform nt.reset_delivery();
  select count(*), count(*) filter (where not is_read) into inbox_before, unread_before from public.notifications where user_id = b;
  create trigger nt_fail_insert before insert on realtime.messages for each row execute function nt.fail_insert();
  perform studyhub_private.enqueue_notification(b);
  select id into job from studyhub_private.notification_delivery_jobs;
  for i in 1..5 loop
    update studyhub_private.notification_delivery_jobs set next_attempt_at = now() - interval '1 second' where id = job;
    r := studyhub_private.dispatch_notifications();
    select * into j from studyhub_private.notification_delivery_jobs where id = job;
    assert j.attempts = i and j.safe_error_code = 'broadcast_unconfirmed' and j.published_at is null, 'attempt ' || i;
    assert j.status = case when i < 5 then 'retry' else 'dead_letter' end, 'status after attempt ' || i || ': ' || j.status;
    assert not (j.last_dispatch_id = any(ids)), 'fresh dispatch id per attempt';
    assert nt.messages(j.last_dispatch_id) = 0, 'no broadcast record for failed attempt';
    ids := ids || j.last_dispatch_id;
    if i < 5 then
      assert j.next_attempt_at - clock_timestamp() between interval '0 s' and
             make_interval(secs => 5 * power(2, least(i - 1, 3)) * 1.2 + 1), 'bounded backoff ' || i;
    end if;
  end loop;
  assert (select dead_letter = 1 and alarm from studyhub_private.notification_health), 'dead letter raises alarm';
  r := studyhub_private.dispatch_notifications();
  assert (r->>'claimed')::int = 0, 'dead letter not retried automatically';

  drop trigger nt_fail_insert on realtime.messages;
  assert studyhub_private.requeue_notification_jobs(array[job]) = 1, 'operator requeue';
  r := studyhub_private.dispatch_notifications();
  select * into j from studyhub_private.notification_delivery_jobs where id = job;
  assert j.status = 'published' and j.attempts = 1 and not (j.last_dispatch_id = any(ids)) and nt.messages(j.last_dispatch_id) = 1,
    'requeued job published with a new dispatch id';
  assert (select count(*) from public.notifications where user_id = b) = inbox_before
     and (select count(*) from public.notifications where user_id = b and not is_read) = unread_before,
    'replay does not change inbox or read state';
end $$;

-- 4. Mismatched / duplicate / pre-existing confirmations fail; worker read denial fails safely.
do $$
declare b uuid := nt.uid('b'); r jsonb; j record; stale uuid := gen_random_uuid();
begin
  -- Mismatched event.
  perform nt.reset_delivery();
  create trigger nt_mangle before insert on realtime.messages for each row execute function nt.mangle_event();
  perform studyhub_private.enqueue_notification(b);
  r := studyhub_private.dispatch_notifications();
  select * into j from studyhub_private.notification_delivery_jobs;
  assert j.status = 'retry' and j.safe_error_code = 'broadcast_unconfirmed' and nt.messages(j.last_dispatch_id) = 0,
    'mismatched event fails and the record is rolled back';
  drop trigger nt_mangle on realtime.messages;

  -- Mismatched private flag.
  perform nt.reset_delivery();
  create trigger nt_mangle before insert on realtime.messages for each row execute function nt.mangle_private();
  perform studyhub_private.enqueue_notification(b);
  r := studyhub_private.dispatch_notifications();
  assert (select status = 'retry' from studyhub_private.notification_delivery_jobs), 'public record does not confirm';
  drop trigger nt_mangle on realtime.messages;

  -- Duplicate exact match is ambiguous.
  perform nt.reset_delivery();
  create trigger nt_dup after insert on realtime.messages for each row execute function nt.duplicate_row();
  perform studyhub_private.enqueue_notification(b);
  r := studyhub_private.dispatch_notifications();
  select * into j from studyhub_private.notification_delivery_jobs;
  assert j.status = 'retry' and nt.messages(j.last_dispatch_id) = 0, 'duplicate confirmation fails';
  drop trigger nt_dup on realtime.messages;

  -- An older record on the same topic, or a pre-existing correlation id, proves nothing.
  insert into realtime.messages(payload, event, topic, private, extension)
    values (jsonb_build_object('schema_version', 1, 'dispatch_id', stale), 'notifications_changed',
            'notifications:' || b, true, 'broadcast');
  perform nt.expect_error(format('select studyhub_private.publish_notification(%L, %L)', b, stale), 'N0001');
  assert nt.messages(stale) = 1, 'stale id not re-sent';

  -- Adapter runs as a role that cannot read Realtime -> failure recorded, nothing published.
  -- (postgres itself always reads realtime.messages through pg_read_all_data membership.)
  perform nt.reset_delivery();
  create role nt_unprivileged_worker nologin;
  grant usage on schema studyhub_private to nt_unprivileged_worker;
  alter function studyhub_private.publish_notification(uuid, uuid) owner to nt_unprivileged_worker;
  perform studyhub_private.enqueue_notification(b);
  r := studyhub_private.dispatch_notifications();
  select * into j from studyhub_private.notification_delivery_jobs;
  assert j.status = 'retry' and j.safe_error_code = 'permission_denied' and j.published_at is null,
    'read denial: ' || coalesce(j.safe_error_code, j.status);
  alter function studyhub_private.publish_notification(uuid, uuid) owner to postgres;
end $$;

-- 5. Rollback after confirmation: neither the record nor published status survives.
do $$
declare b uuid := nt.uid('b'); r jsonb; dispatch uuid;
begin
  perform nt.reset_delivery();
  perform studyhub_private.enqueue_notification(b);
  begin
    r := studyhub_private.dispatch_notifications();
    select last_dispatch_id into dispatch from studyhub_private.notification_delivery_jobs;
    assert nt.messages(dispatch) = 1 and (r->>'published')::int = 1, 'confirmed inside transaction';
    raise exception 'simulated crash' using errcode = 'P0099';
  exception when sqlstate 'P0099' then null;
  end;
  assert nt.messages(dispatch) = 0, 'broadcast record rolled back';
  assert (select status = 'pending' and attempts = 0 and last_dispatch_id is null
          from studyhub_private.notification_delivery_jobs), 'claim/status rolled back; job available again';
  r := studyhub_private.dispatch_notifications();
  assert (r->>'published')::int = 1, 'job reprocessed after crash';
end $$;

-- 6. Batch bound, banned recipient skip, deleted recipient cleanup, invalidations, retention.
do $$
declare a uuid := nt.uid('a'); b uuid := nt.uid('b'); c uuid := nt.uid('c'); d uuid := nt.uid('d'); r jsonb;
begin
  perform nt.reset_delivery();
  insert into studyhub_private.notification_delivery_jobs(recipient_id, next_attempt_at)
    select case when g % 2 = 0 then b else c end, now() - interval '2 minutes' from generate_series(1, 150) g;
  assert (select oldest_due_seconds > 60 and alarm from studyhub_private.notification_health), 'oldest-due alarm';
  r := studyhub_private.dispatch_notifications();
  assert (r->>'claimed')::int = 100 and (r->>'published')::int = 100, 'batch bounded: ' || r;
  assert (select count(*) from studyhub_private.notification_delivery_jobs where status = 'pending') = 50, '50 left for later';
  assert (select count(distinct last_dispatch_id) from studyhub_private.notification_delivery_jobs where status = 'published') = 2,
    'coalesced to one signal per recipient';

  perform nt.reset_delivery();
  update public.profiles set is_banned = true where id = d;
  delete from studyhub_private.notification_delivery_jobs;
  perform studyhub_private.enqueue_notification(d);
  r := studyhub_private.dispatch_notifications();
  assert (select status = 'skipped' and safe_error_code = 'recipient_unavailable' from studyhub_private.notification_delivery_jobs),
    'banned recipient skipped without payload';
  update public.profiles set is_banned = false where id = d;

  -- Ban of an actor fans out to affected recipients through bounded invalidation.
  perform nt.reset_delivery();
  insert into public.notifications(user_id, actor_id, type) values (b, d, 'friend_accept'), (c, d, 'friend_accept');
  delete from studyhub_private.notification_delivery_jobs;
  update public.profiles set is_banned = true where id = d;
  r := studyhub_private.dispatch_notifications();
  assert exists (select 1 from studyhub_private.notification_delivery_jobs where recipient_id = b)
     and exists (select 1 from studyhub_private.notification_delivery_jobs where recipient_id = c),
    'ban invalidation reaches recipients';
  assert not exists (select 1 from studyhub_private.notification_invalidations), 'invalidation consumed';
  update public.profiles set is_banned = false where id = d;

  -- Block/unblock enqueues both sides; post ownership change queues a post invalidation.
  perform nt.reset_delivery();
  insert into public.blocks(blocker_id, blocked_id) values (a, c);
  assert nt.jobs(a) = 1 and nt.jobs(c) = 1, 'block invalidates both users';
  update public.posts set author_id = c where id = '30000000-0000-0000-0000-000000000001';
  assert exists (select 1 from studyhub_private.notification_invalidations where scope = 'post'), 'post change queued';

  -- Deleted recipient: inbox and jobs cascade, no orphan job is created.
  perform nt.reset_delivery();
  insert into public.notifications(user_id, actor_id, type) values (d, a, 'friend_accept');
  assert nt.jobs(d) = 1, 'job for d';
  delete from auth.users where id = d;
  assert nt.jobs(d) = 0 and nt.inbox(d) = 0, 'recipient deletion cleans inbox and jobs';

  -- Deleted inbox row: replaying its jobs never recreates it.
  perform nt.reset_delivery();
  delete from public.notifications where user_id = b;
  r := studyhub_private.dispatch_notifications();
  assert nt.inbox(b) = 0, 'delivery never recreates inbox rows';

  -- Retention: only completed history older than 7 days, bounded.
  perform nt.reset_delivery();
  insert into studyhub_private.notification_delivery_jobs(recipient_id, status, published_at, created_at) values
    (b, 'published', now() - interval '8 days', now() - interval '8 days'),
    (b, 'skipped', now() - interval '8 days', now() - interval '8 days'),
    (b, 'published', now() - interval '6 days', now() - interval '6 days');
  insert into studyhub_private.notification_delivery_jobs(recipient_id, status, created_at) values
    (b, 'pending', now() - interval '30 days'), (b, 'retry', now() - interval '30 days'),
    (b, 'dead_letter', now() - interval '30 days');
  assert studyhub_private.cleanup_notification_jobs(1) = 1, 'bounded cleanup batch';
  assert studyhub_private.cleanup_notification_jobs() = 1, 'second batch';
  assert (select count(*) from studyhub_private.notification_delivery_jobs) = 4
     and not exists (select 1 from studyhub_private.notification_delivery_jobs
                     where status in ('published','skipped') and published_at < now() - interval '7 days'),
    'pending/retry/dead-letter and recent history kept';
end $$;

-- 7. Realtime authorization as the Realtime server evaluates it (role + claims + topic).
do $$
declare a uuid := nt.uid('a'); b uuid := nt.uid('b'); r jsonb; seen bigint;
begin
  perform nt.reset_delivery();
  perform studyhub_private.enqueue_notification(a);
  perform studyhub_private.enqueue_notification(b);
  r := studyhub_private.dispatch_notifications();

  perform set_config('realtime.topic', 'notifications:' || a, true);
  perform nt.act_as(a);
  select count(*) into seen from realtime.messages where topic = 'notifications:' || a;
  perform nt.reset_role();
  assert seen >= 1, 'owner can receive own topic';

  perform set_config('realtime.topic', 'notifications:' || b, true);
  perform nt.act_as(a);
  select count(*) into seen from realtime.messages;
  perform nt.reset_role();
  assert seen = 0, 'cannot receive another user topic';

  perform nt.act_as(null);
  select count(*) into seen from realtime.messages;
  perform nt.reset_role();
  assert seen = 0, 'anonymous cannot receive';

  perform set_config('realtime.topic', 'notifications:' || a, true);
  perform nt.act_as(a);
  perform nt.expect_error(format($q$insert into realtime.messages(payload, event, topic, private, extension)
    values ('{}', 'notifications_changed', %L, true, 'broadcast')$q$, 'notifications:' || a), '42501');
  perform nt.reset_role();

  update public.profiles set is_banned = true where id = a;
  perform set_config('realtime.topic', 'notifications:' || a, true);
  perform nt.act_as(a);
  select count(*) into seen from realtime.messages;
  perform nt.reset_role();
  assert seen = 0, 'banned user cannot receive';
  update public.profiles set is_banned = false where id = a;

  -- Existing chat uses public postgres_changes channels; the only policies on realtime.messages are ours.
  assert (select count(*) from pg_policies where schemaname = 'realtime' and tablename = 'messages'
          and policyname not like 'notifications_%') = 0, 'no other realtime.messages policies affected';
  perform set_config('realtime.topic', '', true);
end $$;

select 'PASS 02_delivery' as result;
rollback;
