-- Notification center: source identity, preferences, interaction producers and inbox RPCs.
-- Idempotent so the same body can be mirrored in supabase/schema.sql.
-- Depends on 20261027_friendship_rpc.sql and 20261027150000_friend_notifications.sql.
begin;

-- ---------------------------------------------------------------------------
-- 1. Source identity on the existing inbox. Legacy rows keep NULL source data:
--    there is no provable link from (actor, recipient) to one friendship id.
-- ---------------------------------------------------------------------------
alter table public.notifications
  add column if not exists source_kind text,
  add column if not exists source_id uuid,
  add column if not exists event_key text,
  add column if not exists template_version integer not null default 1,
  add column if not exists opened_at timestamptz;

do $$
begin
  if not exists (select 1 from pg_constraint where conname = 'notifications_source_kind_check') then
    alter table public.notifications add constraint notifications_source_kind_check
      check (source_kind is null or source_kind in ('friendship','like','comment'));
  end if;
  if not exists (select 1 from pg_constraint where conname = 'notifications_source_pair_check') then
    alter table public.notifications add constraint notifications_source_pair_check
      check ((source_kind is null) = (source_id is null) and (event_key is null or source_id is not null));
  end if;
  if not exists (select 1 from pg_constraint where conname = 'notifications_template_version_check') then
    alter table public.notifications add constraint notifications_template_version_check
      check (template_version between 1 and 1000);
  end if;
end $$;

-- One inbox row per recipient + event type + source incarnation; legacy rows are exempt.
create unique index if not exists notifications_event_key_idx
  on public.notifications(user_id, event_key) where event_key is not null;
create index if not exists notifications_source_idx
  on public.notifications(source_kind, source_id) where source_id is not null;
create index if not exists notifications_actor_idx on public.notifications(actor_id) where actor_id is not null;
create index if not exists notifications_post_idx on public.notifications(post_id) where post_id is not null;
create index if not exists notifications_user_unread_idx
  on public.notifications(user_id, created_at desc, id desc) where not is_read;

-- ---------------------------------------------------------------------------
-- 2. Private preferences. Missing rows mean enabled (default-on).
--    notification_recipient_state is the per-recipient lock that serializes
--    producers and preference changes.
-- ---------------------------------------------------------------------------
create schema if not exists studyhub_private;
revoke all on schema studyhub_private from public, anon, authenticated;

create table if not exists studyhub_private.notification_recipient_state (
  user_id uuid primary key references public.profiles(id) on delete cascade,
  created_at timestamptz not null default now()
);
create table if not exists studyhub_private.notification_preferences (
  user_id uuid not null references public.profiles(id) on delete cascade,
  channel text not null default 'in_app' check (channel = 'in_app'),
  category text not null check (category in ('friendship','like','comment')),
  enabled boolean not null,
  updated_at timestamptz not null default now(),
  primary key (user_id, channel, category)
);
alter table studyhub_private.notification_recipient_state enable row level security;
alter table studyhub_private.notification_preferences enable row level security;
revoke all on studyhub_private.notification_recipient_state, studyhub_private.notification_preferences
  from public, anon, authenticated;

create or replace function studyhub_private.lock_notification_recipient(recipient uuid) returns boolean
language plpgsql security definer set search_path = '' as $$
begin
  insert into studyhub_private.notification_recipient_state(user_id)
    select id from public.profiles where id = recipient
    on conflict (user_id) do nothing;
  perform 1 from studyhub_private.notification_recipient_state where user_id = recipient for update;
  return found;
end;
$$;

create or replace function studyhub_private.notification_preferences_json(recipient uuid) returns jsonb
language sql stable security definer set search_path = '' as $$
  select jsonb_object_agg(c.category, coalesce(p.enabled, true) order by c.category)
  from unnest(array['friendship','like','comment']) as c(category)
  left join studyhub_private.notification_preferences p
    on p.user_id = recipient and p.channel = 'in_app' and p.category = c.category;
$$;

