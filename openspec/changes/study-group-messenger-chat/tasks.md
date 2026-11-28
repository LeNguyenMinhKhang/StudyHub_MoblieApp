## 1. Database: liên kết chat với nhóm học tập

- [x] 1.1 Tạo migration `supabase/migrations/<timestamp>_study_group_chat.sql` (một transaction): thêm `conversations.study_group_id` (nullable, unique, FK `study_groups` `on delete restrict`) và `check (study_group_id is null or type = 'group')`.
- [x] 1.2 Định nghĩa lại `public.is_conversation_member(uuid, uuid)`: hội thoại gắn nhóm → thành viên `study_group_members`, nhóm chưa archive, profile không bị khóa; hội thoại khác → giữ logic `conversation_members` cũ. Giữ chữ ký, `stable security definer` và grant.
- [x] 1.3 Thêm RPC `public.get_or_create_study_group_conversation(p_group_id uuid)` dùng `studyhub_private.group_access`, `insert ... on conflict (study_group_id) do nothing`, upsert con trỏ đọc của người gọi, trả `conversation_id, group_id, name, member_count`; grant cho `authenticated`.
- [x] 1.4 Định nghĩa lại `send_chat_message`: kiểm tra `is_conversation_member`; khôi phục kiểm tra `direct_chat_available` cho direct (bị mất ở `20261116_chat_status_and_documents.sql`); giữ kiểm tra text/ảnh/tài liệu; chỉ cập nhật `status = 'read'` cho direct; upsert `last_read_at` của người gửi.
- [x] 1.5 Định nghĩa lại `mark_conversation_read` (kiểm tra quyền, upsert con trỏ, cập nhật `status` chỉ cho direct) và sửa trigger `on_conversation_member_read` để bỏ qua hội thoại gắn nhóm.
- [x] 1.6 Thêm trigger `after delete on study_group_members` xóa hàng `conversation_members` của hội thoại gắn nhóm tương ứng.
- [x] 1.7 Drop + create `list_my_conversations()` trả thêm `study_group_id`; union hội thoại nhóm của nhóm đang hoạt động mà người dùng là thành viên (tiêu đề từ `study_groups.name`, bỏ nhóm chưa có tin); giữ lọc `direct_chat_available`; cấp lại grant; `notify pgrst, 'reload schema'`.
- [x] 1.8 Định nghĩa lại `invite_study_group_member` cho mọi thành viên hiện tại (`group_access(..., false, true)`), giữ kiểm tra bạn bè của người mời, chống trùng pending và các mã lỗi hiện có; danh sách/thu hồi lời mời và các thao tác quản lý khác vẫn yêu cầu owner (design D12).
- [x] 1.9 Cho `study_groups.subject_id` nullable; sửa `create_study_group` chấp nhận `p_subject_id` null (vẫn kiểm tra `G0006` khi có môn, `creation_payload` giữ `subject_id`); đổi `group_projection`/`group_invitation_projection` sang `left join subjects` và trả `subject: null` khi không có môn (design D13).
- [x] 1.10 Đồng bộ phần thay đổi vào `supabase/schema.sql`, giữ nguyên các phần ngoài phạm vi.

## 2. Database tests

- [x] 2.1 Thêm `supabase/tests/study_group_chat/run_local.py` theo mẫu `supabase/tests/study_groups/run_local.py`: thành viên lấy/tạo hội thoại; hai lần gọi đồng thời trả cùng id; người ngoài và nhóm archived nhận `G0004`.
- [x] 2.2 Test quyền: thành viên đọc/gửi được; sau khi rời, bị xóa, nhóm archive hoặc tài khoản bị khóa thì `select messages` trả rỗng và `send_chat_message` báo `42501`; insert trực tiếp vào `messages` của người ngoài bị RLS chặn; thành viên mới chấp nhận lời mời đọc được lịch sử.
- [x] 2.3 Test `list_my_conversations`: nhóm có tin xuất hiện với `study_group_id` và đếm chưa đọc đúng; đếm về 0 sau `mark_conversation_read`; nhóm đã rời/archive không hiện.
- [x] 2.4 Sửa `supabase/tests/study_groups/run_local.py`: thành viên thường mời được bạn của mình (thay khẳng định `G0005` cũ); mời người không phải bạn của mình → `G0007`; hai thành viên mời cùng một người → trả cùng lời mời; người ngoài/cựu thành viên → `G0004`; thành viên thường đọc/thu hồi lời mời pending, xóa người → bị từ chối; owner thu hồi được lời mời do thành viên gửi.
- [x] 2.5 Sửa test tạo nhóm: `p_subject_id` null → tạo thành công và projection trả `subject: null`; môn không hoạt động → `G0006`; retry cùng request ID nhưng đổi lựa chọn môn → `G0010`; lời mời của nhóm không có môn trả `subject: null`; nhóm cũ có môn không đổi sau migration.
- [x] 2.6 Test hồi quy chat 1-1: get-or-create direct, đã xem, người bị chặn không gửi được qua RPC và hội thoại bị ẩn khỏi danh sách.

