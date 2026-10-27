## 1. Đối chiếu baseline và chuẩn bị kiểm chứng database

- [x] 1.1 Đọc lại proposal/design/hai spec và kiểm tra diff hiện tại của `ui/profile`, `common`, avatar; xác định các điểm tích hợp để giữ nguyên thay đổi đang có.
- [x] 1.2 Đối chiếu schema, policies, grants, RPC, FK và migration history trên database test được phép với `supabase/schema.sql` và migrations; kiểm tra self-row, cặp đảo chiều và consumer friendships ngoài repo. Ghi kết quả thực tế vào change; nếu dữ liệu vi phạm thì cập nhật kế hoạch xử lý trước khi áp constraints, không tự xóa/gộp.
- [x] 1.3 Xác định runner SQL/disposable database và cách tạo session JWT/role A, B, C, anon, banned trong test; repo hiện chưa có Supabase CLI config. Chuẩn bị fixture tổng hợp và hai connection độc lập cho race, không dùng dữ liệu production.

### Hosted deployment and local cleanup — 2026-10-27

- User explicitly requested deploying the friendship migration to hosted Supabase and removing the disposable local database setup.
- Supabase CLI 2.122.0 authenticated and linked to `igajdsukpotsplzjjqls` (StudyHub - Moblie App). Hosted preflight confirmed the expected profiles/blocks/friendships columns, participant cascade FKs, pending/accepted constraint, zero friendship rows, zero self rows and zero duplicate unordered pairs. No friendship RPCs existed before deployment.
- Applied `supabase/migrations/20261027_friendship_rpc.sql` through the linked Management API. Recorded version `20261027`, name `friendship_rpc`, and migration SQL in `supabase_migrations.schema_migrations` in the same transaction.
- Hosted verification confirmed all seven public RPCs, SECURITY DEFINER with empty search_path, authenticated-only execution, no anon execution, no direct anon/authenticated table access, no authenticated access to private helpers, RLS enabled, self check and unordered-pair/page indexes. Friendship rows remained zero.
- Historical local integration/race results behind tasks 1.3, 2.6 and 2.7 are prior-run evidence only. The local-only test runner, bootstrap, config, instructions, logs and startup secrets were removed at user request. No synthetic-data or race suite was run against the hosted database. Android end-to-end behavior remains unverified.
- Stopped `studyhub-disposable` with `--no-backup` to remove its containers/data volumes. Kept migration files, schema snapshot and ignored hosted CLI link metadata. Docker itself and unrelated project resources are outside cleanup scope.
- Historical migration versions in this checkout differ from the hosted history (`20261018174535`, `20261026034635`). Do not blindly run `db push --include-all`; reconcile historical versions before any future bulk push.
- Existing profile/common/avatar changes remain preserved; external friendship consumers remain unverified. Outstanding migration failure-path/query-plan checks and Android tasks below remain open.

## 2. Database contract, migration và authorization

- [ ] 2.1 Thêm migration timestamp mới trong `supabase/migrations/` với preflight fail-fast trong transaction, check khác chính mình, unique cặp vô hướng và indexes theo actor/status/created_at/id; thử dữ liệu vi phạm để xác nhận migration không sửa dữ liệu một phần.
- [x] 2.2 Tạo `get_friendship_state` và `list_my_friendships` với actor từ auth.uid, filter banned/blocks hai chiều, projection public cố định, ba loại danh sách, cursor đôi, default 20/max 50 và giới hạn input; xác nhận không N+1 và không trả thông tin riêng tư.
- [x] 2.3 Tạo `send_friend_request` với pending-only, self/eligibility guards và unique-conflict handling; gửi lặp/ngược chiều trả state hiện có, không auto-accept.
- [x] 2.4 Tạo `accept_friend_request`, `decline_friend_request`, `cancel_friend_request`, `remove_friendship` theo id, kiểm actor/status dưới row lock; bảo vệ request mới trước action cũ, cho cleanup đúng quyền khi peer không khả dụng và trả snapshot/kết quả lỗi theo design.
- [x] 2.5 Chốt và khai báo mã lỗi máy đọc được cho invalid input, unavailable, permission và stale state; mỗi RPC definer có search_path an toàn, kiểm session, EXECUTE chỉ authenticated, helper không lộ ra client. Revoke table access, bỏ write policies cũ, giữ RLS và đồng bộ phần friendships trong `supabase/schema.sql`.
- [x] 2.6 Thêm/chạy SQL tests dưới `supabase/tests/` với runner ở 1.3 cho ma trận A/B/C/anon/banned: tự kết bạn, giả actor, insert accepted, sửa participants/status, direct SELECT/write, pending/accepted transitions, block hai chiều, peer bị xóa và cleanup; xác minh output không lộ private fields.
- [x] 2.7 Chạy race tests hai connection cho gửi cùng/ngược chiều, accept với cancel/decline, remove đồng thời và action theo id cũ sau gửi mới; kiểm tra đúng số hàng/trạng thái và không có mutation trái quyền.
- [ ] 2.8 Kiểm tra list bằng tối thiểu 45 quan hệ mỗi loại, timestamp trùng, cursor sai, limit thiếu/quá lớn/không hợp lệ và query plan; xác nhận không trùng/mất dòng khi dữ liệu ổn định. Chạy migration trên database test mới và database có schema cũ, ghi rõ kết quả.

