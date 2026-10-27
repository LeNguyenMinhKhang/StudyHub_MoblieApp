-- ==============================================================================
-- Migration: Sửa lỗi đăng bài, xóa bài viết, đính kèm ảnh và quyền truy cập
-- Hướng dẫn: Mở Supabase Dashboard -> SQL Editor -> Dán toàn bộ nội dung file này -> Bấm Run
-- ==============================================================================

-- 1. Cho phép môn học (subject_id) và loại bài (type) có thể NULL khi người dùng không chọn
alter table public.posts alter column subject_id drop not null;
alter table public.posts alter column type drop not null;
alter table public.posts drop constraint if exists posts_type_check;

-- 2. Tự động xóa file đính kèm khi xóa bài viết (Cascade Delete)
alter table public.post_files drop constraint if exists post_files_post_id_fkey;
alter table public.post_files 
    add constraint post_files_post_id_fkey 
    foreign key (post_id) 
    references public.posts(id) 
    on delete cascade;

-- 3. Cấp quyền truy cập cho bảng posts và post_files
grant select, insert, update, delete on public.posts to authenticated;
grant select on public.posts to anon;

grant select, insert, update, delete on public.post_files to authenticated;
grant select on public.post_files to anon;

-- Bật RLS cho posts
alter table public.posts enable row level security;

-- Đọc bài viết công khai
drop policy if exists "posts_select_all" on public.posts;
create policy "posts_select_all" on public.posts
    for select
    using (true);

-- Đăng bài viết (chỉ đăng bài với chính author_id của mình)
drop policy if exists "posts_insert_own" on public.posts;
create policy "posts_insert_own" on public.posts
    for insert to authenticated
    with check (auth.uid() = author_id);

-- Sửa bài viết của mình
drop policy if exists "posts_update_own" on public.posts;
create policy "posts_update_own" on public.posts
    for update to authenticated
    using (auth.uid() = author_id);

-- Xóa bài viết của mình
drop policy if exists "posts_delete_own" on public.posts;
create policy "posts_delete_own" on public.posts
    for delete to authenticated
    using (auth.uid() = author_id);

-- Bật RLS cho post_files
alter table public.post_files enable row level security;

-- Đọc file bài viết công khai (để hiện ảnh cho mọi người xem)
drop policy if exists "post_files_select_all" on public.post_files;
create policy "post_files_select_all" on public.post_files
    for select
    using (true);

-- Tải file đính kèm bài viết
drop policy if exists "post_files_insert_own" on public.post_files;
create policy "post_files_insert_own" on public.post_files
    for insert to authenticated
    with check (true);

-- Xóa file đính kèm
drop policy if exists "post_files_delete_own" on public.post_files;
create policy "post_files_delete_own" on public.post_files
    for delete to authenticated
    using (true);

-- 4. Cho phép xem thông tin profiles công khai (để hiện tên tác giả và avatar)
grant select on public.profiles to authenticated, anon;

drop policy if exists "profiles_select_all" on public.profiles;
create policy "profiles_select_all" on public.profiles
    for select
    using (true);

-- 5. Cấu hình Supabase Storage cho bucket 'documents' (chứa ảnh bài viết)
insert into storage.buckets (id, name, public)
values ('documents', 'documents', true)
on conflict (id) do update set public = true;

drop policy if exists "Allow public read documents" on storage.objects;
create policy "Allow public read documents"
    on storage.objects for select
    using (bucket_id = 'documents');

drop policy if exists "Allow authenticated upload documents" on storage.objects;
create policy "Allow authenticated upload documents"
    on storage.objects for insert
    to authenticated
    with check (bucket_id = 'documents');

drop policy if exists "Allow authenticated delete documents" on storage.objects;
create policy "Allow authenticated delete documents"
    on storage.objects for delete
    to authenticated
    using (bucket_id = 'documents');
