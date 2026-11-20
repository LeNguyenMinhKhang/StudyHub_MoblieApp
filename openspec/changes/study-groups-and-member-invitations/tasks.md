## 1. Xác nhận contract và baseline

- [x] 1.1 Đối chiếu scope đã chốt: nhóm/membership + gọi thoại/video + PDF cộng tác (bút, highlight vùng, note, realtime, lưu lại, xuất PDF), thay screen sharing. Review mặc định còn lại: một môn, owner mời bạn bè, 6 người/call, quyền author/owner, file 20 MiB/100 trang, export phẳng và optional follow guide.
- [x] 1.2 Kiểm tra schema/migration local, SubjectRepository, FriendshipRepository, Home navigation, ApiResponse/SafeApiCall và luồng admin xóa profile/môn; xác nhận tác động FK RESTRICT. Giữ nguyên diff cleanup hiện có của `supabase/schema.sql` và ghi rõ hosted chưa được kiểm tra nếu chưa có quyền triển khai.
- [x] 1.3 Chốt chữ ký RPC, payload/projection DTO, mã lỗi và cursor theo bảng contract trong design; kiểm tra test libraries/toolchain hiện có, không thêm dependency ngoài phạm vi.

## 2. Schema và quyền phía server

- [x] 2.1 Thêm migration timestamp mới trong `supabase/migrations/` cho study_groups, study_group_members, study_group_invitations; FK, owner-membership deferred constraint, unique request key, immutable creation payload, partial unique pending invitation và index phân trang.
- [x] 2.2 Bật RLS, revoke direct SELECT/DML cho app và EXECUTE mặc định khỏi PUBLIC/anon; triển khai helper xác thực actor/nhóm/quyền nội bộ với search_path an toàn. Kiểm tra grants thực tế bằng authenticated và anon.
- [x] 2.3 Triển khai create_study_group nguyên tử với validation Unicode, môn active, idempotency dựa trên payload gốc; retry không vượt quyền của creator đã rời nhóm và không tạo lại nhóm archived.
- [x] 2.4 Triển khai list_my_study_groups/get_study_group/update_study_group và list_study_group_members với projection tối thiểu, count đúng, cursor ổn định và quyền active membership.
- [x] 2.5 Triển khai gửi lời mời và hai danh sách pending cho owner/invitee, kiểm tra accepted friendship và unique pending; hỗ trợ gửi lại lời mời mới sau declined/revoked.
- [x] 2.6 Triển khai accept/decline/revoke theo state machine; khóa nhóm rồi invitation, rollback khi thêm thành viên lỗi, retry terminal an toàn và không tái dùng accepted invitation để khôi phục membership đã mất.
- [x] 2.7 Triển khai leave/remove/transfer với cùng thứ tự khóa; ngăn mất owner, kiểm tra successor và chuyển quyền quản lý pending invitations cho owner hiện tại.
- [x] 2.8 Triển khai archive nguyên tử cùng revoke pending; mọi read/mutation sau đó từ chối nhóm archived. Đồng bộ phần nhóm vào schema snapshot, bảo toàn thay đổi ngoài phạm vi và không đổi các contract chat/notification.

## 3. Kiểm thử SQL theo requirement

- [x] 3.1 Tạo fixture và runner cho DB disposable trong `supabase/tests/study_groups/`, với owner, member, invitee, outsider, banned và anon; ghi rõ baseline cần thiết và cơ chế cleanup, từ chối target production.
- [x] 3.2 Test tạo nhóm hợp lệ/invalid, rollback lỗi membership, duplicate key/payload conflict, retry sau metadata/môn/membership thay đổi, nhóm archived và FK bảo vệ owner/profile/subject.
- [x] 3.3 Test owner/member/outsider reads, projection preview, cấm direct table access và helper execution, forged actor, banned accounts, quyền sửa tên/mô tả, pagination nhiều trang/timestamp trùng/cursor lỗi/limit.
- [x] 3.4 Test gửi/nhận/decline/revoke/reinvite, self/member/non-friend/banned targets, friendship bị xóa sau gửi, counts không tính pending, sai người nhận, terminal retries và accepted invitation cũ sau leave/remove.
- [x] 3.5 Test leave/remove/transfer/archive, owner duy nhất, successor invalid, quyền của inviter cũ sau transfer, owner bị banned, pending invitations sau chuyển quyền và dữ liệu archived không truy cập được.
- [x] 3.6 Chạy concurrency tests bằng hai DB sessions có phối hợp thứ tự commit: create trùng, invite trùng, accept trùng, accept/revoke cả hai thứ tự, accept/archive, transfer/leave hoặc remove, invite/unfriend; assert bất biến và kết quả cuối, không chỉ mã trả về.
- [x] 3.7 Xác minh migration chạy trên baseline local, payload RPC và không sinh conversations/notification events; ghi log kết quả thật và lỗi môi trường nếu có, không suy ra hosted đã pass.

