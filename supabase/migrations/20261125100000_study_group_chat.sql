-- =====================================================================
-- Migration: Study group chat, member invitations by any member, optional subject
-- Change: openspec/changes/study-group-messenger-chat
--
-- 1. conversations.study_group_id links one group conversation to a study group.
-- 2. Study group membership is the only authority for linked conversations.
-- 3. send_chat_message / mark_conversation_read / list_my_conversations are redefined.
--    send_chat_message restores the direct_chat_available check that
--    20261116_chat_status_and_documents.sql accidentally dropped.
-- 4. Any member (not only the owner) can invite their own accepted friends.
-- 5. study_groups.subject_id becomes optional.
-- =====================================================================
begin;

-- ---------------------------------------------------------------------
-- 1. Link conversation <-> study group
-- ---------------------------------------------------------------------
alter table public.conversations
    add column if not exists study_group_id uuid references public.study_groups(id) on delete restrict;

do $$
begin
    if not exists (select 1 from pg_constraint where conname = 'conversations_study_group_unique') then
        alter table public.conversations
            add constraint conversations_study_group_unique unique (study_group_id);
    end if;
    if not exists (select 1 from pg_constraint where conname = 'conversations_study_group_type_check') then
        alter table public.conversations
            add constraint conversations_study_group_type_check
            check (study_group_id is null or type = 'group');
    end if;
end $$;

-- ---------------------------------------------------------------------
-- 2. Membership authority
-- ---------------------------------------------------------------------
create or replace function public.is_conversation_member(p_conversation_id uuid, p_user_id uuid)
returns boolean
language sql
security definer
set search_path = 'public', 'auth', 'pg_temp'
stable
as $$
  select coalesce((
    select case
      when c.study_group_id is not null then exists (
        select 1
        from public.study_group_members m
        join public.study_groups g on g.id = m.group_id
        join public.profiles p on p.id = m.user_id
        where m.group_id = c.study_group_id
          and m.user_id = p_user_id
          and g.archived_at is null
          and not p.is_banned
      )
      else exists (
        select 1 from public.conversation_members cm
        where cm.conversation_id = p_conversation_id and cm.user_id = p_user_id
      )
    end
    from public.conversations c
    where c.id = p_conversation_id
  ), false);
$$;

grant execute on function public.is_conversation_member(uuid, uuid) to authenticated, anon;

-- The creator of a group conversation must not keep reading the row after leaving the group.
drop policy if exists "conversations_select_member" on public.conversations;
create policy "conversations_select_member"
on public.conversations
for select
using (
    (
        (created_by = auth.uid() and study_group_id is null)
        or public.is_conversation_member(id, auth.uid())
    )
    and (
        type <> 'direct'
        or public.direct_chat_available(id, auth.uid())
    )
);