create or replace function public.get_my_notification_preferences() returns jsonb
language plpgsql stable security definer set search_path = '' as $$
begin
  return studyhub_private.notification_preferences_json(studyhub_private.friend_actor());
end;
$$;

create or replace function public.set_my_notification_preferences(p_category text, p_enabled boolean) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare actor uuid := studyhub_private.friend_actor();
begin
  if p_category is null or p_category not in ('friendship','like','comment') or p_enabled is null then
    raise exception 'invalid preference' using errcode = 'F0001';
  end if;
  -- Same lock as producers: a setting applies to every interaction committed after it.
  perform studyhub_private.lock_notification_recipient(actor);
  insert into studyhub_private.notification_preferences(user_id, channel, category, enabled)
    values (actor, 'in_app', p_category, p_enabled)
    on conflict (user_id, channel, category) do update set enabled = excluded.enabled, updated_at = now();
  return studyhub_private.notification_preferences_json(actor);
end;
$$;

-- ---------------------------------------------------------------------------
-- 3. Visibility helpers shared by producers, lists, counts and mutations.
-- ---------------------------------------------------------------------------
-- Mirrors PostTypeUtils.parse: "<category>:<visibility>" or a bare visibility word.
create or replace function studyhub_private.notification_post_visibility(raw_type text) returns text
language sql immutable set search_path = '' as $$
  select case v when 'friends' then 'friends' when 'private' then 'private' else 'public' end
  from (select case when position(':' in coalesce(raw_type,'')) > 0
                    then lower(btrim(split_part(raw_type, ':', 2)))
                    else lower(btrim(coalesce(raw_type,''))) end as v) s;
$$;

create or replace function studyhub_private.post_viewable(viewer uuid, target_post uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.posts p join public.profiles a on a.id = p.author_id
    where p.id = target_post and not a.is_banned
      and (p.author_id = viewer or (
        studyhub_private.friend_peer_available(viewer, p.author_id)
        and case studyhub_private.notification_post_visibility(p.type)
              when 'private' then false
              when 'friends' then exists (
                select 1 from public.friendships f where f.status = 'accepted'
                  and least(f.requester_id, f.addressee_id) = least(viewer, p.author_id)
                  and greatest(f.requester_id, f.addressee_id) = greatest(viewer, p.author_id))
              else true end)));
$$;

