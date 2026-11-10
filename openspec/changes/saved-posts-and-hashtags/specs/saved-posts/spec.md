## ADDED Requirements

### Requirement: Toggle bookmark post
Hệ thống PHẢI cho phép người dùng đã đăng nhập lưu (bookmark) hoặc hủy lưu bài viết. Thao tác toggle PHẢI là nguyên tử trên cơ sở dữ liệu và trả về trạng thái mới của bài viết đối với người dùng (true nếu đã lưu, false nếu đã hủy lưu). Không cho phép lưu bài viết không tồn tại hoặc bài viết bị chặn quyền xem.

#### Scenario: User saves an unsaved post
- **WHEN** an authenticated user clicks the bookmark button on an unsaved post
- **THEN** the post is added to the user's `saved_posts`, the bookmark icon changes to saved state, and the server returns success with saved status true

#### Scenario: User removes a saved post
- **WHEN** an authenticated user clicks the bookmark button on a previously saved post
- **THEN** the post is removed from the user's `saved_posts`, the bookmark icon changes to unsaved state, and the server returns success with saved status false

#### Scenario: Unauthenticated user cannot save post
- **WHEN** a user who is not signed in attempts to save a post
- **THEN** the action is rejected with an unauthenticated error and no record is modified

### Requirement: Enrich post with saved status
Khi nạp danh sách bài viết (trên Bảng tin, Trang cá nhân, hoặc Kết quả tìm kiếm), hệ thống PHẢI điền trường `isSavedByCurrentUser` trên mỗi đối tượng bài viết hiển thị để giao diện hiển thị đúng trạng thái của nút Bookmark.

#### Scenario: Post feed shows correct bookmark state
- **WHEN** an authenticated user loads the post feed
- **THEN** posts that exist in the user's `saved_posts` have `isSavedByCurrentUser = true`, and other posts have `isSavedByCurrentUser = false`

### Requirement: View saved posts list
Hệ thống PHẢI cung cấp danh sách các bài viết đã lưu cho chính chủ tài khoản xem trong trang cá nhân (`ProfileFragment`). Danh sách này chỉ hiển thị khi người dùng đang xem trang cá nhân của chính mình. Nếu một bài viết đã lưu bị xóa bởi tác giả, bài viết đó KHÔNG được hiển thị trong danh sách.

#### Scenario: Owner views saved posts tab
- **WHEN** the authenticated user navigates to their own profile and selects the "Đã lưu" tab
- **THEN** the list of posts saved by this user is displayed in newest-saved order, reusing the post adapter

#### Scenario: Viewing another user's profile hides saved tab
- **WHEN** the authenticated user views another user's profile
- **THEN** the "Đã lưu" tab is hidden and only public/friend posts of that user are shown