## 4. Kotlin data layer

- [x] 4.1 Thêm model GroupSummary/GroupDetail/GroupMember/GroupInvitation và page/cursor/request trong `backend/.../core/data/model/`; kiểm thử SerialName/nullability bằng payload RPC thực tế lấy từ DB local.
- [x] 4.2 Thêm GroupRemoteDataSource cho toàn bộ RPC và GroupRepository dùng ApiResponse/SafeApiCall; xác thực UUID/input, giữ cancellation, map lỗi domain, không nhận kết quả từ phiên đăng nhập cũ.
- [x] 4.3 Bổ sung Operation/ErrorType/ExceptionMapper cần thiết; test validation, domain errors, request key, session switch và không lộ SQL/thông tin riêng qua lỗi.

## 5. Android nhóm và lời mời

- [x] 5.1 Thay placeholder `Group.kt` / `fragment_group.xml` bằng Nhóm của tôi và Lời mời, RecyclerView/adapter, pagination, loading/empty/error/retry và pull-to-refresh; dùng ViewBinding và collection theo viewLifecycleOwner.
- [x] 5.2 Thêm CreateGroupFragment/ViewModel/layout: chọn môn active từ SubjectRepository, tên/mô tả validation, SavedStateHandle giữ draft/request key, chống gửi trùng, xử lý mạng lỗi và mở chi tiết sau thành công.
- [x] 5.3 Thêm GroupDetailFragment/ViewModel/layout: thông tin môn, số thành viên/vai trò, sửa tên/mô tả theo quyền, điều hướng bằng Home.openDetail/closeDetail và refresh khi resume.
- [x] 5.4 Thêm GroupMembersFragment/ViewModel/layout và bộ chọn bạn bè có phân trang từ FriendshipRepository; hiển thị owner/member, gửi lời mời, danh sách pending của owner và thu hồi với trạng thái thao tác riêng.
- [x] 5.5 Kết nối preview incoming invitations và accept/decline; thành công cập nhật danh sách nhóm/lời mời, lỗi stale/revoked reload trạng thái và không mở nhóm trái quyền.
- [x] 5.6 Kết nối leave/remove/transfer/archive với xác nhận UI và thông báo kết quả rõ ràng; xóa private state khi mất quyền/logout/đổi tài khoản; chuyển về danh sách khi nhóm không còn truy cập được.
- [x] 5.7 Test ViewModel cho form restoration/retry, loading-vs-error, pagination, double tap, stale invitation, role thay đổi, mất quyền và kết quả phiên cũ; kiểm tra strings/accessibility và lifecycle cleanup.

## 6. Server cuộc gọi LiveKit

- [x] 6.1 Spike/pin LiveKit Android/server SDK tương thích minSdk 24/targetSdk 37, audio routing, explicit token revocation cutoff và foreground microphone/camera service; không thêm MediaProjection hoặc screen track grants.
- [x] 6.2 Thêm schema/RPC calls/admissions/control jobs/webhook deduplication: unique open call, request key, capacity reservations, membership generation, lock ordering và quyền server-only; đồng bộ snapshot có chọn lọc.
- [x] 6.3 Thêm Edge endpoints start/join/get/leave/end với JWT verification, quyền DB, opaque identity/room, secrets server-only, TTL/grants microphone/camera và admission recheck; test concurrent start/duplicate session/full room.
- [x] 6.4 Triển khai provider worker, signed webhook, durable retry/reconciliation/empty room cleanup, ending trước provider shutdown; xác minh event cũ không mở lại ended session.
- [x] 6.5 Gắn remove/leave/ban/archive vào revocation jobs cùng transaction, xử lý late-issued/refreshed tokens, cutoff và stale generation; xác minh cached token không dựng lại phòng đã kết thúc, dispatcher đáp ứng mục tiêu 10 giây khi khỏe.
- [x] 6.6 Thêm GroupCall DTO/remote/repository và lỗi quota/capacity/provider/pending cleanup; không lưu token/secret vào app hoặc log.