create or replace function studyhub_private.notification_visible(n public.notifications, viewer uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select n.user_id = viewer
    and exists (select 1 from public.profiles where id = viewer and not is_banned)
    and (n.actor_id is null or studyhub_private.friend_peer_available(viewer, n.actor_id))
    and (n.post_id is null or studyhub_private.post_viewable(viewer, n.post_id));
$$;

-- ---------------------------------------------------------------------------
-- 4. Producer. Recipients come from source rows, never from client input.
--    Returns silently on ineligible/suppressed events; raises on bad input so
--    the business transaction rolls back instead of committing unnotified.
-- ---------------------------------------------------------------------------
create or replace function studyhub_private.create_notification(
  recipient uuid, actor uuid, kind text, p_source_kind text, p_source_id uuid, p_post_id uuid default null
) returns void language plpgsql security definer set search_path = '' as $$
declare v_category text; v_enabled boolean;
begin
  v_category := case when kind in ('friend_request','friend_accept') then 'friendship'
                   when kind in ('like','comment') then kind end;
  if v_category is null or p_source_id is null or p_source_kind is null
     or p_source_kind <> (case v_category when 'friendship' then 'friendship' else kind end) then
    raise exception 'invalid notification source' using errcode = 'F0001';
  end if;
  if recipient is null or actor is null or recipient = actor then return; end if;
  if not exists (select 1 from public.profiles where id = actor and not is_banned)
     or not studyhub_private.friend_peer_available(actor, recipient) then
    return;
  end if;
  if p_post_id is not null and not (
       exists (select 1 from public.posts p where p.id = p_post_id and p.author_id = recipient)
       and studyhub_private.post_viewable(actor, p_post_id)) then
    return;
  end if;
  if not studyhub_private.lock_notification_recipient(recipient) then return; end if;
  select coalesce((select p.enabled from studyhub_private.notification_preferences p
                   where p.user_id = recipient and p.channel = 'in_app' and p.category = v_category), true)
    into v_enabled;
  if not v_enabled then return; end if;
  insert into public.notifications(user_id, actor_id, type, post_id, source_kind, source_id, event_key, template_version)
    values (recipient, actor, kind, p_post_id, p_source_kind, p_source_id, kind || ':' || p_source_id::text, 1)
    on conflict (user_id, event_key) where event_key is not null do nothing;
end;
$$;

create or replace function studyhub_private.notify_post_interaction() returns trigger
language plpgsql security definer set search_path = '' as $$
declare kind text := case tg_table_name when 'likes' then 'like' else 'comment' end; recipient uuid;
begin
  if tg_op = 'DELETE' then
    delete from public.notifications where source_kind = kind and source_id = old.id;
    return old;
  end if;
  -- Replies notify only the post owner in v1; parent-comment authors are not fanned out.
  select author_id into recipient from public.posts where id = new.post_id;
  perform studyhub_private.create_notification(recipient, new.user_id, kind, kind, new.id, new.post_id);
  return new;
end;
$$;

-- INSERT/DELETE only: editing a comment never creates another notification.
drop trigger if exists notifications_like_source on public.likes;
create trigger notifications_like_source after insert or delete on public.likes
  for each row execute function studyhub_private.notify_post_interaction();
drop trigger if exists notifications_comment_source on public.comments;
create trigger notifications_comment_source after insert or delete on public.comments
  for each row execute function studyhub_private.notify_post_interaction();

-- ---------------------------------------------------------------------------
-- 5. Inbox RPCs. Old signatures keep their legacy item shape.
-- ---------------------------------------------------------------------------
create or replace function studyhub_private.notification_page(
  viewer uuid, p_filter text, p_limit integer, p_cursor_created_at timestamptz, p_cursor_id uuid, legacy boolean
) returns jsonb language plpgsql stable security definer set search_path = '' as $$
declare page_size integer; result jsonb;
begin
  if p_filter is null or p_filter not in ('all','unread') or p_limit is null or p_limit <= 0
     or ((p_cursor_created_at is null) <> (p_cursor_id is null))
     or (p_cursor_created_at is not null and not isfinite(p_cursor_created_at)) then
    raise exception 'invalid page input' using errcode = 'F0001';
  end if;
  page_size := least(p_limit, 50);
  with visible as materialized (
    select n.* from public.notifications n
    where n.user_id = viewer and studyhub_private.notification_visible(n, viewer)
  ), candidates as materialized (
    select * from visible
    where (p_filter = 'all' or not is_read)
      and (p_cursor_id is null or (created_at, id) < (p_cursor_created_at, p_cursor_id))
    order by created_at desc, id desc limit page_size + 1
  ), page as (
    select c.*, case when p.id is null then null else jsonb_build_object('id', p.id, 'username', p.username,
             'display_name', p.display_name, 'avatar_url', p.avatar_url) end as actor
    from (select * from candidates order by created_at desc, id desc limit page_size) c
    left join public.profiles p on p.id = c.actor_id
  )
  select jsonb_build_object(
    'items', coalesce((select jsonb_agg(
        case when legacy then jsonb_build_object('id', id, 'type', type, 'is_read', is_read,
               'created_at', created_at, 'post_id', post_id, 'actor', actor)
             else jsonb_build_object('id', id, 'type', type, 'is_read', is_read, 'created_at', created_at,
               'post_id', post_id, 'actor', actor, 'source_kind', source_kind, 'source_id', source_id,
               'template_version', template_version, 'opened_at', opened_at) end
        order by created_at desc, id desc) from page), '[]'::jsonb),
    'next_cursor', case when (select count(*) from candidates) > page_size then
        (select jsonb_build_object('created_at', created_at, 'id', id) from page order by created_at, id limit 1)
      else null end,
    -- Global visible unread count, independent of filter and page.
    'unread_count', (select count(*) from visible where not is_read)) into result;
  return result;
end;
$$;

create or replace function public.list_my_notifications_v2(
  p_filter text default 'all', p_limit integer default 20,
  p_cursor_created_at timestamptz default null, p_cursor_id uuid default null
) returns jsonb language plpgsql stable security definer set search_path = '' as $$
begin
  return studyhub_private.notification_page(studyhub_private.friend_actor(), p_filter, p_limit,
    p_cursor_created_at, p_cursor_id, false);
end;
$$;

create or replace function public.list_my_notifications(
  p_limit integer default 20, p_cursor_created_at timestamptz default null, p_cursor_id uuid default null
) returns jsonb language plpgsql security definer set search_path = '' as $$
begin
  return studyhub_private.notification_page(studyhub_private.friend_actor(), 'all', p_limit,
    p_cursor_created_at, p_cursor_id, true);
end;
$$;

-- Mark-all updates only rows visible to this statement's snapshot; later arrivals stay unread.
create or replace function public.mark_notifications_read(p_ids uuid[] default null) returns integer
language plpgsql security definer set search_path = '' as $$
declare actor uuid := studyhub_private.friend_actor(); changed integer;
begin
  if p_ids is not null and (cardinality(p_ids) = 0 or cardinality(p_ids) > 100) then
    raise exception 'invalid ids' using errcode = 'F0001';
  end if;
  update public.notifications n set is_read = true
    where n.user_id = actor and not n.is_read and (p_ids is null or n.id = any(p_ids))
      and studyhub_private.notification_visible(n, actor);
  get diagnostics changed = row_count;
  return changed;
end;
$$;

-- First open is recorded once; repeats/retries only keep the row read.
create or replace function public.open_my_notification(p_id uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare actor uuid := studyhub_private.friend_actor(); opened public.notifications;
begin
  if p_id is null then raise exception 'invalid id' using errcode = 'F0001'; end if;
  update public.notifications n set is_read = true, opened_at = coalesce(n.opened_at, now())
    where n.id = p_id and n.user_id = actor and studyhub_private.notification_visible(n, actor)
    returning n.* into opened;
  if not found then raise exception 'notification unavailable' using errcode = 'F0004'; end if;
  return jsonb_build_object('id', opened.id, 'type', opened.type, 'post_id', opened.post_id, 'actor_id', opened.actor_id);
end;
$$;

-- ---------------------------------------------------------------------------
-- 6. Friendship producers: same guards/transitions, now with source ids,
--    idempotent event keys and preferences. Legacy rows (source_id NULL)
--    keep the previous actor/recipient cleanup behavior.
-- ---------------------------------------------------------------------------
create or replace function public.send_friend_request(p_target_id uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare actor uuid := studyhub_private.friend_actor(); rel public.friendships; inserted uuid;
begin
  if p_target_id is null or p_target_id = actor then
    raise exception 'invalid target' using errcode = 'F0001';
  end if;
  if not studyhub_private.friend_peer_available(actor, p_target_id) then
    raise exception 'peer unavailable' using errcode = 'F0002';
  end if;
  for attempt in 1..3 loop
    inserted := null;
    insert into public.friendships(requester_id, addressee_id, status)
      values (actor, p_target_id, 'pending') on conflict do nothing returning id into inserted;
    if inserted is not null then
      perform studyhub_private.create_notification(p_target_id, actor, 'friend_request', 'friendship', inserted);
    end if;
    select * into rel from public.friendships
      where least(requester_id, addressee_id) = least(actor, p_target_id)
        and greatest(requester_id, addressee_id) = greatest(actor, p_target_id) for update;
    if found then return studyhub_private.friend_snapshot(actor, p_target_id); end if;
  end loop;
  raise exception 'relationship changed' using errcode = 'F0004';
exception when foreign_key_violation then
  raise exception 'peer unavailable' using errcode = 'F0002';
end;
$$;

create or replace function studyhub_private.friend_mutate(p_id uuid, action text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare actor uuid := studyhub_private.friend_actor(); rel public.friendships; target uuid;
begin
  if p_id is null then raise exception 'invalid relationship' using errcode = 'F0001'; end if;
  select * into rel from public.friendships where id = p_id for update;
  if not found then raise exception 'relationship unavailable' using errcode = 'F0004'; end if;
  if actor not in (rel.requester_id, rel.addressee_id) then
    raise exception 'relationship unavailable' using errcode = 'F0004';
  end if;
  target := case when actor = rel.requester_id then rel.addressee_id else rel.requester_id end;
  if action in ('accept','decline') and actor <> rel.addressee_id
     or action = 'cancel' and actor <> rel.requester_id then
    raise exception 'action not permitted' using errcode = 'F0003';
  end if;
  if action = 'accept' then
    if not studyhub_private.friend_peer_available(actor, target) then
      raise exception 'peer unavailable' using errcode = 'F0002';
    end if;
    if rel.status = 'pending' then
      update public.friendships set status = 'accepted' where id = rel.id;
      perform studyhub_private.create_notification(target, actor, 'friend_accept', 'friendship', rel.id);
      update public.notifications set is_read = true
        where user_id = actor and actor_id = target and type = 'friend_request' and not is_read
          and (source_id = rel.id or source_id is null);
    end if;
    return studyhub_private.friend_snapshot(actor, target);
  elsif action in ('decline','cancel','remove') then
    if (action = 'remove' and rel.status <> 'accepted')
       or (action <> 'remove' and rel.status <> 'pending') then
      raise exception 'relationship changed' using errcode = 'F0004';
    end if;
    delete from public.friendships where id = rel.id;
    if action = 'cancel' then
      delete from public.notifications
        where user_id = rel.addressee_id and actor_id = rel.requester_id and type = 'friend_request'
          and (source_id = rel.id or source_id is null);
    elsif action = 'decline' then
      update public.notifications set is_read = true
        where user_id = actor and actor_id = target and type = 'friend_request' and not is_read
          and (source_id = rel.id or source_id is null);
    end if;
    return jsonb_build_object('target_id', target, 'state', 'none', 'friendship_id', null);
  end if;
  raise exception 'invalid action' using errcode = 'F0001';
end;
$$;

-- ---------------------------------------------------------------------------
-- 7. BREAKING: no direct client table access. RPCs are the only data path.
-- ---------------------------------------------------------------------------
drop policy if exists "notifications_insert_as_actor" on public.notifications;
drop policy if exists "notifications_update_own" on public.notifications;
drop policy if exists "notifications_select_own" on public.notifications;
revoke all on public.notifications from public, anon, authenticated;

-- Only this change's helpers: a schema-wide revoke would also strip grants other
-- features rely on (e.g. search_norm, which posts/profiles index expressions call
-- with the writer's privileges).
revoke all on function studyhub_private.lock_notification_recipient(uuid),
  studyhub_private.notification_preferences_json(uuid),
  studyhub_private.notification_post_visibility(text),
  studyhub_private.post_viewable(uuid, uuid),
  studyhub_private.notification_visible(public.notifications, uuid),
  studyhub_private.create_notification(uuid, uuid, text, text, uuid, uuid),
  studyhub_private.notify_post_interaction(),
  studyhub_private.notification_page(uuid, text, integer, timestamptz, uuid, boolean),
  studyhub_private.friend_mutate(uuid, text)
  from public, anon, authenticated;
revoke all on function public.list_my_notifications_v2(text,integer,timestamptz,uuid),
  public.list_my_notifications(integer,timestamptz,uuid), public.mark_notifications_read(uuid[]),
  public.open_my_notification(uuid), public.get_my_notification_preferences(),
  public.set_my_notification_preferences(text,boolean), public.send_friend_request(uuid)
  from public, anon, authenticated;
grant execute on function public.list_my_notifications_v2(text,integer,timestamptz,uuid),
  public.list_my_notifications(integer,timestamptz,uuid), public.mark_notifications_read(uuid[]),
  public.open_my_notification(uuid), public.get_my_notification_preferences(),
  public.set_my_notification_preferences(text,boolean), public.send_friend_request(uuid)
  to authenticated;

notify pgrst, 'reload schema';
commit;