## 3. Backend Kotlin (Supabase data layer)

- [x] 3.1 Thêm model `GroupConversation` (`conversation_id`, `group_id`, `name`, `member_count`) và `MessageSender` (`id`, `display_name`, `avatar_url`); thêm `studyGroupId` (nullable, mặc định null) vào `ConversationSummary`.
- [x] 3.2 Thêm vào `ChatRemoteDataSource`: `getOrCreateStudyGroupConversation(groupId)` và `getSenderProfiles(ids)` (lô ≤ 100 id). Không đổi chữ ký các hàm 1-1.
- [x] 3.3 Thêm `Operation` tương ứng và hàm `ChatRepository.getOrCreateStudyGroupConversation`, `getSenderProfiles` qua `SafeApiCall`; ánh xạ `G0004` sang lỗi nhóm không khả dụng như `GroupRepository`.
- [x] 3.4 Cho `GroupSummary.subject`, `GroupInvitation.subject`, `CreateGroupRequest.subjectId` nullable; `GroupRemoteDataSource.create` gửi null khi không có môn; `GroupRepository.create` chỉ chuẩn hóa UUID môn khi khác null; cập nhật fixture `backend/src/test/resources/study_groups/rpc_payloads.json` có ca `subject: null` và `GroupRepositoryTest`.
- [x] 3.5 Unit test repository với fake data source: ánh xạ thành công/`G0004`/lỗi mạng, chia lô id người gửi, giữ nguyên `CancellationException`.

## 4. ViewModel dùng chung cho chat

- [x] 4.1 Tách logic gửi lạc quan, retry, realtime, đánh dấu đã đọc từ `DirectChatViewModel` sang `open class ConversationChatViewModel`; `DirectChatViewModel` kế thừa và giữ nguyên API công khai.
- [x] 4.2 Sửa khi tách: khớp tin realtime với tin tạm chỉ khi cùng `senderId`, `tempId` không trùng khi gửi liên tiếp nhanh.
- [x] 4.3 Thêm `GroupChatViewModel`: lấy hội thoại nhóm, header (tên, số thành viên), cache `MessageSender` và tải bổ sung khi gặp `sender_id` mới, trạng thái `inaccessible` khi `G0004`, không áp dụng suy luận "đã xem".
- [x] 4.4 Unit test ViewModel bằng fake repository: gửi chữ/ảnh/tài liệu thành công và lỗi, retry không nhân đôi, tin realtime của người khác cùng nội dung không thay tin tạm, cache người gửi, chuyển `inaccessible`.

## 5. Giao diện kiểu Messenger dùng chung

- [x] 5.1 Thêm hàm thuần `buildChatItems(...)` tạo `ChatItem.TimeSeparator`/`ChatItem.Bubble` (chuỗi ≤ 5 phút, mốc khi cách > 15 phút hoặc khác ngày, `showSenderName`, `showAvatar`, `showStatus`) và unit test các trường hợp biên.
- [x] 5.2 Chuyển `MessageAdapter` sang `ListAdapter<ChatItem, …>`; thêm view type mốc thời gian; drawable bo góc theo vị trí chuỗi cho cả hai phía; tên + avatar người gửi trong layout nhận; icon trạng thái theo `showStatus`; giữ click ảnh/tài liệu/retry.
- [x] 5.3 Tạo `fragment_conversation_chat.xml` (header có vùng icon hành động, thanh cuộc gọi ẩn, danh sách, thanh nhập dạng viên thuốc) và `abstract class ConversationChatFragment` chứa list/input/picker (giới hạn 20 MB)/mở URL; `DirectChatFragment` chuyển sang kế thừa, giữ tham số `newInstance`.
- [x] 5.4 Thanh nhập: khi có chữ thì gộp icon ảnh/tài liệu thành icon mở rộng; nút gửi tắt khi trống; input nằm trên bàn phím (`adjustResize`/insets) và cuộn tới tin mới nhất. **Ghi chú:** logic ẩn/hiện icon có trong code; vị trí thanh nhập khi mở bàn phím (xử lý IME insets trong `ConversationChatFragment`) chưa kiểm chứng trên thiết bị, chuyển sang 7.3.
- [x] 5.5 Thêm vector drawable theo design D11 và chuỗi/`contentDescription` tiếng Việt vào `strings_groups.xml` (hoặc `strings.xml` cho phần chat chung).

## 6. Màn hình nhóm

