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

-- =====================================================================
-- 24. Unified search (pg_trgm + unaccent RPCs, phân hệ 7.3)
-- Source: supabase/migrations/20261106_unified_search.sql
-- =====================================================================

do $$
begin
    if to_regprocedure('studyhub_private.friend_actor()') is null
       or to_regprocedure('studyhub_private.friend_peer_available(uuid,uuid)') is null then
        raise exception 'search preflight failed: friendship helpers are missing';
    end if;
end;
$$;

create schema if not exists extensions;
create extension if not exists pg_trgm with schema extensions;
create extension if not exists unaccent with schema extensions;

do $$
begin
    if exists (
        select 1 from pg_extension e join pg_namespace n on n.oid = e.extnamespace
        where e.extname in ('pg_trgm', 'unaccent') and n.nspname <> 'extensions'
    ) then
        raise exception 'search preflight failed: pg_trgm and unaccent must live in schema extensions';
    end if;
end;
$$;

-- ------------------------------------------------------------------------------
-- Normalization helpers (IMMUTABLE so they can back expression indexes).
-- unaccent itself is STABLE; the dictionary-qualified call is pinned here.
-- Reindex the *_trgm_idx indexes after upgrading the unaccent rules.
-- ------------------------------------------------------------------------------
create or replace function studyhub_private.search_norm(p_value text)
returns text
language sql
immutable
strict
parallel safe
set search_path = ''
as $$
    select btrim(regexp_replace(
        regexp_replace(
            lower(extensions.unaccent('extensions.unaccent'::regdictionary, normalize(p_value, NFC))),
            '[_./-]+', ' ', 'g'),
        '\s+', ' ', 'g'))
$$;

-- Same expression as search_norm after dropping the extension. It is inlined
-- rather than calling search_norm by name: index expressions run as the
-- writing role, which has no USAGE on schema studyhub_private.
create or replace function studyhub_private.search_file_norm(p_value text)
returns text
language sql
immutable
strict
parallel safe
set search_path = ''
as $$
    select btrim(regexp_replace(
        regexp_replace(
            lower(extensions.unaccent('extensions.unaccent'::regdictionary,
                normalize(regexp_replace(p_value, '\.[A-Za-z0-9]{1,8}$', ''), NFC))),
            '[_./-]+', ' ', 'g'),
        '\s+', ' ', 'g'))
$$;

