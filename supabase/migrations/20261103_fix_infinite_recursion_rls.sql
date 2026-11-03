-- =====================================================================
-- Migration: Khắc phục lỗi Infinite Recursion trong RLS Policy của conversation_members
-- Chạy toàn bộ file này trong Supabase Dashboard > SQL Editor
-- =====================================================================

-- 1. Tạo các hàm helper kiểm tra thành viên với SECURITY DEFINER
-- Các hàm này chạy với quyền owner nên KHÔNG kích hoạt RLS đệ quy khi kiểm tra.
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

create or replace function public.is_conversation_admin(p_conversation_id uuid, p_user_id uuid)
returns boolean
language sql
security definer
set search_path = 'public', 'auth', 'pg_temp'
stable
as $$
  select exists (
    select 1 from public.conversation_members
    where conversation_id = p_conversation_id and user_id = p_user_id and role = 'admin'
  );
$$;

grant execute on function public.is_conversation_member(uuid, uuid) to authenticated, anon;
grant execute on function public.is_conversation_admin(uuid, uuid) to authenticated, anon;

-- 2. Cập nhật lại toàn bộ Policy cho bảng 'conversation_members' (Hết đệ quy)
drop policy if exists "conv_members_select_self_conv" on public.conversation_members;
create policy "conv_members_select_self_conv" on public.conversation_members
  for select using (
    user_id = auth.uid()
    or public.is_conversation_member(conversation_id, auth.uid())
  );

drop policy if exists "conv_members_insert_self_or_admin" on public.conversation_members;
create policy "conv_members_insert_self_or_admin" on public.conversation_members
  for insert with check (
    auth.uid() = user_id
    or public.is_conversation_admin(conversation_id, auth.uid())
    or exists (
      select 1 from public.conversations c
      where c.id = conversation_id and c.created_by = auth.uid()
    )
  );

drop policy if exists "conv_members_update_last_read" on public.conversation_members;
create policy "conv_members_update_last_read" on public.conversation_members
  for update using (auth.uid() = user_id);

drop policy if exists "conv_members_delete_self_or_admin" on public.conversation_members;
create policy "conv_members_delete_self_or_admin" on public.conversation_members
  for delete using (
    auth.uid() = user_id
    or public.is_conversation_admin(conversation_id, auth.uid())
  );

-- 3. Cập nhật lại toàn bộ Policy cho bảng 'conversations' (Hết đệ quy)
drop policy if exists "conversations_select_member" on public.conversations;
create policy "conversations_select_member" on public.conversations
  for select using (
    created_by = auth.uid()
    or public.is_conversation_member(id, auth.uid())
  );

drop policy if exists "conversations_insert_authenticated" on public.conversations;
create policy "conversations_insert_authenticated" on public.conversations
  for insert with check (auth.uid() = created_by);

drop policy if exists "conversations_update_group_admin" on public.conversations;
create policy "conversations_update_group_admin" on public.conversations
  for update using (public.is_conversation_admin(id, auth.uid()));

-- 4. Cập nhật lại toàn bộ Policy cho bảng 'messages' (Hết đệ quy)
drop policy if exists "messages_select_member" on public.messages;
create policy "messages_select_member" on public.messages
  for select using (
    sender_id = auth.uid()
    or public.is_conversation_member(conversation_id, auth.uid())
  );

drop policy if exists "messages_insert_member" on public.messages;
create policy "messages_insert_member" on public.messages
  for insert with check (
    auth.uid() = sender_id
    and public.is_conversation_member(conversation_id, auth.uid())
  );

drop policy if exists "messages_update_own" on public.messages;
create policy "messages_update_own" on public.messages
  for update using (auth.uid() = sender_id);

notify pgrst, 'reload schema';

