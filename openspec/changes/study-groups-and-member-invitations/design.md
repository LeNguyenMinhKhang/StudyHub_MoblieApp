## Context

Người dùng đã chọn nhóm gắn với môn học, tạo/quản lý thành viên trước, tham gia bằng lời mời có chấp nhận. Khảo sát local ngày 2026-11-10: `Group.kt` chỉ inflate placeholder; schema có nhóm hội thoại nhưng chưa có nhóm học tập độc lập hoặc lời mời nhóm. Change chat hiện giới hạn 1-1. `ConversationSummary` dùng `target_user_id`, trong khi bản `list_my_conversations` ở schema snapshot trả `type/title`; migration chat vẫn trả DTO 1-1. Không dùng sự không đồng nhất này làm nền cho nhóm mới.

Policy `conversation_members` local cho phép self-insert và self-update không giới hạn cột; không sao chép chúng vào nhóm học tập. Đây là phát hiện từ file, chưa kiểm chứng hosted. `supabase/schema.sql` đang có thay đổi ngoài phạm vi về cleanup bài viết/bình luận; phải giữ nguyên.

Kiến trúc hiện tại: Android Fragment/XML/ViewBinding → ViewModel → Kotlin repository dùng ApiResponse/SafeApiCall → remote data source → Supabase. Subject có `is_active`; friendship được xác nhận bằng `status='accepted'`. Có thể dùng `SubjectRepository` và `FriendshipRepository.list(FRIENDS)` làm nguồn cho bộ chọn. Người dùng đã chốt PDF + bút vẽ + highlight + note chữ + đồng bộ trực tiếp + lưu lại + xuất PDF có đánh dấu để thay Present màn hình, giữ gọi thoại/video. App hiện minSdk 24, target/compileSdk 37; manifest mới có INTERNET, chưa có SDK media, quyền mic/camera hoặc foreground service cho capture.

## Goals / Non-Goals

**Goals:** Nhóm riêng theo môn học; lời mời tách khỏi thành viên; quyền phía server; giao dịch nhất quán; UI có loading/empty/error/retry; gọi thoại/video nhóm và PDF cộng tác có ghi chú bền vững; khả năng kiểm chứng bằng nhiều danh tính và media thực.

**Non-Goals:** Screen sharing/MediaProjection, sửa chữ/bố cục gốc PDF, Word/PowerPoint editor, OCR/text-selection highlight, recording/system playback audio, client web/laptop, remote control, nhắn tin chữ nhóm, gọi 1-1, nhóm công khai, push/chuông cuộc gọi nền và tự triển khai hosted. Bản xuất là PDF phẳng, không hứa giữ searchable text/form/digital signature gốc. Cuộc gọi vẫn tiếp tục khi mở PDF trong StudyHub; microphone có thể tiếp tục qua foreground service khi rời app, camera tạm dừng khi background.

## Decisions

### 1. Nhóm học tập là thực thể riêng

Chọn `study_groups`, `study_group_members`, `study_group_invitations`. Dùng lại `conversations` sẽ gắn quyền học tập vào vòng đời chat và yêu cầu thay contract đang phục vụ chat 1-1. Không tạo hội thoại chữ hoặc nhân đôi membership cho cuộc gọi: mỗi phiên LiveKit tham chiếu group_id và dùng membership nhóm làm nguồn quyền.

```text
subjects ──< study_groups ──< study_group_members >── profiles
                    ├─────< study_group_invitations >── profiles
                    ├─────< study_group_calls ──< call_admissions / control_jobs
                    └─────< group_documents ──< document_versions ──< annotations / operations / snapshots
```

### 2. Dữ liệu và bất biến

| Bảng | Dữ liệu và ràng buộc dự kiến |
| --- | --- |
| study_groups | id, subject_id NOT NULL, name, description, owner_id NOT NULL, created_by, client_request_id, creation_payload, created_at, updated_at, archived_at; UNIQUE(created_by, client_request_id) |
| study_group_members | group_id, user_id, joined_at; PRIMARY KEY(group_id, user_id) |
| study_group_invitations | id, group_id, inviter_id, invitee_id, status, created_at, resolved_at; status pending/accepted/declined/revoked; partial unique(group_id, invitee_id) WHERE status='pending' |

