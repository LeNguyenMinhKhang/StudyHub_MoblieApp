## Why

Nhóm học tập đã có thành viên, gọi thoại/video và kho PDF, nhưng chưa có kênh nhắn tin chữ (change `study-groups-and-member-invitations` để ngoài phạm vi). Thành viên phải rời StudyHub để trao đổi. Chat 1-1 đã có sẵn bảng `messages`, RPC gửi, realtime, upload ảnh/tài liệu và `MessageAdapter`, nên có thể tái sử dụng thay vì viết lại. Đồng thời các màn hình nhóm hiện toàn là nút chữ xếp dọc ("Bắt đầu gọi thoại", "Tải lên", "Thành viên"...), khác hẳn trải nghiệm nhắn tin quen thuộc kiểu Messenger.

## What Changes

- Mỗi nhóm học tập đang hoạt động có đúng một cuộc trò chuyện nhóm. Server tạo hội thoại khi cần (idempotent), dùng lại bảng `conversations` (`type = 'group'`, đã có sẵn trong schema), `messages`, `send_chat_message`, `mark_conversation_read`, realtime và bucket `chat`.
- Membership nhóm học tập là nguồn quyền duy nhất: chỉ thành viên hiện tại của nhóm chưa giải tán mới đọc, nhận realtime và gửi tin. Rời nhóm, bị xóa hoặc nhóm bị giải tán thì mất quyền ngay; thành viên mới thấy lịch sử tin của nhóm.
- **Mọi thành viên được thêm người (kiểu Messenger):** mọi thành viên hiện tại đều mời được bạn bè đã kết bạn của mình vào nhóm, qua luồng lời mời có chấp nhận đã có. Hiện tại chỉ trưởng nhóm được mời, nên đây là thay đổi quy tắc của change `study-groups-and-member-invitations`. Xóa thành viên, chuyển quyền, đổi tên/mô tả, xem và thu hồi lời mời đang chờ, giải tán vẫn chỉ trưởng nhóm (admin) làm được. Ai cũng rời nhóm được (trưởng nhóm phải chuyển quyền trước, như cũ).
- **Môn học thành tùy chọn:** tạo nhóm không bắt buộc chọn môn học nữa; mặc định là "Không gắn môn học". Nếu chọn môn thì môn đó phải đang hoạt động và vẫn không đổi được sau khi tạo (như cũ). Nhóm không gắn môn hiển thị bình thường trong danh sách, lời mời, chat và thông tin nhóm. Nhóm cũ đã có môn giữ nguyên. Đây cũng là thay đổi quy tắc "đúng một môn học" của change `study-groups-and-member-invitations`.
- Gửi chữ, ảnh và tài liệu trong nhóm qua đúng luồng của chat 1-1 (gửi lạc quan, trạng thái đang gửi/lỗi/gửi lại). Tin nhận được trong nhóm hiển thị tên và avatar người gửi.
- Tái cấu trúc phần dùng chung: tách `DirectChatViewModel` thành logic hội thoại dùng chung cho 1-1 và nhóm; `MessageAdapter` hỗ trợ chế độ nhóm. Chat 1-1 giữ nguyên hành vi (đã xem, chặn người dùng).
- **Giao diện kiểu Messenger** cho cả chat 1-1 và chat nhóm: header gồm avatar, tên, dòng phụ và các icon hành động; bong bóng tin gom theo người gửi liên tiếp, avatar ở tin cuối của chuỗi, tên người gửi ở tin đầu (nhóm), mốc thời gian chia đoạn; thanh nhập dạng viên thuốc với icon ảnh/tài liệu ở trái và icon gửi ở phải.
- Mở một nhóm từ danh sách sẽ vào thẳng màn hình chat nhóm. Header chat nhóm có icon gọi thoại, gọi video và icon thông tin (ⓘ); cuộc gọi đang diễn ra hiện thành thanh "Tham gia" gọn dưới header.
- Màn hình chi tiết nhóm hiện tại trở thành màn hình **Thông tin nhóm** (mở từ icon ⓘ): sửa tên/mô tả, Thành viên, Tài liệu, Rời nhóm, Giải tán hiển thị dạng hàng có icon.
- Đổi các nút chức năng dạng chữ sang icon (có `contentDescription`, vùng chạm ≥ 48dp): nút quay lại trong các màn hình nhóm, upload PDF trong kho tài liệu, điều khiển cuộc gọi (mic, camera, đổi camera, loa, rời, kết thúc cho tất cả).
- Tab Chat hiển thị cả hội thoại nhóm (icon nhóm, tên nhóm, tin cuối); chạm vào sẽ mở màn hình chat nhóm.
- Ngoài phạm vi: "đã xem bởi" từng thành viên, reaction/trả lời/thu hồi/sửa tin, nhắc tên (@), typing indicator, ghim tin, tin thoại, sticker, thông báo đẩy cho tin nhóm, tìm kiếm trong hội thoại, nhóm chat không gắn với nhóm học tập, và đổi luồng nghiệp vụ của cuộc gọi hay tài liệu PDF.

