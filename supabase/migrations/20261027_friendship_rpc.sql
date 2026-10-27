begin;

-- Fail before changing anything; never normalize existing user relationships.
lock table public.friendships in share row exclusive mode;
do $$
begin
    if exists (select 1 from public.friendships where requester_id = addressee_id)
       or exists (select 1 from public.friendships
          group by least(requester_id, addressee_id), greatest(requester_id, addressee_id)
          having count(*) > 1) then
        raise exception 'friendship preflight failed: self or duplicate pair' using errcode = 'F0001';
    end if;
end;
$$;
alter table public.friendships drop constraint if exists friendships_distinct_users;
alter table public.friendships add constraint friendships_distinct_users check (requester_id <> addressee_id);
create unique index if not exists friendships_unordered_pair_key
    on public.friendships (least(requester_id, addressee_id), greatest(requester_id, addressee_id));
create index if not exists friendships_requester_page_idx on public.friendships(requester_id, status, created_at desc, id desc);
create index if not exists friendships_addressee_page_idx on public.friendships(addressee_id, status, created_at desc, id desc);

create schema if not exists studyhub_private;
revoke all on schema studyhub_private from public, anon, authenticated;

create or replace function studyhub_private.friend_actor() returns uuid
language plpgsql security definer set search_path = '' as $$
declare actor uuid := auth.uid();
begin
    if actor is null then raise exception 'session required' using errcode = 'F0005'; end if;
    if not exists(select 1 from public.profiles where id = actor and not is_banned) then
        raise exception 'actor unavailable' using errcode = 'F0006';
    end if;
    return actor;
end;
$$;

create or replace function studyhub_private.friend_peer_available(actor uuid, target uuid) returns boolean
language sql stable security definer set search_path = '' as $$
    select exists(select 1 from public.profiles where id = target and not is_banned)
       and not exists(select 1 from public.blocks
          where (blocker_id = actor and blocked_id = target) or (blocker_id = target and blocked_id = actor));
$$;

