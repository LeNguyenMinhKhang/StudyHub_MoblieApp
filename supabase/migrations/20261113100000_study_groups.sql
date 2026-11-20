begin;

create schema if not exists studyhub_private;
revoke all on schema studyhub_private from public, anon, authenticated;

create table public.study_groups (
    id uuid primary key default gen_random_uuid(),
    subject_id uuid not null references public.subjects(id) on delete restrict,
    name text not null check (char_length(name) between 1 and 100),
    description text check (char_length(description) <= 1000),
    owner_id uuid not null references public.profiles(id) on delete restrict,
    created_by uuid not null references public.profiles(id) on delete restrict,
    client_request_id uuid not null,
    creation_payload jsonb not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    archived_at timestamptz,
    unique (created_by, client_request_id)
);
create table public.study_group_members (
    group_id uuid not null references public.study_groups(id) on delete restrict,
    user_id uuid not null references public.profiles(id) on delete restrict,
    joined_at timestamptz not null default now(),
    primary key (group_id, user_id)
);
alter table public.study_groups add constraint study_groups_owner_membership
    foreign key (id, owner_id) references public.study_group_members(group_id, user_id)
    deferrable initially deferred;
create table public.study_group_invitations (
    id uuid primary key default gen_random_uuid(),
    group_id uuid not null references public.study_groups(id) on delete restrict,
    inviter_id uuid not null references public.profiles(id) on delete restrict,
    invitee_id uuid not null references public.profiles(id) on delete restrict,
    status text not null default 'pending' check (status in ('pending','accepted','declined','revoked')),
    created_at timestamptz not null default now(),
    resolved_at timestamptz,
    check (inviter_id <> invitee_id),
    check ((status = 'pending') = (resolved_at is null))
);
create unique index study_group_pending_invitation on public.study_group_invitations(group_id, invitee_id) where status = 'pending';
create index study_groups_page on public.study_groups(created_at desc, id desc) where archived_at is null;
create index study_group_member_user on public.study_group_members(user_id, group_id);
create index study_group_member_page on public.study_group_members(group_id, joined_at, user_id);
create index study_group_invitee_page on public.study_group_invitations(invitee_id, created_at desc, id desc) where status = 'pending';
create index study_group_invitation_page on public.study_group_invitations(group_id, created_at desc, id desc) where status = 'pending';
alter table public.study_groups enable row level security;
alter table public.study_group_members enable row level security;
alter table public.study_group_invitations enable row level security;
revoke all on public.study_groups, public.study_group_members, public.study_group_invitations from public, anon, authenticated;

create function studyhub_private.group_immutable() returns trigger
language plpgsql set search_path = '' as $$
begin
    if (new.id, new.subject_id, new.created_by, new.client_request_id, new.creation_payload, new.created_at)
       is distinct from (old.id, old.subject_id, old.created_by, old.client_request_id, old.creation_payload, old.created_at) then
        raise exception 'immutable creation' using errcode = 'G0010';
    end if;
    return new;
end;
$$;
create trigger study_group_immutable before update on public.study_groups
for each row execute function studyhub_private.group_immutable();

create function studyhub_private.group_actor() returns uuid
language plpgsql security definer set search_path = '' as $$
declare actor uuid := auth.uid();
begin
    if actor is null then raise exception 'session required' using errcode = 'G0002'; end if;
    if not exists(select 1 from public.profiles where id = actor and not is_banned) then
        raise exception 'account unavailable' using errcode = 'G0003';
    end if;
    return actor;
end;
$$;

create function studyhub_private.group_access(p_group_id uuid, p_actor uuid, p_owner boolean default false, p_lock boolean default false)
returns public.study_groups language plpgsql security definer set search_path = '' as $$
declare g public.study_groups;
begin
    if p_lock then select * into g from public.study_groups where id = p_group_id for update;
    else select * into g from public.study_groups where id = p_group_id; end if;
    if g.id is null or g.archived_at is not null or not exists(
        select 1 from public.study_group_members where group_id = g.id and user_id = p_actor
    ) then raise exception 'group unavailable' using errcode = 'G0004'; end if;
    perform studyhub_private.group_actor();
    if p_owner and g.owner_id <> p_actor then raise exception 'owner required' using errcode = 'G0005'; end if;
    return g;
end;
$$;

