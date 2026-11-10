## 1. Database & Migrations (Supabase)

- [x] 1.1 Tạo migration mới `supabase/migrations/20261110_saved_posts_and_hashtags.sql`:
  - Tạo hàm RPC `public.toggle_saved_post(p_post_id uuid) returns boolean` (`SECURITY DEFINER`, chỉ cấp quyền EXECUTE cho `authenticated`).
  - Thêm chỉ mục trên `public.post_hashtags(hashtag_id, post_id)` và `public.hashtags(name)`.
  - Cập nhật hàm `public.search_posts` để hỗ trợ tìm kiếm bài viết có liên kết với hashtag phù hợp.
  - Cập nhật file `supabase/schema.sql` đồng bộ với thay đổi mới.

## 2. Data Layer (`backend`)

- [x] 2.1 Cập nhật Models:
  - Bổ sung data class `Hashtag(id: String, name: String)`.
  - Thêm `val isSavedByCurrentUser: Boolean = false` và `val hashtags: List<String> = emptyList()` vào `PostDetail`.
  - Cập nhật `HashtagUtils` chuẩn hóa và lọc tag.
- [x] 2.2 Cập nhật `PostRemoteDataSource`:
  - Bổ sung `toggleSavePost(postId: String): Boolean`.
  - Bổ sung `getSavedPosts(limit: Long, offset: Long): List<PostDetail>`.
  - Cập nhật `enrichPosts(posts: List<Post>): List<PostDetail>`:
    - Nạp trạng thái đã lưu (`isSavedByCurrentUser`) theo `saved_posts`.
    - Nạp danh sách hashtags của từng bài viết từ `post_hashtags` & `hashtags`.
  - Cập nhật `createPost(...)` để lưu hashtags vào `hashtags` và `post_hashtags`.
- [x] 2.3 Cập nhật `PostRepository`:
  - Thêm `toggleSavePost(postId: String): Boolean`.
  - Thêm `getSavedPosts(limit: Long, offset: Long): List<PostDetail>`.
  - Thêm phương thức `createPost` có nhận danh sách hashtag.

## 3. UI Layer (`app-user`)

- [x] 3.1 Nút Bookmark & Tương tác Lưu bài viết:
  - Cập nhật `item_post.xml` và `PostAdapter.kt`: thêm nút Bookmark với icon bật/tắt (filled/outline), xử lý callback click lưu bài viết với optimistic update.
  - Cập nhật `fragment_post_detail.xml` và `PostDetailFragment.kt`: thêm nút Bookmark trên thanh công cụ và đồng bộ trạng thái.
- [x] 3.2 Giao diện Tab "Đã lưu" trong `ProfileFragment`:
  - Cập nhật `fragment_profile.xml` thêm `TabLayout` gồm 2 tab "Bài viết của tôi" và "Đã lưu".
  - Cập nhật `ProfileFragment.kt` để tải và hiển thị danh sách bài viết đã lưu khi chuyển sang tab "Đã lưu". Tab này chỉ hiển thị khi người dùng xem profile chính mình.
- [x] 3.3 Giao diện Gắn Hashtag khi tạo bài viết:
  - Cập nhật `fragment_post_create.xml`: thêm ô nhập hashtag kèm `ChipGroup` hiển thị các tag đã thêm và nút xóa từng tag.
  - Cập nhật `PostCreateFragment.kt`: xử lý chuẩn hóa hashtag khi người dùng nhấn Enter/Thêm tag, và gửi kèm danh sách tags khi đăng/sửa bài.
- [x] 3.4 Hiển thị Hashtag và Tìm kiếm theo Hashtag:
  - Cập nhật `item_post.xml` và `fragment_post_detail.xml`: thêm `ChipGroup` hiển thị danh sách hashtag dưới nội dung bài viết.
  - Trong `PostAdapter.kt` và `PostDetailFragment.kt`: bind các hashtag thành Chip có thể click được.
  - Khi click vào một Chip hashtag: điều hướng đến `SearchFragment` với từ khóa `#<hashtag>` ở tab Bài viết (`SearchTab.POSTS`).

## 4. Verification & Validation

- [x] 4.1 Kiểm tra đối chiếu diff và rà soát cú pháp toàn bộ các tệp Kotlin và XML.
- [x] 4.2 Kiểm tra RLS và phân quyền: đảm bảo RPC `toggle_saved_post` chỉ cấp quyền cho `authenticated` và bảo vệ dữ liệu theo `auth.uid() = user_id`.
