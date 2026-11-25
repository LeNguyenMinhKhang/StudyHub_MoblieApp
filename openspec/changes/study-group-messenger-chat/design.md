## Context

Khảo sát local ngày 2026-11-25 trên nhánh `fix/post-adapter-merge`:

- **Chat 1-1 đã chạy được và là "code cũ" để tái sử dụng.** Bảng `conversations` đã có `type in ('direct','group')`, `name`, `avatar_url`; `conversation_members` có `last_read_at`; `messages` hỗ trợ `text/image/file/document` và trạng thái `sending/sent/failed/seen/read`. RPC `send_chat_message`, `mark_conversation_read`, `list_my_conversations` (đã có nhánh `type <> 'direct'` dùng `c.name`), realtime publication cho `messages`, bucket `chat`. RLS của `messages`/`conversations` gọi `public.is_conversation_member()` và `public.direct_chat_available()` (hàm sau trả `true` với hội thoại không phải direct).
- Client: `ChatRemoteDataSource`/`ChatRepository` (backend), `DirectChatViewModel` (gửi lạc quan, retry theo `pendingPayloads`, realtime, đánh dấu đã đọc), `MessageAdapter` (sent/received, ảnh, thẻ tài liệu, icon trạng thái), `DirectChatFragment` + `fragment_direct_chat.xml` (đã dùng icon ảnh/tài liệu/gửi).
- Nhóm học tập: `study_groups`, `study_group_members` (PK `(group_id, user_id)`), `studyhub_private.group_access()` ném `G0004` khi không phải thành viên hoặc nhóm đã archive. `GroupDetailFragment` hiện là ScrollView nhiều `Button` chữ (gọi thoại/video, Thành viên, Tài liệu, Rời, Giải tán); `fragment_group_documents.xml` và `fragment_group_call.xml` cũng dùng `Button` chữ. Điều hướng qua `Home.openDetail()`/`closeDetail()`/`closeGroupDetails()`.
- **Phát hiện hồi quy:** `20261116_chat_status_and_documents.sql` định nghĩa lại `send_chat_message` và bỏ mất kiểm tra `direct_chat_available` mà `20261108130000_block_direct_chat.sql` đã thêm. Hiện tại RLS `messages_insert_member` vẫn chặn insert trực tiếp, nhưng RPC `security definer` bỏ qua RLS nên người bị chặn vẫn gửi được qua RPC. Change này phải định nghĩa lại `send_chat_message`, nên khôi phục kiểm tra đó luôn.

## Goals / Non-Goals

**Goals:**
- Chat nhóm tái sử dụng tối đa bảng, RPC, storage, realtime, repository, ViewModel và adapter của chat 1-1.
- Quyền chat nhóm lấy từ membership nhóm học tập, thực thi phía server; không nhân đôi nguồn quyền.
- Giao diện kiểu Messenger dùng chung cho 1-1 và nhóm; nút chức năng trong màn hình nhóm/tài liệu/cuộc gọi thành icon có nhãn trợ năng.
- Giữ nguyên hành vi chat 1-1 (đã xem, chặn) và luồng nghiệp vụ cuộc gọi/tài liệu.

**Non-Goals:**
- "Đã xem bởi" từng người, reaction, trả lời, sửa/thu hồi, @mention, typing, push notification cho tin nhóm.
- Chuyển bucket `chat` sang private hay signed URL (rủi ro có sẵn, xem bên dưới).
- Viết lại cuộc gọi hoặc viewer PDF; chỉ thay nút trong layout.
- Chuyển sang Jetpack Compose; giữ Fragment + XML + ViewBinding.

## Decisions

### D1. Dùng lại `conversations`/`messages` thay vì bảng `group_messages` riêng
Bảng riêng sẽ phải nhân bản RPC gửi, realtime channel, đường dẫn storage, model, adapter và logic retry. Dùng lại cho phép ChatRepository gửi tin nhóm bằng đúng `sendTextMessage/sendImageMessage/sendDocumentMessage` hiện có. Change nhóm học tập trước đó tránh dùng `conversations` vì lo gắn quyền học tập vào vòng đời chat; D2 xử lý điều này bằng cách cho chat tham chiếu nhóm (một chiều), không phải nhóm phụ thuộc chat.