Vai trò được suy ra từ `group.owner_id = member.user_id`, không lưu thêm role có thể lệch. Dùng FK tổng hợp hoãn kiểm tra cuối giao dịch từ `(study_groups.id, owner_id)` đến membership để bảo đảm chủ nhóm luôn là thành viên; create chèn nhóm và membership trong cùng transaction. FK môn học và profile liên quan dùng RESTRICT để không vô tình xóa dữ liệu/để nhóm mất chủ khi xóa bản ghi cha; đây là tác động cần kiểm tra với các luồng admin khi apply. Archive giữ hàng nhóm, thành viên và lịch sử lời mời nhưng cấm truy cập bằng API người dùng; không xóa vật lý trong luồng giải tán.

Tên được trim, dài 1–100 ký tự; mô tả tùy chọn, tối đa 1.000 ký tự. Backend xác thực giới hạn bằng số ký tự Unicode, Android không cắt theo byte và có kiểm thử tiếng Việt/emoji. Tên nhóm không cần duy nhất. Mỗi nhóm chọn đúng một môn đang hoạt động lúc tạo; sau tạo môn cố định. Môn bị vô hiệu hóa không làm mất nhóm cũ và không chặn quản lý thành viên. `creation_payload` lưu đầu vào chuẩn hóa bất biến để so khớp retry, không so với tên/mô tả có thể đã được sửa. Request key/payload không nằm trong projection đọc của nhóm.

### 3. Lời mời là trạng thái trước khi tham gia

Trưởng nhóm chỉ mời bạn bè accepted tại thời điểm gửi. Khóa hàng friendship tương ứng khi kiểm tra/gửi để có thứ tự xác định với thao tác hủy kết bạn. Không cho mời chính mình, thành viên hiện có hoặc tài khoản không khả dụng. Lời mời pending không làm tăng số thành viên. Gửi lại khi đã pending trả lời mời hiện tại; sau declined/revoked có thể gửi lời mời mới với ID mới nếu vẫn đủ điều kiện.

Người nhận chấp nhận hoặc từ chối. Không tự hết hạn ở phiên bản đầu. Hủy kết bạn sau khi gửi không tự hủy lời mời; tình bạn chỉ là điều kiện gửi. Chuyển trưởng nhóm giữ lời mời pending dưới quyền quản lý của trưởng nhóm mới; `inviter_id` là lịch sử, không trao quyền quản lý cho người mời cũ. Người mời cũ bị khóa tài khoản không tự hủy lời mời của nhóm; người thực hiện hiện tại và người nhận tham gia phải là tài khoản hợp lệ, không bị banned.

Chỉ người nhận pending thấy preview: tên nhóm, môn, mô tả, tên/avatar công khai của người mời. Không cho preview lộ danh sách thành viên, lịch sử lời mời khác hoặc dữ liệu profile riêng. Lời mời terminal không hiển thị trong danh sách đang chờ; không xây màn hình lịch sử ở bản đầu.

### 4. RPC và quyền truy cập

RPC lấy danh tính từ `auth.uid()`, kiểm tra profile tồn tại/không banned, trạng thái nhóm và quyền hiện tại. Dùng SECURITY DEFINER với search_path cố định và tên schema đầy đủ; revoke EXECUTE khỏi PUBLIC/anon, chỉ grant cho authenticated các RPC công khai. Helper nội bộ không phải API. Bật RLS cho cả ba bảng; không cấp direct DML cho app, không cấp direct SELECT bảng mới cho app. Các RPC đọc trả projection tối thiểu và tự kiểm quyền; test cả lời gọi RPC và truy cập bảng trực tiếp. Không mở rộng quyền profile hiện tại để hiển thị nhóm.

| RPC dự kiến | Contract chính |
| --- | --- |
| create_study_group(request_id, subject_id, name, description) | Tạo nhóm + membership chủ nhóm nguyên tử; request_id được giữ qua retry/khôi phục form; cùng actor/key/payload trả cùng nhóm, payload khác báo conflict; nhóm đã archive trả unavailable và không tạo lại |
| list_my_study_groups(cursor, limit), get_study_group(group_id) | Chỉ nhóm active có membership hiện tại; summary gồm môn, vai trò, số thành viên |
| update_study_group(group_id, name, description) | Chỉ chủ nhóm; không đổi môn, owner hoặc created_by qua API này |
| list_study_group_members(group_id, cursor, limit) | Thành viên xem projection id, display_name, avatar, role, joined_at |
| list_my_study_group_invitations(cursor, limit) | Chỉ pending gửi cho actor ở nhóm active, kèm preview tối thiểu |
| list_study_group_invitations(group_id, cursor, limit) | Chỉ chủ nhóm hiện tại, danh sách pending |
| invite_study_group_member(group_id, target_id) | Chỉ chủ nhóm + friendship accepted; trả invitation ID/status |
| respond_study_group_invitation(invitation_id, action) | Chỉ invitee; action accept/decline; accept tạo membership cùng transaction |
| revoke_study_group_invitation(invitation_id) | Chỉ chủ nhóm hiện tại; pending → revoked |
| leave_study_group(group_id), remove_study_group_member(group_id, target_id) | Xóa membership thường; cấm xóa chủ nhóm; không tái kích hoạt lời mời cũ |
| transfer_study_group_ownership(group_id, target_id) | Chỉ chủ nhóm; target là thành viên khác không banned; đổi owner nguyên tử |
| archive_study_group(group_id) | Chủ nhóm xác nhận giải tán dù còn thành viên; archive + revoke toàn bộ pending nguyên tử |

