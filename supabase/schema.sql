-- =====================================================================
-- StudyHub – Schema Supabase/PostgreSQL (Đã cập nhật đầy đủ)
-- Chạy toàn bộ file này trong Supabase Dashboard > SQL Editor
-- Đã bao gồm:
-- 1. Cho phép môn học (subject_id) và loại bài (type) để trống (NULL).
-- 2. Tự động xóa file đính kèm khi xóa bài viết (on delete cascade).
-- 3. Quyền RLS cho phép xem bài viết và ảnh đính kèm công khai.
-- 4. Quyền RLS cho phép chính chủ sửa/xóa bài viết và file của mình.
-- 5. Cấu hình Storage bucket 'documents' & 'posts' để lưu ảnh bài viết.
-- =====================================================================

create extension if not exists pgcrypto;  -- cho gen_random_uuid()

-- =====================================================================
-- 1. profiles (phân hệ 1, 2, 12)
-- =====================================================================
create table if not exists public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  username text unique not null,
  display_name text not null,
  avatar_url text,
  bio text,
  role text not null default 'user' check (role in ('user','admin')),
  is_banned boolean not null default false,
  created_at timestamptz not null default now()
);

alter table public.profiles enable row level security;

drop policy if exists "profiles_select_all" on public.profiles;
create policy "profiles_select_all" on public.profiles
  for select using (true);

drop policy if exists "profiles_insert_own" on public.profiles;
create policy "profiles_insert_own" on public.profiles
  for insert with check (auth.uid() = id);

drop policy if exists "profiles_update_own" on public.profiles;
create policy "profiles_update_own" on public.profiles
  for update using (auth.uid() = id);

-- Trigger tự tạo profile khi người dùng đăng ký qua auth.users
create or replace function public.create_profile_for_auth_user()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
    new_username text := lower(btrim(new.raw_user_meta_data ->> 'username'));
    new_display_name text := btrim(new.raw_user_meta_data ->> 'display_name');
begin
    if new_username is null or new_username !~ '^[a-z0-9_]{3,30}$'
        or new_display_name is null or char_length(new_display_name) not between 1 and 80 then
        new_username := 'user_' || substr(new.id::text, 1, 8);
        new_display_name := coalesce(new_display_name, new_username);
    end if;

    insert into public.profiles (id, username, display_name, avatar_url, bio)
    values (new.id, new_username, new_display_name, null, null)
    on conflict (id) do nothing;
    return new;
end;
$$;

drop trigger if exists on_auth_user_created_create_profile on auth.users;
create trigger on_auth_user_created_create_profile
after insert on auth.users
for each row execute function public.create_profile_for_auth_user();

-- RPC Functions phục vụ app đọc và cập nhật profile
create or replace function public.get_my_profile()
returns setof public.profiles
language sql
stable
security definer
set search_path = ''
as $$
    select p.* from public.profiles p
    where auth.uid() is not null and p.id = auth.uid()
    limit 1;
$$;

create or replace function public.get_public_profile_by_id(p_id uuid)
returns table(id uuid, username text, display_name text, avatar_url text, bio text, created_at timestamptz)
language sql
stable
security definer
set search_path = ''
as $$
    select p.id, p.username::text, p.display_name::text, p.avatar_url::text,
           p.bio::text, p.created_at
    from public.profiles p
    where auth.uid() is not null and p.id = p_id and not p.is_banned
    limit 1;
$$;

create or replace function public.get_public_profile_by_username(p_username text)
returns table(id uuid, username text, display_name text, avatar_url text, bio text, created_at timestamptz)
language sql
stable
security definer
set search_path = ''
as $$
    select p.id, p.username::text, p.display_name::text, p.avatar_url::text,
           p.bio::text, p.created_at
    from public.profiles p
    where auth.uid() is not null
      and lower(p.username) = lower(btrim(p_username))
      and not p.is_banned
    limit 1;
$$;

create or replace function public.update_my_profile(p_username text, p_display_name text, p_bio text)
returns setof public.profiles
language plpgsql
security definer
set search_path = ''
as $$
declare
    cleaned_username text := lower(btrim(p_username));
    cleaned_display_name text := btrim(p_display_name);
    cleaned_bio text := nullif(btrim(p_bio), '');
begin
    if auth.uid() is null then
        raise exception 'not authenticated' using errcode = '42501';
    end if;
    if cleaned_username is null or (
        not exists (
            select 1 from public.profiles p
            where p.id = auth.uid() and p.username = p_username
        ) and cleaned_username !~ '^[a-z0-9_]{3,30}$'
    )
        or cleaned_display_name is null or char_length(cleaned_display_name) not between 1 and 80
        or char_length(coalesce(cleaned_bio, '')) > 500 then
        raise exception 'invalid profile input' using errcode = '23514';
    end if;

    return query
    update public.profiles p
    set username = case when p.username = p_username then p.username else cleaned_username end,
        display_name = cleaned_display_name,
        bio = cleaned_bio
    where p.id = auth.uid() and not p.is_banned
    returning p.*;
end;
$$;

grant execute on function public.get_my_profile() to authenticated, anon;
grant execute on function public.get_public_profile_by_id(uuid) to authenticated, anon;
grant execute on function public.get_public_profile_by_username(text) to authenticated, anon;
grant execute on function public.update_my_profile(text, text, text) to authenticated;


-- =====================================================================
-- 2. subjects (phân hệ 3, 12.3)
-- =====================================================================
create table if not exists public.subjects (
  id uuid primary key default gen_random_uuid(),
  name text unique not null,
  description text,
  is_active boolean not null default true,
  created_at timestamptz not null default now()
);

alter table public.subjects enable row level security;

drop policy if exists "subjects_select_all" on public.subjects;
create policy "subjects_select_all" on public.subjects
  for select using (true);

drop policy if exists "subjects_admin_insert" on public.subjects;
create policy "subjects_admin_insert" on public.subjects
  for insert with check (
    exists (select 1 from public.profiles p where p.id = auth.uid() and p.role = 'admin')
  );

drop policy if exists "subjects_admin_update" on public.subjects;
create policy "subjects_admin_update" on public.subjects
  for update using (
    exists (select 1 from public.profiles p where p.id = auth.uid() and p.role = 'admin')
  );

drop policy if exists "subjects_admin_delete" on public.subjects;
create policy "subjects_admin_delete" on public.subjects
  for delete using (
    exists (select 1 from public.profiles p where p.id = auth.uid() and p.role = 'admin')
  );

-- =====================================================================
-- 3. subject_follows (phân hệ 3.2)
-- =====================================================================
create table if not exists public.subject_follows (
  id uuid primary key default gen_random_uuid(),
  subject_id uuid not null references public.subjects(id) on delete cascade,
  user_id uuid not null references public.profiles(id) on delete cascade,
  created_at timestamptz not null default now(),
  unique (subject_id, user_id)
);

alter table public.subject_follows enable row level security;

drop policy if exists "subject_follows_select_own" on public.subject_follows;
create policy "subject_follows_select_own" on public.subject_follows
  for select using (auth.uid() = user_id);

drop policy if exists "subject_follows_insert_own" on public.subject_follows;
create policy "subject_follows_insert_own" on public.subject_follows
  for insert with check (auth.uid() = user_id);

drop policy if exists "subject_follows_delete_own" on public.subject_follows;
create policy "subject_follows_delete_own" on public.subject_follows
  for delete using (auth.uid() = user_id);

