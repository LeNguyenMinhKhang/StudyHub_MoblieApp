-- Notification delivery: transactional outbox, bounded SQL worker with confirmed
-- private Broadcast publication, retry/dead-letter, invalidations, metrics and
-- receive-only Realtime authorization. Idempotent; depends on 20261106120000.
-- The scheduler is NOT enabled here: see supabase/notifications/enable_scheduler.sql.
begin;

-- ---------------------------------------------------------------------------
-- 1. Private storage. Jobs carry no notification content.
-- ---------------------------------------------------------------------------
create table if not exists studyhub_private.notification_delivery_jobs (
  id uuid primary key default gen_random_uuid(),            -- stable event/job identity
  recipient_id uuid not null references public.profiles(id) on delete cascade,
  channel text not null default 'in_app' check (channel = 'in_app'),
  status text not null default 'pending'
    check (status in ('pending','retry','published','dead_letter','skipped')),
  attempts integer not null default 0 check (attempts >= 0),
  last_dispatch_id uuid,                                     -- most recent attempt's correlation id
  next_attempt_at timestamptz not null default now(),
  created_at timestamptz not null default now(),
  published_at timestamptz,
  safe_error_code text check (safe_error_code is null or safe_error_code ~ '^[a-z0-9_]{1,40}$')
);
create index if not exists notification_jobs_due_idx
  on studyhub_private.notification_delivery_jobs(next_attempt_at, id) where status in ('pending','retry');
create index if not exists notification_jobs_recipient_idx
  on studyhub_private.notification_delivery_jobs(recipient_id);
create index if not exists notification_jobs_completed_idx
  on studyhub_private.notification_delivery_jobs(published_at, id) where status in ('published','skipped');
create index if not exists notification_jobs_dead_idx
  on studyhub_private.notification_delivery_jobs(created_at, id) where status = 'dead_letter';

-- Server-side rate-limit state: last confirmed publication per recipient.
create table if not exists studyhub_private.notification_dispatch_state (
  user_id uuid primary key references public.profiles(id) on delete cascade,
  last_published_at timestamptz
);
-- Pending fan-out of visibility changes (ban / post ownership), expanded in bounded batches.
create table if not exists studyhub_private.notification_invalidations (
  scope text not null check (scope in ('actor','post')),
  source_id uuid not null,
  cursor_id uuid,
  primary key (scope, source_id)
);
alter table studyhub_private.notification_delivery_jobs enable row level security;
alter table studyhub_private.notification_dispatch_state enable row level security;
alter table studyhub_private.notification_invalidations enable row level security;
revoke all on studyhub_private.notification_delivery_jobs, studyhub_private.notification_dispatch_state,
  studyhub_private.notification_invalidations from public, anon, authenticated, service_role;

-- ---------------------------------------------------------------------------
-- 2. Outbox producers: one job per affected recipient per inbox statement, in
--    the same transaction. Deleted recipients get no job (FK cleanup).
-- ---------------------------------------------------------------------------
create or replace function studyhub_private.enqueue_notification(recipient uuid) returns void
language sql security definer set search_path = '' as $$
  insert into studyhub_private.notification_delivery_jobs(recipient_id)
    select id from public.profiles where id = recipient;
$$;

create or replace function studyhub_private.notification_rows_changed() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  if tg_op = 'INSERT' then
    insert into studyhub_private.notification_delivery_jobs(recipient_id)
      select distinct n.user_id from new_rows n join public.profiles p on p.id = n.user_id;
  elsif tg_op = 'DELETE' then
    insert into studyhub_private.notification_delivery_jobs(recipient_id)
      select distinct o.user_id from old_rows o join public.profiles p on p.id = o.user_id;
  else
    insert into studyhub_private.notification_delivery_jobs(recipient_id)
      select distinct n.user_id from new_rows n join old_rows o on o.id = n.id
        join public.profiles p on p.id = n.user_id
      where n is distinct from o;
  end if;
  return null;
end;
$$;
drop trigger if exists notification_outbox on public.notifications;
drop trigger if exists notification_outbox_insert on public.notifications;
drop trigger if exists notification_outbox_update on public.notifications;
drop trigger if exists notification_outbox_delete on public.notifications;
create trigger notification_outbox_insert after insert on public.notifications
  referencing new table as new_rows for each statement execute function studyhub_private.notification_rows_changed();
create trigger notification_outbox_update after update on public.notifications
  referencing new table as new_rows old table as old_rows
  for each statement execute function studyhub_private.notification_rows_changed();
create trigger notification_outbox_delete after delete on public.notifications
  referencing old table as old_rows for each statement execute function studyhub_private.notification_rows_changed();