Tên RPC/DTO là contract dự kiến; trước khi viết Kotlin, thống nhất tên field SQL/SerialName và ghi test decode bằng payload thật từ DB local. List dùng keyset ổn định: nhóm/lời mời `(created_at DESC, id DESC)`, thành viên `(joined_at ASC, user_id ASC)`; mặc định 20, tối đa 50, trả next_cursor; từ chối cursor sai cấu trúc. Read bắt đầu sau mutation commit phải phản ánh quyền mới. Không hứa snapshot đồng nhất giữa nhiều trang khi có thay đổi đồng thời.

### 5. Giao dịch, cạnh tranh và lỗi

Các mutation của một nhóm khóa hàng nhóm trước, sau đó invitation/member liên quan và friendship nếu cần; cùng thứ tự ở mọi RPC. Với invitation ID, lookup group_id không trả dữ liệu cho client, khóa nhóm rồi kiểm lại invitation/actor. Kiểm lại quyền sau khi lấy khóa: không tin trạng thái từ UI.

- Accept cạnh tranh revoke: nếu accept thắng thì có membership và revoke trả conflict, không ngầm kick; nếu revoke thắng thì accept trả unavailable. Cả hai không được cùng thành công theo hai ý nghĩa trái nhau.
- Accept cạnh tranh archive: archive thắng thì accept bị từ chối; accept thắng trước thì archive tiếp theo vẫn thu hồi toàn bộ quyền.
- Transfer cạnh tranh leave/remove: thứ tự khóa bảo đảm không có thời điểm commit với owner ngoài membership. Chủ mới không thể rời hoặc bị xóa như thành viên thường.
- Accept lặp trả kết quả hiện tại nếu invitation accepted và actor vẫn là thành viên; sau khi đã rời/bị xóa phải báo unavailable, không tái thêm từ invitation cũ. Decline/revoke lặp cùng trạng thái thành công không đổi dữ liệu; hành động khác với trạng thái terminal báo conflict.
- Create dùng unique request key để chống tạo trùng khi mất phản hồi; không chỉ disable nút trên UI. Lookup request đã có trước khi validate lại môn active; retry sau khi môn bị vô hiệu hóa vẫn trả nhóm cũ nếu actor còn membership. Creator đã rời/bị xóa không được đọc nhóm qua retry: trả unavailable, không tạo nhóm thay thế. Các thao tác leave/remove/transfer không giả định retry vô điều kiện thành công: khi quyền/trạng thái đã đổi, trả trạng thái phù hợp và UI reload.

Lỗi domain: unauthenticated, account_unavailable, group_unavailable, forbidden, validation_failed, subject_unavailable, not_friends, already_member, invitation_unavailable, state_conflict, owner_transfer_required. Với caller ngoài nhóm, dùng unavailable để hạn chế dò ID; chỉ trả lỗi chi tiết khi caller đã có quyền biết thực thể. SafeApiCall map mã ổn định, không hiển thị SQL/stack trace; preserve coroutine cancellation.

### 6. Android và làm mới trạng thái

`Group.kt` giữ entrypoint của Home, có Nhóm của tôi và Lời mời; `CreateGroupFragment`, `GroupDetailFragment`, `GroupMembersFragment` cùng ViewModel/UiState trong package group. Bộ chọn bạn bè có phân trang, ẩn thành viên và pending đã biết, server vẫn kiểm lại. Tái sử dụng Home.openDetail/closeDetail; không áp dụng navigation từ proposal chat cũ nếu không khớp code thực tế.

GroupRepository/GroupRemoteDataSource nằm trong backend; thêm model GroupSummary/GroupDetail/GroupMember/GroupInvitation/GroupPage và domain errors/Operation phù hợp. ViewModel giữ draft và request key qua SavedStateHandle, không giữ View/Activity. Không xóa form khi lỗi; tách lỗi mutation và tải danh sách. Có loading, empty, content, error/retry và trạng thái đang gửi cho từng thao tác.