-- =====================================================================
-- 4. posts (phân hệ 4, 6.5)
-- CẬP NHẬT: subject_id và type ĐƯỢC PHÉP NULL (khi không chọn)
-- =====================================================================
create table if not exists public.posts (
  id uuid primary key default gen_random_uuid(),
  author_id uuid not null references public.profiles(id) on delete cascade,
  subject_id uuid references public.subjects(id) on delete set null,
  type text,
  title text not null,
  content text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

-- Đảm bảo nếu bảng đã tồn tại từ trước thì cũng gỡ bỏ ràng buộc NOT NULL
alter table public.posts alter column subject_id drop not null;
alter table public.posts alter column type drop not null;
alter table public.posts drop constraint if exists posts_type_check;


create index if not exists posts_subject_id_idx on public.posts(subject_id);
create index if not exists posts_author_id_idx on public.posts(author_id);

alter table public.posts enable row level security;

drop policy if exists "posts_select_all" on public.posts;
create policy "posts_select_all" on public.posts
  for select using (true);

drop policy if exists "posts_insert_own" on public.posts;
create policy "posts_insert_own" on public.posts
  for insert with check (auth.uid() = author_id);

drop policy if exists "posts_update_own" on public.posts;
create policy "posts_update_own" on public.posts
  for update using (auth.uid() = author_id);

drop policy if exists "posts_delete_own_or_admin" on public.posts;
create policy "posts_delete_own_or_admin" on public.posts
  for delete using (
    auth.uid() = author_id
    or exists (select 1 from public.profiles p where p.id = auth.uid() and p.role = 'admin')
  );

-- Trigger tự cập nhật updated_at mỗi khi sửa bài viết
create or replace function public.set_updated_at()
returns trigger as $$
begin
  new.updated_at = now();
  return new;
end;
$$ language plpgsql;

drop trigger if exists posts_set_updated_at on public.posts;
create trigger posts_set_updated_at
  before update on public.posts
  for each row execute function public.set_updated_at();

-- =====================================================================
-- 5. post_files (phân hệ 4.1, 4.2)
-- CẬP NHẬT: on delete cascade, cho phép select_all để người khác thấy ảnh
-- =====================================================================
create table if not exists public.post_files (
  id uuid primary key default gen_random_uuid(),
  post_id uuid not null references public.posts(id) on delete cascade,
  file_url text not null,
  file_name text not null,
  file_type text,
  file_size bigint,
  created_at timestamptz not null default now()
);

-- Đảm bảo khóa ngoại cascade delete
alter table public.post_files drop constraint if exists post_files_post_id_fkey;
alter table public.post_files 
  add constraint post_files_post_id_fkey 
  foreign key (post_id) 
  references public.posts(id) 
  on delete cascade;

create index if not exists post_files_post_id_idx on public.post_files(post_id);

alter table public.post_files enable row level security;

drop policy if exists "post_files_select_all" on public.post_files;
create policy "post_files_select_all" on public.post_files
  for select using (true);

drop policy if exists "post_files_insert_own_post" on public.post_files;
create policy "post_files_insert_own_post" on public.post_files
  for insert with check (
    auth.uid() is not null
    and exists (select 1 from public.posts p where p.id = post_id and p.author_id = auth.uid())
  );

drop policy if exists "post_files_delete_own_post" on public.post_files;
create policy "post_files_delete_own_post" on public.post_files
  for delete using (
    auth.uid() is not null
    and (
      exists (select 1 from public.posts p where p.id = post_id and p.author_id = auth.uid())
      or exists (select 1 from public.profiles p where p.id = auth.uid() and p.role = 'admin')
    )
  );

-- =====================================================================
-- 6. hashtags (phân hệ 4.7)
-- =====================================================================
create table if not exists public.hashtags (
  id uuid primary key default gen_random_uuid(),
  name text unique not null
);

alter table public.hashtags enable row level security;

drop policy if exists "hashtags_select_all" on public.hashtags;
create policy "hashtags_select_all" on public.hashtags
  for select using (true);

drop policy if exists "hashtags_insert_authenticated" on public.hashtags;
create policy "hashtags_insert_authenticated" on public.hashtags
  for insert with check (auth.uid() is not null);

-- =====================================================================
-- 7. post_hashtags (phân hệ 4.7)
-- =====================================================================
create table if not exists public.post_hashtags (
  post_id uuid not null references public.posts(id) on delete cascade,
  hashtag_id uuid not null references public.hashtags(id) on delete cascade,
  primary key (post_id, hashtag_id)
);

alter table public.post_hashtags enable row level security;

drop policy if exists "post_hashtags_select_all" on public.post_hashtags;
create policy "post_hashtags_select_all" on public.post_hashtags
  for select using (true);

drop policy if exists "post_hashtags_insert_own_post" on public.post_hashtags;
create policy "post_hashtags_insert_own_post" on public.post_hashtags
  for insert with check (
    exists (select 1 from public.posts p where p.id = post_id and p.author_id = auth.uid())
  );

drop policy if exists "post_hashtags_delete_own_post" on public.post_hashtags;
create policy "post_hashtags_delete_own_post" on public.post_hashtags
  for delete using (
    exists (select 1 from public.posts p where p.id = post_id and p.author_id = auth.uid())
  );

-- =====================================================================
-- 8. likes (phân hệ 6.1, 6.4)
-- =====================================================================
create table if not exists public.likes (
  id uuid primary key default gen_random_uuid(),
  post_id uuid not null references public.posts(id) on delete cascade,
  user_id uuid not null references public.profiles(id) on delete cascade,
  created_at timestamptz not null default now(),
  unique (post_id, user_id)
);

create index if not exists likes_post_id_idx on public.likes(post_id);

alter table public.likes enable row level security;

drop policy if exists "likes_select_all" on public.likes;
create policy "likes_select_all" on public.likes
  for select using (true);

drop policy if exists "likes_insert_own" on public.likes;
create policy "likes_insert_own" on public.likes
  for insert with check (auth.uid() = user_id);

drop policy if exists "likes_delete_own" on public.likes;
create policy "likes_delete_own" on public.likes
  for delete using (auth.uid() = user_id);

-- =====================================================================
-- 9. comments (phân hệ 6.2, 6.3, 6.5)
-- =====================================================================
create table if not exists public.comments (
  id uuid primary key default gen_random_uuid(),
  post_id uuid not null references public.posts(id) on delete cascade,
  user_id uuid not null references public.profiles(id) on delete cascade,
  parent_id uuid references public.comments(id) on delete cascade,
  content text not null,
  is_best_answer boolean not null default false,
  created_at timestamptz not null default now()
);

create index if not exists comments_post_id_idx on public.comments(post_id);

alter table public.comments enable row level security;

drop policy if exists "comments_select_all" on public.comments;
create policy "comments_select_all" on public.comments
  for select using (true);

drop policy if exists "comments_insert_own" on public.comments;
create policy "comments_insert_own" on public.comments
  for insert with check (auth.uid() = user_id);

drop policy if exists "comments_update_own" on public.comments;
create policy "comments_update_own" on public.comments
  for update using (auth.uid() = user_id);

drop policy if exists "comments_update_best_answer_by_post_owner" on public.comments;
create policy "comments_update_best_answer_by_post_owner" on public.comments
  for update using (
    exists (select 1 from public.posts p where p.id = post_id and p.author_id = auth.uid())
  );

drop policy if exists "comments_delete_own_or_post_owner_or_admin" on public.comments;
create policy "comments_delete_own_or_post_owner_or_admin" on public.comments
  for delete using (
    auth.uid() = user_id
    or exists (select 1 from public.posts p where p.id = post_id and p.author_id = auth.uid())
    or exists (select 1 from public.profiles p where p.id = auth.uid() and p.role = 'admin')
  );

-- Thêm best_comment_id vào posts nếu chưa có
do $$
begin
  if not exists (
    select 1 from information_schema.columns 
    where table_schema = 'public' and table_name = 'posts' and column_name = 'best_comment_id'
  ) then
    alter table public.posts
      add column best_comment_id uuid references public.comments(id) on delete set null;
  end if;
end $$;

-- =====================================================================
-- 10. saved_posts (phân hệ 4.6)
-- =====================================================================
create table if not exists public.saved_posts (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles(id) on delete cascade,
  post_id uuid not null references public.posts(id) on delete cascade,
  created_at timestamptz not null default now(),
  unique (user_id, post_id)
);

alter table public.saved_posts enable row level security;

drop policy if exists "saved_posts_select_own" on public.saved_posts;
create policy "saved_posts_select_own" on public.saved_posts
  for select using (auth.uid() = user_id);

drop policy if exists "saved_posts_insert_own" on public.saved_posts;
create policy "saved_posts_insert_own" on public.saved_posts
  for insert with check (auth.uid() = user_id);

drop policy if exists "saved_posts_delete_own" on public.saved_posts;
create policy "saved_posts_delete_own" on public.saved_posts
  for delete using (auth.uid() = user_id);

-- =====================================================================
-- 11. friendships (phân hệ 7.1, 7.2)
-- =====================================================================
create table if not exists public.friendships (
  id uuid primary key default gen_random_uuid(),
  requester_id uuid not null references public.profiles(id) on delete cascade,
  addressee_id uuid not null references public.profiles(id) on delete cascade,
  status text not null default 'pending' check (status in ('pending','accepted')),
  created_at timestamptz not null default now(),
  unique (requester_id, addressee_id)
);

create index if not exists friendships_requester_idx on public.friendships(requester_id);
create index if not exists friendships_addressee_idx on public.friendships(addressee_id);

alter table public.friendships enable row level security;

drop policy if exists "friendships_select_involved" on public.friendships;
create policy "friendships_select_involved" on public.friendships
  for select using (auth.uid() = requester_id or auth.uid() = addressee_id);

drop policy if exists "friendships_insert_as_requester" on public.friendships;
create policy "friendships_insert_as_requester" on public.friendships
  for insert with check (auth.uid() = requester_id);

drop policy if exists "friendships_update_as_addressee" on public.friendships;
create policy "friendships_update_as_addressee" on public.friendships
  for update using (auth.uid() = addressee_id);

drop policy if exists "friendships_delete_involved" on public.friendships;
create policy "friendships_delete_involved" on public.friendships
  for delete using (auth.uid() = requester_id or auth.uid() = addressee_id);

-- =====================================================================
-- 12. blocks (phân hệ 7.6)
-- =====================================================================
create table if not exists public.blocks (
  id uuid primary key default gen_random_uuid(),
  blocker_id uuid not null references public.profiles(id) on delete cascade,
  blocked_id uuid not null references public.profiles(id) on delete cascade,
  created_at timestamptz not null default now(),
  unique (blocker_id, blocked_id)
);

alter table public.blocks enable row level security;

drop policy if exists "blocks_select_own" on public.blocks;
create policy "blocks_select_own" on public.blocks
  for select using (auth.uid() = blocker_id);

drop policy if exists "blocks_insert_own" on public.blocks;
create policy "blocks_insert_own" on public.blocks
  for insert with check (auth.uid() = blocker_id);

drop policy if exists "blocks_delete_own" on public.blocks;
create policy "blocks_delete_own" on public.blocks
  for delete using (auth.uid() = blocker_id);

-- =====================================================================
-- 13. reports (phân hệ 6.7, 12.4)
-- =====================================================================
create table if not exists public.reports (
  id uuid primary key default gen_random_uuid(),
  reporter_id uuid not null references public.profiles(id) on delete cascade,
  target_type text not null check (target_type in ('post','comment','user')),
  target_id uuid not null,
  reason text not null,
  status text not null default 'pending' check (status in ('pending','resolved')),
  created_at timestamptz not null default now()
);

alter table public.reports enable row level security;

drop policy if exists "reports_insert_own" on public.reports;
create policy "reports_insert_own" on public.reports
  for insert with check (auth.uid() = reporter_id);

drop policy if exists "reports_select_admin" on public.reports;
create policy "reports_select_admin" on public.reports
  for select using (
    exists (select 1 from public.profiles p where p.id = auth.uid() and p.role = 'admin')
  );

drop policy if exists "reports_update_admin" on public.reports;
create policy "reports_update_admin" on public.reports
  for update using (
    exists (select 1 from public.profiles p where p.id = auth.uid() and p.role = 'admin')
  );

-- =====================================================================

-- =====================================================================
-- 14. Helper functions for Chat RLS (Tránh đệ quy)
-- =====================================================================
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

-- =====================================================================
-- 15. conversations (phân hệ 8, 9)
-- =====================================================================
create table if not exists public.conversations (
  id uuid primary key default gen_random_uuid(),
  type text not null check (type in ('direct','group')),
  name text,
  avatar_url text,
  subject_id uuid references public.subjects(id) on delete set null,
  created_by uuid not null references public.profiles(id) on delete restrict,
  created_at timestamptz not null default now()
);

-- =====================================================================
-- 16. conversation_members (phân hệ 8, 9.2, 9.4)
-- =====================================================================
create table if not exists public.conversation_members (
  id uuid primary key default gen_random_uuid(),
  conversation_id uuid not null references public.conversations(id) on delete cascade,
  user_id uuid not null references public.profiles(id) on delete cascade,
  role text not null default 'member' check (role in ('admin','member')),
  last_read_at timestamptz,
  joined_at timestamptz not null default now(),
  unique (conversation_id, user_id)
);

create index if not exists conversation_members_conv_idx on public.conversation_members(conversation_id);
create index if not exists conversation_members_user_idx on public.conversation_members(user_id);

alter table public.conversations enable row level security;

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

alter table public.conversation_members enable row level security;

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

-- =====================================================================
-- 17. messages (phân hệ 8.2, 8.3, 8.5, 9.3)
-- =====================================================================
create table if not exists public.messages (
  id uuid primary key default gen_random_uuid(),
  conversation_id uuid not null references public.conversations(id) on delete cascade,
  sender_id uuid not null references public.profiles(id) on delete cascade,
  type text not null default 'text' check (type in ('text','image','file')),
  content text,
  file_url text,
  status text not null default 'sent' check (status in ('sending','sent','failed','seen')),
  created_at timestamptz not null default now()
);

create index if not exists messages_conversation_id_idx on public.messages(conversation_id);

alter table public.messages enable row level security;

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

-- 17. notifications (phân hệ 10)
-- =====================================================================
create table if not exists public.notifications (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles(id) on delete cascade,
  actor_id uuid references public.profiles(id) on delete set null,
  type text not null check (
    type in ('like','comment','friend_request','friend_accept','best_answer','group_added','new_post')
  ),
  post_id uuid references public.posts(id) on delete cascade,
  conversation_id uuid references public.conversations(id) on delete cascade,
  is_read boolean not null default false,
  created_at timestamptz not null default now()
);

create index if not exists notifications_user_id_idx on public.notifications(user_id);

alter table public.notifications enable row level security;

drop policy if exists "notifications_select_own" on public.notifications;
create policy "notifications_select_own" on public.notifications
  for select using (auth.uid() = user_id);

drop policy if exists "notifications_insert_as_actor" on public.notifications;
create policy "notifications_insert_as_actor" on public.notifications
  for insert with check (auth.uid() = actor_id);

drop policy if exists "notifications_update_own" on public.notifications;
create policy "notifications_update_own" on public.notifications
  for update using (auth.uid() = user_id);

-- =====================================================================

-- =====================================================================
-- 19. Quyền truy cập bảng (Grants)
-- =====================================================================
grant usage on schema public to authenticated, anon;
grant select on public.profiles to authenticated, anon;
grant select, insert, update on public.profiles to authenticated;
grant select on public.subjects to authenticated, anon;
grant select on public.posts to authenticated, anon;
grant select, insert, update, delete on public.posts to authenticated;
grant select on public.post_files to authenticated, anon;
grant select, insert, update, delete on public.post_files to authenticated;
grant select, insert, delete on public.likes to authenticated;
grant select, insert, update, delete on public.comments to authenticated;
grant select, insert, update, delete on public.conversations to authenticated;
grant select, insert, update, delete on public.conversation_members to authenticated;
grant select, insert, update, delete on public.messages to authenticated;

-- =====================================================================
-- 20. Supabase Storage Buckets ('documents', 'posts', 'chat')
-- =====================================================================
insert into storage.buckets (id, name, public)
values 
  ('documents', 'documents', true),
  ('posts', 'posts', true),
  ('chat', 'chat', true)
on conflict (id) do update set public = true;

-- Cho phép đọc file công khai
drop policy if exists "Allow public read documents" on storage.objects;
create policy "Allow public read documents"
  on storage.objects for select
  using (bucket_id in ('documents', 'posts'));

drop policy if exists "chat_storage_select_public" on storage.objects;
create policy "chat_storage_select_public" on storage.objects
  for select using (bucket_id = 'chat');

-- Cho phép người dùng đã đăng nhập tải ảnh/tài liệu lên
drop policy if exists "Allow authenticated upload documents" on storage.objects;
create policy "Allow authenticated upload documents"
  on storage.objects for insert
  to authenticated
  with check (bucket_id in ('documents', 'posts'));

drop policy if exists "chat_storage_insert_authenticated" on storage.objects;
create policy "chat_storage_insert_authenticated" on storage.objects
  for insert with check (bucket_id = 'chat' and auth.role() = 'authenticated');

-- Cho phép người dùng đã đăng nhập cập nhật file của mình
drop policy if exists "Allow authenticated update documents" on storage.objects;
create policy "Allow authenticated update documents"
  on storage.objects for update
  to authenticated
  using (bucket_id in ('documents', 'posts'));

-- Cho phép xóa file khi bài viết bị xóa
drop policy if exists "Allow authenticated delete documents" on storage.objects;
create policy "Allow authenticated delete documents"
  on storage.objects for delete
  to authenticated
  using (bucket_id in ('documents', 'posts'));

-- =====================================================================
-- 21. Supabase Realtime CDC Publication
-- =====================================================================
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

-- Friendship RPC contract (20261027_friendship_rpc.sql)

-- Fail before changing anything; never normalize existing user relationships.
lock table public.friendships in share row exclusive mode;
do $$
begin
    if exists (select 1 from public.friendships where requester_id = addressee_id)
       or exists (select 1 from public.friendships
          group by least(requester_id, addressee_id), greatest(requester_id, addressee_id)
          having count(*) > 1) then
        raise exception 'friendship preflight failed: self or duplicate pair' using errcode = 'F0001';
    end if;
end;
$$;
alter table public.friendships drop constraint if exists friendships_distinct_users;
alter table public.friendships add constraint friendships_distinct_users check (requester_id <> addressee_id);
create unique index if not exists friendships_unordered_pair_key
    on public.friendships (least(requester_id, addressee_id), greatest(requester_id, addressee_id));
create index if not exists friendships_requester_page_idx on public.friendships(requester_id, status, created_at desc, id desc);
create index if not exists friendships_addressee_page_idx on public.friendships(addressee_id, status, created_at desc, id desc);

create schema if not exists studyhub_private;
revoke all on schema studyhub_private from public, anon, authenticated;

create or replace function studyhub_private.friend_actor() returns uuid
language plpgsql security definer set search_path = '' as $$
declare actor uuid := auth.uid();
begin
    if actor is null then raise exception 'session required' using errcode = 'F0005'; end if;
    if not exists(select 1 from public.profiles where id = actor and not is_banned) then
        raise exception 'actor unavailable' using errcode = 'F0006';
    end if;
    return actor;
end;
$$;

create or replace function studyhub_private.friend_peer_available(actor uuid, target uuid) returns boolean
language sql stable security definer set search_path = '' as $$
    select exists(select 1 from public.profiles where id = target and not is_banned)
       and not exists(select 1 from public.blocks
          where (blocker_id = actor and blocked_id = target) or (blocker_id = target and blocked_id = actor));
$$;

create or replace function studyhub_private.friend_snapshot(actor uuid, target uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare rel public.friendships; state text; relation_id uuid;
begin
    if target is null then raise exception 'invalid target' using errcode = 'F0001'; end if;
    if actor = target then state := 'self';
    elsif not studyhub_private.friend_peer_available(actor, target) then state := 'unavailable';
    else
        select * into rel from public.friendships
        where least(requester_id, addressee_id) = least(actor, target)
          and greatest(requester_id, addressee_id) = greatest(actor, target);
        relation_id := rel.id;
        state := case when rel.id is null then 'none' when rel.status = 'accepted' then 'friends'
          when rel.requester_id = actor then 'outgoing_pending' else 'incoming_pending' end;
    end if;
    return jsonb_build_object('target_id', target, 'state', state, 'friendship_id', relation_id);
end;
$$;

create or replace function public.get_friendship_state(p_target_id uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
begin
    return studyhub_private.friend_snapshot(studyhub_private.friend_actor(), p_target_id);
end;
$$;

create or replace function public.send_friend_request(p_target_id uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare actor uuid := studyhub_private.friend_actor(); rel public.friendships;
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
        insert into public.friendships(requester_id, addressee_id, status)
        values(actor, p_target_id, 'pending') on conflict do nothing;
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
        update public.friendships set status = 'accepted' where id = rel.id;
        return studyhub_private.friend_snapshot(actor, target);
    elsif action in ('decline','cancel','remove') then
        if (action = 'remove' and rel.status <> 'accepted')
           or (action <> 'remove' and rel.status <> 'pending') then
            raise exception 'relationship changed' using errcode = 'F0004';
        end if;
        delete from public.friendships where id = rel.id;
        return jsonb_build_object('target_id',target,'state','none','friendship_id',null);
    end if;
    raise exception 'invalid action' using errcode = 'F0001';
end;
$$;

create or replace function public.accept_friend_request(p_friendship_id uuid) returns jsonb
language sql security definer set search_path = '' as $$ select studyhub_private.friend_mutate(p_friendship_id,'accept'); $$;
create or replace function public.decline_friend_request(p_friendship_id uuid) returns jsonb
language sql security definer set search_path = '' as $$ select studyhub_private.friend_mutate(p_friendship_id,'decline'); $$;
create or replace function public.cancel_friend_request(p_friendship_id uuid) returns jsonb
language sql security definer set search_path = '' as $$ select studyhub_private.friend_mutate(p_friendship_id,'cancel'); $$;
create or replace function public.remove_friendship(p_friendship_id uuid) returns jsonb
language sql security definer set search_path = '' as $$ select studyhub_private.friend_mutate(p_friendship_id,'remove'); $$;

create or replace function public.list_my_friendships(
    p_kind text, p_limit integer default 20,
    p_cursor_created_at timestamptz default null, p_cursor_id uuid default null
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare actor uuid := studyhub_private.friend_actor(); page_size integer; result jsonb;
begin
    if p_kind is null or p_kind not in ('friends','incoming','outgoing')
       or p_limit is null or p_limit <= 0
       or ((p_cursor_created_at is null) <> (p_cursor_id is null))
       or (p_cursor_created_at is not null and not isfinite(p_cursor_created_at)) then
        raise exception 'invalid page input' using errcode = 'F0001';
    end if;
    page_size := least(p_limit, 50);
    with candidates as materialized (
        select f.id, f.created_at, f.status,
          case when f.requester_id = actor then 'outgoing' else 'incoming' end as direction,
          jsonb_build_object('id',p.id,'username',p.username,'display_name',p.display_name,'avatar_url',p.avatar_url) as peer
        from public.friendships f
        join public.profiles p on p.id = case when f.requester_id = actor then f.addressee_id else f.requester_id end
        where (f.requester_id = actor or f.addressee_id = actor)
          and ((p_kind = 'friends' and f.status = 'accepted')
            or (p_kind = 'incoming' and f.status = 'pending' and f.addressee_id = actor)
            or (p_kind = 'outgoing' and f.status = 'pending' and f.requester_id = actor))
          and not p.is_banned
          and not exists(select 1 from public.blocks b where
            (b.blocker_id = actor and b.blocked_id = p.id) or (b.blocker_id = p.id and b.blocked_id = actor))
          and (p_cursor_id is null or (f.created_at,f.id) < (p_cursor_created_at,p_cursor_id))
        order by f.created_at desc, f.id desc limit page_size + 1
    ), page as (select * from candidates order by created_at desc,id desc limit page_size)
    select jsonb_build_object(
        'items', coalesce((select jsonb_agg(to_jsonb(page) order by created_at desc,id desc) from page),'[]'::jsonb),
        'next_cursor', case when (select count(*) from candidates) > page_size then
           (select jsonb_build_object('created_at',created_at,'id',id) from page order by created_at,id limit 1)
           else null end) into result;
    return result;
end;
$$;

alter table public.friendships enable row level security;
revoke all on public.friendships from public, anon, authenticated;
drop policy if exists friendships_insert_as_requester on public.friendships;
drop policy if exists friendships_update_as_addressee on public.friendships;
drop policy if exists friendships_delete_involved on public.friendships;
revoke all on all functions in schema studyhub_private from public, anon, authenticated;
revoke all on function public.get_friendship_state(uuid), public.send_friend_request(uuid),
    public.accept_friend_request(uuid), public.decline_friend_request(uuid),
    public.cancel_friend_request(uuid), public.remove_friendship(uuid),
    public.list_my_friendships(text,integer,timestamptz,uuid) from public, anon, authenticated;
grant execute on function public.get_friendship_state(uuid), public.send_friend_request(uuid),
    public.accept_friend_request(uuid), public.decline_friend_request(uuid),
    public.cancel_friend_request(uuid), public.remove_friendship(uuid),
    public.list_my_friendships(text,integer,timestamptz,uuid) to authenticated;

-- =====================================================================
-- 23. Direct Chat & Messaging RPC Functions
-- =====================================================================

-- Lấy hoặc tạo cuộc hội thoại 1-1
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

-- Danh sách các cuộc trò chuyện của người dùng hiện tại
create or replace function public.list_my_conversations()
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
        raise exception 'not authenticated' using errcode = '42501';
    end if;

    return query
    with my_convs as (
        select m.conversation_id, m.last_read_at
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
                    join public.profiles p on p.id = other_m.user_id
                    where other_m.conversation_id = c.id and other_m.user_id != v_actor
                    limit 1
                )
                else c.name
            end as computed_title,
            case 
                when c.type = 'direct' then (
                    select p.avatar_url 
                    from public.conversation_members other_m
                    join public.profiles p on p.id = other_m.user_id
                    where other_m.conversation_id = c.id and other_m.user_id != v_actor
                    limit 1
                )
                else c.avatar_url
            end as computed_avatar,
            mc.last_read_at
        from my_convs mc
        join public.conversations c on c.id = mc.conversation_id
    ),
    last_msgs as (
        select distinct on (msg.conversation_id)
            msg.conversation_id,
            msg.content,
            msg.type,
            msg.created_at
        from public.messages msg
        join my_convs mc on mc.conversation_id = msg.conversation_id
        order by msg.conversation_id, msg.created_at desc
    ),
    unreads as (
        select 
            msg.conversation_id,
            count(*) as unread_count
        from public.messages msg
        join my_convs mc on mc.conversation_id = msg.conversation_id
        where msg.sender_id != v_actor 
          and (mc.last_read_at is null or msg.created_at > mc.last_read_at)
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
    left join last_msgs lm on lm.conversation_id = cd.id
    left join unreads u on u.conversation_id = cd.id
    order by coalesce(lm.created_at, '1970-01-01'::timestamptz) desc;
end;
$$;

-- Đánh dấu đã đọc cuộc hội thoại
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
        raise exception 'not authenticated' using errcode = '42501';
    end if;

    update public.conversation_members
    set last_read_at = now()
    where conversation_id = p_conversation_id and user_id = v_actor;
end;
$$;

-- Gửi tin nhắn mới (text hoặc image)
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

    if not public.is_conversation_member(p_conversation_id, v_sender_id) then
        raise exception 'user is not a member of this conversation' using errcode = '42501';
    end if;

    if p_type = 'text' and (p_content is null or btrim(p_content) = '') then
        raise exception 'content cannot be empty for text message' using errcode = '22023';
    end if;

    if p_type = 'image' and (p_file_url is null or btrim(p_file_url) = '') then
        raise exception 'file_url cannot be empty for image message' using errcode = '22023';
    end if;

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

    update public.conversation_members
    set last_read_at = now()
    where conversation_id = p_conversation_id and user_id = v_sender_id;

    return to_jsonb(v_msg);
end;
$$;

grant execute on function public.get_or_create_direct_conversation(uuid) to authenticated;
grant execute on function public.list_my_conversations() to authenticated;
grant execute on function public.mark_conversation_read(uuid) to authenticated;
grant execute on function public.send_chat_message(uuid, text, text, text) to authenticated;

notify pgrst, 'reload schema';

-- =====================================================================
-- 24. Notification center & delivery (phân hệ 10)
-- Mirrors migrations 20261027150000_friend_notifications (indexes),
-- 20261106120000_notification_center and 20261106120100_notification_delivery.
-- Replaces the section 17 notification policies: clients use RPCs only.
-- The worker scheduler is enabled separately: supabase/notifications/enable_scheduler.sql.
-- =====================================================================
create index if not exists notifications_user_page_idx
    on public.notifications(user_id, created_at desc, id desc);
create index if not exists notifications_friend_request_idx
    on public.notifications(user_id, actor_id) where type = 'friend_request';

-- Notification center: source identity, preferences, interaction producers and inbox RPCs.
-- Idempotent so the same body can be mirrored in supabase/schema.sql.
-- Depends on 20261027_friendship_rpc.sql and 20261027150000_friend_notifications.sql.

-- ---------------------------------------------------------------------------
-- 1. Source identity on the existing inbox. Legacy rows keep NULL source data:
--    there is no provable link from (actor, recipient) to one friendship id.
-- ---------------------------------------------------------------------------
alter table public.notifications
  add column if not exists source_kind text,
  add column if not exists source_id uuid,
  add column if not exists event_key text,
  add column if not exists template_version integer not null default 1,
  add column if not exists opened_at timestamptz;

do $$
begin
  if not exists (select 1 from pg_constraint where conname = 'notifications_source_kind_check') then
    alter table public.notifications add constraint notifications_source_kind_check
      check (source_kind is null or source_kind in ('friendship','like','comment'));
  end if;
  if not exists (select 1 from pg_constraint where conname = 'notifications_source_pair_check') then
    alter table public.notifications add constraint notifications_source_pair_check
      check ((source_kind is null) = (source_id is null) and (event_key is null or source_id is not null));
  end if;
  if not exists (select 1 from pg_constraint where conname = 'notifications_template_version_check') then
    alter table public.notifications add constraint notifications_template_version_check
      check (template_version between 1 and 1000);
  end if;
end $$;

-- One inbox row per recipient + event type + source incarnation; legacy rows are exempt.
create unique index if not exists notifications_event_key_idx
  on public.notifications(user_id, event_key) where event_key is not null;
create index if not exists notifications_source_idx
  on public.notifications(source_kind, source_id) where source_id is not null;
create index if not exists notifications_actor_idx on public.notifications(actor_id) where actor_id is not null;
create index if not exists notifications_post_idx on public.notifications(post_id) where post_id is not null;
create index if not exists notifications_user_unread_idx
  on public.notifications(user_id, created_at desc, id desc) where not is_read;

-- ---------------------------------------------------------------------------
-- 2. Private preferences. Missing rows mean enabled (default-on).
--    notification_recipient_state is the per-recipient lock that serializes
--    producers and preference changes.
-- ---------------------------------------------------------------------------
create schema if not exists studyhub_private;
revoke all on schema studyhub_private from public, anon, authenticated;

create table if not exists studyhub_private.notification_recipient_state (
  user_id uuid primary key references public.profiles(id) on delete cascade,
  created_at timestamptz not null default now()
);
create table if not exists studyhub_private.notification_preferences (
  user_id uuid not null references public.profiles(id) on delete cascade,
  channel text not null default 'in_app' check (channel = 'in_app'),
  category text not null check (category in ('friendship','like','comment')),
  enabled boolean not null,
  updated_at timestamptz not null default now(),
  primary key (user_id, channel, category)
);
alter table studyhub_private.notification_recipient_state enable row level security;
alter table studyhub_private.notification_preferences enable row level security;
revoke all on studyhub_private.notification_recipient_state, studyhub_private.notification_preferences
  from public, anon, authenticated;

create or replace function studyhub_private.lock_notification_recipient(recipient uuid) returns boolean
language plpgsql security definer set search_path = '' as $$
begin
  insert into studyhub_private.notification_recipient_state(user_id)
    select id from public.profiles where id = recipient
    on conflict (user_id) do nothing;
  perform 1 from studyhub_private.notification_recipient_state where user_id = recipient for update;
  return found;
end;
$$;

create or replace function studyhub_private.notification_preferences_json(recipient uuid) returns jsonb
language sql stable security definer set search_path = '' as $$
  select jsonb_object_agg(c.category, coalesce(p.enabled, true) order by c.category)
  from unnest(array['friendship','like','comment']) as c(category)
  left join studyhub_private.notification_preferences p
    on p.user_id = recipient and p.channel = 'in_app' and p.category = c.category;
$$;

create or replace function public.get_my_notification_preferences() returns jsonb
language plpgsql stable security definer set search_path = '' as $$
begin
  return studyhub_private.notification_preferences_json(studyhub_private.friend_actor());
end;
$$;

create or replace function public.set_my_notification_preferences(p_category text, p_enabled boolean) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare actor uuid := studyhub_private.friend_actor();
begin
  if p_category is null or p_category not in ('friendship','like','comment') or p_enabled is null then
    raise exception 'invalid preference' using errcode = 'F0001';
  end if;
  -- Same lock as producers: a setting applies to every interaction committed after it.
  perform studyhub_private.lock_notification_recipient(actor);
  insert into studyhub_private.notification_preferences(user_id, channel, category, enabled)
    values (actor, 'in_app', p_category, p_enabled)
    on conflict (user_id, channel, category) do update set enabled = excluded.enabled, updated_at = now();
  return studyhub_private.notification_preferences_json(actor);
end;
$$;

-- ---------------------------------------------------------------------------
-- 3. Visibility helpers shared by producers, lists, counts and mutations.
-- ---------------------------------------------------------------------------
-- Mirrors PostTypeUtils.parse: "<category>:<visibility>" or a bare visibility word.
create or replace function studyhub_private.notification_post_visibility(raw_type text) returns text
language sql immutable set search_path = '' as $$
  select case v when 'friends' then 'friends' when 'private' then 'private' else 'public' end
  from (select case when position(':' in coalesce(raw_type,'')) > 0
                    then lower(btrim(split_part(raw_type, ':', 2)))
                    else lower(btrim(coalesce(raw_type,''))) end as v) s;
$$;

create or replace function studyhub_private.post_viewable(viewer uuid, target_post uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.posts p join public.profiles a on a.id = p.author_id
    where p.id = target_post and not a.is_banned
      and (p.author_id = viewer or (
        studyhub_private.friend_peer_available(viewer, p.author_id)
        and case studyhub_private.notification_post_visibility(p.type)
              when 'private' then false
              when 'friends' then exists (
                select 1 from public.friendships f where f.status = 'accepted'
                  and least(f.requester_id, f.addressee_id) = least(viewer, p.author_id)
                  and greatest(f.requester_id, f.addressee_id) = greatest(viewer, p.author_id))
              else true end)));
$$;

create or replace function studyhub_private.notification_visible(n public.notifications, viewer uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select n.user_id = viewer
    and exists (select 1 from public.profiles where id = viewer and not is_banned)
    and (n.actor_id is null or studyhub_private.friend_peer_available(viewer, n.actor_id))
    and (n.post_id is null or studyhub_private.post_viewable(viewer, n.post_id));
$$;

-- ---------------------------------------------------------------------------
-- 4. Producer. Recipients come from source rows, never from client input.
--    Returns silently on ineligible/suppressed events; raises on bad input so
--    the business transaction rolls back instead of committing unnotified.
-- ---------------------------------------------------------------------------
create or replace function studyhub_private.create_notification(
  recipient uuid, actor uuid, kind text, p_source_kind text, p_source_id uuid, p_post_id uuid default null
) returns void language plpgsql security definer set search_path = '' as $$
declare v_category text; v_enabled boolean;
begin
  v_category := case when kind in ('friend_request','friend_accept') then 'friendship'
                   when kind in ('like','comment') then kind end;
  if v_category is null or p_source_id is null or p_source_kind is null
     or p_source_kind <> (case v_category when 'friendship' then 'friendship' else kind end) then
    raise exception 'invalid notification source' using errcode = 'F0001';
  end if;
  if recipient is null or actor is null or recipient = actor then return; end if;
  if not exists (select 1 from public.profiles where id = actor and not is_banned)
     or not studyhub_private.friend_peer_available(actor, recipient) then
    return;
  end if;
  if p_post_id is not null and not (
       exists (select 1 from public.posts p where p.id = p_post_id and p.author_id = recipient)
       and studyhub_private.post_viewable(actor, p_post_id)) then
    return;
  end if;
  if not studyhub_private.lock_notification_recipient(recipient) then return; end if;
  select coalesce((select p.enabled from studyhub_private.notification_preferences p
                   where p.user_id = recipient and p.channel = 'in_app' and p.category = v_category), true)
    into v_enabled;
  if not v_enabled then return; end if;
  insert into public.notifications(user_id, actor_id, type, post_id, source_kind, source_id, event_key, template_version)
    values (recipient, actor, kind, p_post_id, p_source_kind, p_source_id, kind || ':' || p_source_id::text, 1)
    on conflict (user_id, event_key) where event_key is not null do nothing;
end;
$$;

create or replace function studyhub_private.notify_post_interaction() returns trigger
language plpgsql security definer set search_path = '' as $$
declare kind text := case tg_table_name when 'likes' then 'like' else 'comment' end; recipient uuid;
begin
  if tg_op = 'DELETE' then
    delete from public.notifications where source_kind = kind and source_id = old.id;
    return old;
  end if;
  -- Replies notify only the post owner in v1; parent-comment authors are not fanned out.
  select author_id into recipient from public.posts where id = new.post_id;
  perform studyhub_private.create_notification(recipient, new.user_id, kind, kind, new.id, new.post_id);
  return new;
end;
$$;

-- INSERT/DELETE only: editing a comment never creates another notification.
drop trigger if exists notifications_like_source on public.likes;
create trigger notifications_like_source after insert or delete on public.likes
  for each row execute function studyhub_private.notify_post_interaction();
drop trigger if exists notifications_comment_source on public.comments;
create trigger notifications_comment_source after insert or delete on public.comments
  for each row execute function studyhub_private.notify_post_interaction();

-- ---------------------------------------------------------------------------
-- 5. Inbox RPCs. Old signatures keep their legacy item shape.
-- ---------------------------------------------------------------------------
create or replace function studyhub_private.notification_page(
  viewer uuid, p_filter text, p_limit integer, p_cursor_created_at timestamptz, p_cursor_id uuid, legacy boolean
) returns jsonb language plpgsql stable security definer set search_path = '' as $$
declare page_size integer; result jsonb;
begin
  if p_filter is null or p_filter not in ('all','unread') or p_limit is null or p_limit <= 0
     or ((p_cursor_created_at is null) <> (p_cursor_id is null))
     or (p_cursor_created_at is not null and not isfinite(p_cursor_created_at)) then
    raise exception 'invalid page input' using errcode = 'F0001';
  end if;
  page_size := least(p_limit, 50);
  with visible as materialized (
    select n.* from public.notifications n
    where n.user_id = viewer and studyhub_private.notification_visible(n, viewer)
  ), candidates as materialized (
    select * from visible
    where (p_filter = 'all' or not is_read)
      and (p_cursor_id is null or (created_at, id) < (p_cursor_created_at, p_cursor_id))
    order by created_at desc, id desc limit page_size + 1
  ), page as (
    select c.*, case when p.id is null then null else jsonb_build_object('id', p.id, 'username', p.username,
             'display_name', p.display_name, 'avatar_url', p.avatar_url) end as actor
    from (select * from candidates order by created_at desc, id desc limit page_size) c
    left join public.profiles p on p.id = c.actor_id
  )
  select jsonb_build_object(
    'items', coalesce((select jsonb_agg(
        case when legacy then jsonb_build_object('id', id, 'type', type, 'is_read', is_read,
               'created_at', created_at, 'post_id', post_id, 'actor', actor)
             else jsonb_build_object('id', id, 'type', type, 'is_read', is_read, 'created_at', created_at,
               'post_id', post_id, 'actor', actor, 'source_kind', source_kind, 'source_id', source_id,
               'template_version', template_version, 'opened_at', opened_at) end
        order by created_at desc, id desc) from page), '[]'::jsonb),
    'next_cursor', case when (select count(*) from candidates) > page_size then
        (select jsonb_build_object('created_at', created_at, 'id', id) from page order by created_at, id limit 1)
      else null end,
    -- Global visible unread count, independent of filter and page.
    'unread_count', (select count(*) from visible where not is_read)) into result;
  return result;
end;
$$;

create or replace function public.list_my_notifications_v2(
  p_filter text default 'all', p_limit integer default 20,
  p_cursor_created_at timestamptz default null, p_cursor_id uuid default null
) returns jsonb language plpgsql stable security definer set search_path = '' as $$
begin
  return studyhub_private.notification_page(studyhub_private.friend_actor(), p_filter, p_limit,
    p_cursor_created_at, p_cursor_id, false);
end;
$$;

create or replace function public.list_my_notifications(
  p_limit integer default 20, p_cursor_created_at timestamptz default null, p_cursor_id uuid default null
) returns jsonb language plpgsql security definer set search_path = '' as $$
begin
  return studyhub_private.notification_page(studyhub_private.friend_actor(), 'all', p_limit,
    p_cursor_created_at, p_cursor_id, true);
end;
$$;

-- Mark-all updates only rows visible to this statement's snapshot; later arrivals stay unread.
create or replace function public.mark_notifications_read(p_ids uuid[] default null) returns integer
language plpgsql security definer set search_path = '' as $$
declare actor uuid := studyhub_private.friend_actor(); changed integer;
begin
  if p_ids is not null and (cardinality(p_ids) = 0 or cardinality(p_ids) > 100) then
    raise exception 'invalid ids' using errcode = 'F0001';
  end if;
  update public.notifications n set is_read = true
    where n.user_id = actor and not n.is_read and (p_ids is null or n.id = any(p_ids))
      and studyhub_private.notification_visible(n, actor);
  get diagnostics changed = row_count;
  return changed;
end;
$$;

-- First open is recorded once; repeats/retries only keep the row read.
create or replace function public.open_my_notification(p_id uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare actor uuid := studyhub_private.friend_actor(); opened public.notifications;
begin
  if p_id is null then raise exception 'invalid id' using errcode = 'F0001'; end if;
  update public.notifications n set is_read = true, opened_at = coalesce(n.opened_at, now())
    where n.id = p_id and n.user_id = actor and studyhub_private.notification_visible(n, actor)
    returning n.* into opened;
  if not found then raise exception 'notification unavailable' using errcode = 'F0004'; end if;
  return jsonb_build_object('id', opened.id, 'type', opened.type, 'post_id', opened.post_id, 'actor_id', opened.actor_id);
end;
$$;

-- ---------------------------------------------------------------------------
-- 6. Friendship producers: same guards/transitions, now with source ids,
--    idempotent event keys and preferences. Legacy rows (source_id NULL)
--    keep the previous actor/recipient cleanup behavior.
-- ---------------------------------------------------------------------------
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
  for attempt in 1..3 loop
    inserted := null;
    insert into public.friendships(requester_id, addressee_id, status)
      values (actor, p_target_id, 'pending') on conflict do nothing returning id into inserted;
    if inserted is not null then
      perform studyhub_private.create_notification(p_target_id, actor, 'friend_request', 'friendship', inserted);
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
      perform studyhub_private.create_notification(target, actor, 'friend_accept', 'friendship', rel.id);
      update public.notifications set is_read = true
        where user_id = actor and actor_id = target and type = 'friend_request' and not is_read
          and (source_id = rel.id or source_id is null);
    end if;
    return studyhub_private.friend_snapshot(actor, target);
  elsif action in ('decline','cancel','remove') then
    if (action = 'remove' and rel.status <> 'accepted')
       or (action <> 'remove' and rel.status <> 'pending') then
      raise exception 'relationship changed' using errcode = 'F0004';
    end if;
    delete from public.friendships where id = rel.id;
    if action = 'cancel' then
      delete from public.notifications
        where user_id = rel.addressee_id and actor_id = rel.requester_id and type = 'friend_request'
          and (source_id = rel.id or source_id is null);
    elsif action = 'decline' then
      update public.notifications set is_read = true
        where user_id = actor and actor_id = target and type = 'friend_request' and not is_read
          and (source_id = rel.id or source_id is null);
    end if;
    return jsonb_build_object('target_id', target, 'state', 'none', 'friendship_id', null);
  end if;
  raise exception 'invalid action' using errcode = 'F0001';
end;
$$;

-- ---------------------------------------------------------------------------
-- 7. BREAKING: no direct client table access. RPCs are the only data path.
-- ---------------------------------------------------------------------------
drop policy if exists "notifications_insert_as_actor" on public.notifications;
drop policy if exists "notifications_update_own" on public.notifications;
drop policy if exists "notifications_select_own" on public.notifications;
revoke all on public.notifications from public, anon, authenticated;

-- Only this change's helpers: a schema-wide revoke would also strip grants other
-- features rely on (e.g. search_norm, which posts/profiles index expressions call
-- with the writer's privileges).
revoke all on function studyhub_private.lock_notification_recipient(uuid),
  studyhub_private.notification_preferences_json(uuid),
  studyhub_private.notification_post_visibility(text),
  studyhub_private.post_viewable(uuid, uuid),
  studyhub_private.notification_visible(public.notifications, uuid),
  studyhub_private.create_notification(uuid, uuid, text, text, uuid, uuid),
  studyhub_private.notify_post_interaction(),
  studyhub_private.notification_page(uuid, text, integer, timestamptz, uuid, boolean),
  studyhub_private.friend_mutate(uuid, text)
  from public, anon, authenticated;
revoke all on function public.list_my_notifications_v2(text,integer,timestamptz,uuid),
  public.list_my_notifications(integer,timestamptz,uuid), public.mark_notifications_read(uuid[]),
  public.open_my_notification(uuid), public.get_my_notification_preferences(),
  public.set_my_notification_preferences(text,boolean), public.send_friend_request(uuid)
  from public, anon, authenticated;
grant execute on function public.list_my_notifications_v2(text,integer,timestamptz,uuid),
  public.list_my_notifications(integer,timestamptz,uuid), public.mark_notifications_read(uuid[]),
  public.open_my_notification(uuid), public.get_my_notification_preferences(),
  public.set_my_notification_preferences(text,boolean), public.send_friend_request(uuid)
  to authenticated;

-- Notification delivery: transactional outbox, bounded SQL worker with confirmed
-- private Broadcast publication, retry/dead-letter, invalidations, metrics and
-- receive-only Realtime authorization. Idempotent; depends on 20261106120000.
-- The scheduler is NOT enabled here: see supabase/notifications/enable_scheduler.sql.

-- ---------------------------------------------------------------------------
-- 1. Private storage. Jobs carry no notification content.
-- ---------------------------------------------------------------------------
create table if not exists studyhub_private.notification_delivery_jobs (
  id uuid primary key default gen_random_uuid(),            -- stable event/job identity
  recipient_id uuid not null references public.profiles(id) on delete cascade,
  channel text not null default 'in_app' check (channel = 'in_app'),
  status text not null default 'pending'
    check (status in ('pending','retry','published','dead_letter','skipped')),
  attempts integer not null default 0 check (attempts >= 0),
  last_dispatch_id uuid,                                     -- most recent attempt's correlation id
  next_attempt_at timestamptz not null default now(),
  created_at timestamptz not null default now(),
  published_at timestamptz,
  safe_error_code text check (safe_error_code is null or safe_error_code ~ '^[a-z0-9_]{1,40}$')
);
create index if not exists notification_jobs_due_idx
  on studyhub_private.notification_delivery_jobs(next_attempt_at, id) where status in ('pending','retry');
create index if not exists notification_jobs_recipient_idx
  on studyhub_private.notification_delivery_jobs(recipient_id);
create index if not exists notification_jobs_completed_idx
  on studyhub_private.notification_delivery_jobs(published_at, id) where status in ('published','skipped');
create index if not exists notification_jobs_dead_idx
  on studyhub_private.notification_delivery_jobs(created_at, id) where status = 'dead_letter';

-- Server-side rate-limit state: last confirmed publication per recipient.
create table if not exists studyhub_private.notification_dispatch_state (
  user_id uuid primary key references public.profiles(id) on delete cascade,
  last_published_at timestamptz
);
-- Pending fan-out of visibility changes (ban / post ownership), expanded in bounded batches.
create table if not exists studyhub_private.notification_invalidations (
  scope text not null check (scope in ('actor','post')),
  source_id uuid not null,
  cursor_id uuid,
  primary key (scope, source_id)
);
alter table studyhub_private.notification_delivery_jobs enable row level security;
alter table studyhub_private.notification_dispatch_state enable row level security;
alter table studyhub_private.notification_invalidations enable row level security;
revoke all on studyhub_private.notification_delivery_jobs, studyhub_private.notification_dispatch_state,
  studyhub_private.notification_invalidations from public, anon, authenticated, service_role;

-- ---------------------------------------------------------------------------
-- 2. Outbox producers: one job per affected recipient per inbox statement, in
--    the same transaction. Deleted recipients get no job (FK cleanup).
-- ---------------------------------------------------------------------------
create or replace function studyhub_private.enqueue_notification(recipient uuid) returns void
language sql security definer set search_path = '' as $$
  insert into studyhub_private.notification_delivery_jobs(recipient_id)
    select id from public.profiles where id = recipient;
$$;

create or replace function studyhub_private.notification_rows_changed() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  if tg_op = 'INSERT' then
    insert into studyhub_private.notification_delivery_jobs(recipient_id)
      select distinct n.user_id from new_rows n join public.profiles p on p.id = n.user_id;
  elsif tg_op = 'DELETE' then
    insert into studyhub_private.notification_delivery_jobs(recipient_id)
      select distinct o.user_id from old_rows o join public.profiles p on p.id = o.user_id;
  else
    insert into studyhub_private.notification_delivery_jobs(recipient_id)
      select distinct n.user_id from new_rows n join old_rows o on o.id = n.id
        join public.profiles p on p.id = n.user_id
      where n is distinct from o;
  end if;
  return null;
end;
$$;
drop trigger if exists notification_outbox on public.notifications;
drop trigger if exists notification_outbox_insert on public.notifications;
drop trigger if exists notification_outbox_update on public.notifications;
drop trigger if exists notification_outbox_delete on public.notifications;
create trigger notification_outbox_insert after insert on public.notifications
  referencing new table as new_rows for each statement execute function studyhub_private.notification_rows_changed();
create trigger notification_outbox_update after update on public.notifications
  referencing new table as new_rows old table as old_rows
  for each statement execute function studyhub_private.notification_rows_changed();
create trigger notification_outbox_delete after delete on public.notifications
  referencing old table as old_rows for each statement execute function studyhub_private.notification_rows_changed();

-- Visibility changes that alter what list/count RPCs return without an inbox write.
create or replace function studyhub_private.notification_visibility_changed() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  if tg_table_name = 'blocks' then
    if tg_op = 'DELETE' then
      perform studyhub_private.enqueue_notification(old.blocker_id);
      perform studyhub_private.enqueue_notification(old.blocked_id);
    else
      perform studyhub_private.enqueue_notification(new.blocker_id);
      perform studyhub_private.enqueue_notification(new.blocked_id);
    end if;
  elsif tg_table_name = 'profiles' then
    insert into studyhub_private.notification_invalidations(scope, source_id) values ('actor', new.id)
      on conflict (scope, source_id) do update set cursor_id = null;
    perform studyhub_private.enqueue_notification(new.id);
  else
    insert into studyhub_private.notification_invalidations(scope, source_id) values ('post', new.id)
      on conflict (scope, source_id) do update set cursor_id = null;
  end if;
  return null;
end;
$$;
drop trigger if exists notifications_blocks on public.blocks;
create trigger notifications_blocks after insert or delete on public.blocks
  for each row execute function studyhub_private.notification_visibility_changed();
drop trigger if exists notifications_banned on public.profiles;
create trigger notifications_banned after update of is_banned on public.profiles
  for each row when (old.is_banned is distinct from new.is_banned)
  execute function studyhub_private.notification_visibility_changed();
drop trigger if exists notifications_post_visibility on public.posts;
create trigger notifications_post_visibility after update of author_id on public.posts
  for each row when (old.author_id is distinct from new.author_id)
  execute function studyhub_private.notification_visibility_changed();

-- Expands at most one scope and 100 recipients per worker run.
create or replace function studyhub_private.expand_notification_invalidations() returns integer
language plpgsql security definer set search_path = '' as $$
declare scope_row studyhub_private.notification_invalidations; recipient uuid; last_id uuid; total integer := 0;
begin
  select * into scope_row from studyhub_private.notification_invalidations
    order by scope, source_id limit 1 for update skip locked;
  if not found then return 0; end if;
  for recipient in
    select distinct n.user_id from public.notifications n
    where ((scope_row.scope = 'actor' and n.actor_id = scope_row.source_id)
        or (scope_row.scope = 'post' and n.post_id = scope_row.source_id))
      and (scope_row.cursor_id is null or n.user_id > scope_row.cursor_id)
    order by n.user_id limit 100
  loop
    perform studyhub_private.enqueue_notification(recipient);
    last_id := recipient; total := total + 1;
  end loop;
  if total < 100 then
    delete from studyhub_private.notification_invalidations
      where scope = scope_row.scope and source_id = scope_row.source_id;
  else
    update studyhub_private.notification_invalidations set cursor_id = last_id
      where scope = scope_row.scope and source_id = scope_row.source_id;
  end if;
  return total;
end;
$$;

-- ---------------------------------------------------------------------------
-- 3. Worker-only publication adapter. realtime.send() converts INSERT errors
--    into WARNINGs, so success is proven only by finding exactly one matching
--    realtime.messages row for this attempt's fresh dispatch_id in the same
--    transaction. Managed Realtime objects are not modified.
-- ---------------------------------------------------------------------------
create or replace function studyhub_private.publish_notification(recipient uuid, p_dispatch_id uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare expected_topic text := 'notifications:' || recipient::text; total bigint; exact bigint;
begin
  if recipient is null or p_dispatch_id is null then
    raise exception 'invalid dispatch' using errcode = 'N0002';
  end if;
  if exists (select 1 from realtime.messages m where m.payload->>'dispatch_id' = p_dispatch_id::text) then
    raise exception 'broadcast_unconfirmed' using errcode = 'N0001';
  end if;
  perform realtime.send(jsonb_build_object('schema_version', 1, 'dispatch_id', p_dispatch_id),
                        'notifications_changed', expected_topic, true);
  select count(*),
         count(*) filter (where m.topic = expected_topic and m.event = 'notifications_changed'
                            and m.private and m.extension = 'broadcast'
                            and m.payload->'schema_version' = '1'::jsonb)
    into total, exact
    from realtime.messages m where m.payload->>'dispatch_id' = p_dispatch_id::text;
  if total <> 1 or exact <> 1 then
    raise exception 'broadcast_unconfirmed' using errcode = 'N0001';
  end if;
end;
$$;

create or replace function studyhub_private.notification_safe_error(state text) returns text
language sql immutable set search_path = '' as $$
  select case state when 'N0001' then 'broadcast_unconfirmed' when 'N0002' then 'invalid_dispatch'
                    when '42501' then 'permission_denied' when '42P01' then 'realtime_schema_missing'
                    when '42703' then 'realtime_schema_mismatch' else 'sqlstate_' || lower(state) end;
$$;

-- ---------------------------------------------------------------------------
-- 4. Bounded worker: single run (advisory lock), <=100 claimed jobs, time bound,
--    per-recipient coalescing, 5-second per-recipient interval, 5 attempts.
-- ---------------------------------------------------------------------------
create or replace function studyhub_private.dispatch_notifications(
  p_batch integer default 100, p_time_budget interval default interval '3 seconds'
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  job_ids uuid[]; recipient uuid; ids uuid[]; dispatch uuid; last_sent timestamptz;
  started timestamptz := clock_timestamp(); published integer := 0; failed integer := 0;
  deferred integer := 0; skipped integer := 0; claimed integer := 0; err text;
  min_interval constant interval := interval '5 seconds';
  max_attempts constant integer := 5;
begin
  if not pg_try_advisory_xact_lock(724160611201) then
    return jsonb_build_object('locked', true);
  end if;
  perform studyhub_private.expand_notification_invalidations();
  select array_agg(j.id) into job_ids from (
    select id from studyhub_private.notification_delivery_jobs
    where status in ('pending','retry') and next_attempt_at <= clock_timestamp()
    order by next_attempt_at, id limit least(greatest(coalesce(p_batch, 100), 1), 100)
    for update skip locked) j;
  claimed := coalesce(cardinality(job_ids), 0);

  for recipient in
    select recipient_id from studyhub_private.notification_delivery_jobs
    where id = any(job_ids) group by recipient_id order by min(next_attempt_at), recipient_id
  loop
    -- Unprocessed claimed jobs stay pending and are released at commit.
    exit when clock_timestamp() - started > p_time_budget;
    select array_agg(id) into ids from studyhub_private.notification_delivery_jobs
      where id = any(job_ids) and recipient_id = recipient;

    if not exists (select 1 from public.profiles where id = recipient and not is_banned) then
      -- Banned recipients cannot subscribe; nothing to signal. Unban enqueues a fresh job.
      update studyhub_private.notification_delivery_jobs
        set status = 'skipped', published_at = clock_timestamp(), safe_error_code = 'recipient_unavailable'
        where id = any(ids);
      skipped := skipped + cardinality(ids);
      continue;
    end if;

    insert into studyhub_private.notification_dispatch_state(user_id) values (recipient) on conflict do nothing;
    select last_published_at into last_sent from studyhub_private.notification_dispatch_state
      where user_id = recipient for update;
    if last_sent is not null and last_sent + min_interval > clock_timestamp() then
      -- Throttle: defer (never drop) and coalesce into the next allowed signal.
      update studyhub_private.notification_delivery_jobs set next_attempt_at = last_sent + min_interval
        where id = any(ids);
      deferred := deferred + cardinality(ids);
      continue;
    end if;

    dispatch := gen_random_uuid();   -- fresh for every attempt, never reused on retry/requeue
    begin
      perform studyhub_private.publish_notification(recipient, dispatch);
      update studyhub_private.notification_delivery_jobs
        set status = 'published', attempts = attempts + 1, last_dispatch_id = dispatch,
            published_at = clock_timestamp(), safe_error_code = null
        where id = any(ids);
      update studyhub_private.notification_dispatch_state set last_published_at = clock_timestamp()
        where user_id = recipient;
      published := published + cardinality(ids);
    exception when others then
      -- The subtransaction (send + confirmation + status) is already rolled back here.
      err := studyhub_private.notification_safe_error(sqlstate);
      update studyhub_private.notification_delivery_jobs
        set attempts = attempts + 1, last_dispatch_id = dispatch, safe_error_code = err,
            status = case when attempts + 1 >= max_attempts then 'dead_letter' else 'retry' end,
            next_attempt_at = clock_timestamp()
              + make_interval(secs => 5 * power(2, least(attempts, 3)) * (1 + random() * 0.2))
        where id = any(ids);
      failed := failed + cardinality(ids);
    end;
  end loop;
  return jsonb_build_object('claimed', claimed, 'published', published, 'failed', failed,
                            'deferred', deferred, 'skipped', skipped);
end;
$$;

-- Operator-only replay. Inbox rows are untouched, so replay cannot duplicate notifications.
create or replace function studyhub_private.requeue_notification_jobs(p_job_ids uuid[] default null, p_limit integer default 100)
returns integer language plpgsql security definer set search_path = '' as $$
declare changed integer;
begin
  update studyhub_private.notification_delivery_jobs set status = 'pending', attempts = 0,
      next_attempt_at = now(), safe_error_code = null
    where id in (select id from studyhub_private.notification_delivery_jobs
                 where status = 'dead_letter' and (p_job_ids is null or id = any(p_job_ids))
                 order by created_at, id limit least(greatest(coalesce(p_limit, 100), 1), 1000)
                 for update skip locked);
  get diagnostics changed = row_count;
  return changed;
end;
$$;

-- Bounded retention: only completed history older than 7 days. Pending/retry/dead-letter are kept.
create or replace function studyhub_private.cleanup_notification_jobs(p_limit integer default 1000) returns integer
language plpgsql security definer set search_path = '' as $$
declare changed integer;
begin
  delete from studyhub_private.notification_delivery_jobs where id in (
    select id from studyhub_private.notification_delivery_jobs
    where status in ('published','skipped') and published_at < now() - interval '7 days'
    order by published_at, id limit least(greatest(coalesce(p_limit, 1000), 1), 5000)
    for update skip locked);
  get diagnostics changed = row_count;
  return changed;
end;
$$;

-- ---------------------------------------------------------------------------
-- 5. Operator metrics. No user ids, content or credentials.
-- ---------------------------------------------------------------------------
create or replace view studyhub_private.notification_health as
with j as (
  select
    count(*) filter (where status = 'pending') as pending,
    count(*) filter (where status = 'retry') as retry,
    count(*) filter (where status = 'dead_letter') as dead_letter,
    coalesce(extract(epoch from now() - min(next_attempt_at)
      filter (where status in ('pending','retry') and next_attempt_at <= now())), 0)::numeric(12,1) as oldest_due_seconds,
    coalesce(max(attempts) filter (where status in ('retry','dead_letter')), 0) as max_attempts_open,
    (avg(extract(epoch from published_at - created_at))
      filter (where status = 'published' and published_at > now() - interval '1 hour'))::numeric(12,2) as avg_publish_seconds_1h,
    (percentile_cont(0.95) within group (order by extract(epoch from published_at - created_at))
      filter (where status = 'published' and published_at > now() - interval '1 hour'))::numeric(12,2) as p95_publish_seconds_1h
  from studyhub_private.notification_delivery_jobs
)
select j.*,
  (select count(*) from public.notifications where opened_at is not null) as opened_total,
  (select count(*) from studyhub_private.notification_invalidations) as pending_invalidations,
  (j.dead_letter > 0 or j.oldest_due_seconds > 60) as alarm
from j;
revoke all on studyhub_private.notification_health from public, anon, authenticated, service_role;

-- Only this change's helpers (see the note in 20261106120000).
revoke all on function studyhub_private.enqueue_notification(uuid),
  studyhub_private.notification_rows_changed(),
  studyhub_private.notification_visibility_changed(),
  studyhub_private.expand_notification_invalidations(),
  studyhub_private.publish_notification(uuid, uuid),
  studyhub_private.notification_safe_error(text),
  studyhub_private.dispatch_notifications(integer, interval),
  studyhub_private.requeue_notification_jobs(uuid[], integer),
  studyhub_private.cleanup_notification_jobs(integer)
  from public, anon, authenticated;
revoke all on function studyhub_private.dispatch_notifications(integer, interval),
  studyhub_private.publish_notification(uuid, uuid),
  studyhub_private.requeue_notification_jobs(uuid[], integer),
  studyhub_private.cleanup_notification_jobs(integer) from service_role;

-- ---------------------------------------------------------------------------
-- 6. Receive-only private topics. Clients never publish and never read rows of
--    other users' topics. Payloads carry only schema_version + dispatch_id.
-- ---------------------------------------------------------------------------
drop policy if exists notifications_receive on realtime.messages;
create policy notifications_receive on realtime.messages for select to authenticated
  using (extension = 'broadcast'
    and realtime.topic() = 'notifications:' || (select auth.uid())::text
    and exists (select 1 from public.profiles p where p.id = (select auth.uid()) and not p.is_banned));
-- Restrictive guard: no future permissive policy can open another user's notification topic.
drop policy if exists notifications_receive_guard on realtime.messages;
create policy notifications_receive_guard on realtime.messages as restrictive for select to authenticated
  using (coalesce(realtime.topic(), '') not like 'notifications:%'
    or (extension = 'broadcast'
        and realtime.topic() = 'notifications:' || (select auth.uid())::text
        and exists (select 1 from public.profiles p where p.id = (select auth.uid()) and not p.is_banned)));
drop policy if exists notifications_no_client_send on realtime.messages;
create policy notifications_no_client_send on realtime.messages as restrictive for insert to authenticated
  with check (coalesce(realtime.topic(), topic, '') not like 'notifications:%' and topic not like 'notifications:%');

-- Keep unified-search normalizer grants (index expressions on posts/profiles/subjects/
-- post_files call them as the writing user). Earlier sections revoke every
-- studyhub_private function; see migration 20261107090000_restore_search_norm_grants.
do $$
begin
  if to_regprocedure('studyhub_private.search_norm(text)') is not null then
    grant execute on function studyhub_private.search_norm(text) to authenticated, service_role;
  end if;
  if to_regprocedure('studyhub_private.search_file_norm(text)') is not null then
    grant execute on function studyhub_private.search_file_norm(text) to authenticated, service_role;
  end if;
end $$;

notify pgrst, 'reload schema';