-- Visibility changes that alter what list/count RPCs return without an inbox write.
create or replace function studyhub_private.notification_visibility_changed() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  if tg_table_name = 'blocks' then
    if tg_op = 'DELETE' then
      perform studyhub_private.enqueue_notification(old.blocker_id);
      perform studyhub_private.enqueue_notification(old.blocked_id);
    else
      perform studyhub_private.enqueue_notification(new.blocker_id);
      perform studyhub_private.enqueue_notification(new.blocked_id);
    end if;
  elsif tg_table_name = 'profiles' then
    insert into studyhub_private.notification_invalidations(scope, source_id) values ('actor', new.id)
      on conflict (scope, source_id) do update set cursor_id = null;
    perform studyhub_private.enqueue_notification(new.id);
  else
    insert into studyhub_private.notification_invalidations(scope, source_id) values ('post', new.id)
      on conflict (scope, source_id) do update set cursor_id = null;
  end if;
  return null;
end;
$$;
drop trigger if exists notifications_blocks on public.blocks;
create trigger notifications_blocks after insert or delete on public.blocks
  for each row execute function studyhub_private.notification_visibility_changed();
drop trigger if exists notifications_banned on public.profiles;
create trigger notifications_banned after update of is_banned on public.profiles
  for each row when (old.is_banned is distinct from new.is_banned)
  execute function studyhub_private.notification_visibility_changed();
drop trigger if exists notifications_post_visibility on public.posts;
create trigger notifications_post_visibility after update of author_id on public.posts
  for each row when (old.author_id is distinct from new.author_id)
  execute function studyhub_private.notification_visibility_changed();

-- Expands at most one scope and 100 recipients per worker run.
create or replace function studyhub_private.expand_notification_invalidations() returns integer
language plpgsql security definer set search_path = '' as $$
declare scope_row studyhub_private.notification_invalidations; recipient uuid; last_id uuid; total integer := 0;
begin
  select * into scope_row from studyhub_private.notification_invalidations
    order by scope, source_id limit 1 for update skip locked;
  if not found then return 0; end if;
  for recipient in
    select distinct n.user_id from public.notifications n
    where ((scope_row.scope = 'actor' and n.actor_id = scope_row.source_id)
        or (scope_row.scope = 'post' and n.post_id = scope_row.source_id))
      and (scope_row.cursor_id is null or n.user_id > scope_row.cursor_id)
    order by n.user_id limit 100
  loop
    perform studyhub_private.enqueue_notification(recipient);
    last_id := recipient; total := total + 1;
  end loop;
  if total < 100 then
    delete from studyhub_private.notification_invalidations
      where scope = scope_row.scope and source_id = scope_row.source_id;
  else
    update studyhub_private.notification_invalidations set cursor_id = last_id
      where scope = scope_row.scope and source_id = scope_row.source_id;
  end if;
  return total;
end;
$$;