create or replace function studyhub_private.friend_snapshot(actor uuid, target uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare rel public.friendships; state text; relation_id uuid;
begin
    if target is null then raise exception 'invalid target' using errcode = 'F0001'; end if;
    if actor = target then state := 'self';
    elsif not studyhub_private.friend_peer_available(actor, target) then state := 'unavailable';
    else
        select * into rel from public.friendships
        where least(requester_id, addressee_id) = least(actor, target)
          and greatest(requester_id, addressee_id) = greatest(actor, target);
        relation_id := rel.id;
        state := case when rel.id is null then 'none' when rel.status = 'accepted' then 'friends'
          when rel.requester_id = actor then 'outgoing_pending' else 'incoming_pending' end;
    end if;
    return jsonb_build_object('target_id', target, 'state', state, 'friendship_id', relation_id);
end;
$$;

create or replace function public.get_friendship_state(p_target_id uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
begin
    return studyhub_private.friend_snapshot(studyhub_private.friend_actor(), p_target_id);
end;
$$;

create or replace function public.send_friend_request(p_target_id uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare actor uuid := studyhub_private.friend_actor(); rel public.friendships;
begin
    if p_target_id is null or p_target_id = actor then
        raise exception 'invalid target' using errcode = 'F0001';
    end if;
    if not studyhub_private.friend_peer_available(actor, p_target_id) then
        raise exception 'peer unavailable' using errcode = 'F0002';
    end if;
    -- Unique index arbitrates concurrent/reversed sends. A concurrent cleanup
    -- may remove the conflict; retry a bounded number of times with a new snapshot.
    for attempt in 1..3 loop
        insert into public.friendships(requester_id, addressee_id, status)
        values(actor, p_target_id, 'pending') on conflict do nothing;
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
    -- Unknown and unrelated ids deliberately share the same outcome.
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
        update public.friendships set status = 'accepted' where id = rel.id;
        return studyhub_private.friend_snapshot(actor, target);
    elsif action in ('decline','cancel','remove') then
        if (action = 'remove' and rel.status <> 'accepted')
           or (action <> 'remove' and rel.status <> 'pending') then
            raise exception 'relationship changed' using errcode = 'F0004';
        end if;
        delete from public.friendships where id = rel.id;
        return jsonb_build_object('target_id',target,'state','none','friendship_id',null);
    end if;
    raise exception 'invalid action' using errcode = 'F0001';
end;
$$;

create or replace function public.accept_friend_request(p_friendship_id uuid) returns jsonb
language sql security definer set search_path = '' as $$ select studyhub_private.friend_mutate(p_friendship_id,'accept'); $$;
create or replace function public.decline_friend_request(p_friendship_id uuid) returns jsonb
language sql security definer set search_path = '' as $$ select studyhub_private.friend_mutate(p_friendship_id,'decline'); $$;
create or replace function public.cancel_friend_request(p_friendship_id uuid) returns jsonb
language sql security definer set search_path = '' as $$ select studyhub_private.friend_mutate(p_friendship_id,'cancel'); $$;
create or replace function public.remove_friendship(p_friendship_id uuid) returns jsonb
language sql security definer set search_path = '' as $$ select studyhub_private.friend_mutate(p_friendship_id,'remove'); $$;

create or replace function public.list_my_friendships(
    p_kind text, p_limit integer default 20,
    p_cursor_created_at timestamptz default null, p_cursor_id uuid default null
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare actor uuid := studyhub_private.friend_actor(); page_size integer; result jsonb;
begin
    if p_kind is null or p_kind not in ('friends','incoming','outgoing')
       or p_limit is null or p_limit <= 0
       or ((p_cursor_created_at is null) <> (p_cursor_id is null))
       or (p_cursor_created_at is not null and not isfinite(p_cursor_created_at)) then
        raise exception 'invalid page input' using errcode = 'F0001';
    end if;
    page_size := least(p_limit, 50);
    with candidates as materialized (
        select f.id, f.created_at, f.status,
          case when f.requester_id = actor then 'outgoing' else 'incoming' end as direction,
          jsonb_build_object('id',p.id,'username',p.username,'display_name',p.display_name,'avatar_url',p.avatar_url) as peer
        from public.friendships f
        join public.profiles p on p.id = case when f.requester_id = actor then f.addressee_id else f.requester_id end
        where (f.requester_id = actor or f.addressee_id = actor)
          and ((p_kind = 'friends' and f.status = 'accepted')
            or (p_kind = 'incoming' and f.status = 'pending' and f.addressee_id = actor)
            or (p_kind = 'outgoing' and f.status = 'pending' and f.requester_id = actor))
          and not p.is_banned
          and not exists(select 1 from public.blocks b where
            (b.blocker_id = actor and b.blocked_id = p.id) or (b.blocker_id = p.id and b.blocked_id = actor))
          and (p_cursor_id is null or (f.created_at,f.id) < (p_cursor_created_at,p_cursor_id))
        order by f.created_at desc, f.id desc limit page_size + 1
    ), page as (select * from candidates order by created_at desc,id desc limit page_size)
    select jsonb_build_object(
        'items', coalesce((select jsonb_agg(to_jsonb(page) order by created_at desc,id desc) from page),'[]'::jsonb),
        'next_cursor', case when (select count(*) from candidates) > page_size then
           (select jsonb_build_object('created_at',created_at,'id',id) from page order by created_at,id limit 1)
           else null end) into result;
    return result;
end;
$$;

alter table public.friendships enable row level security;
revoke all on public.friendships from public, anon, authenticated;
drop policy if exists friendships_insert_as_requester on public.friendships;
drop policy if exists friendships_update_as_addressee on public.friendships;
drop policy if exists friendships_delete_involved on public.friendships;
revoke all on all functions in schema studyhub_private from public, anon, authenticated;
revoke all on function public.get_friendship_state(uuid), public.send_friend_request(uuid),
    public.accept_friend_request(uuid), public.decline_friend_request(uuid),
    public.cancel_friend_request(uuid), public.remove_friendship(uuid),
    public.list_my_friendships(text,integer,timestamptz,uuid) from public, anon, authenticated;
grant execute on function public.get_friendship_state(uuid), public.send_friend_request(uuid),
    public.accept_friend_request(uuid), public.decline_friend_request(uuid),
    public.cancel_friend_request(uuid), public.remove_friendship(uuid),
    public.list_my_friendships(text,integer,timestamptz,uuid) to authenticated;
notify pgrst, 'reload schema';
commit;
