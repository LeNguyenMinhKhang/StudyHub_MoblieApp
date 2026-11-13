-- =====================================================================
-- Migration: Direct Messaging (1-1 Chat) & Realtime Configuration
-- Chạy toàn bộ file này trong Supabase Dashboard > SQL Editor
-- =====================================================================

-- 1. Bật Supabase Realtime cho bảng messages và conversations
do $$
begin
  if not exists (
    select 1 from pg_publication_tables 
    where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'messages'
  ) then
    alter publication supabase_realtime add table public.messages;
  end if;

  if not exists (
    select 1 from pg_publication_tables 
    where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'conversations'
  ) then
    alter publication supabase_realtime add table public.conversations;
  end if;
end $$;

alter table public.messages replica identity full;
alter table public.conversations replica identity full;

-- 2. Cấu hình Storage Bucket 'chat' để lưu ảnh tin nhắn
insert into storage.buckets (id, name, public)
values ('chat', 'chat', true)
on conflict (id) do update set public = true;

drop policy if exists "chat_storage_select_public" on storage.objects;
create policy "chat_storage_select_public" on storage.objects
  for select using (bucket_id = 'chat');

drop policy if exists "chat_storage_insert_authenticated" on storage.objects;
create policy "chat_storage_insert_authenticated" on storage.objects
  for insert with check (bucket_id = 'chat' and auth.role() = 'authenticated');

-- 3. Function RPC: Lấy hoặc tạo cuộc hội thoại 1-1 (Direct Conversation)
create or replace function public.get_or_create_direct_conversation(p_target_id uuid)
returns table(
    conversation_id uuid,
    target_user_id uuid,
    target_display_name text,
    target_avatar_url text
)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_actor uuid := auth.uid();
    v_conversation_id uuid;
    v_target_name text;
    v_target_avatar text;
begin
    if v_actor is null then
        raise exception 'not authenticated' using errcode = '42501';
    end if;

    if p_target_id is null or p_target_id = v_actor then
        raise exception 'invalid target user' using errcode = '22023';
    end if;

    -- Kiểm tra người dùng mục tiêu có tồn tại và không bị khóa
    select p.display_name, p.avatar_url
    into v_target_name, v_target_avatar
    from public.profiles p
    where p.id = p_target_id and not p.is_banned;

    if not found then
        raise exception 'target user unavailable' using errcode = 'P0002';
    end if;

    -- Tìm cuộc hội thoại 1-1 đã có giữa 2 người
    select c.id into v_conversation_id
    from public.conversations c
    join public.conversation_members m1 on m1.conversation_id = c.id and m1.user_id = v_actor
    join public.conversation_members m2 on m2.conversation_id = c.id and m2.user_id = p_target_id
    where c.type = 'direct'
    limit 1;

    -- Nếu chưa có thì tạo mới hội thoại và thêm cả 2 vào thành viên
    if v_conversation_id is null then
        insert into public.conversations (type, created_by)
        values ('direct', v_actor)
        returning id into v_conversation_id;

        insert into public.conversation_members (conversation_id, user_id, role)
        values 
            (v_conversation_id, v_actor, 'member'),
            (v_conversation_id, p_target_id, 'member');
    end if;

    return query
    select v_conversation_id, p_target_id, v_target_name, v_target_avatar;
end;
$$;

-- 4. Function RPC: Lấy danh sách cuộc trò chuyện của tôi (Hộp thư đến)
create or replace function public.list_my_conversations()
returns table(
    conversation_id uuid,
    target_user_id uuid,
    target_display_name text,
    target_avatar_url text,
    last_message_content text,
    last_message_type text,
    last_message_created_at timestamptz,
    last_message_sender_id uuid,
    unread_count bigint
)
language sql
stable
security definer
set search_path = ''
as $$
    select 
        c.id as conversation_id,
        target.id as target_user_id,
        target.display_name as target_display_name,
        target.avatar_url as target_avatar_url,
        last_msg.content as last_message_content,
        last_msg.type as last_message_type,
        last_msg.created_at as last_message_created_at,
        last_msg.sender_id as last_message_sender_id,
        coalesce(unread.count, 0) as unread_count
    from public.conversations c
    join public.conversation_members self_m 
        on self_m.conversation_id = c.id and self_m.user_id = auth.uid()
    join public.conversation_members other_m 
        on other_m.conversation_id = c.id and other_m.user_id <> auth.uid()
    join public.profiles target 
        on target.id = other_m.user_id
    left join lateral (
        select m.content, m.type, m.created_at, m.sender_id
        from public.messages m
        where m.conversation_id = c.id
        order by m.created_at desc
        limit 1
    ) last_msg on true
    left join lateral (
        select count(*) as count
        from public.messages m
        where m.conversation_id = c.id 
          and m.sender_id <> auth.uid()
          and (self_m.last_read_at is null or m.created_at > self_m.last_read_at)
    ) unread on true
    where c.type = 'direct' and not target.is_banned
    order by coalesce(last_msg.created_at, c.created_at) desc;
$$;

-- 5. Function RPC: Đánh dấu đã đọc cuộc hội thoại
create or replace function public.mark_conversation_read(p_conversation_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
    update public.conversation_members
    set last_read_at = now()
    where conversation_id = p_conversation_id and user_id = auth.uid();
end;
$$;

-- Cấp quyền gọi các hàm RPC cho người dùng đã đăng nhập (authenticated)
grant execute on function public.get_or_create_direct_conversation(uuid) to authenticated;
grant execute on function public.list_my_conversations() to authenticated;
grant execute on function public.mark_conversation_read(uuid) to authenticated;

notify pgrst, 'reload schema';

