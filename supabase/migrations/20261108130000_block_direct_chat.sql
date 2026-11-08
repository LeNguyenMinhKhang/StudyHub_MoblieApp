
-- ============================================================
-- STUDYHUB
-- MIGRATION: BLOCK DIRECT CHAT
-- Date: 2026-11-08
--
-- Mục tiêu:
-- 1. Không cho tạo/mở direct chat khi có block hai chiều.
-- 2. Không cho gửi tin nhắn direct khi có block.
-- 3. Ẩn direct chat bị block khỏi danh sách.
-- 4. Không ảnh hưởng group chat.
-- 5. Không xóa lịch sử tin nhắn.
-- ============================================================


-- ============================================================
-- 1. HELPER: KIỂM TRA QUYỀN TRUY CẬP CONVERSATION
-- ============================================================

create or replace function public.direct_chat_available(
    p_conversation_id uuid,
    p_actor_id uuid
)
returns boolean
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
    v_type text;
    v_peer_id uuid;
begin
    if p_actor_id is null or p_conversation_id is null then
        return false;
    end if;

    select c.type
    into v_type
    from public.conversations c
    where c.id = p_conversation_id;

    if not found then
        return false;
    end if;

    -- Group chat không bị ảnh hưởng bởi chức năng block.
    if v_type <> 'direct' then
        return true;
    end if;

    -- Tìm người còn lại trong direct conversation.
    select m.user_id
    into v_peer_id
    from public.conversation_members m
    where m.conversation_id = p_conversation_id
      and m.user_id <> p_actor_id
    limit 1;

    if v_peer_id is null then
        return false;
    end if;

    -- Kiểm tra block hai chiều.
    return studyhub_private.friend_peer_available(
        p_actor_id,
        v_peer_id
    );
end;
$$;

revoke all on function public.direct_chat_available(uuid, uuid)
from public, anon;

grant execute on function public.direct_chat_available(uuid, uuid)
to authenticated;


-- ============================================================
-- 2. GET OR CREATE DIRECT CONVERSATION
-- ============================================================

create or replace function public.get_or_create_direct_conversation(
    p_target_id uuid
)
returns jsonb
language plpgsql
security definer
set search_path = 'public', 'auth', 'studyhub_private', 'pg_temp'
as $$
declare
    v_actor uuid := auth.uid();
    v_conversation_id uuid;
    v_target_name text;
    v_target_avatar text;
begin
    if v_actor is null then
        raise exception 'not authenticated'
            using errcode = '42501';
    end if;

    if p_target_id is null or p_target_id = v_actor then
        raise exception 'invalid target user'
            using errcode = '22023';
    end if;

    -- Không cho mở hoặc tạo direct chat nếu có block.
    if not studyhub_private.friend_peer_available(
        v_actor,
        p_target_id
    ) then
        raise exception 'direct conversation unavailable'
            using errcode = '42501';
    end if;

    select p.display_name, p.avatar_url
    into v_target_name, v_target_avatar
    from public.profiles p
    where p.id = p_target_id
      and not p.is_banned;

    if not found then
        raise exception 'target user unavailable'
            using errcode = 'P0002';
    end if;

    -- Tìm cuộc trò chuyện direct đã tồn tại.
    select c.id
    into v_conversation_id
    from public.conversations c
    where c.type = 'direct'
      and exists (
          select 1
          from public.conversation_members m
          where m.conversation_id = c.id
            and m.user_id = v_actor
      )
      and exists (
          select 1
          from public.conversation_members m
          where m.conversation_id = c.id
            and m.user_id = p_target_id
      )
    limit 1;

    -- Nếu chưa tồn tại thì tạo mới.
    if v_conversation_id is null then
        insert into public.conversations (
            type,
            created_by
        )
        values (
            'direct',
            v_actor
        )
        returning id into v_conversation_id;

        insert into public.conversation_members (
            conversation_id,
            user_id,
            role
        )
        values
            (v_conversation_id, v_actor, 'admin'),
            (v_conversation_id, p_target_id, 'member')
        on conflict (conversation_id, user_id) do nothing;
    end if;

    return jsonb_build_object(
        'conversation_id', v_conversation_id,
        'target_user_id', p_target_id,
        'target_display_name', v_target_name,
        'target_avatar_url', v_target_avatar
    );
end;
$$;

grant execute on function public.get_or_create_direct_conversation(uuid)
to authenticated;


-- ============================================================
-- 3. LIST MY CONVERSATIONS
-- DROP FUNCTION TRƯỚC ĐỂ TRÁNH LỖI 42P13
-- ============================================================

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
    unread_count bigint
)
language plpgsql
security definer
set search_path = 'public', 'auth', 'pg_temp'
as $$
declare
    v_actor uuid := auth.uid();
