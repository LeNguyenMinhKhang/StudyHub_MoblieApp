begin;

-- Friendship RPCs write notification rows; clients read them only through the RPCs below.
create index if not exists notifications_user_page_idx
    on public.notifications(user_id, created_at desc, id desc);
create index if not exists notifications_friend_request_idx
    on public.notifications(user_id, actor_id) where type = 'friend_request';

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
    -- Unique index arbitrates concurrent/reversed sends. A concurrent cleanup
    -- may remove the conflict; retry a bounded number of times with a new snapshot.
    for attempt in 1..3 loop
        inserted := null;
        insert into public.friendships(requester_id, addressee_id, status)
        values(actor, p_target_id, 'pending') on conflict do nothing returning id into inserted;
        -- Only a newly created request notifies; repeated sends return the existing state silently.
        if inserted is not null then
            insert into public.notifications(user_id, actor_id, type) values (p_target_id, actor, 'friend_request');
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
        if rel.status = 'pending' then
            update public.friendships set status = 'accepted' where id = rel.id;
            insert into public.notifications(user_id, actor_id, type) values (target, actor, 'friend_accept');
            update public.notifications set is_read = true
            where user_id = actor and actor_id = target and type = 'friend_request' and not is_read;
        end if;
        return studyhub_private.friend_snapshot(actor, target);
    elsif action in ('decline','cancel','remove') then
        if (action = 'remove' and rel.status <> 'accepted')
           or (action <> 'remove' and rel.status <> 'pending') then
            raise exception 'relationship changed' using errcode = 'F0004';
        end if;
        delete from public.friendships where id = rel.id;
        if action = 'cancel' then
            -- A withdrawn request should not linger in the recipient's notifications.
            delete from public.notifications
            where user_id = rel.addressee_id and actor_id = rel.requester_id and type = 'friend_request';
        elsif action = 'decline' then
            update public.notifications set is_read = true
            where user_id = actor and actor_id = target and type = 'friend_request' and not is_read;
        end if;
        return jsonb_build_object('target_id',target,'state','none','friendship_id',null);
    end if;
    raise exception 'invalid action' using errcode = 'F0001';
end;
$$;

create or replace function public.list_my_notifications(
    p_limit integer default 20,
    p_cursor_created_at timestamptz default null, p_cursor_id uuid default null
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare actor uuid := studyhub_private.friend_actor(); page_size integer; result jsonb;
begin
    if p_limit is null or p_limit <= 0
       or ((p_cursor_created_at is null) <> (p_cursor_id is null))
       or (p_cursor_created_at is not null and not isfinite(p_cursor_created_at)) then
        raise exception 'invalid page input' using errcode = 'F0001';
    end if;
    page_size := least(p_limit, 50);
    with visible as (
        select n.id, n.type, n.is_read, n.created_at, n.post_id,
          case when p.id is null then null else
            jsonb_build_object('id',p.id,'username',p.username,'display_name',p.display_name,'avatar_url',p.avatar_url) end as actor
        from public.notifications n
        left join public.profiles p on p.id = n.actor_id
        where n.user_id = actor
          -- Hide rows from banned or blocked actors, matching the friendship visibility rules.
          and (n.actor_id is null or (p.id is not null and studyhub_private.friend_peer_available(actor, p.id)))
    ), candidates as materialized (
        select * from visible
        where p_cursor_id is null or (created_at,id) < (p_cursor_created_at,p_cursor_id)
        order by created_at desc, id desc limit page_size + 1
    ), page as (select * from candidates order by created_at desc,id desc limit page_size)
    select jsonb_build_object(
        'items', coalesce((select jsonb_agg(to_jsonb(page) order by created_at desc,id desc) from page),'[]'::jsonb),
        'next_cursor', case when (select count(*) from candidates) > page_size then
           (select jsonb_build_object('created_at',created_at,'id',id) from page order by created_at,id limit 1)
           else null end,
        'unread_count', (select count(*) from visible where not is_read)) into result;
    return result;
end;
$$;

create or replace function public.mark_notifications_read(p_ids uuid[] default null) returns integer
language plpgsql security definer set search_path = '' as $$
declare actor uuid := studyhub_private.friend_actor(); changed integer;
begin
    if p_ids is not null and (cardinality(p_ids) = 0 or cardinality(p_ids) > 100) then
        raise exception 'invalid ids' using errcode = 'F0001';
    end if;
    -- Null marks everything; ids not owned by the actor are ignored.
    update public.notifications set is_read = true
    where user_id = actor and not is_read and (p_ids is null or id = any(p_ids));
    get diagnostics changed = row_count;
    return changed;
end;
$$;

revoke all on all functions in schema studyhub_private from public, anon, authenticated;
revoke all on function public.send_friend_request(uuid),
    public.list_my_notifications(integer,timestamptz,uuid), public.mark_notifications_read(uuid[])
    from public, anon, authenticated;
grant execute on function public.send_friend_request(uuid),
    public.list_my_notifications(integer,timestamptz,uuid), public.mark_notifications_read(uuid[])
    to authenticated;
notify pgrst, 'reload schema';
commit;