- [x] 6.1 Thêm `GroupChatFragment` kế thừa `ConversationChatFragment`: header avatar chữ cái, tên nhóm, "N thành viên", icon gọi thoại/video/thông tin; thanh cuộc gọi dùng `GroupCallBannerViewModel` + `CallSessionManager` với `POLL_MS` chỉ khi STARTED; chặn mở cuộc gọi hai lần; đóng các màn hình nhóm khi `inaccessible`.
- [x] 6.2 Đổi `GroupDetailFragment` thành `GroupInfoFragment` với `fragment_group_info.xml`: phần đầu avatar/tên/môn/số thành viên; các hàng icon Sửa (owner), Thành viên, Tài liệu, Rời nhóm (non-owner), Giải tán (owner, màu cảnh báo); giữ dialog xác nhận và xử lý lỗi; bỏ banner cuộc gọi khỏi màn hình này. **Ghi chú:** giữ tên lớp `GroupDetailFragment` và `GroupDetailViewModel` (không đổi tên để tránh churn), layout vẫn là `fragment_group_detail.xml`.
- [x] 6.3 Điều hướng: `GroupBrowserFragment` (`openGroup`) và `CreateGroupFragment` (`created`) mở `GroupChatFragment`; `Chat.kt` mở `GroupChatFragment` cho hội thoại có `studyGroupId`; `ConversationAdapter` hiện icon nhóm khi không có avatar.
- [x] 6.4 Đổi nút chữ sang icon: back trong `fragment_group.xml`, `fragment_create_group.xml`, `fragment_group_documents.xml`, `fragment_group_call.xml`; upload trong kho tài liệu (đưa lên top bar); tạo nhóm thành icon thêm ở top bar danh sách nhóm.
- [x] 6.5 Điều khiển cuộc gọi thành icon tròn: mic, camera (đổi icon + `contentDescription` theo state), đổi camera, loa/tai nghe (vẫn mở bộ chọn tuyến âm thanh), rời, kết thúc cho tất cả (đỏ, giữ dialog xác nhận); cập nhật `GroupCallFragment` dùng `setImageResource`/`contentDescription` thay `setText`.
- [x] 6.6 `GroupMembersViewModel`: thành viên thường thấy mục `MEMBERS` + `FRIENDS` (mời bạn của mình, hiện "Đã mời" khi server trả lời mời đã có); `PENDING`, `REMOVE`, `TRANSFER` vẫn chỉ cho owner. Thêm hàng "Thêm thành viên" cho mọi người trong Thông tin nhóm.
- [x] 6.7 Tạo nhóm không bắt buộc môn: spinner mục đầu "Không gắn môn học", nút Tạo không phụ thuộc môn, lỗi tải môn không chặn tạo; dòng nhóm, xem trước lời mời, tóm tắt Thông tin nhóm bỏ phần môn khi null (thêm chuỗi tóm tắt không có môn trong `strings_groups.xml`).
- [x] 6.8 Cập nhật `GroupViewModelsTest` và các test liên quan nếu đổi tên/điều hướng làm thay đổi hành vi được kiểm thử.

## 7. Kiểm tra và nghiệm thu

- [x] 7.1 Chạy `./gradlew :backend:testDebugUnitTest :app-user:testDebugUnitTest` và `./gradlew :app-user:assembleDebug`; sửa lỗi lint mới liên quan (`contentDescription`, touch target). **Đã chạy:** `:backend:testDebugUnitTest` (99 test), `:app-user:testDebugUnitTest` (119 test), `:app-user:assembleDebug`, `:app-admin:compileDebugKotlin`, `:app-user:lintDebug` (0 lỗi).
- [x] 7.2 Chạy SQL test nhóm 2 và test hiện có `supabase/tests/study_groups/run_local.py` trên DB local có baseline. **Đã chạy:** `study_group_chat/run_local.py` (14 test) và `study_groups/run_local.py` (14 test) trên Postgres 17 trong Docker.
- [x] 7.3 Smoke test 3 tài khoản: tạo nhóm không chọn môn → owner mời A → A chấp nhận rồi mời B (bạn của A) → B chấp nhận → chat chữ/ảnh/tài liệu realtime, tên/avatar người gửi, đếm chưa đọc ở tab Chat; rời/bị xóa/giải tán khi đang mở chat; gọi thoại/video từ icon header và thanh "Tham gia"; upload PDF bằng icon; điều khiển cuộc gọi bằng icon với TalkBack.
- [x] 7.4 Smoke test hồi quy chat 1-1: mở từ hồ sơ/bạn bè, gửi chữ/ảnh/tài liệu, đã xem, retry khi mất mạng, chặn người dùng. Kiểm tra nhóm cũ có môn vẫn hiển thị môn.
- [x] 7.5 Trước khi archive change này: archive `study-groups-and-member-invitations` trước, rồi dùng `openspec-update` đổi delta `study-group-membership` và `study-groups` thành MODIFIED của "Owners invite accepted friends" và "Create a subject-linked study group atomically" (design D12, D13).