Tải lại khi vào màn hình/onResume, kéo refresh và sau mutation. Khi RPC báo mất quyền, xóa dữ liệu nhóm khỏi UI rồi quay về danh sách. Danh sách/chi tiết nhóm không lưu cache xuống đĩa; PDF renderer và draft annotation có cache riêng theo account/version, áp dụng cleanup ở phần tài liệu. Khi đổi tài khoản/logout, hủy request cũ, xóa state, không nhận kết quả của phiên trước. Client đang mở màn hình có thể còn thấy dữ liệu đã tải cho tới lần refresh; quyền đọc server bị chặn ngay với request mới, không hứa thu hồi tức thì màn hình offline. Nút chuyển quyền, xóa thành viên và giải tán có xác nhận rõ đối tượng/tác động.

### 7. Gọi nhóm bằng LiveKit Cloud và server điều khiển

Chọn LiveKit Cloud Build cho demo, tránh tự vận hành SFU/TURN. Tự host là phương án khác cần kế hoạch vận hành và thu hồi token riêng, không chuyển đổi ngầm trong change này. Supabase lưu quyền/trạng thái/tài liệu, LiveKit chỉ vận chuyển audio/video camera. Kotlin `backend` vẫn là thư viện client; thêm Supabase Edge Functions chạy phía server cho secret và LiveKit Server API. Pin SDK Android và server SDK tương thích sau spike compile với minSdk 24/targetSdk 37, không chốt phiên bản bằng phỏng đoán.

```text
GroupCallFragment → GroupCallViewModel → CallSessionManager + foreground service
        │                                  └── LiveKit Android SDK ↔ LiveKit Cloud
        └── GroupCallRepository → Edge Functions → Postgres authorization
                                          └── LiveKit Server API
LiveKit signed webhook → server reconciliation → call state/control jobs
```

App-user sở hữu media session/renderer, foreground service và PDF viewer/annotation overlay để phù hợp Android lifecycle; backend sở hữu DTO, gọi HTTP/RPC và error mapping. Một app instance chỉ duy trì một cuộc gọi tại một thời điểm. Không đưa đối tượng Room/Activity vào repository dữ liệu hoặc lưu join token vào đĩa/log.

Mọi thành viên hợp lệ có thể bắt đầu hoặc vào cuộc gọi. `initial_mode=audio|video` chỉ chọn trạng thái ban đầu; camera mặc định tắt cho audio và chỉ bật khi người dùng chủ động đồng ý. Mute/unmute, camera on/off, đổi camera, chọn audio route, participant grid và reconnect nằm trong phạm vi. Người khởi tạo (nếu vẫn là thành viên) và trưởng nhóm hiện tại có thể kết thúc cho tất cả; người khác chỉ rời cuộc gọi. Người khởi tạo rời cuộc gọi không tự kết thúc khi còn người khác. Khởi tạo cuộc gọi khi ứng dụng đang hiển thị; không bật capture từ thông báo nền không có thao tác người dùng.

### 8. Trạng thái phiên gọi và tính nhất quán hai hệ thống

Bổ sung `study_group_calls(id, group_id, started_by, initial_mode, status, provider_room_name, provider_room_sid, version, created_at, ended_at, empty_since)`, với status starting/active/ending/ended/failed. Partial unique group_id cho starting/active/ending bảo đảm một phiên chưa đóng mỗi nhóm. Room dùng UUID riêng cho từng phiên, không tái dùng tên theo group_id. Mặc định demo tối đa 6 participants/room; đây là giới hạn sản phẩm đề xuất, không phải hạn mức Cloud.

Thêm admission records theo call/user để lưu identity UUID, token issued-at/expiry và membership generation; không lưu token thô. Reservations có TTL 60 giây, được tính vào capacity để tránh cấp dư suất; LiveKit room maxParticipants là lớp bảo vệ bổ sung. Cùng user/room chỉ có một admission đang hiệu lực; trả lỗi đang dùng thiết bị khác khi có phiên hiện hữu, không tự giành kết nối. Client session ID phân biệt retry với thiết bị mới; membership generation đổi khi remove/rejoin để retry cũ không khôi phục quyền.

