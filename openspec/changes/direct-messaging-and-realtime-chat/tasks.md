## 1. Database & Supabase Configuration

- [x] 1.1 Tạo file migration SQL `supabase/migrations/20261103_direct_chat_and_realtime.sql` bao gồm:
  - Bật Realtime publication cho `messages` và `conversations`.
  - Tạo storage bucket `chat` và các policy RLS.
  - RPC function `get_or_create_direct_conversation(p_target_id uuid)`.
  - RPC function `list_my_conversations()`.
  - RPC function `mark_conversation_read(p_conversation_id uuid)`.
  - Cấp quyền EXECUTE cho vai trò `authenticated`.
- [x] 1.3 Bổ sung các migration khắc phục RLS và ràng buộc:
  - `20261103_fix_chat_permissions_and_rpc.sql`: Cấp quyền PostgREST cho `authenticated` và trả về `jsonb`.
  - `20261103_fix_infinite_recursion_rls.sql`: Thêm hàm helper `is_conversation_member` với `SECURITY DEFINER` ngăn ngừa đệ quy RLS vô hạn.
  - `20261103_add_send_chat_message_rpc.sql`: RPC `send_chat_message` gửi tin nhắn atomic, tự sinh UUID và cập nhật trạng thái đọc.
  - Đồng bộ và chuẩn hóa toàn diện vào `supabase/schema.sql`.

## 2. Thư viện & Cấu hình Android Backend

- [x] 2.1 Cập nhật `gradle/libs.versions.toml` bổ sung `supabase-realtime` (`realtime-kt`).
- [x] 2.2 Thêm dependency `supabase-realtime` vào `backend/build.gradle.kts`.
- [x] 2.3 Cấu hình `install(Realtime)` trong `backend/src/main/java/com/doan/studyhub/core/data/SupabaseClient.kt`.

## 3. Data Models & Remote Data Source / Repository

- [x] 3.1 Tạo các data class trong `backend/.../core/data/model/`:
  - `ConversationSummary.kt`: Đại diện cho item danh sách chat (avatar, tên, tin nhắn cuối, unread_count).
  - `ChatMessage.kt`: Đại diện cho một tin nhắn trong phòng chat.
  - `DirectChatRecipient.kt`: Thông tin bạn chat khi khởi tạo.
  - `SendMessageRequest.kt`: Dữ liệu gửi tin nhắn mới.
- [x] 3.2 Tạo `ChatRemoteDataSource.kt` trong `backend/.../core/data/remote/`:
  - `listMyConversations()`
  - `getOrCreateDirectConversation(targetUserId: String)`
  - `getMessages(conversationId: String)`
  - `sendMessage(request: SendMessageRequest)`
  - `uploadChatImage(conversationId: String, fileName: String, bytes: ByteArray)`
  - `markConversationRead(conversationId: String)`
  - `observeMessages(conversationId: String): Flow<ChatMessage>` (Supabase Realtime)
- [x] 3.3 Tạo `ChatRepository.kt` trong `backend/.../core/data/repository/` triển khai logic xử lý dữ liệu và bọc trong `ApiResponse<T>`.

## 4. UI Layer - Danh sách trò chuyện (Conversation List)

- [x] 4.1 Tạo layout item `app-user/src/main/res/layout/item_conversation.xml`.
- [x] 4.2 Cập nhật layout `app-user/src/main/res/layout/fragment_chat.xml` với Toolbar, SwipeRefreshLayout, RecyclerView và Empty View.
- [x] 4.3 Tạo `ConversationAdapter.kt` hiển thị danh sách hội thoại với Glide cho avatar và định dạng thời gian.
- [x] 4.4 Tạo `ChatViewModel.kt` quản lý `ChatUiState`.
- [x] 4.5 Cập nhật `Chat.kt` (`ChatFragment`) kết nối ViewModel, RecyclerView, sự kiện click mở phòng chat và pull-to-refresh.

## 5. UI Layer - Màn hình Chat chi tiết 1-1 (Direct Chat)

- [x] 5.1 Tạo layout bong bóng tin nhắn:
  - `app-user/src/main/res/layout/item_message_sent.xml` (tin nhắn gửi đi)
  - `app-user/src/main/res/layout/item_message_received.xml` (tin nhắn nhận về)
- [x] 5.2 Tạo layout `app-user/src/main/res/layout/fragment_direct_chat.xml` với Toolbar, RecyclerView và thanh nhập tin nhắn kèm nút gửi ảnh.
- [x] 5.3 Tạo `MessageAdapter.kt` hiển thị linh hoạt tin nhắn văn bản và hình ảnh.
- [x] 5.4 Tạo `DirectChatViewModel.kt` tải tin nhắn cũ, gửi tin nhắn mới, upload ảnh, lắng nghe Realtime và tự động cuộn.
- [x] 5.5 Tạo `DirectChatFragment.kt` xử lý giao diện, nhập liệu, chọn ảnh và điều hướng.
- [x] 5.6 Tích hợp mở chi tiết thông qua `Home.openDetail()` và `Home.closeDetail()`.
- [x] 5.7 Gắn nút "Nhắn tin" từ trang cá nhân (`ProfileFragment`) để mở `DirectChatFragment`.

## 6. Kiểm tra & Hoàn thiện

- [x] 6.1 Bàn giao và hướng dẫn người dùng kiểm thử trực tiếp trên ứng dụng.
