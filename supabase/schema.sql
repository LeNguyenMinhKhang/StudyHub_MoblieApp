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
-- 14. conversations (phân hệ 8, 9)
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

-- Create the referenced table before conversations policies query membership.
create table if not exists public.conversation_members (
  id uuid primary key default gen_random_uuid(),
  conversation_id uuid not null references public.conversations(id) on delete cascade,
  user_id uuid not null references public.profiles(id) on delete cascade,
  role text not null default 'member' check (role in ('admin','member')),
  last_read_at timestamptz,
  joined_at timestamptz not null default now(),
  unique (conversation_id, user_id)
);

alter table public.conversations enable row level security;

drop policy if exists "conversations_select_member" on public.conversations;
create policy "conversations_select_member" on public.conversations
  for select using (
    exists (
      select 1 from public.conversation_members m
      where m.conversation_id = id and m.user_id = auth.uid()
    )
  );

drop policy if exists "conversations_insert_authenticated" on public.conversations;
create policy "conversations_insert_authenticated" on public.conversations
  for insert with check (auth.uid() = created_by);

drop policy if exists "conversations_update_group_admin" on public.conversations;
create policy "conversations_update_group_admin" on public.conversations
  for update using (
    exists (
      select 1 from public.conversation_members m
      where m.conversation_id = id and m.user_id = auth.uid() and m.role = 'admin'
    )
  );

-- =====================================================================
-- 15. conversation_members (phân hệ 8, 9.2, 9.4)
-- =====================================================================
create index if not exists conversation_members_conv_idx on public.conversation_members(conversation_id);
create index if not exists conversation_members_user_idx on public.conversation_members(user_id);

alter table public.conversation_members enable row level security;

drop policy if exists "conv_members_select_self_conv" on public.conversation_members;
create policy "conv_members_select_self_conv" on public.conversation_members
  for select using (
    exists (
      select 1 from public.conversation_members m2
      where m2.conversation_id = conversation_id and m2.user_id = auth.uid()
    )
  );

drop policy if exists "conv_members_insert_self_or_admin" on public.conversation_members;
create policy "conv_members_insert_self_or_admin" on public.conversation_members
  for insert with check (
    auth.uid() = user_id
    or exists (
      select 1 from public.conversation_members m2
      where m2.conversation_id = conversation_id and m2.user_id = auth.uid() and m2.role = 'admin'
    )
  );

drop policy if exists "conv_members_update_last_read" on public.conversation_members;
create policy "conv_members_update_last_read" on public.conversation_members
  for update using (auth.uid() = user_id);

drop policy if exists "conv_members_delete_self_or_admin" on public.conversation_members;
create policy "conv_members_delete_self_or_admin" on public.conversation_members
  for delete using (
    auth.uid() = user_id
    or exists (
      select 1 from public.conversation_members m2
      where m2.conversation_id = conversation_id and m2.user_id = auth.uid() and m2.role = 'admin'
    )
  );