Thêm private control jobs (revoke participant, end room) và webhook deduplication theo event ID. App không có quyền ghi/đọc trực tiếp các bảng điều khiển, admission hoặc secret. Lock theo group → call → admission; mutation DB và thêm job nằm cùng transaction. Không gọi provider qua mạng trong transaction DB và không giả định Postgres/LiveKit commit nguyên tử. Job thu hồi được đánh thức ngay sau commit và có bộ quét dự phòng tối đa mỗi 5 giây, tách khỏi reconciliation phòng mỗi 30 giây; phải kiểm chứng cơ chế dispatch/scheduler triển khai thực đáp ứng mục tiêu 10 giây, không dựa vào cron mỗi phút hoặc chỉ webhook từ client.

Các Edge endpoint dự kiến: `start-or-join-group-call`, `get-group-call`, `leave-group-call`, `end-group-call`; endpoint riêng cho signed webhook và worker server-only. Xác thực Supabase JWT thực sự, lấy actor từ session, DB kiểm membership/account/active group; không tin user_id hoặc room do client gửi. Endpoint token chỉ ký grant sau admission được kiểm tra, recheck version trước trả kết quả. Service-role chỉ dùng thao tác nội bộ đã được phân quyền; không biến client input thành RPC service-role tùy ý.

Khởi tạo có request key, concurrent start trả cùng phiên; provider create thất bại phải reconcile/retry theo room UUID, không sinh phòng song song. Join trả domain error rõ khi đầy phòng/quota/lỗi provider. Get-call refresh mỗi 10 giây chỉ khi group detail đang STARTED, cộng refresh sau thao tác; trong phòng dùng SDK events. Trạng thái active được xác nhận qua provider, không chỉ vì token đã cấp. Phòng không có ai sau 60 giây được đóng bởi worker; webhook mất/trễ được bù bằng reconciliation mỗi 30 giây. Event cũ không được chuyển ended → active hoặc làm thay đổi phiên mới: kiểm call ID, room SID/version và trạng thái hiện tại.

End-call đặt ending để chặn join trước khi gọi provider. Worker thu hồi mọi identity đã được cấp token, disconnect participants rồi đóng phòng; chỉ đánh dấu ended khi xác nhận provider đã xử lý. Request thất bại giữ ending với retry/backoff và trạng thái UI tương ứng. Cần kiểm thử cached token không thể dựng lại phòng vừa đóng; giữ tombstone/cleanup theo token horizon, không coi DeleteRoom đơn lẻ là đủ thu hồi.

### 9. Token và thu hồi quyền media