### D2. Liên kết một chiều `conversations.study_group_id`
Thêm cột `study_group_id uuid null references public.study_groups(id) on delete restrict`, `unique`, cùng `check (study_group_id is null or type = 'group')`. Không ép mọi hội thoại `group` phải có `study_group_id` vì dữ liệu hosted có thể đã có hội thoại nhóm cũ. RPC mới `public.get_or_create_study_group_conversation(p_group_id uuid) returns jsonb` (`security definer`, `search_path = ''`):
1. `studyhub_private.group_access(p_group_id, actor)` → tái dùng mã lỗi `G0004`.
2. `insert ... on conflict (study_group_id) do nothing`, sau đó `select` → idempotent khi gọi đồng thời.
3. Upsert hàng `conversation_members` của người gọi (con trỏ đọc).
4. Trả `{conversation_id, group_id, name, member_count}`.

`name` của hội thoại không phải nguồn tên; `list_my_conversations` lấy tên từ `study_groups.name` để đổi tên nhóm hiện ngay.

### D3. Membership nhóm là nguồn quyền; `conversation_members` chỉ là con trỏ đọc
Định nghĩa lại `public.is_conversation_member(p_conversation_id, p_user_id)`:
- Nếu hội thoại có `study_group_id`: `true` khi tồn tại `study_group_members(group_id, user_id)`, nhóm `archived_at is null` và profile không `is_banned`.
- Ngược lại: giữ logic cũ theo `conversation_members`.

Vì mọi RLS (`messages_select_member`, `messages_insert_member`, `conversations_select_member`) và `send_chat_message` đều gọi hàm này, một thay đổi áp dụng cho đọc, gửi và realtime (postgres_changes đánh giá RLS theo JWT người nhận). Chữ ký và grant giữ nguyên. Hàm vẫn `stable security definer`, truy vấn theo PK nên chi phí không đáng kể.

`conversation_members` của hội thoại nhóm chỉ giữ `last_read_at`, được tạo bởi RPC ở D2, `mark_conversation_read` và `send_chat_message` (upsert). Trigger `after delete on study_group_members` xóa hàng con trỏ tương ứng để không còn đếm chưa đọc cho người đã rời. Hàng thừa (nếu trigger lỡ) không cấp quyền vì D3 không đọc nó cho hội thoại gắn nhóm.

Phương án bị loại: đồng bộ đầy đủ `conversation_members` bằng trigger insert/delete và để logic cũ làm quyền — nhân đôi membership, lệch khi archive (phải xóa hàng loạt) và dễ sai khi trigger lỗi.

### D4. Định nghĩa lại `send_chat_message` và `mark_conversation_read`
Một migration mới gom: kiểm tra `is_conversation_member` (D3); khôi phục `direct_chat_available` cho direct; giữ kiểm tra nội dung text/ảnh/tài liệu của bản 20261116; chỉ cập nhật `messages.status = 'read'` và trigger `on_conversation_member_read` cho hội thoại **direct**. Với nhóm, việc cập nhật trạng thái sẽ phát sự kiện UPDATE realtime cho mọi thành viên ở mỗi lần có người đọc mà UI nhóm không hiển thị (D9), nên bỏ qua. Upsert con trỏ đọc của người gửi thay vì `update` (người nhóm có thể chưa có hàng).

### D5. `list_my_conversations` trả thêm `study_group_id`
Drop + create lại vì đổi kiểu trả về. `my_convs` = hội thoại direct theo `conversation_members` (như cũ) **union** hội thoại gắn nhóm mà người dùng đang là thành viên của nhóm chưa archive (join `study_group_members`, left join con trỏ đọc). Tiêu đề nhóm = `study_groups.name`, `avatar_url` = null (client vẽ avatar chữ cái). Bỏ hội thoại nhóm chưa có tin. Hàm vẫn lọc `direct_chat_available` cho direct. `ConversationSummary` thêm `@SerialName("study_group_id") val studyGroupId: String? = null` — tương thích ngược.