begin
    if v_actor is null then
        raise exception 'not authenticated'
            using errcode = '42501';
    end if;

    return query
    with my_convs as (
        select
            m.conversation_id,
            m.last_read_at
        from public.conversation_members m
        where m.user_id = v_actor
    ),

    conv_details as (
        select
            c.id,
            c.type,

            case
                when c.type = 'direct' then (
                    select p.display_name
                    from public.conversation_members other_m
                    join public.profiles p
                      on p.id = other_m.user_id
                    where other_m.conversation_id = c.id
                      and other_m.user_id <> v_actor
                    limit 1
                )
                else c.name
            end as computed_title,

            case
                when c.type = 'direct' then (
                    select p.avatar_url
                    from public.conversation_members other_m
                    join public.profiles p
                      on p.id = other_m.user_id
                    where other_m.conversation_id = c.id
                      and other_m.user_id <> v_actor
                    limit 1
                )
                else c.avatar_url
            end as computed_avatar,

            mc.last_read_at

        from my_convs mc
        join public.conversations c
          on c.id = mc.conversation_id
    ),

    last_msgs as (
        select distinct on (msg.conversation_id)
            msg.conversation_id,
            msg.content,
            msg.type,
            msg.created_at
        from public.messages msg
        join my_convs mc
          on mc.conversation_id = msg.conversation_id
        order by
            msg.conversation_id,
            msg.created_at desc
    ),

    unreads as (
        select
            msg.conversation_id,
            count(*) as unread_count
        from public.messages msg
        join my_convs mc
          on mc.conversation_id = msg.conversation_id
        where msg.sender_id <> v_actor
          and (
              mc.last_read_at is null
              or msg.created_at > mc.last_read_at
          )
        group by msg.conversation_id
    )

    select
        cd.id as conversation_id,
        cd.type,
        coalesce(cd.computed_title, 'Trò chuyện') as title,
        cd.computed_avatar as avatar_url,
        lm.content as last_message_content,
        lm.type as last_message_type,
        lm.created_at as last_message_at,
        coalesce(u.unread_count, 0) as unread_count

    from conv_details cd

    left join last_msgs lm
      on lm.conversation_id = cd.id

    left join unreads u
      on u.conversation_id = cd.id

    where
        cd.type <> 'direct'
        or public.direct_chat_available(
            cd.id,
            v_actor
        )

    order by coalesce(
        lm.created_at,
        '1970-01-01'::timestamptz
    ) desc;

end;
$$;

grant execute on function public.list_my_conversations()
to authenticated;


-- ============================================================
-- 4. SEND CHAT MESSAGE
-- ============================================================

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
    v_msg record;
    v_conversation_type text;
begin
    if v_sender_id is null then
        raise exception 'not authenticated'
            using errcode = '42501';
    end if;

    -- Người gửi phải là thành viên.
    if not public.is_conversation_member(
        p_conversation_id,
        v_sender_id
    ) then
        raise exception 'user is not a member of this conversation'
            using errcode = '42501';
    end if;

    select c.type
    into v_conversation_type
    from public.conversations c
    where c.id = p_conversation_id;

    if not found then
        raise exception 'conversation not found'
            using errcode = 'P0002';
    end if;

    -- Chỉ kiểm tra block với direct chat.
    -- Group chat vẫn hoạt động bình thường.
    if v_conversation_type = 'direct'
       and not public.direct_chat_available(
           p_conversation_id,
           v_sender_id
       ) then

        raise exception 'direct conversation unavailable'
            using errcode = '42501';
    end if;

    -- Kiểm tra nội dung tin nhắn.
    if coalesce(p_type, 'text') = 'text'
       and (
           p_content is null
           or btrim(p_content) = ''
       ) then

        raise exception 'content cannot be empty for text message'
            using errcode = '22023';
    end if;

    if coalesce(p_type, 'text') = 'image'
       and (
           p_file_url is null
           or btrim(p_file_url) = ''
       ) then

        raise exception 'file_url cannot be empty for image message'
            using errcode = '22023';
    end if;

    -- Thêm tin nhắn.
    insert into public.messages (
        id,
        conversation_id,
        sender_id,
        type,
        content,
        file_url,
        status,
        created_at
    )
    values (
        gen_random_uuid(),
        p_conversation_id,
        v_sender_id,
        coalesce(p_type, 'text'),
        p_content,
        p_file_url,
        'sent',
        now()
    )
    returning * into v_msg;

    -- Cập nhật trạng thái đã đọc cho người gửi.
    update public.conversation_members
    set last_read_at = now()
    where conversation_id = p_conversation_id
      and user_id = v_sender_id;

    return to_jsonb(v_msg);
end;
$$;

grant execute on function public.send_chat_message(
    uuid,
    text,
    text,
    text
) to authenticated;


-- ============================================================
-- 5. RLS: CONVERSATIONS
-- ============================================================

drop policy if exists "conversations_select_member"
on public.conversations;

create policy "conversations_select_member"
on public.conversations
for select
using (
    (
        created_by = auth.uid()
        or public.is_conversation_member(id, auth.uid())
    )
    and (
        type <> 'direct'
        or public.direct_chat_available(id, auth.uid())
    )
);


-- ============================================================
-- 6. RLS: CONVERSATION MEMBERS
-- ============================================================

drop policy if exists "conv_members_select_self_conv"
on public.conversation_members;

create policy "conv_members_select_self_conv"
on public.conversation_members
for select
using (
    (
        user_id = auth.uid()
        or public.is_conversation_member(
            conversation_id,
            auth.uid()
        )
    )
    and public.direct_chat_available(
        conversation_id,
        auth.uid()
    )
);


-- ============================================================
-- 7. RLS: MESSAGES SELECT
-- ============================================================

drop policy if exists "messages_select_member"
on public.messages;

create policy "messages_select_member"
on public.messages
for select
using (
    public.is_conversation_member(
        conversation_id,
        auth.uid()
    )
    and public.direct_chat_available(
        conversation_id,
        auth.uid()
    )
);


-- ============================================================
-- 8. RLS: MESSAGES INSERT
-- ============================================================

drop policy if exists "messages_insert_member"
on public.messages;

create policy "messages_insert_member"
on public.messages
for insert
with check (
    auth.uid() = sender_id
    and public.is_conversation_member(
        conversation_id,
        auth.uid()
    )
    and public.direct_chat_available(
        conversation_id,
        auth.uid()
    )
);


-- ============================================================
-- 9. RELOAD POSTGREST SCHEMA
-- ============================================================

notify pgrst, 'reload schema';

-- ============================================================
-- END MIGRATION
-- ============================================================