Join token chỉ cho đúng room/identity, subscribe và nguồn microphone/camera, không roomAdmin/roomCreate/record/screen_share/screen_share_audio. TTL đề xuất 5 phút. Cloud tự refresh token khi đang kết nối nên TTL không thay kick/revocation. Remove/leave group, ban hoặc archive chặn token mới và ghi revocation job trong transaction. Worker dùng RemoveParticipant với explicit cutoff bao phủ token đã phát/refresh; kiểm current membership generation và không cấp lại admission cũ. Kiểm chứng contract cutoff/TTL bằng SDK đã pin. [LiveKit tokens](https://docs.livekit.io/frontends/reference/tokens-grants/)

Không hứa media ngắt nguyên tử với DB commit: mục tiêu 10 giây khi provider/worker khỏe. Provider lỗi thì giữ job retry, báo đang thu hồi và chặn join phiên bị ảnh hưởng đến khi reconcile. Token đang ký khi membership bị xóa phải được phát hiện/thu hồi theo version mới. Ban account cần enqueue từ admin/DB trigger. Thu hồi đọc/ghi tài liệu là lớp DB/Storage riêng, không chờ LiveKit.

### 10. Tài liệu nhóm và phiên bản PDF bất biến

PDF thuộc group, không thuộc call; mọi thành viên hợp lệ đọc/upload/ghi chú cả ngoài meeting. Uploader hoặc owner được upload version mới/archive tài liệu. Archive có xác nhận, khóa truy cập nhưng không xóa vật lý ngay. Mặc định đề xuất 20 MiB/file, 100 trang, 10.000 annotation/version; từ chối PDF hỏng/mã hóa/vượt giới hạn với lỗi rõ. Kiểm MIME, signature, parser/page count và tài nguyên; không tin đuôi file. Parser không chạy embedded script/attachment hoặc tự mở link; xử lý file không tin cậy trong worker/process cô lập khi khả dụng.

| Dữ liệu | Trách nhiệm |
| --- | --- |
| group_documents | group_id, title, uploaded_by, current_version_id, archived_at |
| group_document_versions | immutable ID, object_key, content_hash, size, pages, canonical page dimensions/rotation, validation_status |
| group_document_annotations | UUID, version_id, page_index, kind ink/highlight/note, geometry/style/content, author_id, row_version, document_revision, deleted_at |
| group_document_operations | actor/op_id unique, payload fingerprint, committed result; retry không nhân đôi |
| group_document_events | ordered revision per version, mutation/tombstone phục vụ catch-up |
| group_document_snapshots | immutable manifest tại version/hash/revision, requester/expiry; initial load và export nhất quán |
| group_document_guidance | call_id, guide_id, version_id, page_index, generation/lease; điều hướng, không cấp quyền annotate |

Dùng bucket private mới group-documents và object key UUID bất biến. Không dùng bucket documents/posts công khai hiện có. Download bằng JWT và Storage RLS kiểm current active membership, không phát public URL hay signed download URL dài hạn. RPC/bảng/snapshot/page fetch kiểm group/document active và actor không banned. Không direct DML metadata/annotations; upload chỉ vào reservation, không overwrite ready object. [Supabase private buckets](https://supabase.com/docs/guides/storage/buckets/fundamentals)

Upload: reserve theo request ID → upload private pending object → server kiểm file/hash/pages → finalize recheck quyền → ready. Retry cùng request không tạo bản sao, failure không đưa file pending vào danh sách, worker dọn orphan hết hạn. Không tin client tự khai validated; spike parser server có resource limits, nếu Edge không phù hợp thì dùng validator worker riêng và ghi rõ deployment/dependency. Version mới không mang annotations cũ sang trang mới. Viewer cũ tiếp tục đúng version với thông báo có bản mới; version history giữ bản cũ và annotations riêng.

### 11. Viewer và thao tác ghi chú

Ưu tiên Android PdfRenderer render từng trang, custom overlay cho ink/highlight/note và PdfDocument export qua Canvas; spike trên minSdk 24, không mặc định API annotation mới có trên mọi thiết bị. Các API này không cung cấp collaboration sẵn. [PdfRenderer](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer), [PdfDocument](https://developer.android.com/reference/android/graphics/pdf/PdfDocument)

Geometry theo đúng version/page canonical, x/y chuẩn hóa 0..1, độ rộng nét theo cạnh ngắn trang. Cùng transform page-to-view/view-to-page dùng cho zoom, rotation, hit-test và export. Giữ page dimensions/rotation trong metadata. Ink là polyline UUID mỗi nét; highlight là vùng chữ nhật bán trong suốt kéo chọn, không phụ thuộc OCR; note là pin với text tối đa 2.000 ký tự Unicode. Whitelist kiểu/style, tối đa 4.096 điểm/nét và 64 KiB/op; reject page sai, geometry ngoài trang hoặc mismatch version.

Mọi thành viên tạo annotation; author sửa/xóa của mình; owner được sửa/xóa để quản lý, mutation có actor/time/revision. Eraser chọn xóa cả annotation, chưa làm pixel eraser. Undo là mutation mới có expected version, chỉ thao tác của chính mình và không ghi đè thay đổi mới của người khác. Annotation đã commit vẫn còn khi tác giả rời nhóm. Bản gốc không bị sửa.

Viewer có tài liệu/trang, bút/highlight/note/chọn-xóa, trạng thái lưu và call controls thu gọn. Mọi người cùng annotate, không presenter gate. Đọc tự do mặc định; tùy chọn Theo người hướng dẫn đồng bộ tài liệu/trang, không ép zoom. Một guide trong call được claim dưới lock và có lease; rời call/mất quyền thì release. Mọi người tắt follow được; guide không khóa quyền annotate. Kết thúc meeting chỉ kết thúc guidance/media, không xóa tài liệu hoặc notes.

### 12. Đồng bộ, conflict và reconnect

DB là nguồn dữ liệu chính; LiveKit chỉ cho mic/camera. RPC mutation nhận annotation UUID/op_id/expected_row_version, actor từ auth; kiểm quyền/payload rồi tăng revision dưới lock version. Commit annotation + operation result + event trong cùng transaction. Broadcast private sau commit chỉ gửi invalidation tối thiểu; client fetch delta/snapshot được kiểm quyền. [Database changes](https://supabase.com/docs/guides/realtime/subscribing-to-database-changes)

Hai nét khác UUID độc lập. Hai lần sửa cùng note compare-and-set row_version: request sau conflict, giữ draft và trả bản hiện tại nếu còn quyền; không silent last-write-wins. Delete là tombstone. Retry cùng op_id/payload trả committed result sau khi kiểm quyền; cùng ID khác payload conflict. UI Đã lưu chỉ sau server ACK, không dựa vào event hoặc optimistic rendering.

Bản đầu đồng bộ khi nhấc bút, hoàn tất vùng highlight hoặc Save note, không stream từng chuyển động/caret. Mục tiêu update tới máy online trong 2 giây khi dịch vụ khỏe; fallback revision check mỗi 5 giây lúc viewer foreground. Initial load lấy immutable snapshot revision, buffer event rồi catch-up revision mới hơn. Reconnect fetch delta phân trang; gặp gap/retention hết thì tải snapshot mới và rebase pending ops. Duplicate/out-of-order không nhân hoặc mất annotation. Snapshot paging cố định manifest, không trộn revision giữa các trang.

Private Realtime authorization không thay DB/Storage permissions. Invalidation không mang text/geometry/file URL. Membership đổi thì tăng group channel epoch trong transaction, chỉ phát topic mới; người còn quyền lấy lại topic khi refresh, người bị xóa không fetch được dữ liệu. Xóa state/cache khi client biết mất quyền. Không thể thu hồi bản người dùng đã export ra ngoài trước đó.

Pending ops/draft lưu app-private theo user/version, không kèm token. Offline edit hiện Chưa đồng bộ, reconnect kiểm lại quyền/version rồi gửi op_id cũ theo thứ tự. Conflict giữ draft để xử lý; access denied dừng retry và khóa editor. Logout/account switch xóa pending/cache account cũ, cảnh báo trước thao tác chủ động bỏ draft chưa lưu. Test crash sau DB commit trước ACK, logout giữa download và reconnect sau kick.

### 13. Xuất PDF có đánh dấu

Người có quyền đọc được export. Chỉ xuất committed data: nếu còn pending/conflict, cho đồng bộ trước hoặc chủ động chọn chỉ xuất bản đã lưu. Server tạo snapshot immutable exact version/hash/revision trong một transaction; mọi trang của export đọc cùng manifest. Recheck quyền khi fetch và trước công bố file; revoke giữa export thì cancel, dọn file tạm và không báo thành công.

Bản đầu render nền PDF từng trang thành bitmap có giới hạn, vẽ overlay cùng transform vào PdfDocument, rồi lưu bản mới qua Storage Access Framework. Ink/highlight đúng vị trí; note pin đánh số trên trang và phụ lục gồm số trang/tác giả/toàn bộ text, wrap và phân trang không cắt chữ. Ghi version/revision/thời điểm trong metadata xuất. Không overwrite original. Bản phẳng mở được bằng viewer ngoài, không giữ annotation editable/searchable text/form/signature gốc; original và annotations editable vẫn còn trong StudyHub. Mục tiêu 150–200 dpi tùy trang với trần bitmap, xử lý off main thread từng trang, progress/cancel/cleanup; kiểm bộ nhớ thực cả PdfDocument lẫn bitmap, không suy ra output memory bounded chỉ vì render từng trang. Nếu gần giới hạn vượt budget, fail rõ hoặc chọn streaming export engine qua review, không crash hay tự thêm license.

Nghiệm thu mở PDF bằng viewer ngoài, đối chiếu đủ trang, note appendix, tiếng Việt, transparency và rotation. Nếu sau này cần bảo toàn text/form trong bản xuất, đánh giá PDF editing engine riêng; không hứa bản phẳng đáp ứng việc đó.

### 14. Quota và nghiệm thu

LiveKit phục vụ voice/video; PDF dùng Storage/DB/Realtime riêng và không truyền như screen video. Đo Usage cả hai dịch vụ, không cam kết quota miễn phí tổng hợp hoặc tự nâng gói. Giữ đề xuất 6 người/call và camera adaptive 360p/480p; người khác trong nhóm vẫn đọc/ghi PDF, không chịu cap media.

Thiếu project/secret: local SQL, fake-provider và render/export vẫn kiểm được, nhưng media/realtime hosted chưa verified. Nghiệm thu ba tài khoản/thiết bị cùng gọi: A vẽ/B highlight/C note, nhận realtime, end call, mở lại, reconnect/catch-up và xuất PDF khớp snapshot. Test quyền file/annotation/export; build/fake không chứng minh media/collaboration thật.

## Risks / Trade-offs

- [Các mặc định sản phẩm chưa được xác nhận riêng] → Ghi rõ ở proposal và Open Questions; review trong lần duyệt change, không mô tả là lựa chọn người dùng đã chốt.
- [Nhóm và chat tách biệt] → Chi phí liên kết về sau, nhưng không nhân đôi membership ở bản đầu.
- [Trưởng nhóm bị banned] → Deny truy cập của tài khoản đó, không tự trao quyền; thành viên khác vẫn đọc/rời được. Khôi phục quyền bởi admin là phạm vi khác; báo rõ giới hạn vận hành.
- [FK RESTRICT chặn xóa profile/môn học liên quan] → Kiểm tra luồng admin trước apply; không thay bằng cascade có thể xóa nhóm hoặc làm mất owner. Vô hiệu hóa môn/tài khoản vẫn dùng được theo quy định trên.
- [Danh sách nhóm không Realtime] → Refresh và lỗi quyền có kiểm soát; trạng thái gọi refresh định kỳ, media participants cập nhật qua LiveKit events.
- [DB và LiveKit không có transaction chung] → Admission/version, durable control jobs, webhook deduplication và reconciliation; không nhầm DB commit với media đã ngắt.
- [PDF lớn hoặc lệch geometry] → Giới hạn tài nguyên, canonical coordinates và kiểm visual đa thiết bị/export.
- [Export phẳng không giữ text/form gốc] → Ghi rõ ở thao tác export và giữ original riêng; kiểm độ rõ và Unicode.
- [Mất quyền khi client có cache] → Chặn DB/Storage ngay, epoch kênh/cleanup local; file đã export ngoài app không thể thu hồi.
- [Quota/provider/SDK thay đổi] → Pin dependency, đo Usage, lỗi quota có thể phục hồi; xác minh grant/revocation thực trên Cloud trước khi gọi hoàn tất.
- [RPC definer có thể vượt RLS] → Kiểm tra quyền bên trong từng RPC, review grants/projections và test đa danh tính bằng role authenticated, không chỉ gọi bằng owner DB.
- [Local schema không đồng nhất với migration/hosted] → Test migration mới trên DB local có baseline đã kiểm chứng; đối chiếu hosted khi triển khai được giao. Không sửa chat hoặc phần cleanup ngoài phạm vi để làm proposal này.

## Migration Plan

1. Review các mặc định, quyền và tiêu chí nghiệm thu; kiểm tra baseline schema/grants cùng thứ tự migration thực tế ở local. Không đọc credentials vào báo cáo.
2. Viết migration additive cho nhóm/membership/invitations và call/admission/control jobs, documents/versions/annotations/revisions/snapshots cùng RPC và private Storage; test áp dụng mới và chạy lại nếu migration thiết kế idempotent. Không backfill từ conversations vì chưa có liên kết nghiệp vụ được xác nhận.
3. Đồng bộ phần nhóm trong schema snapshot, giữ diff cleanup bài viết/bình luận đang có. Chạy SQL integration/concurrency bằng DB disposable và Kotlin/build checks.
4. Khi được giao triển khai hosted, cấu hình LiveKit Cloud và secrets phía Edge Functions, webhook có xác minh chữ ký, worker/reconciliation; áp dụng DB và server trước Android. Xác minh RPC/grants, private Storage/Realtime, call + collaborative PDF và export bằng ba tài khoản test. Khi chưa làm bước này ghi rõ local/fake-only và media thật chưa kiểm chứng.
5. Nếu rollout lỗi: khóa start/join mới, thu hồi/ngắt các media session bằng server, sau đó rollback client; giữ DB và dữ liệu mới. Có thể revoke RPC mới để tạm ngừng truy cập. Ưu tiên forward-fix, không drop bảng có dữ liệu người dùng và không coi rollback UI là rollback DB/media.

## Open Questions

- Các mặc định để review đã có đáp án đề xuất: một môn cố định mỗi nhóm; chỉ mời bạn bè tại lúc gửi; không hết hạn tự động; chỉ trưởng nhóm mời; nhóm có thể bắt đầu với một người. Không còn câu hỏi bắt buộc để tạo artifact.
- Mặc định gọi được đề xuất: tối đa 6 người/phiên cho demo, mọi thành viên được bắt đầu gọi, owner hoặc người khởi tạo còn membership được end-for-all, PDF có đọc tự do/theo guide tùy chọn. Người dùng đã chốt PDF annotation/export thay screen Present; không gồm Word/PowerPoint editor.
- Cần LiveKit project/credentials phía server khi triển khai và xác nhận version SDK tương thích targetSdk 37; chưa có các bằng chứng đó trong lần cập nhật kế hoạch.
- Trước triển khai từ xa cần xác nhận baseline hosted và luồng quản trị xóa profile/môn học; hiện chỉ có bằng chứng local. Đây là kiểm tra môi trường/compatibility, không phải lý do để coi implementation đã được kiểm chứng.