### D6. Hồ sơ người gửi lấy theo lô từ `profiles`
`profiles` đã có policy `profiles_select_all`. `ChatRemoteDataSource.getSenderProfiles(ids)` chọn `id, display_name, avatar_url` với `isIn("id", ids)`, tối đa 100 id/lần. ViewModel giữ cache `Map<String, MessageSender>`, tải lần đầu cho các `sender_id` trong lịch sử và tải bổ sung khi realtime mang `sender_id` chưa biết. Lỗi tra cứu không làm hỏng danh sách (hiển thị "Thành viên" + avatar mặc định). Không dùng `list_study_group_members` vì phân trang 50 và không có người đã rời nhóm.

### D7. Tách ViewModel dùng chung
`DirectChatViewModel` hiện chứa toàn bộ logic gửi/retry/realtime. Tách thành `open class ConversationChatViewModel(repository)` với điểm mở `protected abstract suspend fun resolveConversation(): ApiResponse<String>` và cờ `showsReadReceipts`. `DirectChatViewModel` giữ tên và API công khai (`initChat`, `sendText`, `sendImage`, `sendDocument`, `retrySendMessage`, `refreshReadStatus`) để `DirectChatFragment` không phải đổi luồng. `GroupChatViewModel` gọi `getOrCreateStudyGroupConversation`, giữ `GroupChatHeader(name, memberCount)` trong UiState, ánh xạ `G0004` sang trạng thái `inaccessible` như `GroupDetailViewModel`.

Đồng thời sửa hai điểm nhỏ của logic cũ khi tách: khớp tin realtime với tin tạm chỉ khi cùng `senderId` (tránh tin của người khác trong nhóm thay thế tin tạm có cùng nội dung), và tạo `tempId` bằng bộ đếm/UUID thay vì chỉ `currentTimeMillis()`.

### D8. Fragment và layout dùng chung
Tạo `abstract class ConversationChatFragment` giữ RecyclerView, input bar, picker ảnh/tài liệu (giới hạn 20 MB như cũ), mở URL và quan sát state chung. Layout `fragment_conversation_chat.xml` thay `fragment_direct_chat.xml`: header (back, avatar, tên, dòng phụ, `LinearLayout` icon hành động), thanh cuộc gọi (ẩn mặc định), danh sách, thanh nhập. `DirectChatFragment` và `GroupChatFragment` kế thừa, chỉ điền header/hành động và tham số. `GroupChatFragment` dùng lại `GroupCallBannerViewModel` + `CallSessionManager` với cùng chu kỳ `POLL_MS` trong `repeatOnLifecycle(STARTED)` như `GroupDetailFragment.bindCallBanner()`.

### D9. Bubble kiểu Messenger bằng danh sách hiển thị tính sẵn
Thêm hàm thuần `buildChatItems(messages, currentUserId, isGroup, senders): List<ChatItem>` với `ChatItem.TimeSeparator` và `ChatItem.Bubble(message, runPosition = SINGLE|FIRST|MIDDLE|LAST, showSenderName, showAvatar, showStatus)`. Quy tắc: cùng người gửi và cách nhau ≤ 5 phút thì chung chuỗi; khoảng cách > 15 phút hoặc khác ngày thì chèn mốc thời gian. `showStatus` = luôn với `sending/failed`; với `sent/read` chỉ ở tin cuối cùng của mình. Nhóm: `read` hiển thị như `sent`. `MessageAdapter` chuyển sang `ListAdapter<ChatItem, …>`, chọn drawable góc bo theo `runPosition` (thêm 4 biến thể mỗi phía). Hàm thuần được unit test, không cần thiết bị.

### D10. Điều hướng nhóm
- `GroupBrowserFragment` (`state.openGroup`) và `CreateGroupFragment` (`state.created`) mở `GroupChatFragment.newInstance(id)`.
- `GroupDetailFragment` đổi tên thành `GroupInfoFragment` (giữ ViewModel `GroupDetailViewModel` và logic sửa/rời/giải tán), layout `fragment_group_info.xml` dạng các hàng icon; phần banner gọi chuyển sang chat.
- `Chat.kt` chọn `GroupChatFragment` khi `type == "group" && studyGroupId != null`, còn lại `DirectChatFragment`.
- `closeGroupDetails()` đã pop toàn bộ back stack con nên phủ cả màn hình chat nhóm.

