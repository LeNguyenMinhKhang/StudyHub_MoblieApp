## Why

README xếp "Tìm kiếm" (7.3) là tính năng `P1`: một màn hình với các tab Người dùng, Bài viết, Môn học, Tài liệu. Hiện app chỉ có tra cứu đúng nguyên username và lọc môn học trong bộ nhớ. Người dùng không tìm được bài viết hay tài liệu. Nếu gõ sai chính tả hoặc gõ không dấu, như "toan cao cap" thay cho "Toán cao cấp", thì không ra kết quả.

Quyền riêng tư bài viết hiện chỉ được lọc trong app, trong khi RLS cho đọc mọi bài và mọi file. Vì vậy tìm kiếm phải thực hiện trên server có kiểm quyền, không được truy vấn bảng trực tiếp từ client.

## What Changes

- Thêm màn hình Tìm kiếm trong `app-user`. Mở từ biểu tượng tìm kiếm trên thanh đầu trang của Home, gồm:
  - Một ô nhập từ khóa và bốn tab: Người dùng, Bài viết, Môn học, Tài liệu.
  - Chờ người dùng ngừng gõ rồi mới tìm, chỉ tải tab đang mở, có tải thêm.
  - Đủ trạng thái chưa nhập, đang tải, rỗng, lỗi kèm thử lại.
  - Mở kết quả bằng các màn hình đã có: trang cá nhân, chi tiết bài viết, chi tiết môn học.
- So khớp trên server theo ba mức xếp hạng: khớp đúng hoặc khớp đầu chuỗi, chứa từ khóa, gần đúng bằng trigram (`pg_trgm`). Mức gần đúng giúp tìm ra kết quả khi gõ sai chính tả.
- Chuẩn hóa không dấu (`unaccent`), không phân biệt hoa thường, coi `_ . - /` là dấu tách từ. Khi điểm ngang nhau, kết quả khớp đúng dấu được xếp trên.
- Tab Người dùng tìm gần đúng theo `username` và `display_name`. Kết quả bỏ người bị khóa và người chặn hoặc bị chặn theo cả hai chiều, chỉ trả thông tin công khai. Đây là thay đổi có chủ đích so với quyết định "chỉ tìm đúng nguyên username, không có API danh bạ" của change `add-friend-requests-and-friends-list`. Hộp thoại tra cứu username hiện có vẫn giữ nguyên.
- Tab Bài viết chỉ trả bài người tìm được phép xem theo quyền Công khai/Bạn bè/Riêng tư. Server tính quyền này, không giới hạn số bạn bè.
- Tab Tài liệu trả từng file đính kèm không phải ảnh, thuộc các bài người tìm được phép xem. Bấm vào thì mở bài chứa file.
- Tab Môn học chỉ trả các môn đang hoạt động.
- Thêm migration Supabase gồm:
  - Bật `pg_trgm` và `unaccent` trong schema `extensions`.
  - Hàm chuẩn hóa IMMUTABLE và chỉ mục GIN trigram.
  - Bốn RPC `SECURITY DEFINER` chỉ cấp quyền cho `authenticated`.
  - Đồng bộ lại `supabase/schema.sql`.
- Thêm tầng dữ liệu tìm kiếm trong `backend` theo `ApiResponse`/`SafeApiCall`. Tách phần ghép thông tin cho bài viết (tác giả, môn học, file, lượt thích, bình luận) thành hàm dùng chung, để kết quả Bài viết hiển thị bằng `PostAdapter` hiện có.

## Capabilities

### New Capabilities

- `search-matching`: Chuẩn hóa từ khóa và dữ liệu, giới hạn đầu vào, ba mức khớp, ngưỡng chịu lỗi gõ, thứ tự xếp hạng ổn định và phân trang có giới hạn.
- `unified-search`: Màn hình Tìm kiếm bốn tab, phạm vi trường dữ liệu từng tab, kiểm quyền trên server (người bị khóa/bị chặn, quyền riêng tư bài, file không phải ảnh, môn đang hoạt động), dữ liệu trả về, điều hướng kết quả và các trạng thái giao diện.

### Modified Capabilities

Không có. `openspec/specs/` hiện chỉ có `remote-profile-avatar`, và change này không đổi yêu cầu của capability đó. Contract tra cứu đúng username trong change kết bạn (chưa archive) cũng được giữ nguyên.

## Impact

- **Supabase:**
  - Migration mới trong `supabase/migrations/`: extension, `studyhub_private.search_norm`, helper quyền riêng tư bài viết, chỉ mục trigram trên `profiles`, `posts`, `subjects`, `post_files`, và các RPC `search_profiles`, `search_posts`, `search_subjects`, `search_documents`.
  - Dùng lại `studyhub_private.friend_actor()`/`friend_peer_available()` và các mã lỗi `F0001`/`F0005`/`F0006`.
  - Lịch sử migration trên hosted đang lệch với repo, nên không dùng `db push --include-all`.
- **backend:** model kết quả tìm kiếm, `SearchRemoteDataSource`, `SearchRepository`, `Operation.SEARCH`, và một hàm ghép thông tin bài viết dùng chung trong `PostRemoteDataSource`. Không thêm dependency.
- **app-user:**
  - `ui/search/`: Fragment, ViewModel, UiState, adapter cho người dùng và tài liệu.
  - `fragment_search.xml` và layout dòng kết quả, strings.
  - Thêm `btnHeaderSearch` vào `home.xml` và `Home.kt`.
  - Dùng lại `PostAdapter`, `SubjectAdapter`, `ProfileFragment.forUser`, `PostDetailFragment.newInstance`, `SubjectDetailFragment.newInstance`.
- **Kiểm chứng khi triển khai:**
  - Test SQL theo ma trận tài khoản: chủ bài, bạn bè, người lạ, người bị khóa, chặn hai chiều, anon.
  - Test so khớp: không dấu, gõ sai, ký tự `%`/`_`.
  - Unit test repository và ViewModel, build Gradle, smoke test trên thiết bị.
- **Rủi ro còn lại:** RLS `posts`/`post_files` vẫn cho đọc trực tiếp bài và file riêng tư qua table API cho tới khi có change sửa riêng. Change này chỉ bảo đảm đường tìm kiếm không làm lộ thêm dữ liệu.
- **Ngoài phạm vi:**
  - Tab Hashtag (7.4), lịch sử tìm kiếm (7.5), gợi ý "Có phải bạn muốn tìm".
  - Tìm theo nội dung bên trong file.
  - Sửa RLS `posts`/`post_files`, và lỗi policy `post_files` insert/delete đang dùng `true` (cần change riêng).
  - Tách quyền riêng tư khỏi cột `type`, nâng dependency, production rollout.
- Đây là proposal: chưa sửa code chạy thật và chưa chạy migration.
