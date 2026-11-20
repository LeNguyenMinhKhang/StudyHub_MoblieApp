## Context

Khảo sát hệ thống hiện tại tại thời điểm thiết kế:
- **Cơ sở dữ liệu:**
  - `supabase/schema.sql` đã có sẵn 3 bảng:
    - `public.saved_posts` (`id`, `user_id`, `post_id`, `created_at`, unique `(user_id, post_id)`). RLS chỉ cho phép chính chủ `select/insert/delete`.
    - `public.hashtags` (`id`, `name text unique not null`). RLS cho phép mọi người đọc, người dùng đã đăng nhập được thêm mới.
    - `public.post_hashtags` (`post_id`, `hashtag_id`, primary key `(post_id, hashtag_id)`). RLS cho phép mọi người đọc, chỉ tác giả bài viết được thêm/xóa liên kết.
  - RPC `search_posts` trong `20261106_unified_search.sql` lọc theo quyền riêng tư (công khai, bạn bè, tác giả, chặn 2 chiều) nhưng hiện chỉ so khớp trên `title` và `content` của `posts`.
- **Backend Kotlin (`com.doan.studyhub`):**
  - `PostRemoteDataSource.enrichPosts(posts: List<Post>)` là hàm tập trung dùng chung cho cả Bảng tin (`Feed`) và Kết quả tìm kiếm (`SearchTab.POSTS`) để nạp thêm thông tin tác giả, môn học, file, lượt thích, và số bình luận.
  - `PostDetail` là data class đại diện cho bài viết đầy đủ khi hiển thị.
- **Frontend Android (`app-user`):**
  - `Home.kt` điều phối màn hình bằng `childFragmentManager` với các hàm `requestRoot(fragment)` và `openDetail(fragment)`.
  - `PostAdapter.kt` hiển thị danh sách bài viết trên Feed, Profile và Tìm kiếm.
  - `PostDetailFragment.kt` hiển thị chi tiết một bài viết.
  - `PostCreateFragment.kt` quản lý giao diện tạo bài viết mới.
  - `ProfileFragment.kt` hiển thị thông tin trang cá nhân và danh sách bài viết của người dùng.

## Goals / Non-Goals

**Goals:**
- Bật/tắt lưu bài viết (Bookmark toggle) nhanh chóng từ cả Feed và trang chi tiết với phản hồi giao diện tức thì (optimistic update).
- Trang cá nhân của chính người dùng (`ProfileFragment`) có tab hiển thị danh sách "Bài viết đã lưu", tái sử dụng `PostAdapter`.
- Thêm hashtag khi tạo bài viết thông qua ô nhập và giao diện Chip trực quan, hỗ trợ xóa tag trước khi đăng.
- Chuẩn hóa hashtag trên client và server (chữ thường, bỏ dấu `#`, bỏ ký tự khoảng trắng thừa).
- Hiển thị danh sách hashtag dạng Chip có thể click được trên từng bài viết ở Feed và trang chi tiết.
- Khi click vào hashtag, chuyển đến màn hình `SearchFragment` với từ khóa `#<hashtag>` và mở sẵn tab Bài viết.
- Cập nhật backend và migration để `search_posts` tìm được các bài viết có gắn hashtag tương ứng.

**Non-Goals:**
- Theo dõi hashtag (Follow hashtag) và nhận thông báo theo hashtag (để dành cho giai đoạn sau).
- Bảng xếp hạng xu hướng hashtag (Trending hashtags) hoặc thống kê tần suất xuất hiện.
- Phân loại bài viết đã lưu vào các thư mục/bộ sưu tập con (chỉ dùng danh sách phẳng đơn giản).

## Decisions

