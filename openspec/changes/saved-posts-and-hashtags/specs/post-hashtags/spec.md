## ADDED Requirements

### Requirement: Hashtag input and normalization
Hệ thống PHẢI cho phép người dùng thêm hashtag khi tạo hoặc chỉnh sửa bài viết qua ô nhập tag riêng. Mỗi hashtag PHẢI được tự động chuẩn hóa: cắt khoảng trắng thừa, chuyển về chữ thường, loại bỏ ký tự `#` ở đầu, và chỉ chấp nhận chữ cái, chữ số hoặc dấu gạch dưới. Một bài viết không được chứa quá 10 hashtag và độ dài mỗi hashtag từ 1 đến 50 ký tự.

#### Scenario: Valid hashtag normalization
- **WHEN** a user enters "#ToanHoc12" into the hashtag input
- **THEN** it is normalized and added to the post as "toanhoc12"

#### Scenario: Duplicate tags in the same post are ignored
- **WHEN** a user enters "toan12" and then "#toan12"
- **THEN** only a single tag "toan12" is linked to the post

### Requirement: Display hashtags on posts
Hệ thống PHẢI hiển thị danh sách các hashtag liên kết dưới dạng Chip/Badge có thể bấm được trên mỗi bài viết ở cả Bảng tin (`PostAdapter`) và Chi tiết bài viết (`PostDetailFragment`).

#### Scenario: Post with hashtags displays chips
- **WHEN** a post has associated hashtags ["toan12", "onthi"]
- **THEN** two clickable chips "#toan12" and "#onthi" are rendered below the post content

#### Scenario: Post without hashtags displays no chips
- **WHEN** a post has no associated hashtags
- **THEN** the hashtag container is hidden and consumes no layout space

### Requirement: Search posts by hashtag
Khi người dùng bấm vào một Chip hashtag hoặc nhập từ khóa tìm kiếm theo hashtag (ví dụ: `#toan12` hoặc `toan12`), hệ thống PHẢI trả về các bài viết có gắn hashtag đó trong tab Bài viết của màn hình Tìm kiếm (`SearchFragment`), đồng thời tuân thủ đầy đủ quy tắc kiểm tra quyền riêng tư bài viết.

#### Scenario: Clicking hashtag navigates to search
- **WHEN** a user clicks on the chip "#onthi" in a post
- **THEN** the app opens `SearchFragment` with query "#onthi" under the Posts tab and displays matching posts

#### Scenario: Search query matches post hashtags
- **WHEN** a viewer searches for "toan12"
- **THEN** posts linked with hashtag "toan12" are returned in the results, respecting visibility policies

