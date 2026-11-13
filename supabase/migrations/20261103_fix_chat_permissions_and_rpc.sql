-- =====================================================================
-- Migration: Fix Chat Permissions, RLS Policies, and Direct Chat RPC
-- Chạy toàn bộ file này trong Supabase Dashboard > SQL Editor
-- =====================================================================

-- 1. Cấp quyền truy cập bảng cho role 'authenticated' (PostgREST yêu cầu)
grant select, insert, update, delete on public.conversations to authenticated;
grant select, insert, update, delete on public.conversation_members to authenticated;
grant select, insert, update, delete on public.messages to authenticated;

-- 2. Cập nhật Policy trên bảng conversation_members:
-- Cho phép:
--   a) Thêm chính mình (user_id = auth.uid())
--   b) Người tạo cuộc trò chuyện (conversations.created_by = auth.uid()) được thêm thành viên
--   c) Quản trị viên cuộc trò chuyện (role = 'admin') được thêm thành viên
drop policy if exists "conv_members_insert_self_or_admin" on public.conversation_members;
create policy "conv_members_insert_self_or_admin" on public.conversation_members
  for insert with check (
    auth.uid() = user_id
    or exists (
      select 1 from public.conversations c
      where c.id = conversation_id and c.created_by = auth.uid()
    )
    or exists (
      select 1 from public.conversation_members m2
      where m2.conversation_id = conversation_id and m2.user_id = auth.uid() and m2.role = 'admin'
    )
  );

-- Cho phép xem thành viên nếu là thành viên hoặc là chính mình
drop policy if exists "conv_members_select_self_conv" on public.conversation_members;
create policy "conv_members_select_self_conv" on public.conversation_members
  for select using (
    user_id = auth.uid()
    or exists (
      select 1 from public.conversation_members m2
      where m2.conversation_id = conversation_id and m2.user_id = auth.uid()
    )
  );

-- 3. Cập nhật Policy trên bảng conversations
drop policy if exists "conversations_select_member" on public.conversations;
create policy "conversations_select_member" on public.conversations
  for select using (
    created_by = auth.uid()
    or exists (
      select 1 from public.conversation_members m
      where m.conversation_id = id and m.user_id = auth.uid()
    )
  );

drop policy if exists "conversations_insert_authenticated" on public.conversations;
create policy "conversations_insert_authenticated" on public.conversations
  for insert with check (auth.uid() = created_by);

-- 4. Cập nhật Policy trên bảng messages
drop policy if exists "messages_select_member" on public.messages;
create policy "messages_select_member" on public.messages
  for select using (
    sender_id = auth.uid()
    or exists (
      select 1 from public.conversation_members m
      where m.conversation_id = conversation_id and m.user_id = auth.uid()
    )
  );

drop policy if exists "messages_insert_member" on public.messages;
create policy "messages_insert_member" on public.messages
  for insert with check (
    auth.uid() = sender_id
    and exists (
      select 1 from public.conversation_members m
      where m.conversation_id = conversation_id and m.user_id = auth.uid()
    )
  );

-- 5. Cải tiến RPC get_or_create_direct_conversation trả về JSONB
drop function if exists public.get_or_create_direct_conversation(uuid);

create or replace function public.get_or_create_direct_conversation(p_target_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = 'public', 'auth', 'pg_temp'
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

    select display_name, avatar_url
    into v_target_name, v_target_avatar
    from public.profiles
    where id = p_target_id and not is_banned;

    if not found then
        raise exception 'target user unavailable' using errcode = 'P0002';
    end if;

    -- Tìm cuộc hội thoại direct đã có giữa 2 người
    select c.id into v_conversation_id
    from public.conversations c
    where c.type = 'direct'
      and exists (select 1 from public.conversation_members m where m.conversation_id = c.id and m.user_id = v_actor)
      and exists (select 1 from public.conversation_members m where m.conversation_id = c.id and m.user_id = p_target_id)
    limit 1;

    -- Nếu chưa có thì tạo mới hội thoại và thêm cả 2 vào thành viên
    if v_conversation_id is null then
        insert into public.conversations (type, created_by)
        values ('direct', v_actor)
        returning id into v_conversation_id;

        insert into public.conversation_members (conversation_id, user_id, role)
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

grant execute on function public.get_or_create_direct_conversation(uuid) to authenticated;
grant execute on function public.list_my_conversations() to authenticated;
grant execute on function public.mark_conversation_read(uuid) to authenticated;

notify pgrst, 'reload schema';

