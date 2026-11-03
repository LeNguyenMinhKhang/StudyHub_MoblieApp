## Why

Màn hình “Nhắn tin & Thảo luận” (`Chat.kt`) hiện chỉ là màn hình placeholder, người dùng chưa thể xem danh sách các cuộc trò chuyện, chưa thể nhắn tin 1-1, gửi ảnh hoặc nhận tin nhắn theo thời gian thực (Realtime). Hoàn thiện tính năng Nhắn tin 1-1 sẽ kết nối học viên với nhau, tăng cường tương tác học tập và trao đổi tài liệu trên StudyHub.

## What Changes

- **Danh sách trò chuyện (Inbox)**: Thay placeholder tại `Chat.kt` bằng danh sách các cuộc trò chuyện 1-1; hiển thị avatar bạn chat, tên, xem trước tin nhắn mới nhất, thời gian và số lượng tin nhắn chưa đọc. Hỗ trợ pull-to-refresh và trạng thái rỗng/đang tải.
- **Tạo / Mở cuộc trò chuyện 1-1**: Cho phép bắt đầu nhắn tin với bạn bè từ danh sách bạn bè hoặc trang cá nhân (`ProfileFragment`), tự động tìm cuộc trò chuyện cũ hoặc tạo mới nếu chưa từng chat (tránh sinh bản ghi duplicate).
- **Màn hình Chat chi tiết (`DirectChatFragment`)**:
  - Hiển thị danh sách tin nhắn phân biệt bên gửi (người dùng hiện tại) và bên nhận (bạn chat).
  - Hỗ trợ gửi tin nhắn văn bản (text message).
  - Hỗ trợ gửi hình ảnh (image message) thông qua Supabase Storage bucket `chat`.
  - Hiển thị ảnh kèm thanh tiến trình upload và cho phép click xem ảnh.
  - Tự động cuộn xuống tin nhắn mới nhất.
  - Tự động đánh dấu đã đọc (`mark_conversation_read`) khi người dùng mở hoặc xem tin nhắn trong phòng chat.
- **Đồng bộ thời gian thực (Supabase Realtime)**: Lắng nghe thay đổi INSERT từ bảng `messages` thông qua Supabase Realtime WebSocket để hiển thị tin nhắn mới ngay lập tức mà không cần reload.
- **Backend & Database**:
  - Bổ sung RPC functions: `get_or_create_direct_conversation`, `list_my_conversations`, `mark_conversation_read`.
  - Kích hoạt Realtime replication cho bảng `messages` và `conversations`.
  - Tạo bucket `chat` trong Supabase Storage và cấp quyền RLS phù hợp.
  - Bổ sung thư viện `supabase-realtime` (`realtime-kt`) vào module `backend`.

## Capabilities

### New Capabilities

- `direct-messaging`: Quản lý danh sách hội thoại 1-1, bắt đầu chat, gửi/nhận tin nhắn văn bản và hình ảnh kèm đồng bộ Supabase Realtime.

### Modified Capabilities

- Không có canonical spec bị sửa đổi. Tích hợp màn hình từ `Chat.kt`, trang profile và danh sách bạn bè.

## Impact

- `app-user`:
  - `ui/chat/Chat.kt`, `fragment_chat.xml`: Chuyển thành danh sách cuộc trò chuyện với RecyclerView, Adapter, ViewModel, UiState.
  - `ui/chat/DirectChatFragment.kt`, `fragment_direct_chat.xml`: Màn hình phòng chat chi tiết, bong bóng chat (`item_message_sent.xml`, `item_message_received.xml`), thanh soạn tin nhắn.
  - `nav_graph.xml`: Thêm destination cho `DirectChatFragment` và arguments (`conversationId`, `targetUserId`, `targetName`, `targetAvatar`).
  - Nút "Nhắn tin" trên `ProfileFragment` và màn hình Bạn bè.
- `backend`:
  - Model: `ConversationItem.kt`, `ChatMessage.kt`, `SendMessageRequest.kt`.
  - Data Sources & Repositories: `ChatRemoteDataSource.kt`, `ChatRepository.kt`.
  - `SupabaseClient.kt`: Cài đặt plugin `Realtime`.
  - `libs.versions.toml` & `backend/build.gradle.kts`: Bổ sung dependency `supabase-realtime`.
- Supabase:
  - File migration SQL `supabase/migrations/20261103_direct_chat_and_realtime.sql` chứa các RPC, bucket `chat` và cấu hình publication `supabase_realtime`.