create function studyhub_private.group_text(p_value text) returns text
language sql immutable set search_path = '' as $$
    select regexp_replace(p_value, '^[[:space:]]+|[[:space:]]+$', '', 'g');
$$;
create function studyhub_private.group_validate(p_name text, p_description text) returns void
language plpgsql set search_path = '' as $$
begin
    if p_name is null or char_length(p_name) not between 1 and 100 or char_length(p_description) > 1000 then
        raise exception 'invalid input' using errcode = 'G0001';
    end if;
end;
$$;

create function studyhub_private.group_projection(p_id uuid, p_actor uuid) returns jsonb
language sql stable security definer set search_path = '' as $$
    select jsonb_build_object('id', g.id, 'name', g.name, 'description', g.description,
        'subject', jsonb_build_object('id', s.id, 'name', s.name, 'is_active', s.is_active),
        'role', case when g.owner_id = p_actor then 'owner' else 'member' end,
        'member_count', (select count(*) from public.study_group_members m where m.group_id = g.id),
        'created_at', g.created_at, 'updated_at', g.updated_at)
    from public.study_groups g join public.subjects s on s.id = g.subject_id where g.id = p_id;
$$;
create function studyhub_private.group_invitation_projection(p_id uuid, p_with_invitee boolean) returns jsonb
language sql stable security definer set search_path = '' as $$
    select jsonb_build_object('id', i.id, 'status', i.status, 'created_at', i.created_at,
        'group_id', g.id, 'group_name', g.name, 'description', g.description,
        'subject', jsonb_build_object('id', s.id, 'name', s.name, 'is_active', s.is_active),
        'inviter', jsonb_build_object('id', p.id, 'display_name', p.display_name, 'avatar_url', p.avatar_url))
        || case when p_with_invitee then jsonb_build_object('invitee',
            jsonb_build_object('id', t.id, 'display_name', t.display_name, 'avatar_url', t.avatar_url)) else '{}'::jsonb end
    from public.study_group_invitations i join public.study_groups g on g.id = i.group_id
    join public.subjects s on s.id = g.subject_id join public.profiles p on p.id = i.inviter_id
    join public.profiles t on t.id = i.invitee_id where i.id = p_id;
$$;

create function public.get_study_group(p_group_id uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare actor uuid := studyhub_private.group_actor();
begin
    perform studyhub_private.group_access(p_group_id, actor);
    return studyhub_private.group_projection(p_group_id, actor);
end;
$$;

create function public.create_study_group(p_request_id uuid, p_subject_id uuid, p_name text, p_description text default null)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare actor uuid := studyhub_private.group_actor(); g public.study_groups; payload jsonb;
    clean_name text := studyhub_private.group_text(p_name);
    clean_description text := nullif(studyhub_private.group_text(p_description), '');
begin
    if p_request_id is null or p_subject_id is null then raise exception 'invalid input' using errcode = 'G0001'; end if;
    perform studyhub_private.group_validate(clean_name, clean_description);
    payload := jsonb_build_object('subject_id', p_subject_id, 'name', clean_name, 'description', clean_description);
    -- Transaction-scoped serialization also covers retry while the subject is disabled.
    perform pg_advisory_xact_lock(hashtextextended(actor::text || ':' || p_request_id::text, 37131));
    select * into g from public.study_groups where created_by = actor and client_request_id = p_request_id for update;
    if found then
        perform studyhub_private.group_access(g.id, actor);
        if g.creation_payload <> payload then raise exception 'request conflict' using errcode = 'G0010'; end if;
        return studyhub_private.group_projection(g.id, actor);
    end if;
    perform 1 from public.subjects where id = p_subject_id and is_active for share;
    if not found then raise exception 'subject unavailable' using errcode = 'G0006'; end if;
    insert into public.study_groups(subject_id, name, description, owner_id, created_by, client_request_id, creation_payload)
        values(p_subject_id, clean_name, clean_description, actor, actor, p_request_id, payload) returning * into g;
    insert into public.study_group_members(group_id, user_id) values(g.id, actor);
    return studyhub_private.group_projection(g.id, actor);
end;
$$;

create function public.update_study_group(p_group_id uuid, p_name text, p_description text default null)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare actor uuid := studyhub_private.group_actor();
    clean_name text := studyhub_private.group_text(p_name);
    clean_description text := nullif(studyhub_private.group_text(p_description), '');
begin
    perform studyhub_private.group_access(p_group_id, actor, true, true);
    perform studyhub_private.group_validate(clean_name, clean_description);
    update public.study_groups set name = clean_name, description = clean_description, updated_at = now() where id = p_group_id;
    return studyhub_private.group_projection(p_group_id, actor);
end;
$$;

create function studyhub_private.group_cursor(p_cursor jsonb, p_limit integer)
returns table(at timestamptz, id uuid) language plpgsql set search_path = '' as $$
begin
    if p_limit is null or p_limit not between 1 and 50 then raise exception 'invalid limit' using errcode = 'G0001'; end if;
    if p_cursor is null then return query select null::timestamptz, null::uuid; return; end if;
    if jsonb_typeof(p_cursor) <> 'object' or not (p_cursor ?& array['at','id'])
       or (p_cursor - 'at' - 'id') <> '{}'::jsonb
       or jsonb_typeof(p_cursor->'at') <> 'string' or jsonb_typeof(p_cursor->'id') <> 'string'
       or (p_cursor->>'at') !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})$' then
        raise exception 'invalid cursor' using errcode = 'G0001';
    end if;
    begin
        at := (p_cursor->>'at')::timestamptz; id := (p_cursor->>'id')::uuid;
        if not isfinite(at) then raise exception 'invalid cursor' using errcode = 'G0001'; end if;
    exception when invalid_text_representation or invalid_datetime_format or datetime_field_overflow then
        raise exception 'invalid cursor' using errcode = 'G0001';
    end;
    return next;