create or replace function studyhub_private.search_like_escape(p_value text)
returns text
language sql
immutable
strict
parallel safe
set search_path = ''
as $$
    select replace(replace(replace(p_value, '\', '\\'), '%', '\%'), '_', '\_')
$$;

-- Validates paging input and returns the normalized query and page window.
create or replace function studyhub_private.search_input(
    p_query text, p_limit integer, p_offset integer,
    out q text, out q_like text, out q_raw text, out page_size integer)
language plpgsql
immutable
set search_path = ''
as $$
begin
    if p_query is null or p_limit is null or p_offset is null
       or p_limit <= 0 or p_offset < 0 or char_length(p_query) > 100 then
        raise exception 'invalid search input' using errcode = 'F0001';
    end if;
    q := coalesce(studyhub_private.search_norm(p_query), '');
    q_like := studyhub_private.search_like_escape(q);
    q_raw := lower(btrim(p_query));
    page_size := greatest(least(p_limit, 50, 100 - p_offset), 0);
end;
$$;

-- Mirrors PostTypeUtils.parse: "<category>:<visibility>" or a legacy single value.
-- SQL-standard body: names are bound at creation (no search_path exposure) and
-- the planner can inline it into the search queries.
create or replace function studyhub_private.post_visibility(p_type text)
returns text
language sql
immutable
parallel safe
return case pg_catalog.lower(pg_catalog.btrim(
        case when pg_catalog.strpos(coalesce(p_type, ''), ':') > 0 then pg_catalog.split_part(p_type, ':', 2)
             else coalesce(p_type, '') end, E' \t\r\n'))
    when 'friends' then 'friends'
    when 'private' then 'private'
    else 'public'
end;

-- ------------------------------------------------------------------------------
-- Trigram expression indexes. Queries must use the same expressions.
-- ------------------------------------------------------------------------------
create index if not exists profiles_username_trgm_idx
    on public.profiles using gin (studyhub_private.search_norm(username) extensions.gin_trgm_ops);
create index if not exists profiles_display_name_trgm_idx
    on public.profiles using gin (studyhub_private.search_norm(display_name) extensions.gin_trgm_ops);
create index if not exists posts_title_trgm_idx
    on public.posts using gin (studyhub_private.search_norm(title) extensions.gin_trgm_ops);
create index if not exists posts_content_trgm_idx
    on public.posts using gin (studyhub_private.search_norm(content) extensions.gin_trgm_ops);
create index if not exists subjects_name_trgm_idx
    on public.subjects using gin (studyhub_private.search_norm(name) extensions.gin_trgm_ops);
create index if not exists post_files_file_name_trgm_idx
    on public.post_files using gin (studyhub_private.search_file_norm(file_name) extensions.gin_trgm_ops);

-- ------------------------------------------------------------------------------
-- RPCs. Ranking: tier (1 prefix, 2 contains, 3 fuzzy), exact-diacritic match,
-- word similarity, tab secondary key, id. Pages are capped at 50 items and
-- the first 100 ranked results.
-- ------------------------------------------------------------------------------
create or replace function public.search_profiles(p_query text, p_limit integer default 20, p_offset integer default 0)
returns jsonb
language plpgsql
security definer
set search_path = ''
set pg_trgm.word_similarity_threshold = '0.5'
as $$
declare
    v_actor uuid := studyhub_private.friend_actor();
    v_q text; v_like text; v_raw text; v_size integer; v_contains text; v_fuzzy boolean;
    v_result jsonb;
begin
    select i.q, i.q_like, i.q_raw, i.page_size into v_q, v_like, v_raw, v_size
    from studyhub_private.search_input(p_query, p_limit, p_offset) i;
    if char_length(v_q) < 2 or v_size = 0 then
        return jsonb_build_object('items', '[]'::jsonb, 'has_more', false);
    end if;
    v_contains := '%' || v_like || '%';
    -- Trigrams ignore symbols, so queries such as "100%" or "c++" stay literal.
    v_fuzzy := char_length(v_q) >= 4 and v_q !~ '[^[:alnum:][:space:]]';

    with hits as (
        select p.id, p.username, p.display_name, p.avatar_url,
               studyhub_private.search_norm(p.username) as n_user,
               studyhub_private.search_norm(p.display_name) as n_display
        from public.profiles p
        where not p.is_banned
          and (p.id = v_actor or not exists (
                select 1 from public.blocks b
                where (b.blocker_id = v_actor and b.blocked_id = p.id)
                   or (b.blocker_id = p.id and b.blocked_id = v_actor)))
          and (studyhub_private.search_norm(p.username) like v_contains escape '\'
               or studyhub_private.search_norm(p.display_name) like v_contains escape '\'
               or (v_fuzzy and v_q operator(extensions.<%) studyhub_private.search_norm(p.username))
               or (v_fuzzy and v_q operator(extensions.<%) studyhub_private.search_norm(p.display_name)))
    ), ordered as (
        select h.*, row_number() over (order by
            case when h.n_user like v_like || '%' escape '\' or h.n_display like v_like || '%' escape '\' then 1
                 when h.n_user like v_contains escape '\' or h.n_display like v_contains escape '\' then 2
                 else 3 end,
            (strpos(lower(h.username), v_raw) > 0 or strpos(lower(h.display_name), v_raw) > 0) desc,
            greatest(extensions.word_similarity(v_q, h.n_user), extensions.word_similarity(v_q, h.n_display)) desc,
            h.username, h.id) as rn
        from hits h
    )
    select jsonb_build_object(
        'items', coalesce(jsonb_agg(jsonb_build_object(
                'id', o.id, 'username', o.username, 'display_name', o.display_name, 'avatar_url', o.avatar_url)
            order by o.rn) filter (where o.rn > p_offset and o.rn <= p_offset + v_size), '[]'::jsonb),
        'has_more', coalesce(bool_or(o.rn > p_offset + v_size), false) and p_offset + v_size < 100)
    into v_result
    from ordered o
    where o.rn <= p_offset + v_size + 1;
    return v_result;
end;
$$;

create or replace function public.search_subjects(p_query text, p_limit integer default 20, p_offset integer default 0)
returns jsonb
language plpgsql
security definer
set search_path = ''
set pg_trgm.word_similarity_threshold = '0.5'
as $$
declare
    v_actor uuid := studyhub_private.friend_actor();
    v_q text; v_like text; v_raw text; v_size integer; v_contains text; v_fuzzy boolean;
    v_result jsonb;
begin
    select i.q, i.q_like, i.q_raw, i.page_size into v_q, v_like, v_raw, v_size
    from studyhub_private.search_input(p_query, p_limit, p_offset) i;
    if char_length(v_q) < 2 or v_size = 0 then
        return jsonb_build_object('items', '[]'::jsonb, 'has_more', false);
    end if;
    v_contains := '%' || v_like || '%';
    -- Trigrams ignore symbols, so queries such as "100%" or "c++" stay literal.
    v_fuzzy := char_length(v_q) >= 4 and v_q !~ '[^[:alnum:][:space:]]';

    with hits as (
        select s.id, s.name, s.description, s.is_active, s.created_at,
               studyhub_private.search_norm(s.name) as n_name,
               studyhub_private.search_norm(s.description) as n_desc
        from public.subjects s
        where s.is_active
          and (studyhub_private.search_norm(s.name) like v_contains escape '\'
               or studyhub_private.search_norm(s.description) like v_contains escape '\'
               or (v_fuzzy and v_q operator(extensions.<%) studyhub_private.search_norm(s.name)))
    ), ordered as (
        select h.*, row_number() over (order by
            case when h.n_name like v_like || '%' escape '\' or h.n_desc like v_like || '%' escape '\' then 1
                 when h.n_name like v_contains escape '\' or h.n_desc like v_contains escape '\' then 2
                 else 3 end,
            (strpos(lower(h.name), v_raw) > 0 or strpos(lower(coalesce(h.description, '')), v_raw) > 0) desc,
            extensions.word_similarity(v_q, h.n_name) desc,
            h.name, h.id) as rn
        from hits h
    )
    select jsonb_build_object(
        'items', coalesce(jsonb_agg(jsonb_build_object(
                'id', o.id, 'name', o.name, 'description', o.description,
                'is_active', o.is_active, 'created_at', o.created_at)
            order by o.rn) filter (where o.rn > p_offset and o.rn <= p_offset + v_size), '[]'::jsonb),
        'has_more', coalesce(bool_or(o.rn > p_offset + v_size), false) and p_offset + v_size < 100)
    into v_result
    from ordered o
    where o.rn <= p_offset + v_size + 1;
    return v_result;
end;
$$;

create or replace function public.search_posts(p_query text, p_limit integer default 20, p_offset integer default 0)
returns jsonb
language plpgsql
security definer
set search_path = ''
set pg_trgm.word_similarity_threshold = '0.5'
as $$
declare
    v_actor uuid := studyhub_private.friend_actor();
    v_q text; v_like text; v_raw text; v_size integer; v_contains text; v_fuzzy boolean;
    v_result jsonb;
begin
    select i.q, i.q_like, i.q_raw, i.page_size into v_q, v_like, v_raw, v_size
    from studyhub_private.search_input(p_query, p_limit, p_offset) i;
    if char_length(v_q) < 2 or v_size = 0 then
        return jsonb_build_object('items', '[]'::jsonb, 'has_more', false);
    end if;
    v_contains := '%' || v_like || '%';
    -- Trigrams ignore symbols, so queries such as "100%" or "c++" stay literal.
    v_fuzzy := char_length(v_q) >= 4 and v_q !~ '[^[:alnum:][:space:]]';

    -- Visibility: the author sees everything; others need an author who is not
    -- banned, no block either way, and public visibility or an accepted
    -- friendship. Friend and block sets are computed once per call.
    with viewer_friends as materialized (
        select case when f.requester_id = v_actor then f.addressee_id else f.requester_id end as peer_id
        from public.friendships f
        where f.status = 'accepted' and (f.requester_id = v_actor or f.addressee_id = v_actor)
    ), blocked as materialized (
        select b.blocked_id as peer_id from public.blocks b where b.blocker_id = v_actor
        union
        select b.blocker_id from public.blocks b where b.blocked_id = v_actor
    ), hits as (
        select p.id, p.title, p.content, p.created_at,
               studyhub_private.search_norm(p.title) as n_title,
               studyhub_private.search_norm(p.content) as n_content
        from public.posts p
        join public.profiles a on a.id = p.author_id
        where (studyhub_private.search_norm(p.title) like v_contains escape '\'
               or studyhub_private.search_norm(p.content) like v_contains escape '\'
               or (v_fuzzy and v_q operator(extensions.<%) studyhub_private.search_norm(p.title)))
          and (p.author_id = v_actor
               or (not a.is_banned
                   and not exists (select 1 from blocked x where x.peer_id = p.author_id)
                   and case studyhub_private.post_visibility(p.type)
                       when 'public' then true
                       when 'friends' then exists (select 1 from viewer_friends vf where vf.peer_id = p.author_id)
                       else false
                   end))
    ), ordered as (
        select h.*, row_number() over (order by
            case when h.n_title like v_like || '%' escape '\' or h.n_content like v_like || '%' escape '\' then 1
                 when h.n_title like v_contains escape '\' or h.n_content like v_contains escape '\' then 2
                 else 3 end,
            (strpos(lower(h.title), v_raw) > 0 or strpos(lower(coalesce(h.content, '')), v_raw) > 0) desc,
            extensions.word_similarity(v_q, h.n_title) desc,
            h.created_at desc, h.id) as rn
        from hits h
    )
    -- Build the row JSON only for the returned page.
    select jsonb_build_object(
        'items', coalesce(jsonb_agg(to_jsonb(p) order by o.rn)
            filter (where o.rn > p_offset and o.rn <= p_offset + v_size), '[]'::jsonb),
        'has_more', coalesce(bool_or(o.rn > p_offset + v_size), false) and p_offset + v_size < 100)
    into v_result
    from ordered o
    join public.posts p on p.id = o.id
    where o.rn <= p_offset + v_size + 1;
    return v_result;
end;
$$;

create or replace function public.search_documents(p_query text, p_limit integer default 20, p_offset integer default 0)
returns jsonb
language plpgsql
security definer
set search_path = ''
set pg_trgm.word_similarity_threshold = '0.5'
as $$
declare
    v_actor uuid := studyhub_private.friend_actor();
    v_q text; v_like text; v_raw text; v_size integer; v_contains text; v_fuzzy boolean;
    v_result jsonb;
begin
    select i.q, i.q_like, i.q_raw, i.page_size into v_q, v_like, v_raw, v_size
    from studyhub_private.search_input(p_query, p_limit, p_offset) i;
    if char_length(v_q) < 2 or v_size = 0 then
        return jsonb_build_object('items', '[]'::jsonb, 'has_more', false);
    end if;
    v_contains := '%' || v_like || '%';
    -- Trigrams ignore symbols, so queries such as "100%" or "c++" stay literal.
    v_fuzzy := char_length(v_q) >= 4 and v_q !~ '[^[:alnum:][:space:]]';

    -- Candidates come from two index-friendly branches (file name, post title);
    -- the parent post then gets the same visibility rules as search_posts.
    with viewer_friends as materialized (
        select case when f.requester_id = v_actor then f.addressee_id else f.requester_id end as peer_id
        from public.friendships f
        where f.status = 'accepted' and (f.requester_id = v_actor or f.addressee_id = v_actor)
    ), blocked as materialized (
        select b.blocked_id as peer_id from public.blocks b where b.blocker_id = v_actor
        union
        select b.blocker_id from public.blocks b where b.blocked_id = v_actor
    ), candidates as (
        select f.id from public.post_files f
        where studyhub_private.search_file_norm(f.file_name) like v_contains escape '\'
           or (v_fuzzy and v_q operator(extensions.<%) studyhub_private.search_file_norm(f.file_name))
        union
        select f.id from public.posts p join public.post_files f on f.post_id = p.id
        where studyhub_private.search_norm(p.title) like v_contains escape '\'
    ), hits as (
        select f.id, f.file_name, f.file_type, f.file_size, f.created_at,
               p.id as post_id, p.title as post_title, a.display_name as author_display_name,
               studyhub_private.search_file_norm(f.file_name) as n_file,
               studyhub_private.search_norm(p.title) as n_title
        from candidates c
        join public.post_files f on f.id = c.id
        join public.posts p on p.id = f.post_id
        join public.profiles a on a.id = p.author_id
        where not (coalesce(f.file_type, '') ilike 'image/%'
                   or f.file_name ~* '\.(png|jpe?g|webp|gif|bmp|heic)$')
          and (p.author_id = v_actor
               or (not a.is_banned
                   and not exists (select 1 from blocked x where x.peer_id = p.author_id)
                   and case studyhub_private.post_visibility(p.type)
                       when 'public' then true
                       when 'friends' then exists (select 1 from viewer_friends vf where vf.peer_id = p.author_id)
                       else false
                   end))
    ), ordered as (
        select h.*, row_number() over (order by
            case when h.n_file like v_like || '%' escape '\' or h.n_title like v_like || '%' escape '\' then 1
                 when h.n_file like v_contains escape '\' or h.n_title like v_contains escape '\' then 2
                 else 3 end,
            (strpos(lower(h.file_name), v_raw) > 0 or strpos(lower(h.post_title), v_raw) > 0) desc,
            extensions.word_similarity(v_q, h.n_file) desc,
            h.created_at desc, h.id) as rn
        from hits h
    )
    select jsonb_build_object(
        'items', coalesce(jsonb_agg(jsonb_build_object(
                'id', o.id, 'file_name', o.file_name, 'file_type', o.file_type, 'file_size', o.file_size,
                'created_at', o.created_at, 'post_id', o.post_id, 'post_title', o.post_title,
                'author_display_name', o.author_display_name)
            order by o.rn) filter (where o.rn > p_offset and o.rn <= p_offset + v_size), '[]'::jsonb),
        'has_more', coalesce(bool_or(o.rn > p_offset + v_size), false) and p_offset + v_size < 100)
    into v_result
    from ordered o
    where o.rn <= p_offset + v_size + 1;
    return v_result;
end;
$$;

-- ------------------------------------------------------------------------------
-- Grants. Helpers stay private; index expressions run with the writer's
-- privileges, so writers of indexed tables need EXECUTE on the normalizers.
-- ------------------------------------------------------------------------------
revoke all on function
    studyhub_private.search_norm(text), studyhub_private.search_file_norm(text),
    studyhub_private.search_like_escape(text), studyhub_private.search_input(text, integer, integer),
    studyhub_private.post_visibility(text)
    from public, anon, authenticated;
grant execute on function studyhub_private.search_norm(text), studyhub_private.search_file_norm(text)
    to authenticated, service_role;

revoke all on function
    public.search_profiles(text, integer, integer), public.search_subjects(text, integer, integer),
    public.search_posts(text, integer, integer), public.search_documents(text, integer, integer)
    from public, anon, authenticated;
grant execute on function
    public.search_profiles(text, integer, integer), public.search_subjects(text, integer, integer),
    public.search_posts(text, integer, integer), public.search_documents(text, integer, integer)
    to authenticated;

notify pgrst, 'reload schema';
