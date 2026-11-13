## ADDED Requirements

### Requirement: Danh sách cuộc trò chuyện 1-1
Hệ thống PHẢI cho phép người dùng đã xác thực tải danh sách các cuộc hội thoại 1-1 mà mình tham gia. Mỗi mục hội thoại hiển thị thông tin bạn chat (tên, avatar), trích đoạn tin nhắn mới nhất, thời gian gửi và số lượng tin nhắn chưa đọc.

#### Scenario: Tải danh sách hội thoại thành công
- **WHEN** người dùng mở tab Chat hoặc thực hiện kéo để làm mới (pull-to-refresh)
- **THEN** danh sách hiển thị các cuộc trò chuyện được sắp xếp theo thời gian tin nhắn mới nhất giảm dần
- **AND** hiển thị số tin nhắn chưa đọc nếu có

#### Scenario: Chưa có cuộc trò chuyện nào
- **WHEN** người dùng chưa từng nhắn tin với ai
- **THEN** màn hình hiển thị trạng thái rỗng (empty state) hướng dẫn bắt đầu trò chuyện với bạn bè

---

### Requirement: Bắt đầu hoặc mở cuộc trò chuyện 1-1
Hệ thống PHẢI đảm bảo mỗi cặp người dùng chỉ có tối đa một cuộc trò chuyện 1-1. Khi người dùng chọn nhắn tin với một người khác (từ profile hoặc danh sách bạn bè), hệ thống sẽ mở cuộc trò chuyện hiện có hoặc tạo mới nếu chưa tồn tại.

#### Scenario: Mở cuộc trò chuyện đã tồn tại
- **WHEN** người dùng chọn nhắn tin với một người bạn đã từng chat trước đó
- **THEN** hệ thống trả về cuộc trò chuyện cũ kèm toàn bộ lịch sử tin nhắn

#### Scenario: Nhắn tin lần đầu tiên với một người dùng
- **WHEN** người dùng chọn nhắn tin với một người bạn chưa từng có hội thoại
- **THEN** hệ thống tạo một cuộc hội thoại mới loại `direct` chứa đúng 2 thành viên và mở màn hình chat

---

### Requirement: Gửi và nhận tin nhắn văn bản
Người dùng PHẢI có thể gửi tin nhắn văn bản trong phòng chat và nhận tin nhắn tức thì qua kết nối thời gian thực (Supabase Realtime).

#### Scenario: Gửi tin nhắn văn bản thành công
- **WHEN** người dùng nhập nội dung văn bản hợp lệ và nhấn Gửi
- **THEN** tin nhắn được lưu vào cơ sở dữ liệu và xuất hiện ở phía bên phải màn hình chat
- **AND** ô nhập nội dung được làm trống

#### Scenario: Nhận tin nhắn mới theo thời gian thực
- **WHEN** đối phương gửi tin nhắn trong khi người dùng đang mở phòng chat
- **THEN** tin nhắn mới tự động hiển thị ở phía bên trái màn hình mà không cần reload
- **AND** danh sách tự động cuộn xuống dưới cùng

---

### Requirement: Gửi hình ảnh trong cuộc trò chuyện
Hệ thống PHẢI cho phép người dùng chọn hình ảnh từ thiết bị, tải lên Supabase Storage và gửi dưới dạng tin nhắn ảnh.

#### Scenario: Gửi ảnh thành công
- **WHEN** người dùng chọn ảnh từ thư viện và nhấn Gửi
- **THEN** tệp ảnh được tải lên bucket `chat` và bản ghi tin nhắn mới có loại `image` được lưu kèm URL của ảnh
- **AND** ảnh được hiển thị trực tiếp trong bong bóng chat