-- =====================================================================
-- 16. messages (phân hệ 8.2, 8.3, 8.5, 9.3)
-- =====================================================================
create table if not exists public.messages (
  id uuid primary key default gen_random_uuid(),
  conversation_id uuid not null references public.conversations(id) on delete cascade,
  sender_id uuid not null references public.profiles(id) on delete cascade,
  type text not null check (type in ('text','image','file')),
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
    exists (
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

drop policy if exists "messages_update_own" on public.messages;
create policy "messages_update_own" on public.messages
  for update using (auth.uid() = sender_id);

-- =====================================================================
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
-- 18. Quyền truy cập bảng (Grants)
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

-- =====================================================================
-- 19. Supabase Storage Buckets ('documents' & 'posts' cho ảnh và file bài viết)
-- =====================================================================
insert into storage.buckets (id, name, public)
values 
  ('documents', 'documents', true),
  ('posts', 'posts', true)
on conflict (id) do update set public = true;

-- Cho phép đọc file công khai
drop policy if exists "Allow public read documents" on storage.objects;
create policy "Allow public read documents"
  on storage.objects for select
  using (bucket_id in ('documents', 'posts'));

-- Cho phép người dùng đã đăng nhập tải ảnh/tài liệu lên
drop policy if exists "Allow authenticated upload documents" on storage.objects;
create policy "Allow authenticated upload documents"
  on storage.objects for insert
  to authenticated
  with check (bucket_id in ('documents', 'posts'));

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
-- Hết. 17 bảng đầy đủ, RLS bật, Cascade Delete, Storage bucket đã cấu hình.
-- =====================================================================

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
notify pgrst, 'reload schema';

-- Friend notification RPCs (20261027150000_friend_notifications.sql)

-- Friendship RPCs write notification rows; clients read them only through the RPCs below.
create index if not exists notifications_user_page_idx
    on public.notifications(user_id, created_at desc, id desc);
create index if not exists notifications_friend_request_idx
    on public.notifications(user_id, actor_id) where type = 'friend_request';

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
    -- Unique index arbitrates concurrent/reversed sends. A concurrent cleanup
    -- may remove the conflict; retry a bounded number of times with a new snapshot.
    for attempt in 1..3 loop
        inserted := null;
        insert into public.friendships(requester_id, addressee_id, status)
        values(actor, p_target_id, 'pending') on conflict do nothing returning id into inserted;
        -- Only a newly created request notifies; repeated sends return the existing state silently.
        if inserted is not null then
            insert into public.notifications(user_id, actor_id, type) values (p_target_id, actor, 'friend_request');
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
        if rel.status = 'pending' then
            update public.friendships set status = 'accepted' where id = rel.id;
            insert into public.notifications(user_id, actor_id, type) values (target, actor, 'friend_accept');
            update public.notifications set is_read = true
            where user_id = actor and actor_id = target and type = 'friend_request' and not is_read;
        end if;
        return studyhub_private.friend_snapshot(actor, target);
    elsif action in ('decline','cancel','remove') then
        if (action = 'remove' and rel.status <> 'accepted')
           or (action <> 'remove' and rel.status <> 'pending') then
            raise exception 'relationship changed' using errcode = 'F0004';
        end if;
        delete from public.friendships where id = rel.id;
        if action = 'cancel' then
            -- A withdrawn request should not linger in the recipient's notifications.
            delete from public.notifications
            where user_id = rel.addressee_id and actor_id = rel.requester_id and type = 'friend_request';
        elsif action = 'decline' then
            update public.notifications set is_read = true
            where user_id = actor and actor_id = target and type = 'friend_request' and not is_read;
        end if;
        return jsonb_build_object('target_id',target,'state','none','friendship_id',null);
    end if;
    raise exception 'invalid action' using errcode = 'F0001';
end;
$$;

create or replace function public.list_my_notifications(
    p_limit integer default 20,
    p_cursor_created_at timestamptz default null, p_cursor_id uuid default null
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare actor uuid := studyhub_private.friend_actor(); page_size integer; result jsonb;
begin
    if p_limit is null or p_limit <= 0
       or ((p_cursor_created_at is null) <> (p_cursor_id is null))
       or (p_cursor_created_at is not null and not isfinite(p_cursor_created_at)) then
        raise exception 'invalid page input' using errcode = 'F0001';
    end if;
    page_size := least(p_limit, 50);
    with visible as (
        select n.id, n.type, n.is_read, n.created_at, n.post_id,
          case when p.id is null then null else
            jsonb_build_object('id',p.id,'username',p.username,'display_name',p.display_name,'avatar_url',p.avatar_url) end as actor
        from public.notifications n
        left join public.profiles p on p.id = n.actor_id
        where n.user_id = actor
          -- Hide rows from banned or blocked actors, matching the friendship visibility rules.
          and (n.actor_id is null or (p.id is not null and studyhub_private.friend_peer_available(actor, p.id)))
    ), candidates as materialized (
        select * from visible
        where p_cursor_id is null or (created_at,id) < (p_cursor_created_at,p_cursor_id)
        order by created_at desc, id desc limit page_size + 1
    ), page as (select * from candidates order by created_at desc,id desc limit page_size)
    select jsonb_build_object(
        'items', coalesce((select jsonb_agg(to_jsonb(page) order by created_at desc,id desc) from page),'[]'::jsonb),
        'next_cursor', case when (select count(*) from candidates) > page_size then
           (select jsonb_build_object('created_at',created_at,'id',id) from page order by created_at,id limit 1)
           else null end,
        'unread_count', (select count(*) from visible where not is_read)) into result;
    return result;
end;
$$;

create or replace function public.mark_notifications_read(p_ids uuid[] default null) returns integer
language plpgsql security definer set search_path = '' as $$
declare actor uuid := studyhub_private.friend_actor(); changed integer;
begin
    if p_ids is not null and (cardinality(p_ids) = 0 or cardinality(p_ids) > 100) then
        raise exception 'invalid ids' using errcode = 'F0001';
    end if;
    -- Null marks everything; ids not owned by the actor are ignored.
    update public.notifications set is_read = true
    where user_id = actor and not is_read and (p_ids is null or id = any(p_ids));
    get diagnostics changed = row_count;
    return changed;
end;
$$;

revoke all on all functions in schema studyhub_private from public, anon, authenticated;
revoke all on function public.send_friend_request(uuid),
    public.list_my_notifications(integer,timestamptz,uuid), public.mark_notifications_read(uuid[])
    from public, anon, authenticated;
grant execute on function public.send_friend_request(uuid),
    public.list_my_notifications(integer,timestamptz,uuid), public.mark_notifications_read(uuid[])
    to authenticated;
notify pgrst, 'reload schema';