## 3. Shared Kotlin data layer

- [ ] 3.1 Thêm model/DTO quan hệ, viewer-relative state, public peer, list kind, cursor và page trong `backend/src/main/java/com/doan/studyhub/core/data/model/`; xác minh serialization khớp RPC và không dùng private `Profile` cho dòng bạn bè.
- [ ] 3.2 Tạo `FriendshipRemoteDataSource.kt` trong `core/data/remote/` bằng Supabase client/RPC hiện có, input có giới hạn và decode đúng output; dùng dependency injection đủ cho fake remote, không thêm SDK.
- [ ] 3.3 Tạo `FriendshipRepository.kt` trong `core/data/repository/` cho state/list/send/accept/decline/cancel/remove với `ApiResponse`, `SafeApiCall`, session provider, UUID validation và structured cancellation.
- [ ] 3.4 Bổ sung operations trong `common/SafeApiCall.kt`, lỗi và mapping theo code trong `ErrorType.kt`/`ExceptionMapper.kt`; xác minh không thay đổi lỗi profile/username/avatar và không đưa thông báo SQL thô lên UI.
- [ ] 3.5 Thêm `FriendshipRepositoryTest.kt` và test DTO/error mapping tại `backend/src/test/` cho session thiếu, self/UUID sai, request direction, conflict/stale/unavailable, cancellation và payload public; các tests không cần database thật.

## 4. State cho danh sách và quan hệ profile

- [ ] 4.1 Thêm `FriendViewModel`/`FriendUiState` trong `app-user/.../ui/friend/` với state/cursor từng tab, load/refresh/load-more, dedupe, per-row mutation, reset các tab liên quan sau thành công và giữ nội dung khi load-more/refresh lỗi.
- [x] 4.2 Thêm `FriendshipViewModel`/state cho social section của profile với các state self/none/outgoing/incoming/friends/unavailable; chặn mutation khi chưa biết state, xử lý lỗi theo hàng và đọc lại sau timeout thay vì tự resend.
- [ ] 4.3 Gắn session identity + generation guard, clear state khi logout/account switch và bỏ response cũ; hỗ trợ refresh khi quay lại màn hình, không cập nhật binding hết lifecycle hay trigger tải posts khi state quan hệ đổi.
- [ ] 4.4 Thêm tests state tại `app-user/src/test/.../ui/friend/` cho mapping actions, giữ dữ liệu khi lỗi, cursor retry/dedupe, concurrent taps, timeout reconciliation, stale response/account switch và self qua explicit profile id; dùng fake repository và test setup hiện có.

## 5. Màn Bạn bè và tích hợp profile

- [x] 5.1 Thay placeholder `Friend.kt`/`fragment_friend.xml` bằng ViewBinding và ba tab Bạn bè/Nhận/Đã gửi; thêm `FriendshipAdapter`, layout dòng, loading/empty/error/retry/refresh/load-more và strings/accessibility labels.
- [x] 5.2 Nối các action accept/decline/cancel/remove với ViewModel; hiển thị progress riêng từng hàng, xác nhận trước remove, mở peer bằng `Home.openDetail(ProfileFragment.forUser(id))` và dùng avatar URL/placeholder hiện có.
- [x] 5.3 Refactor `ProfileLookupDialogFragment.kt` phát Fragment Result chứa profile id; đăng ký consumer trong `Friend` và `ProfileFragment`, thêm Tìm người trên Bạn bè và kiểm tra cả hai entry point vẫn hoạt động với exact username.
- [x] 5.4 Thêm social section vào `fragment_profile.xml` và binding trong `ProfileFragment.kt` theo state; không hiển thị self actions kể cả explicit id, giữ nguyên edit profile/avatar/posts và back-stack của Home.
- [ ] 5.5 Nối lifecycle collection/cleanup và refresh khi quay lại cho friends/profile; kiểm tra view recreation, chuyển tab và quay lại sau mutation trên profile không để danh sách cũ hoặc nhân đôi hàng.