-- ---------------------------------------------------------------------------
-- 3. Worker-only publication adapter. realtime.send() converts INSERT errors
--    into WARNINGs, so success is proven only by finding exactly one matching
--    realtime.messages row for this attempt's fresh dispatch_id in the same
--    transaction. Managed Realtime objects are not modified.
-- ---------------------------------------------------------------------------
create or replace function studyhub_private.publish_notification(recipient uuid, p_dispatch_id uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare expected_topic text := 'notifications:' || recipient::text; total bigint; exact bigint;
begin
  if recipient is null or p_dispatch_id is null then
    raise exception 'invalid dispatch' using errcode = 'N0002';
  end if;
  if exists (select 1 from realtime.messages m where m.payload->>'dispatch_id' = p_dispatch_id::text) then
    raise exception 'broadcast_unconfirmed' using errcode = 'N0001';
  end if;
  perform realtime.send(jsonb_build_object('schema_version', 1, 'dispatch_id', p_dispatch_id),
                        'notifications_changed', expected_topic, true);
  select count(*),
         count(*) filter (where m.topic = expected_topic and m.event = 'notifications_changed'
                            and m.private and m.extension = 'broadcast'
                            and m.payload->'schema_version' = '1'::jsonb)
    into total, exact
    from realtime.messages m where m.payload->>'dispatch_id' = p_dispatch_id::text;
  if total <> 1 or exact <> 1 then
    raise exception 'broadcast_unconfirmed' using errcode = 'N0001';
  end if;
end;
$$;

create or replace function studyhub_private.notification_safe_error(state text) returns text
language sql immutable set search_path = '' as $$
  select case state when 'N0001' then 'broadcast_unconfirmed' when 'N0002' then 'invalid_dispatch'
                    when '42501' then 'permission_denied' when '42P01' then 'realtime_schema_missing'
                    when '42703' then 'realtime_schema_mismatch' else 'sqlstate_' || lower(state) end;
$$;

-- ---------------------------------------------------------------------------
-- 4. Bounded worker: single run (advisory lock), <=100 claimed jobs, time bound,
--    per-recipient coalescing, 5-second per-recipient interval, 5 attempts.
-- ---------------------------------------------------------------------------
create or replace function studyhub_private.dispatch_notifications(
  p_batch integer default 100, p_time_budget interval default interval '3 seconds'
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  job_ids uuid[]; recipient uuid; ids uuid[]; dispatch uuid; last_sent timestamptz;
  started timestamptz := clock_timestamp(); published integer := 0; failed integer := 0;
  deferred integer := 0; skipped integer := 0; claimed integer := 0; err text;
  min_interval constant interval := interval '5 seconds';
  max_attempts constant integer := 5;
begin
  if not pg_try_advisory_xact_lock(724160611201) then
    return jsonb_build_object('locked', true);
  end if;
  perform studyhub_private.expand_notification_invalidations();
  select array_agg(j.id) into job_ids from (
    select id from studyhub_private.notification_delivery_jobs
    where status in ('pending','retry') and next_attempt_at <= clock_timestamp()
    order by next_attempt_at, id limit least(greatest(coalesce(p_batch, 100), 1), 100)
    for update skip locked) j;
  claimed := coalesce(cardinality(job_ids), 0);

  for recipient in
    select recipient_id from studyhub_private.notification_delivery_jobs
    where id = any(job_ids) group by recipient_id order by min(next_attempt_at), recipient_id
  loop
    -- Unprocessed claimed jobs stay pending and are released at commit.
    exit when clock_timestamp() - started > p_time_budget;
    select array_agg(id) into ids from studyhub_private.notification_delivery_jobs
      where id = any(job_ids) and recipient_id = recipient;

    if not exists (select 1 from public.profiles where id = recipient and not is_banned) then
      -- Banned recipients cannot subscribe; nothing to signal. Unban enqueues a fresh job.
      update studyhub_private.notification_delivery_jobs
        set status = 'skipped', published_at = clock_timestamp(), safe_error_code = 'recipient_unavailable'
        where id = any(ids);
      skipped := skipped + cardinality(ids);
      continue;
    end if;

    insert into studyhub_private.notification_dispatch_state(user_id) values (recipient) on conflict do nothing;
    select last_published_at into last_sent from studyhub_private.notification_dispatch_state
      where user_id = recipient for update;
    if last_sent is not null and last_sent + min_interval > clock_timestamp() then
      -- Throttle: defer (never drop) and coalesce into the next allowed signal.
      update studyhub_private.notification_delivery_jobs set next_attempt_at = last_sent + min_interval
        where id = any(ids);
      deferred := deferred + cardinality(ids);
      continue;
    end if;

    dispatch := gen_random_uuid();   -- fresh for every attempt, never reused on retry/requeue
    begin
      perform studyhub_private.publish_notification(recipient, dispatch);
      update studyhub_private.notification_delivery_jobs
        set status = 'published', attempts = attempts + 1, last_dispatch_id = dispatch,
            published_at = clock_timestamp(), safe_error_code = null
        where id = any(ids);
      update studyhub_private.notification_dispatch_state set last_published_at = clock_timestamp()
        where user_id = recipient;
      published := published + cardinality(ids);
    exception when others then
      -- The subtransaction (send + confirmation + status) is already rolled back here.
      err := studyhub_private.notification_safe_error(sqlstate);
      update studyhub_private.notification_delivery_jobs
        set attempts = attempts + 1, last_dispatch_id = dispatch, safe_error_code = err,
            status = case when attempts + 1 >= max_attempts then 'dead_letter' else 'retry' end,
            next_attempt_at = clock_timestamp()
              + make_interval(secs => 5 * power(2, least(attempts, 3)) * (1 + random() * 0.2))
        where id = any(ids);
      failed := failed + cardinality(ids);
    end;
  end loop;
  return jsonb_build_object('claimed', claimed, 'published', published, 'failed', failed,
                            'deferred', deferred, 'skipped', skipped);
end;
$$;

-- Operator-only replay. Inbox rows are untouched, so replay cannot duplicate notifications.
create or replace function studyhub_private.requeue_notification_jobs(p_job_ids uuid[] default null, p_limit integer default 100)
returns integer language plpgsql security definer set search_path = '' as $$
declare changed integer;
begin
  update studyhub_private.notification_delivery_jobs set status = 'pending', attempts = 0,
      next_attempt_at = now(), safe_error_code = null
    where id in (select id from studyhub_private.notification_delivery_jobs
                 where status = 'dead_letter' and (p_job_ids is null or id = any(p_job_ids))
                 order by created_at, id limit least(greatest(coalesce(p_limit, 100), 1), 1000)
                 for update skip locked);
  get diagnostics changed = row_count;
  return changed;
end;
$$;

-- Bounded retention: only completed history older than 7 days. Pending/retry/dead-letter are kept.
create or replace function studyhub_private.cleanup_notification_jobs(p_limit integer default 1000) returns integer
language plpgsql security definer set search_path = '' as $$
declare changed integer;
begin
  delete from studyhub_private.notification_delivery_jobs where id in (
    select id from studyhub_private.notification_delivery_jobs
    where status in ('published','skipped') and published_at < now() - interval '7 days'
    order by published_at, id limit least(greatest(coalesce(p_limit, 1000), 1), 5000)
    for update skip locked);
  get diagnostics changed = row_count;
  return changed;
end;
$$;

-- ---------------------------------------------------------------------------
-- 5. Operator metrics. No user ids, content or credentials.
-- ---------------------------------------------------------------------------
create or replace view studyhub_private.notification_health as
with j as (
  select
    count(*) filter (where status = 'pending') as pending,
    count(*) filter (where status = 'retry') as retry,
    count(*) filter (where status = 'dead_letter') as dead_letter,
    coalesce(extract(epoch from now() - min(next_attempt_at)
      filter (where status in ('pending','retry') and next_attempt_at <= now())), 0)::numeric(12,1) as oldest_due_seconds,
    coalesce(max(attempts) filter (where status in ('retry','dead_letter')), 0) as max_attempts_open,
    (avg(extract(epoch from published_at - created_at))
      filter (where status = 'published' and published_at > now() - interval '1 hour'))::numeric(12,2) as avg_publish_seconds_1h,
    (percentile_cont(0.95) within group (order by extract(epoch from published_at - created_at))
      filter (where status = 'published' and published_at > now() - interval '1 hour'))::numeric(12,2) as p95_publish_seconds_1h
  from studyhub_private.notification_delivery_jobs
)
select j.*,
  (select count(*) from public.notifications where opened_at is not null) as opened_total,
  (select count(*) from studyhub_private.notification_invalidations) as pending_invalidations,
  (j.dead_letter > 0 or j.oldest_due_seconds > 60) as alarm
from j;
revoke all on studyhub_private.notification_health from public, anon, authenticated, service_role;

-- Only this change's helpers (see the note in 20261106120000).
revoke all on function studyhub_private.enqueue_notification(uuid),
  studyhub_private.notification_rows_changed(),
  studyhub_private.notification_visibility_changed(),
  studyhub_private.expand_notification_invalidations(),
  studyhub_private.publish_notification(uuid, uuid),
  studyhub_private.notification_safe_error(text),
  studyhub_private.dispatch_notifications(integer, interval),
  studyhub_private.requeue_notification_jobs(uuid[], integer),
  studyhub_private.cleanup_notification_jobs(integer)
  from public, anon, authenticated;
revoke all on function studyhub_private.dispatch_notifications(integer, interval),
  studyhub_private.publish_notification(uuid, uuid),
  studyhub_private.requeue_notification_jobs(uuid[], integer),
  studyhub_private.cleanup_notification_jobs(integer) from service_role;

-- ---------------------------------------------------------------------------
-- 6. Receive-only private topics. Clients never publish and never read rows of
--    other users' topics. Payloads carry only schema_version + dispatch_id.
-- ---------------------------------------------------------------------------
drop policy if exists notifications_receive on realtime.messages;
create policy notifications_receive on realtime.messages for select to authenticated
  using (extension = 'broadcast'
    and realtime.topic() = 'notifications:' || (select auth.uid())::text
    and exists (select 1 from public.profiles p where p.id = (select auth.uid()) and not p.is_banned));
-- Restrictive guard: no future permissive policy can open another user's notification topic.
drop policy if exists notifications_receive_guard on realtime.messages;
create policy notifications_receive_guard on realtime.messages as restrictive for select to authenticated
  using (coalesce(realtime.topic(), '') not like 'notifications:%'
    or (extension = 'broadcast'
        and realtime.topic() = 'notifications:' || (select auth.uid())::text
        and exists (select 1 from public.profiles p where p.id = (select auth.uid()) and not p.is_banned)));
drop policy if exists notifications_no_client_send on realtime.messages;
create policy notifications_no_client_send on realtime.messages as restrictive for insert to authenticated
  with check (coalesce(realtime.topic(), topic, '') not like 'notifications:%' and topic not like 'notifications:%');

notify pgrst, 'reload schema';
commit;