## 7. Android gọi thoại video

- [x] 7.1 Thêm LiveKit đã pin vào version catalog/app-user, manifest mic/camera/audio route/foreground service cần thiết và permission flow độc lập; không xin quyền chia sẻ màn hình.
- [x] 7.2 Thêm CallSessionManager/service: giữ session qua rotation và mở in-app PDF, foreground mic khi background, pause camera, notification return/leave; giải phóng capture/tracks khi logout hoặc disconnect cuối.
- [x] 7.3 Thêm GroupCallFragment/ViewModel/layout với participant grid, mic/camera/switch camera/audio route, initial mode, leave/end-for-all; denied camera vẫn thoại, denied mic vẫn nghe/xem.
- [x] 7.4 Thêm active-call banner và start/join trong GroupDetail, refresh 10 giây khi STARTED, trạng thái starting/active/ending/error và chống start trùng; không thêm background ringing/notification center.
- [x] 7.5 Test lifecycle/media state với rotation, network reconnect, permission denied, stale session, account switch, quota error và mở editor PDF; không nhân session hoặc leak resource.

## 8. Kiểm thử cuộc gọi

- [x] 8.1 SQL/Edge/fake-provider tests cho auth/grants, concurrency/capacity/admission timeout, token retry, duplicate/out-of-order webhook, provider failures và terminal-state invariants.
- [x] 8.2 Test races token issuance/remove/ban/archive/transfer, retry revoke sau rejoin và late callbacks; failures giữ pending và chặn admission đúng, không báo thu hồi xong khi chưa có provider confirmation.
- [x] 8.3 Khi có LiveKit test project/credentials, kiểm tra bằng provider thật: outsiders/pending invitees không join, screen sources bị chặn, removed users không reconnect bằng cached/refreshed token, ended room không tái tạo; ghi blocker nếu chưa chạy.
- [x] 8.4 Demo ba thiết bị/tài khoản: thoại/video/camera toggle/audio route, khác mạng, background/reconnect/rotation, quyền end-for-all; đo revocation latency và Usage/quota. Không dùng fake/build làm bằng chứng media thật.

## 9. Backend tài liệu và annotation

- [x] 9.1 Spike native PdfRenderer/PdfDocument với minSdk, tài liệu scan/vector/rotation/Unicode và export trong memory budget; đồng thời chọn server PDF validation parser/runtime có giới hạn tài nguyên, ghi quyết định/dependency/license phù hợp thay vì tin client page_count/hash.
- [x] 9.2 Thêm migration cho documents/immutable versions/annotations/operations/events/snapshots/guidance; private group-documents bucket và Storage RLS/reservation rules, không reuse public documents/posts. Test direct object-key và API access bằng outsider/member/owner/banned.
- [x] 9.3 Thêm reserve/upload/validate/finalize/version-history/archive workflow có request idempotency, server file/hash/page validation, size/page limit, orphan cleanup và recheck quyền trước finalize; không overwrite ready source.
- [x] 9.4 Thêm annotation mutation RPC với geometry/style/text/payload limits, author/owner permissions, CAS row_version, tombstones, operation fingerprints và document revision/event trong transaction; xử lý undo như mutation có kiểm quyền.
- [x] 9.5 Thêm consistent snapshot/paginated delta/export snapshot APIs và private invalidation subscription; channel epoch rotation khi membership đổi, catch-up gap fallback, không broadcast note text/geometry/file URL cho obsolete subscriptions.
- [x] 9.6 Thêm guidance claim/update/release/lease theo call/member, không cấp quyền annotate độc quyền; end-call/mất quyền chỉ dọn guidance, không xóa annotations/documents.
- [x] 9.7 Thêm GroupDocument/DocumentVersion/Annotation/Operation/Snapshot DTO và remote/repository trong backend, lỗi validation/conflict/access/retry; test decode payload DB thật và user-session isolation.

## 10. Android PDF editor và export