### 1. Thao tác Lưu bài viết bằng RPC `toggle_saved_post`
- **Lựa chọn:** Tạo RPC `public.toggle_saved_post(p_post_id uuid) returns boolean` chạy với `SECURITY DEFINER`.
- **Lý do:**
  - Nếu dùng PostgREST thuần từ client: cần gọi `select` để kiểm tra tồn tại, sau đó gọi `insert` hoặc `delete`. Điều này tốn 2 round-trips và dễ phát sinh race condition nếu người dùng bấm nhanh.
  - RPC thực hiện thao tác kiểm tra và insert/delete trong một transaction nguyên tử, trả về `true` nếu bài viết vừa được lưu thành công, hoặc `false` nếu vừa được hủy lưu.
  - Đảm bảo kiểm tra người dùng đã xác thực (`auth.uid() is not null`) và bài viết `p_post_id` thực sự tồn tại và người dùng có quyền xem bài viết đó.

### 2. Danh sách bài viết đã lưu `get_my_saved_posts`
- **Lựa chọn:** Bổ sung hàm trong `PostRemoteDataSource.getSavedPosts(limit: Long, offset: Long)` truy vấn các bài viết từ bảng `saved_posts` của người dùng hiện tại, sau đó gọi `enrichPosts(...)`.
- **Lý do:** Tận dụng lại 100% logic làm giàu dữ liệu đã có trong `enrichPosts` (tác giả, môn học, file, like, comment, và cả hashtag), đảm bảo giao diện đồng nhất tuyệt đối với bài viết trên Feed.

### 3. Quy tắc chuẩn hóa Hashtag
- **Định dạng:**
  - Tự động bỏ ký tự `#` ở đầu nếu người dùng gõ vào.
  - Chuyển toàn bộ về chữ thường (`lowercase`), cắt khoảng trắng đầu/cuối (`trim`).
  - Cho phép chữ cái (kể cả tiếng Việt có dấu), chữ số, và dấu gạch dưới `_`. Loại bỏ các ký tự đặc biệt khác.
  - Độ dài tối đa mỗi hashtag: 50 ký tự. Tối thiểu 1 ký tự.
  - Mỗi bài viết tối đa 10 hashtags để tránh spam.
- **Lưu trữ:**
  - Bảng `hashtags`: lưu `name` duy nhất (`unique`). Sử dụng `on conflict (name) do update / do nothing` khi gắn tag mới.
  - Bảng `post_hashtags`: lưu cặp `(post_id, hashtag_id)`.

### 4. Tích hợp Tìm kiếm theo Hashtag vào `search_posts`
- **Quy tắc khớp:**
  - Khi người dùng tìm kiếm từ khóa bắt đầu bằng `#` (ví dụ: `#toan12`), hệ thống bóc tách phần tên tag `toan12` và ưu tiên khớp chính xác hoặc khớp tiền tố trong bảng `hashtags`.
  - Khi tìm kiếm từ khóa thông thường, `search_posts` vẫn so khớp thêm vào danh sách hashtag của bài viết bên cạnh `title` và `content`.
  - Cập nhật chỉ mục: thêm index trên `hashtags.name` (hoặc trigram/btree) và `post_hashtags(hashtag_id, post_id)` để đảm bảo tốc độ truy vấn.

### 5. Điều hướng giao diện Android
- Bổ sung hàm điều hướng trong `Home.kt`:
  ```kotlin
  fun navigateToSearch(initialQuery: String? = null, initialTab: SearchTab = SearchTab.POSTS)
  ```
- Cho phép `PostAdapter` và `PostDetailFragment` gọi callback khi người dùng click vào hashtag chip để mở ngay màn hình Tìm kiếm với query `#<tag>`.
- Trong `ProfileFragment`:
  - Thêm `TabLayout` với 2 tab: "Bài viết" và "Đã lưu" (chỉ hiển thị tab "Đã lưu" khi đang xem Profile của chính tài khoản đăng nhập).
  - Sử dụng lại cùng một `RecyclerView` và `PostAdapter`, ViewModel chỉ cần chuyển đổi nguồn dữ liệu tương ứng.

