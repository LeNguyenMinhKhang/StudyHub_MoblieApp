-- =====================================================================
-- Migration: Chat Message Status (Sent/Read) & Document Attachments
-- File: supabase/migrations/20261116_chat_status_and_documents.sql
-- =====================================================================

-- 0. Nới rộng CHECK constraint trên bảng messages để cho phép 'read' và 'document'
alter table public.messages drop constraint if exists messages_status_check;
alter table public.messages add constraint messages_status_check 
  check (status in ('sending', 'sent', 'failed', 'seen', 'read'));

alter table public.messages drop constraint if exists messages_type_check;
alter table public.messages add constraint messages_type_check 
  check (type in ('text', 'image', 'file', 'document'));

-- 1. Trigger tự động: Khi bất kỳ ai cập nhật last_read_at trong conversation_members,
-- tự động chuyển toàn bộ tin nhắn trước đó của đối phương thành 'read'.
create or replace function public.on_conversation_member_read()
returns trigger
language plpgsql
security definer
set search_path = 'public', 'auth', 'pg_temp'
as $$
begin
    if new.last_read_at is not null and (old.last_read_at is null or new.last_read_at > old.last_read_at) then
        update public.messages
        set status = 'read'
        where conversation_id = new.conversation_id
          and sender_id <> new.user_id
          and created_at <= new.last_read_at
          and status <> 'read'
          and status <> 'seen';
    end if;
    return new;
end;
$$;

drop trigger if exists trg_conversation_member_read on public.conversation_members;
create trigger trg_conversation_member_read
after update of last_read_at on public.conversation_members
for each row
execute function public.on_conversation_member_read();

-- 2. Cập nhật hàm mark_conversation_read:
create or replace function public.mark_conversation_read(p_conversation_id uuid)
returns void
language plpgsql
security definer
set search_path = 'public', 'auth', 'pg_temp'
as $$
declare
    v_actor uuid := auth.uid();
begin
    if v_actor is null then
        return;
    end if;

    -- Cập nhật mốc thời gian đọc của thành viên
    update public.conversation_members
    set last_read_at = now()
    where conversation_id = p_conversation_id and user_id = v_actor;

    -- Đánh dấu trực tiếp tin nhắn của người khác sang 'read'
    update public.messages
    set status = 'read'
    where conversation_id = p_conversation_id
      and sender_id <> v_actor
      and status <> 'read'
      and status <> 'seen';
end;
$$;

grant execute on function public.mark_conversation_read(uuid) to authenticated;

-- 3. Cập nhật hàm send_chat_message:
-- Hỗ trợ thêm loại tin nhắn 'document' và tự động đánh dấu đã đọc các tin nhắn trước.
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
begin
    if v_sender_id is null then
        raise exception 'not authenticated' using errcode = '42501';
    end if;

    -- Kiểm tra người gửi có trong cuộc hội thoại không
    if not public.is_conversation_member(p_conversation_id, v_sender_id) then
        raise exception 'user is not a member of this conversation' using errcode = '42501';
    end if;

    -- Kiểm tra tính hợp lệ theo loại tin nhắn
    if p_type = 'text' and (p_content is null or btrim(p_content) = '') then
        raise exception 'content cannot be empty for text message' using errcode = '22023';
    end if;

    if (p_type = 'image' or p_type = 'document' or p_type = 'file') and (p_file_url is null or btrim(p_file_url) = '') then
        raise exception 'file_url cannot be empty for media/document message' using errcode = '22023';
    end if;

    -- Khi gửi tin nhắn mới, chắc chắn người gửi đã đọc các tin trước của đối phương
    update public.messages
    set status = 'read'
    where conversation_id = p_conversation_id
      and sender_id <> v_sender_id
      and status <> 'read'
      and status <> 'seen';

    -- Thêm tin nhắn vào bảng messages
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

    -- Cập nhật last_read_at cho chính người gửi
    update public.conversation_members
    set last_read_at = now()
    where conversation_id = p_conversation_id and user_id = v_sender_id;

    return to_jsonb(v_msg);
end;
$$;

grant execute on function public.send_chat_message(uuid, text, text, text) to authenticated;

-- 4. Đồng bộ ngay trạng thái 'read' cho các tin nhắn cũ đã được xem trước đây:
update public.messages m
set status = 'read'
where (status = 'sent' or status = 'sending')
  and exists (
    select 1 from public.conversation_members cm
    where cm.conversation_id = m.conversation_id
      and cm.user_id <> m.sender_id
      and cm.last_read_at >= m.created_at
  );

notify pgrst, 'reload schema';
