-- =====================================================================
-- Migration: Chat Message Status (Sent/Read) & Document Attachments
-- File: supabase/migrations/20261116_chat_status_and_documents.sql
-- =====================================================================

-- 1. Cập nhật hàm mark_conversation_read:
-- Khi người dùng mở cuộc hội thoại, cập nhật last_read_at trong conversation_members
-- đồng thời chuyển toàn bộ tin nhắn do người khác gửi sang trạng thái 'read'.
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

    -- Cập nhật trạng thái tin nhắn từ người khác sang 'read'
    update public.messages
    set status = 'read'
    where conversation_id = p_conversation_id
      and sender_id <> v_actor
      and status <> 'read';
end;
$$;

grant execute on function public.mark_conversation_read(uuid) to authenticated;

-- 2. Cập nhật hàm send_chat_message:
-- Hỗ trợ thêm loại tin nhắn 'document' bên cạnh 'text' và 'image'.
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

    if (p_type = 'image' or p_type = 'document') and (p_file_url is null or btrim(p_file_url) = '') then
        raise exception 'file_url cannot be empty for media/document message' using errcode = '22023';
    end if;

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

notify pgrst, 'reload schema';