end;
$$;

create function public.list_my_study_groups(p_cursor jsonb default null, p_limit integer default 20)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare actor uuid := studyhub_private.group_actor(); c record; r record; items jsonb := '[]'; next_cursor jsonb; n integer := 0;
begin
    select * into c from studyhub_private.group_cursor(p_cursor, p_limit);
    for r in select g.id, g.created_at from public.study_groups g
        join public.study_group_members m on m.group_id = g.id and m.user_id = actor
        where g.archived_at is null and (c.at is null or (g.created_at, g.id) < (c.at, c.id))
        order by g.created_at desc, g.id desc limit p_limit + 1 loop
        n := n + 1;
        if n > p_limit then return jsonb_build_object('items', items, 'next_cursor', next_cursor); end if;
        items := items || jsonb_build_array(studyhub_private.group_projection(r.id, actor));
        next_cursor := jsonb_build_object('at', r.created_at, 'id', r.id);
    end loop;
    return jsonb_build_object('items', items, 'next_cursor', null);
end;
$$;

create function public.list_study_group_members(p_group_id uuid, p_cursor jsonb default null, p_limit integer default 20)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare actor uuid := studyhub_private.group_actor(); g public.study_groups; c record; r record;
    items jsonb := '[]'; next_cursor jsonb; n integer := 0;
begin
    g := studyhub_private.group_access(p_group_id, actor);
    select * into c from studyhub_private.group_cursor(p_cursor, p_limit);
    for r in select m.user_id, m.joined_at, p.display_name, p.avatar_url from public.study_group_members m
        join public.profiles p on p.id = m.user_id where m.group_id = p_group_id
        and (c.at is null or (m.joined_at, m.user_id) > (c.at, c.id))
        order by m.joined_at, m.user_id limit p_limit + 1 loop
        n := n + 1;
        if n > p_limit then return jsonb_build_object('items', items, 'next_cursor', next_cursor); end if;
        items := items || jsonb_build_array(jsonb_build_object('user_id', r.user_id, 'display_name', r.display_name,
            'avatar_url', r.avatar_url, 'joined_at', r.joined_at, 'role', case when g.owner_id = r.user_id then 'owner' else 'member' end));
        next_cursor := jsonb_build_object('at', r.joined_at, 'id', r.user_id);
    end loop;
    return jsonb_build_object('items', items, 'next_cursor', null);
end;
$$;

create function studyhub_private.group_invitations(p_group_id uuid, p_cursor jsonb, p_limit integer)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare actor uuid := studyhub_private.group_actor(); c record; r record;
    items jsonb := '[]'; next_cursor jsonb; n integer := 0;