-- ---------------------------------------------------------------------
-- 3a. Get or create the conversation of a study group
-- ---------------------------------------------------------------------
create or replace function public.get_or_create_study_group_conversation(p_group_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_actor uuid := studyhub_private.group_actor();
    g public.study_groups;
    v_conversation_id uuid;
begin
    g := studyhub_private.group_access(p_group_id, v_actor);

    insert into public.conversations (type, name, created_by, study_group_id)
    values ('group', g.name, v_actor, g.id)
    on conflict (study_group_id) do nothing;

    select c.id into v_conversation_id
    from public.conversations c
    where c.study_group_id = g.id;

    insert into public.conversation_members (conversation_id, user_id, role)
    values (v_conversation_id, v_actor, 'member')
    on conflict (conversation_id, user_id) do nothing;

    return jsonb_build_object(
        'conversation_id', v_conversation_id,
        'group_id', g.id,
        'name', g.name,
        'member_count', (select count(*) from public.study_group_members m where m.group_id = g.id)
    );
end;
$$;

revoke all on function public.get_or_create_study_group_conversation(uuid) from public, anon;
grant execute on function public.get_or_create_study_group_conversation(uuid) to authenticated;

-- ---------------------------------------------------------------------
-- 3b. Send message
-- ---------------------------------------------------------------------
create or replace function public.send_chat_message(
    p_conversation_id uuid,
    p_content text default null,
    p_type text default 'text',
    p_file_url text default null
)
returns jsonb
language plpgsql
security definer
set search_path = 'public', 'auth', 'pg_temp'
as $$
declare
    v_sender_id uuid := auth.uid();
    v_type text := coalesce(p_type, 'text');
    v_conversation_type text;
    v_study_group_id uuid;
    v_msg record;
begin
    if v_sender_id is null then
        raise exception 'not authenticated' using errcode = '42501';
    end if;

    select c.type, c.study_group_id
    into v_conversation_type, v_study_group_id
    from public.conversations c
    where c.id = p_conversation_id;

    if not found or not public.is_conversation_member(p_conversation_id, v_sender_id) then
        raise exception 'user is not a member of this conversation' using errcode = '42501';
    end if;

    if v_conversation_type = 'direct'
       and not public.direct_chat_available(p_conversation_id, v_sender_id) then
        raise exception 'direct conversation unavailable' using errcode = '42501';
    end if;

    if v_type = 'text' and (p_content is null or btrim(p_content) = '') then
        raise exception 'content cannot be empty for text message' using errcode = '22023';
    end if;

    if v_type in ('image', 'document', 'file') and (p_file_url is null or btrim(p_file_url) = '') then
        raise exception 'file_url cannot be empty for media/document message' using errcode = '22023';
    end if;

    -- Read receipts exist only in direct conversations.
    if v_conversation_type = 'direct' then
        update public.messages
        set status = 'read'
        where conversation_id = p_conversation_id
          and sender_id <> v_sender_id
          and status not in ('read', 'seen');
    end if;

    insert into public.messages (id, conversation_id, sender_id, type, content, file_url, status, created_at)
    values (gen_random_uuid(), p_conversation_id, v_sender_id, v_type, p_content, p_file_url, 'sent', now())
    returning * into v_msg;

    insert into public.conversation_members (conversation_id, user_id, role, last_read_at)
    values (p_conversation_id, v_sender_id, 'member', now())
    on conflict (conversation_id, user_id) do update set last_read_at = excluded.last_read_at;

    return to_jsonb(v_msg);
end;
$$;

grant execute on function public.send_chat_message(uuid, text, text, text) to authenticated;

-- ---------------------------------------------------------------------
-- 3c. Mark read (+ trigger only touches direct conversations)
-- ---------------------------------------------------------------------
create or replace function public.on_conversation_member_read()
returns trigger
language plpgsql
security definer
set search_path = 'public', 'auth', 'pg_temp'
as $$
begin
    if exists (
        select 1 from public.conversations c
        where c.id = new.conversation_id and c.study_group_id is not null
    ) then
        return new;
    end if;

    if new.last_read_at is not null and (old.last_read_at is null or new.last_read_at > old.last_read_at) then
        update public.messages
        set status = 'read'
        where conversation_id = new.conversation_id
          and sender_id <> new.user_id
          and created_at <= new.last_read_at
          and status not in ('read', 'seen');
    end if;
    return new;
end;
$$;

create or replace function public.mark_conversation_read(p_conversation_id uuid)
returns void
language plpgsql
security definer
set search_path = 'public', 'auth', 'pg_temp'
as $$
declare
    v_actor uuid := auth.uid();
    v_type text;
begin
    if v_actor is null then
        return;
    end if;

    select c.type into v_type from public.conversations c where c.id = p_conversation_id;
    if not found or not public.is_conversation_member(p_conversation_id, v_actor) then
        return;
    end if;

    insert into public.conversation_members (conversation_id, user_id, role, last_read_at)
    values (p_conversation_id, v_actor, 'member', now())
    on conflict (conversation_id, user_id) do update set last_read_at = excluded.last_read_at;

    if v_type = 'direct' then
        update public.messages
        set status = 'read'
        where conversation_id = p_conversation_id
          and sender_id <> v_actor
          and status not in ('read', 'seen');
    end if;
end;
$$;

grant execute on function public.mark_conversation_read(uuid) to authenticated;

-- ---------------------------------------------------------------------
-- 3d. Leaving a group drops the read pointer
-- ---------------------------------------------------------------------
create or replace function studyhub_private.group_chat_drop_pointer()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    delete from public.conversation_members cm
    using public.conversations c
    where c.study_group_id = old.group_id
      and cm.conversation_id = c.id
      and cm.user_id = old.user_id;
    return old;
end;
$$;

revoke all on function studyhub_private.group_chat_drop_pointer() from public, anon, authenticated;

drop trigger if exists study_group_member_drop_chat_pointer on public.study_group_members;
create trigger study_group_member_drop_chat_pointer
after delete on public.study_group_members
for each row execute function studyhub_private.group_chat_drop_pointer();

-- ---------------------------------------------------------------------
-- 3e. Conversation list
-- ---------------------------------------------------------------------
drop function if exists public.list_my_conversations();

create function public.list_my_conversations()
returns table (
    conversation_id uuid,
    type text,
    title text,
    avatar_url text,
    last_message_content text,
    last_message_type text,
    last_message_at timestamptz,
    unread_count bigint,
    study_group_id uuid
)
language plpgsql
security definer
set search_path = 'public', 'auth', 'pg_temp'
as $$
declare
    v_actor uuid := auth.uid();
begin
    if v_actor is null then
        raise exception 'not authenticated' using errcode = '42501';
    end if;

    return query
    with my_convs as (
        -- Direct and legacy group conversations: the read pointer row is the membership.
        select m.conversation_id as cid,
               c.type as ctype,
               c.study_group_id as sgid,
               m.last_read_at as read_at,
               null::text as group_title
        from public.conversation_members m
        join public.conversations c on c.id = m.conversation_id
        where m.user_id = v_actor
          and c.study_group_id is null
        union all
        -- Study group conversations: study group membership is the authority.
        select c.id,
               c.type,
               c.study_group_id,
               coalesce(cm.last_read_at, sgm.joined_at),
               g.name
        from public.study_group_members sgm
        join public.study_groups g on g.id = sgm.group_id and g.archived_at is null
        join public.conversations c on c.study_group_id = g.id
        left join public.conversation_members cm
          on cm.conversation_id = c.id and cm.user_id = v_actor
        where sgm.user_id = v_actor
          and exists (select 1 from public.profiles p where p.id = v_actor and not p.is_banned)
    ),
    conv_details as (
        select mc.cid as id,
               mc.ctype as ctype,
               mc.sgid as sgid,
               mc.read_at as read_at,
               case
                   when mc.ctype = 'direct' then (
                       select p.display_name
                       from public.conversation_members other_m
                       join public.profiles p on p.id = other_m.user_id
                       where other_m.conversation_id = mc.cid and other_m.user_id <> v_actor
                       limit 1)
                   when mc.sgid is not null then mc.group_title
                   else c.name
               end as computed_title,
               case
                   when mc.ctype = 'direct' then (
                       select p.avatar_url
                       from public.conversation_members other_m
                       join public.profiles p on p.id = other_m.user_id
                       where other_m.conversation_id = mc.cid and other_m.user_id <> v_actor
                       limit 1)
                   when mc.sgid is not null then null
                   else c.avatar_url
               end as computed_avatar
        from my_convs mc
        join public.conversations c on c.id = mc.cid
    ),
    last_msgs as (
        select distinct on (msg.conversation_id)
               msg.conversation_id as cid,
               msg.content,
               msg.type as mtype,
               msg.created_at
        from public.messages msg
        join my_convs mc on mc.cid = msg.conversation_id
        order by msg.conversation_id, msg.created_at desc
    ),
    unreads as (
        select msg.conversation_id as cid, count(*) as n
        from public.messages msg
        join my_convs mc on mc.cid = msg.conversation_id
        where msg.sender_id <> v_actor
          and (mc.read_at is null or msg.created_at > mc.read_at)
        group by msg.conversation_id
    )
    select cd.id,
           cd.ctype,
           coalesce(cd.computed_title, 'Trò chuyện'),
           cd.computed_avatar,
           lm.content,
           lm.mtype,
           lm.created_at,
           coalesce(u.n, 0),
           cd.sgid
    from conv_details cd
    left join last_msgs lm on lm.cid = cd.id
    left join unreads u on u.cid = cd.id
    where (cd.ctype <> 'direct' or public.direct_chat_available(cd.id, v_actor))
      and (cd.sgid is null or lm.created_at is not null)
    order by coalesce(lm.created_at, '1970-01-01'::timestamptz) desc;
end;
$$;

grant execute on function public.list_my_conversations() to authenticated;

-- ---------------------------------------------------------------------
-- 4. Any member may invite their own accepted friends
-- ---------------------------------------------------------------------
create or replace function public.invite_study_group_member(p_group_id uuid, p_target_id uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare actor uuid := studyhub_private.group_actor(); i public.study_group_invitations;
begin
    perform studyhub_private.group_access(p_group_id, actor, false, true);
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

-- ---------------------------------------------------------------------
-- 5. Optional subject
-- ---------------------------------------------------------------------
alter table public.study_groups alter column subject_id drop not null;

create or replace function studyhub_private.group_projection(p_id uuid, p_actor uuid) returns jsonb
language sql stable security definer set search_path = '' as $$
    select jsonb_build_object('id', g.id, 'name', g.name, 'description', g.description,
        'subject', case when s.id is null then null
            else jsonb_build_object('id', s.id, 'name', s.name, 'is_active', s.is_active) end,
        'role', case when g.owner_id = p_actor then 'owner' else 'member' end,
        'member_count', (select count(*) from public.study_group_members m where m.group_id = g.id),
        'created_at', g.created_at, 'updated_at', g.updated_at)
    from public.study_groups g left join public.subjects s on s.id = g.subject_id where g.id = p_id;
$$;

create or replace function studyhub_private.group_invitation_projection(p_id uuid, p_with_invitee boolean) returns jsonb
language sql stable security definer set search_path = '' as $$
    select jsonb_build_object('id', i.id, 'status', i.status, 'created_at', i.created_at,
        'group_id', g.id, 'group_name', g.name, 'description', g.description,
        'subject', case when s.id is null then null
            else jsonb_build_object('id', s.id, 'name', s.name, 'is_active', s.is_active) end,
        'inviter', jsonb_build_object('id', p.id, 'display_name', p.display_name, 'avatar_url', p.avatar_url))
        || case when p_with_invitee then jsonb_build_object('invitee',
            jsonb_build_object('id', t.id, 'display_name', t.display_name, 'avatar_url', t.avatar_url)) else '{}'::jsonb end
    from public.study_group_invitations i join public.study_groups g on g.id = i.group_id
    left join public.subjects s on s.id = g.subject_id join public.profiles p on p.id = i.inviter_id
    join public.profiles t on t.id = i.invitee_id where i.id = p_id;
$$;

create or replace function public.create_study_group(p_request_id uuid, p_subject_id uuid, p_name text, p_description text default null)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare actor uuid := studyhub_private.group_actor(); g public.study_groups; payload jsonb;
    clean_name text := studyhub_private.group_text(p_name);
    clean_description text := nullif(studyhub_private.group_text(p_description), '');
begin
    if p_request_id is null then raise exception 'invalid input' using errcode = 'G0001'; end if;
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
    if p_subject_id is not null then
        perform 1 from public.subjects where id = p_subject_id and is_active for share;
        if not found then raise exception 'subject unavailable' using errcode = 'G0006'; end if;
    end if;
    insert into public.study_groups(subject_id, name, description, owner_id, created_by, client_request_id, creation_payload)
        values(p_subject_id, clean_name, clean_description, actor, actor, p_request_id, payload) returning * into g;
    insert into public.study_group_members(group_id, user_id) values(g.id, actor);
    return studyhub_private.group_projection(g.id, actor);
end;
$$;

notify pgrst, 'reload schema';

commit;