## Capabilities

### New Capabilities
- `study-group-chat`: Hội thoại nhóm gắn với nhóm học tập; quyền đọc/gửi/realtime theo membership nhóm; gửi chữ/ảnh/tài liệu; hiển thị người gửi; xuất hiện trong danh sách Chat.
- `study-group-membership`: Quy tắc mới "mọi thành viên mời bạn bè"; quyền quản lý khác giữ cho trưởng nhóm. Capability này đang ở change `study-groups-and-member-invitations` chưa archive nên được viết dạng ADDED; sẽ chuyển sang MODIFIED thay cho "Owners invite accepted friends" khi change kia đã archive (xem design D12).
- `study-groups`: Môn học của nhóm thành tùy chọn. Cùng cách xử lý như trên: ADDED bây giờ, chuyển thành MODIFIED cho "Create a subject-linked study group atomically" sau khi change kia archive (design D13).
- `messenger-chat-ui`: Giao diện nhắn tin kiểu Messenger dùng chung cho chat 1-1 và nhóm; điều hướng nhóm vào chat; màn hình Thông tin nhóm; chuyển nút chức năng dạng chữ sang icon trong màn hình nhóm, tài liệu và cuộc gọi.

### Modified Capabilities
- Không có. `openspec/specs/` hiện chỉ có `remote-profile-avatar`; các yêu cầu chat 1-1 và nhóm học tập nằm trong change chưa archive, nên change này viết yêu cầu mới và giữ nguyên hành vi 1-1 đã có.

## Impact

- `supabase/migrations/`: migration mới thêm `conversations.study_group_id` (unique, FK), RPC lấy/tạo hội thoại nhóm, cập nhật kiểm tra membership cho hội thoại gắn nhóm, đồng bộ hàng đọc (`conversation_members`) theo vòng đời thành viên nhóm, cập nhật `send_chat_message`/`list_my_conversations`, nới `invite_study_group_member` cho mọi thành viên, cho `study_groups.subject_id` nullable và cập nhật `create_study_group`/projection nhóm/lời mời. Đồng bộ `supabase/schema.sql`; sửa test `supabase/tests/study_groups/run_local.py` đang khẳng định thành viên thường bị từ chối mời (`G0005`).
- `backend/.../core/data/`: thêm hàm vào `ChatRemoteDataSource`/`ChatRepository` (lấy hội thoại nhóm, tra tên/avatar người gửi), model `GroupConversation`/`MessageSender`, các `Operation` liên quan; `GroupSummary.subject`, `GroupInvitation.subject`, `CreateGroupRequest.subjectId` thành nullable. Không đổi chữ ký các hàm 1-1 đang dùng.
- `app-user/.../ui/chat/`: tách ViewModel dùng chung, nâng cấp `MessageAdapter` và layout bubble/input/header; định tuyến theo `type` ở `Chat.kt`.
- `app-user/.../ui/group/`: thêm `GroupChatFragment`, đổi `GroupDetailFragment` thành Thông tin nhóm; chuyển layout `fragment_group*.xml`, `fragment_group_documents.xml`, `fragment_group_call.xml` sang icon; thêm vector drawable (gọi thoại, video, info, mic, camera, loa, kết thúc gọi...) và chuỗi trong `strings_groups.xml`.
- Kiểm thử: SQL test quyền trong `supabase/tests/`, unit test ViewModel/adapter logic, build `:app-user:assembleDebug` và smoke test nhiều tài khoản.
- Không thêm dependency mới.