### D11. Icon
Thêm vector drawable Material Symbols (Apache 2.0) vào `app-user/src/main/res/drawable/`: `ic_call`, `ic_videocam`, `ic_videocam_off`, `ic_info`, `ic_mic`, `ic_mic_off`, `ic_cameraswitch`, `ic_volume_up`, `ic_headset`, `ic_call_end`, `ic_logout` (đã có), `ic_upload`, `ic_add_circle`, `ic_edit`, `ic_delete`. Không thêm thư viện. Mỗi `ImageButton` 48dp, `contentDescription` lấy từ `strings_groups.xml`; với nút bật/tắt, đổi cả icon và `contentDescription` theo state trong `GroupCallFragment`. Nút hành động trong hàng danh sách (Chấp nhận/Từ chối/Mời/Xóa/Chuyển) giữ chữ vì là lựa chọn mang nghĩa.

### D12. Mọi thành viên mời bạn bè (người dùng chọn ngày 2026-11-25)
`invite_study_group_member` đổi `group_access(p_group_id, actor, true, true)` thành `group_access(p_group_id, actor, false, true)`. Các kiểm tra còn lại giữ nguyên: bạn bè phải là bạn của **người mời** (`friendships` theo `actor`), không tự mời, không mời người đã là thành viên, mỗi cặp nhóm/người nhận chỉ một lời mời pending (trả lại lời mời đang có). Danh sách và thu hồi lời mời pending, xóa thành viên, chuyển quyền, sửa thông tin, giải tán vẫn yêu cầu owner. Trả lại lời mời pending của người khác chỉ lộ `id/status`, không lộ người mời, nên chấp nhận được.

UI: `GroupMembersViewModel` cho thành viên thường thấy mục `MEMBERS` và `FRIENDS` (danh sách bạn bè của chính họ, hành động `INVITE`); `PENDING` vẫn chỉ cho owner. Vì thành viên thường không đọc được danh sách pending, nếu server trả lời mời đã có thì hàng hiện "Đã mời" (dùng lại `knownPending`). Màn hình Thông tin nhóm có hàng "Thêm thành viên" cho mọi người, mở mục `FRIENDS`.

**Thứ tự archive:** yêu cầu "Owners invite accepted friends" đang nằm trong change `study-groups-and-member-invitations` (chưa archive, chưa có canonical spec). Delta ở đây viết dạng ADDED để validate được. Trước khi archive change này, phải archive change kia trước, rồi dùng `openspec-update` đổi delta thành MODIFIED của requirement gốc. Nếu không, canonical spec sẽ có hai quy tắc mâu thuẫn.

### D13. Môn học tùy chọn (người dùng chọn ngày 2026-11-25)
Người dùng xác nhận nhóm không cần gắn với một môn học cụ thể. Chọn **tùy chọn** thay vì bỏ hẳn cột: giữ dữ liệu cũ, không đổi chữ ký RPC, và nhóm nào muốn vẫn gắn môn được.
- `alter table study_groups alter column subject_id drop not null` (FK giữ nguyên).
- `create_study_group(p_request_id, p_subject_id, p_name, p_description)` giữ chữ ký; chỉ bắt `G0001` khi thiếu `p_request_id`; nếu `p_subject_id` khác null thì vẫn kiểm tra môn đang hoạt động (`G0006`). `creation_payload` vẫn chứa `subject_id` (có thể null) nên retry đổi lựa chọn môn vẫn báo `G0010`.
- `group_projection` và `group_invitation_projection` đổi `join subjects` thành `left join`, trả `'subject', null` khi không có môn (dùng `case when s.id is null`).
- Kotlin: `GroupSummary.subject: GroupSubject? = null`, `GroupInvitation.subject: GroupSubject? = null`, `CreateGroupRequest.subjectId: String?`; `GroupRemoteDataSource` chỉ `put("p_subject_id", …)` khi có giá trị, còn không thì gửi JSON null; `GroupRepository.create` chỉ chuẩn hóa UUID khi khác null.
- UI tạo nhóm: mục đầu của spinner là "Không gắn môn học" (thay "Chọn môn học"); nút Tạo không còn phụ thuộc `subjectId`; lỗi tải môn học chỉ hiện cảnh báo, không chặn tạo nhóm. Các chỗ dùng `subject.name` (`GroupViewModels` dòng danh sách/lời mời, `group_summary`) bỏ phần môn khi null; thêm chuỗi tóm tắt không có môn.
- Test: `supabase/tests/study_groups/run_local.py` hiện kiểm tra `p_subject_id` null → `G0001`; đổi thành tạo thành công không có môn, thêm test retry đổi môn → `G0010`, và kiểm tra projection trả `subject: null`. Cập nhật `GroupRepositoryTest`/`GroupViewModelsTest` và fixture `backend/src/test/resources/study_groups/rpc_payloads.json`.
- Cùng thứ tự archive như D12 với requirement "Create a subject-linked study group atomically".

