## Why

Trong `supabase/schema.sql`, dự án đã thiết kế sẵn các bảng:
- `saved_posts` (phân hệ 4.6) để lưu vết bài viết người dùng đánh dấu/lưu lại.
- `hashtags` và `post_hashtags` (phân hệ 4.7) để phân loại bài viết theo các chủ đề bằng hashtag.

Tuy nhiên, hiện tại:
1. Ứng dụng Android (`app-user`) và tầng dữ liệu `backend` chưa kết nối và khai thác các bảng này:
   - Người dùng không có nút lưu bài viết (Bookmark) trên bảng tin Feed hay trong màn hình chi tiết bài viết, và không có chỗ nào để xem lại các bài viết mình đã lưu.
   - Khi đăng bài viết (`PostCreateFragment`), người dùng chưa có ô nhập hashtag để gắn thẻ chủ đề cho bài.
   - Các bài viết trên Feed (`PostAdapter`) và chi tiết (`PostDetailFragment`) chưa hiển thị các hashtag liên kết.
2. Hệ thống tìm kiếm thống nhất (`SearchFragment` / RPC `search_posts`) chưa hỗ trợ tìm kiếm bài viết theo hashtag, và người dùng chưa thể bấm vào một hashtag trên bài viết để xem ngay các bài viết cùng chủ đề.

Đề xuất này bổ sung đầy đủ luồng nghiệp vụ từ database, backend Kotlin đến giao diện Android cho 3 tính năng: Lưu bài viết, Gắn Hashtag và Tìm kiếm theo Hashtag.

## What Changes

- **1. Lưu bài viết (Saved Posts / Bookmarks):**
  - Cập nhật model `PostDetail` bổ sung thuộc tính `isSavedByCurrentUser: Boolean = false`.
  - Trong `PostRemoteDataSource` và `PostRepository`:
    - Thêm hàm `toggleSavePost(postId: String): Boolean` (lưu hoặc bỏ lưu bài viết trong bảng `saved_posts`).
    - Bổ sung làm giàu dữ liệu trong `enrichPosts(...)`: kiểm tra danh sách `postIds` nào đã được người dùng hiện tại lưu.
    - Thêm hàm `getSavedPosts(limit: Long, offset: Long): List<PostDetail>` truy vấn các bài viết đã lưu của người dùng hiện tại (kèm kiểm tra quyền riêng tư và trạng thái bài viết còn tồn tại).
  - Giao diện `app-user`:
    - Bổ sung nút Bookmark (icon bookmark bật/tắt) trên từng bài viết trong bảng tin (`item_post.xml` & `PostAdapter.kt`).
    - Bổ sung nút Bookmark trên thanh tác vụ của `PostDetailFragment`.
    - Bổ sung tab/mục "Đã lưu" trong `ProfileFragment` (chỉ hiển thị khi người dùng đang xem trang cá nhân của chính mình) để xem danh sách bài viết đã lưu, tái sử dụng `PostAdapter`.

- **2. Quản lý Hashtag (Post Hashtags):**
  - Model dữ liệu trong `backend`:
    - Tạo data class `Hashtag(id: String, name: String)`.
    - Bổ sung danh sách `hashtags: List<String> = emptyList()` vào `PostDetail`.
  - Trong `PostRemoteDataSource` và `PostRepository`:
    - Khi tạo bài viết (`createPost`) hoặc sửa bài viết, nhận danh sách `hashtags: List<String>`. Tự động chuẩn hóa (loại bỏ ký tự đặc biệt, chuyển chữ thường, bỏ tiền tố `#` thừa), upsert vào bảng `hashtags`, sau đó thêm vào bảng liên kết `post_hashtags`.
    - Trong `enrichPosts(...)`: truy vấn danh sách hashtag liên kết với các `postIds` và gán vào `PostDetail`.
  - Giao diện `app-user`:
    - Trong `PostCreateFragment` và `fragment_post_create.xml`: thêm ô nhập hashtag kèm ChipGroup trực quan để người dùng thêm/xóa tag chủ động trước khi đăng bài.
    - Trong `PostAdapter` (`item_post.xml`) và `PostDetailFragment` (`fragment_post_detail.xml`): hiển thị danh sách hashtag dạng Chip/Badge nằm dưới nội dung bài viết.

- **3. Tìm kiếm theo Hashtag (Search by Hashtag):**
  - Khi người dùng click vào một Chip hashtag ở bất kỳ bài viết nào (trên Feed hoặc trang chi tiết), ứng dụng chuyển hướng đến `SearchFragment` với từ khóa tìm kiếm `#<hashtag>` và kích hoạt tab Bài viết (`SearchTab.POSTS`).
  - Cập nhật RPC tìm kiếm bài viết hoặc logic tìm kiếm:
    - Cho phép tìm kiếm bài viết theo tên hashtag gắn kèm trong `post_hashtags` bên cạnh việc tìm kiếm theo tiêu đề và nội dung.
    - Vẫn đảm bảo kiểm tra quyền riêng tư bài viết (công khai, bạn bè, tác giả, không bị chặn) nhất quán với hệ thống `unified_search`.

## Capabilities

### New Capabilities
- `saved-posts`: Cho phép người dùng lưu/bỏ lưu bài viết, truy vấn danh sách bài viết đã lưu của bản thân và hiển thị trên giao diện người dùng.
- `post-hashtags`: Cho phép gắn hashtag khi tạo/chỉnh sửa bài viết, hiển thị hashtag dạng Chip trên bài viết và tìm kiếm bài viết dựa theo hashtag.

### Modified Capabilities
- `unified-search`: Bổ sung khả năng khớp bài viết theo hashtag trong tab Bài viết (`search_posts`).

## Impact

- **Supabase / Database:**
  - Tận dụng cấu trúc bảng `saved_posts`, `hashtags`, `post_hashtags` đã có trong `supabase/schema.sql`.
  - Thêm migration mở rộng:
    - Bổ sung RPC hỗ trợ toggle lưu bài viết an toàn (`toggle_saved_post(p_post_id uuid)`).
    - Cập nhật hàm `search_posts` trong `unified_search` để hỗ trợ tìm kiếm bài viết thông qua các hashtag được gắn.
    - Cập nhật `supabase/schema.sql` đồng bộ với migration mới.
- **backend (`com.doan.studyhub`):**
  - Models: cập nhật `PostDetail.kt` (thêm `isSavedByCurrentUser`, `hashtags`), thêm `Hashtag.kt`.
  - Data sources & Repositories: cập nhật `PostRemoteDataSource.kt`, `PostRepository.kt`, `SearchRemoteDataSource.kt` nếu cần.
- **app-user (`com.doan.studyhub.user`):**
  - `ui/post/`: cập nhật `PostAdapter`, `PostCreateFragment`, `PostDetailFragment`, xử lý sự kiện click bookmark và click hashtag.
  - `ui/profile/`: bổ sung tab "Đã lưu" trong `ProfileFragment` và `ProfileViewModel`.
  - `ui/search/`: xử lý nhận tham số hashtag khi điều hướng đến `SearchFragment`.
  - Layouts & Resources: `item_post.xml`, `fragment_post_detail.xml`, `fragment_post_create.xml`, `fragment_profile.xml`, icons drawable bookmark và hashtag chips.