## 6. Xác minh và bàn giao implementation

- [x] 6.1 Chạy `./gradlew.bat :backend:testDebugUnitTest :app-user:testDebugUnitTest :app-user:assembleDebug`; ghi chính xác pass/fail hoặc blocker môi trường, không nâng toolchain để né lỗi ngoài phạm vi.
- [ ] 6.2 Smoke test hai tài khoản trên thiết bị/emulator: tìm người, gửi, nhận, accept, decline, cancel, remove/cancel dialog, gửi lại, tự mở profile, trạng thái hai chiều sau refresh và điều hướng trở về.
- [ ] 6.3 Smoke lỗi mạng/timeout, danh sách rỗng/nhiều trang, account switch, avatar fallback và profile/edit/avatar/posts hồi quy; đối chiếu từng scenario hai spec với bằng chứng test, đánh dấu phần chưa kiểm chứng rõ ràng.
- [ ] 6.4 Review diff, SQL grants/function authorization và tương thích migration; chạy `openspec.cmd validate add-friend-requests-and-friends-list --strict --no-interactive`. Cập nhật tasks theo việc thực sự hoàn thành và ghi hướng dẫn rollout database trước app/rollback an toàn; không tự triển khai production hoặc archive change còn việc dở.

### Profile action integration — 2026-10-27

- Diagnosed missing Android integration: the hosted RPC existed, but ProfileFragment had no friendship ViewModel binding or action buttons. Added the profile social section and connected send/cancel/accept/decline/remove, loading/error/read-retry states, lifecycle-aware collection and refresh on resume. Remove requires confirmation. Self profile exposes no friendship actions. Relationship updates do not reload posts.
- Added four FriendshipViewModel tests: duplicate send taps, reconciliation after mutation failure, discard response after account switch, and explicit own-profile suppression. All passed.
- `gradlew.bat :backend:testDebugUnitTest :app-user:testDebugUnitTest :app-user:assembleDebug` completed successfully. APK: `app-user/build/outputs/apk/debug/app-user-debug.apk`.
- The Friends tab remains a placeholder; tasks 5.1–5.3 and the list-specific portions of 4.4/5.5 remain pending. Sending is available through own Profile > Find profile > exact username > public profile. Rebuild/install the new APK before testing.
- No two-account device/emulator smoke test was performed; a successful build is not proof of Android-to-hosted end-to-end behavior.

### Friends screen, notifications and log fix — 2026-10-27

- ProfileFragment logged `CancellationException` as an error: `render()` reloaded posts on every state emission, cancelling the in-flight request, and leaving the screen cancelled the view scope. Posts now load once per profile id (plus on resume) and cancellation is rethrown instead of logged.
- Tasks 5.1–5.3: `Friend` uses ViewBinding with Bạn bè/Lời mời nhận/Đã gửi tabs, `FriendshipAdapter`, per-row progress/error, remove confirmation, load-more, retry and refresh on resume. `ProfileLookupDialogFragment` now emits a Fragment Result consumed by both `Friend` and `ProfileFragment`.
- Notifications (design §7, new requirement in friend-requests spec): migration `20261027150000_friend_notifications.sql`, `NotificationRepository`/remote/model, `NotificationViewModel`, Notification screen, unread badge on the hamburger button and drawer item.
- Hosted: verified `send_friend_request`/`friend_mutate` bodies matched the repo before replacing them; applied the migration through the linked Management API and recorded version `20261027150000 friend_notifications`. Verified both new RPCs are definer, empty search_path, authenticated-only; private helpers not exposed; new indexes present. The two pending requests created before this migration have no notification rows (no backfill performed); they appear in the Lời mời nhận tab.
- `gradlew.bat :backend:testDebugUnitTest :app-user:testDebugUnitTest :app-user:assembleDebug` passed; added three NotificationViewModelTest cases. No two-account device smoke test was performed.
- Not addressed: the pre-existing `notifications_insert_as_actor` policy plus default Supabase table grants still allow authenticated users to insert notifications directly with themselves as actor.