## Risks / Trade-offs

- [Bucket `chat` public, ai đăng nhập cũng upload được] → Có sẵn từ chat 1-1; URL ảnh/tài liệu nhóm đoán được khó nhưng không bị thu hồi khi rời nhóm. Ghi nhận trong proposal là ngoài phạm vi; đề xuất change riêng chuyển sang bucket private + signed URL cho cả 1-1 và nhóm.
- [Đổi `is_conversation_member` ảnh hưởng mọi RLS chat] → Nhánh direct giữ nguyên biểu thức cũ; SQL test hồi quy cho direct (thành viên, không thành viên, bị chặn) chạy cùng test nhóm.
- [Drop/create `list_my_conversations` đổi kiểu trả về] → Client cũ bỏ qua trường lạ (`ignoreUnknownKeys` cần kiểm tra trong cấu hình Supabase serializer khi apply); grant được cấp lại trong cùng migration.
- [Schema local và hosted có thể lệch (đã ghi ở change nhóm học tập)] → Migration dùng `if not exists`/`create or replace` nơi an toàn; test trên DB local có baseline; đối chiếu hosted khi được giao deploy.
- [Hiệu năng đọc lịch sử dài] → Bản đầu tải toàn bộ như chat 1-1. Index `messages_conversation_id_idx` có sẵn; thêm index `(conversation_id, created_at)` nếu test cho thấy chậm. Phân trang lịch sử để change sau.
- [Tách ViewModel có thể làm hỏng chat 1-1] → Giữ API công khai `DirectChatViewModel`; unit test logic gửi/retry/khớp tin tạm chạy cho cả hai chế độ; smoke test 1-1 trong nghiệm thu.
- [Realtime UPDATE trạng thái `read` ở nhóm] → Tránh bằng D4.

## Migration Plan

1. Migration `supabase/migrations/2026112xxxxxxx_study_group_chat.sql` trong một transaction: cột + constraint, `is_conversation_member`, RPC D2, `send_chat_message`, `mark_conversation_read`, trigger đọc chỉ cho direct, trigger xóa con trỏ khi rời nhóm, `list_my_conversations`, grant, `notify pgrst, 'reload schema'`.
2. Đồng bộ phần tương ứng vào `supabase/schema.sql`, giữ thay đổi ngoài phạm vi.
3. Phát hành backend + app-user cùng lúc; app cũ vẫn chạy chat 1-1 vì chữ ký RPC 1-1 không đổi.
4. Rollback: migration ngược khôi phục `is_conversation_member`/`send_chat_message`/`list_my_conversations` từ bản 20261108/20261116 và drop RPC D2; cột `study_group_id` có thể giữ lại (nullable) để không mất dữ liệu.

## Open Questions

- Có muốn hiện tin hệ thống ("A đã tham gia nhóm", "B đã rời nhóm") trong chat không? Mặc định bản đầu: không.
- Có cho phép trưởng nhóm xóa tin của thành viên không? Mặc định: không (chưa có xóa tin ở 1-1).