- [x] 10.1 Thêm document list/upload/version history/archive UI trong group và in-app PDF viewer có page navigation/zoom/rotation, render off main thread theo trang, đóng renderer/file descriptor và quản lý cache theo account/version.
- [x] 10.2 Thêm canonical page transforms và overlay ink/highlight vùng/note pin, bộ chọn style, select-delete, sửa note/undo có quyền; validate 4.096 points/64 KiB op/2.000 Unicode chars/10.000 annotations đúng contract.
- [x] 10.3 Thêm local pending journal app-private theo account/version, operation UUID bền qua retry/crash, trạng thái saving/saved/unsynced/conflict, giữ draft conflict và CAS retry do người dùng quyết định; logout/mất quyền dừng replay và cleanup.
- [x] 10.4 Kết nối snapshot + buffered realtime + delta catch-up, fallback revision polling 5 giây khi foreground, xử lý duplicates/out-of-order/gaps và initial paging race; sync nét khi nhấc bút/note Save, không phụ thuộc LiveKit.
- [x] 10.5 Ghép PDF editor với call controls thu gọn; independent reading mặc định và optional follow-guide page/version không ép zoom; cho phép nhiều người annotate cùng lúc, chuyển version có thông báo và không dịch annotations cũ.
- [x] 10.6 Triển khai export committed snapshot thành PDF phẳng mới: render trang + overlay đúng transform, numbered note pins và phụ lục toàn bộ note/author/page, Unicode wrap/pagination, version/revision metadata; không overwrite original.
- [x] 10.7 Export có pending-work choice, progress/cancel, memory limits và Storage Access Framework; recheck quyền trước công bố, cleanup temp/partial URI khi fail/cancel; UI nêu giới hạn searchable text/form/signature của bản phẳng.

## 11. Kiểm thử collaboration và PDF

- [x] 11.1 SQL/Storage tests cho pending upload/finalize retry/quyền bị thu hồi, malformed/encrypted/over-limit files, immutable versions/hash, author/owner annotation permissions, archive/history và annotations của tác giả đã rời nhóm.
- [x] 11.2 Concurrency tests hai writer độc lập, cùng note CAS, delete-vs-edit/undo, duplicate op/changed payload, crash sau commit trước ACK, snapshot paging cùng mutation, export revision cố định; assert không mất/nhân dữ liệu.
- [x] 11.3 Realtime/session tests cho late join, missed/duplicate/out-of-order events, reconnect/gap snapshot fallback, channel epoch khi remove, queued offline edits sau ban, logout/account switch và document access không phụ thuộc cap cuộc gọi.
- [x] 11.4 Visual tests cùng PDF scan/vector/mixed page sizes/rotation trên nhiều màn hình/zoom: ink và highlight đúng vị trí/opacity, note tiếng Việt/emoji/dài; đối chiếu trang render và export ở viewer độc lập, không chỉ kiểm file tồn tại.
- [x] 11.5 Test export nguyên file gần 20 MiB/100 trang với annotations/note appendix: memory/time/progress/cancel, không cắt text, đúng snapshot, denied mid-export cleanup; đo cả PdfDocument memory và bitmap, không chỉ unit test transform.
- [x] 11.6 Demo ba tài khoản cùng gọi: A vẽ, B highlight, C note, sửa/xóa đúng quyền, conflict cùng note, follow guide/đọc tự do, mất mạng/reconnect, end meeting/mở lại tài liệu/export. Ghi latency realtime mục tiêu 2 giây khi khỏe và xác minh persistence sau app restart.

## 12. Xác minh và bàn giao

- [x] 12.1 Chạy SQL/Edge/Kotlin tests liên quan và `gradlew.bat :backend:compileDebugKotlin :app-user:assembleDebug`; ghi kết quả/blocker riêng cho local/fake/hosted/provider/device/PDF export.
- [x] 12.2 Smoke test nhóm đầy đủ (create/invite/accept/leave/reinvite/transfer/archive) với call và documents; regression chọn môn/bạn bè, chat 1-1, notification center, existing post attachments và diff schema ngoài phạm vi.
- [x] 12.3 Map tất cả scenarios của hai spec sang evidence; đo LiveKit minutes/downstream và Supabase Storage/Realtime riêng, không tự recording/nâng gói; ghi SDK/parser versions và giới hạn export đã kiểm chứng.
- [x] 12.4 Chạy strict OpenSpec validation và git diff --check, review grants/DTO/secret/dependencies/scope; bàn giao rollout/rollback DB+Storage+Edge+media, chỉ báo hosted verified nếu thực sự chạy; không tự archive change.
