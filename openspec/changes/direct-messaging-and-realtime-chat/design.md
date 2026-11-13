# Design: Direct Messaging and Realtime Chat

## 1. Overview & Architecture

Tính năng nhắn tin 1-1 trên StudyHub tuân thủ kiến trúc phân tầng của dự án:
```
[app-user UI Layer]
  ├── ChatFragment & ChatViewModel (Conversation List)
  └── DirectChatFragment & DirectChatViewModel (Chat Detail)
           │
[backend Data Layer]
  ├── ChatRepository (Orchestration, Realtime flow mapping)
  ├── ChatRemoteDataSource (Postgrest RPC & Supabase Realtime channel)
  └── SupabaseClient (Auth, Postgrest, Storage, Realtime)
           │
[Supabase Cloud Backend]
  ├── Tables: conversations, conversation_members, messages
  ├── Storage: bucket 'chat'
  ├── Replication: supabase_realtime
  └── RPCs: get_or_create_direct_conversation, list_my_conversations, mark_conversation_read
```

## 2. Database Schema & RPC Strategy

### 2.1 Tránh Duplicate Conversation 1-1
Khi người dùng A mở trang cá nhân của B và bấm "Nhắn tin", hệ thống gọi RPC `get_or_create_direct_conversation(target_user_id)`.
RPC này chạy trong transaction:
1. Tìm cuộc hội thoại kiểu `direct` mà cả 2 người cùng là thành viên trong bảng `conversation_members`.
2. Nếu tìm thấy, trả về ngay `conversation_id`.
3. Nếu chưa có, tạo bản ghi mới trong `conversations` (type = 'direct') và thêm 2 dòng vào `conversation_members`, sau đó trả về `conversation_id`.

### 2.2 Tối ưu hóa danh sách cuộc trò chuyện
Gọi RPC `list_my_conversations()` để lấy danh sách hội thoại của người dùng hiện tại kèm theo:
- ID và thông tin profile của bạn chat (tên, avatar).
- Nội dung và thời gian của tin nhắn mới nhất (`last_msg`).
- Số lượng tin nhắn chưa đọc (`unread_count` tính dựa trên `last_read_at` của thành viên).

### 2.3 Realtime Event Streaming
Sử dụng Supabase Realtime Postgres Changes:
- Client subscribe vào kênh Realtime channel theo filter `conversation_id=eq.{id}` trên bảng `messages`.
- Khi có event `INSERT`, tin nhắn mới sẽ được emit qua Kotlin Flow và thêm trực tiếp vào danh sách tin nhắn của ViewModel.

### 2.4 Upload hình ảnh qua Storage
- Khi người dùng gửi ảnh, ảnh được nén nhẹ và tải lên bucket `chat` với đường dẫn `{conversation_id}/{timestamp}_{filename}.jpg`.
- Sau khi upload thành công, URL công khai/xác thực được chèn vào bảng `messages` với `type = 'image'` và `file_url`.

## 3. UI/UX Design

### 3.1 Màn hình Danh sách cuộc trò chuyện (`Chat.kt`)
- Toolbar: Tiêu đề "Đoạn chat".
- SwipeRefreshLayout + RecyclerView.
- Mỗi item (`item_conversation.xml`):
  - Avatar bạn chat (Glide hiển thị ảnh tròn hoặc fallback icon).
  - Tên hiển thị bạn chat.
  - Tin nhắn mới nhất (nếu là ảnh thì hiển thị "[Hình ảnh]").
  - Thời gian nhắn tin gần nhất.
  - Badge unread count màu đỏ nếu `unread_count > 0`.
- Empty state: Khi chưa có cuộc trò chuyện nào, hướng dẫn kết bạn hoặc tìm bạn học để nhắn tin.

### 3.2 Màn hình Chi tiết cuộc trò chuyện (`DirectChatFragment.kt`)
- Header Toolbar: Nút Back, Avatar bạn chat, Tên bạn chat.
- RecyclerView danh sách tin nhắn:
  - `item_message_sent.xml`: Tin nhắn của người dùng hiện tại (nằm bên phải, nền màu primary, chữ trắng).
  - `item_message_received.xml`: Tin nhắn của bạn chat (nằm bên trái, nền xám nhẹ, chữ đen).
  - Hiển thị nội dung text hoặc ảnh ImageView (bo tròn góc).
  - Hiển thị thời gian gửi tin nhắn.
- Bottom Input Bar:
  - Nút đính kèm ảnh (mở ActivityResultContracts.GetContent).
  - EditText soạn tin nhắn.
  - Nút Gửi (Send button).

## 4. Error Handling & Edge Cases
- **Mất kết nối mạng**: Supabase Realtime tự động reconnect; người dùng có thể kéo để refresh hoặc gửi lại tin nhắn nếu lỗi.
- **Tài khoản bị block/banned**: RPC từ chối tạo hoặc gửi tin nhắn đến tài khoản bị cấm.
- **Người dùng gửi chuỗi rỗng**: Vô hiệu hóa nút gửi hoặc bỏ qua khoảng trắng.