begin
    if p_group_id is not null then perform studyhub_private.group_access(p_group_id, actor, true); end if;
    select * into c from studyhub_private.group_cursor(p_cursor, p_limit);
    for r in select i.id, i.created_at from public.study_group_invitations i
        join public.study_groups g on g.id = i.group_id where g.archived_at is null and i.status = 'pending'
        and ((p_group_id is null and i.invitee_id = actor) or (p_group_id is not null and i.group_id = p_group_id))
        and (c.at is null or (i.created_at, i.id) < (c.at, c.id))
        order by i.created_at desc, i.id desc limit p_limit + 1 loop
        n := n + 1;
        if n > p_limit then return jsonb_build_object('items', items, 'next_cursor', next_cursor); end if;
        items := items || jsonb_build_array(studyhub_private.group_invitation_projection(r.id, p_group_id is not null));
        next_cursor := jsonb_build_object('at', r.created_at, 'id', r.id);
    end loop;
    return jsonb_build_object('items', items, 'next_cursor', null);
end;
$$;
create function public.list_my_study_group_invitations(p_cursor jsonb default null, p_limit integer default 20)
returns jsonb language sql security definer set search_path = '' as $$
    select studyhub_private.group_invitations(null, p_cursor, p_limit);
$$;
create function public.list_study_group_invitations(p_group_id uuid, p_cursor jsonb default null, p_limit integer default 20)
returns jsonb language plpgsql security definer set search_path = '' as $$
begin
    if p_group_id is null then raise exception 'group unavailable' using errcode = 'G0004'; end if;
    return studyhub_private.group_invitations(p_group_id, p_cursor, p_limit);
end;
$$;

