-- =====================================================================
-- Migration: Add send_chat_message RPC & Fix messages table constraints
-- Chạy toàn bộ file này trong Supabase Dashboard > SQL Editor
-- =====================================================================

-- 1. Đảm bảo các cột trong bảng public.messages có giá trị mặc định và cho phép null đúng cách
alter table public.messages alter column id set default gen_random_uuid();
alter table public.messages alter column created_at set default now();
alter table public.messages alter column status set default 'sent';
alter table public.messages alter column content drop not null;
alter table public.messages alter column file_url drop not null;

-- 2. Hàm helper kiểm tra thành viên cuộc trò chuyện (idempotent, tránh đệ quy RLS)
create or replace function public.is_conversation_member(p_conversation_id uuid, p_user_id uuid)
returns boolean
language sql
security definer
set search_path = 'public', 'auth', 'pg_temp'
stable
as $$
  select exists (
    select 1 from public.conversation_members
    where conversation_id = p_conversation_id and user_id = p_user_id
  );
$$;

grant execute on function public.is_conversation_member(uuid, uuid) to authenticated, anon;

-- 3. Tạo RPC gửi tin nhắn send_chat_message
drop function if exists public.send_chat_message(uuid, text, text, text);
drop function if exists public.send_chat_message;

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

    -- Kiểm tra tính hợp lệ của nội dung tin nhắn
    if p_type = 'text' and (p_content is null or btrim(p_content) = '') then
        raise exception 'content cannot be empty for text message' using errcode = '22023';
    end if;

    if p_type = 'image' and (p_file_url is null or btrim(p_file_url) = '') then
        raise exception 'file_url cannot be empty for image message' using errcode = '22023';
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

    -- Cập nhật last_read_at cho người gửi
    update public.conversation_members
    set last_read_at = now()
    where conversation_id = p_conversation_id and user_id = v_sender_id;

    return to_jsonb(v_msg);
end;
$$;

grant execute on function public.send_chat_message(uuid, text, text, text) to authenticated;