create function public.invite_study_group_member(p_group_id uuid, p_target_id uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare actor uuid := studyhub_private.group_actor(); i public.study_group_invitations;
begin
    perform studyhub_private.group_access(p_group_id, actor, true, true);
    if p_target_id is null or p_target_id = actor then raise exception 'invalid target' using errcode = 'G0001'; end if;
    if not exists(select 1 from public.profiles where id = p_target_id and not is_banned) then
        raise exception 'account unavailable' using errcode = 'G0003'; end if;
    if exists(select 1 from public.study_group_members where group_id = p_group_id and user_id = p_target_id) then
        raise exception 'already member' using errcode = 'G0008'; end if;
    perform 1 from public.friendships where status = 'accepted'
        and least(requester_id, addressee_id) = least(actor, p_target_id)
        and greatest(requester_id, addressee_id) = greatest(actor, p_target_id) for update;
    if not found then raise exception 'friendship required' using errcode = 'G0007'; end if;
    select * into i from public.study_group_invitations where group_id = p_group_id and invitee_id = p_target_id and status = 'pending';
    if not found then insert into public.study_group_invitations(group_id, inviter_id, invitee_id)
        values(p_group_id, actor, p_target_id) returning * into i; end if;
    return jsonb_build_object('id', i.id, 'group_id', i.group_id, 'status', i.status);
end;
$$;

create function studyhub_private.group_respond(p_invitation_id uuid, p_action text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare actor uuid := studyhub_private.group_actor(); i public.study_group_invitations; g public.study_groups; gid uuid;
begin
    if p_action is null or p_action not in ('accept','decline','revoke') then raise exception 'invalid action' using errcode = 'G0001'; end if;
    select group_id into gid from public.study_group_invitations where id = p_invitation_id;
    select * into g from public.study_groups where id = gid for update;
    select * into i from public.study_group_invitations where id = p_invitation_id for update;
    if g.id is null or g.archived_at is not null or i.id is null then
        raise exception 'invitation unavailable' using errcode = 'G0009'; end if;
    perform studyhub_private.group_actor();
    if p_action = 'revoke' then
        perform studyhub_private.group_access(g.id, actor, true);
    elsif i.invitee_id <> actor then raise exception 'invitation unavailable' using errcode = 'G0009'; end if;
    if i.status = 'accepted' and p_action = 'accept' then
        if not exists(select 1 from public.study_group_members where group_id = g.id and user_id = actor) then
            raise exception 'invitation unavailable' using errcode = 'G0009'; end if;
    elsif (i.status = 'declined' and p_action = 'decline') or (i.status = 'revoked' and p_action = 'revoke') then null;
    elsif i.status = 'revoked' and p_action = 'accept' then raise exception 'invitation unavailable' using errcode = 'G0009';
    elsif i.status <> 'pending' then raise exception 'invitation conflict' using errcode = 'G0010';
    else
        if p_action = 'accept' then
            insert into public.study_group_members(group_id, user_id) values(g.id, actor);
        end if;
        update public.study_group_invitations set status = case p_action when 'accept' then 'accepted' when 'decline' then 'declined' else 'revoked' end,
            resolved_at = now() where id = i.id returning * into i;
    end if;
    return jsonb_build_object('id', i.id, 'group_id', i.group_id, 'status', i.status);
end;
$$;
create function public.respond_study_group_invitation(p_invitation_id uuid, p_action text) returns jsonb
language plpgsql security definer set search_path = '' as $$
begin
    if p_action is null or p_action not in ('accept','decline') then raise exception 'invalid action' using errcode = 'G0001'; end if;
    return studyhub_private.group_respond(p_invitation_id, p_action);
end;
$$;
create function public.revoke_study_group_invitation(p_invitation_id uuid) returns jsonb
language sql security definer set search_path = '' as $$ select studyhub_private.group_respond(p_invitation_id, 'revoke'); $$;

create function public.leave_study_group(p_group_id uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare actor uuid := studyhub_private.group_actor(); g public.study_groups;
begin
    g := studyhub_private.group_access(p_group_id, actor, false, true);
    if g.owner_id = actor then raise exception 'transfer ownership first' using errcode = 'G0011'; end if;
    delete from public.study_group_members where group_id = g.id and user_id = actor;
    return jsonb_build_object('group_id', g.id, 'status', 'left');
end;
$$;
create function public.remove_study_group_member(p_group_id uuid, p_target_id uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare actor uuid := studyhub_private.group_actor(); g public.study_groups;
begin
    g := studyhub_private.group_access(p_group_id, actor, true, true);
    if p_target_id = g.owner_id then raise exception 'transfer ownership first' using errcode = 'G0011'; end if;
    delete from public.study_group_members where group_id = g.id and user_id = p_target_id;
    if not found then raise exception 'member unavailable' using errcode = 'G0010'; end if;
    return jsonb_build_object('group_id', g.id, 'status', 'removed');
end;
$$;
create function public.transfer_study_group_ownership(p_group_id uuid, p_target_id uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare actor uuid := studyhub_private.group_actor();
begin
    perform studyhub_private.group_access(p_group_id, actor, true, true);
    if p_target_id is null or p_target_id = actor or not exists(
        select 1 from public.study_group_members m join public.profiles p on p.id = m.user_id
        where m.group_id = p_group_id and m.user_id = p_target_id and not p.is_banned
    ) then raise exception 'invalid successor' using errcode = 'G0010'; end if;
    update public.study_groups set owner_id = p_target_id, updated_at = now() where id = p_group_id;
    return studyhub_private.group_projection(p_group_id, actor);
end;
$$;
create function public.archive_study_group(p_group_id uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare actor uuid := studyhub_private.group_actor();
begin
    perform studyhub_private.group_access(p_group_id, actor, true, true);
    update public.study_groups set archived_at = now(), updated_at = now() where id = p_group_id;
    update public.study_group_invitations set status = 'revoked', resolved_at = now() where group_id = p_group_id and status = 'pending';
    return jsonb_build_object('group_id', p_group_id, 'status', 'archived');
end;
$$;

-- Limit grants to this feature; leave existing notification/chat helpers untouched.
do $$ declare f record;
begin
    for f in select p.oid::regprocedure as signature, n.nspname from pg_proc p
        join pg_namespace n on n.oid = p.pronamespace
        where (n.nspname = 'studyhub_private' and p.proname like 'group\_%' escape '\')
           or (n.nspname = 'public' and p.proname = any(array[
               'create_study_group','get_study_group','update_study_group','list_my_study_groups',
               'list_study_group_members','list_my_study_group_invitations','list_study_group_invitations',
               'invite_study_group_member','respond_study_group_invitation','revoke_study_group_invitation',
               'leave_study_group','remove_study_group_member','transfer_study_group_ownership','archive_study_group'])) loop
        execute format('revoke all on function %s from public, anon, authenticated', f.signature);
        if f.nspname = 'public' then execute format('grant execute on function %s to authenticated', f.signature); end if;
    end loop;
end $$;

notify pgrst, 'reload schema';
commit;
