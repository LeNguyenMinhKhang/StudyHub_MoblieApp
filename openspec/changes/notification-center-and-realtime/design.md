## Context

Nguồn: `SystemDesignInterview.pdf`, Chương 10, trang PDF 151-164. Đã đọc toàn bộ chương và xem Figure 10-10 (trang 158), 10-11 (trang 160), 10-14 (trang 163). Đây là tài liệu tham khảo kiến trúc; các lời yêu cầu trong tình huống phỏng vấn không tự mở rộng phạm vi người dùng đã chọn.

Checkout có inbox kết bạn, `list_my_notifications`, `mark_notifications_read`, Fragment/XML, ViewModel và badge tải theo vòng đời. `likes`/`comments` được ghi trực tiếp bằng PostgREST. Supabase Kotlin 3.1.4 đã có Realtime cho chat. `Settings.kt` là placeholder. Policy `notifications_insert_as_actor` và quyền bảng cần được kiểm kê; schema snapshot chưa chứa đầy đủ migration notification. Preflight read-only ngày 2026-11-06 đã kiểm tra một phần hosted catalog và xác nhận `realtime.send()` bắt lỗi nội bộ rồi chỉ phát WARNING; bằng chứng nằm trong `supabase/README-notifications.md`. Chưa chạy build hoặc smoke test cho change này.

## Goals / Non-Goals

**Goals:** Áp dụng persistence, queue/worker, dedupe, retry, preferences, templates, rate limiting và monitoring của sách cho inbox kết bạn/thích/bình luận; giữ authorization tại server, nội dung đọc qua RPC và badge đồng nhất.

**Non-Goals:** FCM/APNs, SMS/email, nhắc lịch, đa vùng, Kafka/RabbitMQ/Redis riêng, hứa hẹn exactly-once transport, thông báo nhắn tin/nhóm/best-answer/new-post mới, inline accept/decline, analytics bên thứ ba. Chưa tự triển khai hosted.

## Decisions

### 1. Ánh xạ kiến trúc trong sách vào quy mô StudyHub

| Thành phần sách | Áp dụng |
| --- | --- |
| Notification servers/authentication | Private SQL helpers, RPC và trigger nghiệp vụ có kiểm tra quyền |
| Notification database/log | `notifications` là inbox bền vững; outbox giữ lịch sử phát tín hiệu |
| Queue và workers | Bảng outbox PostgreSQL, worker SQL theo lô, scheduler Supabase Cron |
| Channel/provider | Một channel `in_app`; private Supabase Broadcast |
| Templates/settings | Template key/version trong DTO, resource Android; preferences theo nhóm |
| Rate limit/retry/monitoring | Gom tín hiệu theo recipient, retry/backoff, dead-letter và view metrics |
| Open/click tracking | `opened_at` đầu tiên qua RPC có ownership; không dùng mark-all làm số lượt mở |

Không đưa thông tin thiết bị, số điện thoại hoặc email vào model khi chỉ có kênh in-app. Quy mô hàng triệu/ngày trong sách là giả định của bài phỏng vấn, chưa phải tải đã đo của StudyHub.

### 2. Inbox đồng bộ, truyền tín hiệu bất đồng bộ

```text
RPC kết bạn / ghi likes, comments
   -> transaction: kiểm quyền + preferences + nghiệp vụ + inbox + outbox
   -> commit
Cron -> bounded SQL worker -> private Broadcast notifications:<recipient>
Android -> RPC snapshot -> shared state -> badge / danh sách
```

Giữ invariant hiện có: inbox và thao tác kết bạn commit cùng nhau. Trigger trên likes/comments sinh inbox cùng transaction; trigger trên notifications tạo outbox cho INSERT/UPDATE/DELETE. Trigger tạo notification phải lấy actor/recipient từ hàng nguồn hợp lệ, không nhận người nhận tùy ý từ app. Khi ghi inbox/outbox thất bại, transaction nghiệp vụ rollback; Realtime hoặc worker offline sau commit không rollback nghiệp vụ.

So với worker mới tạo inbox, cách này bảo toàn contract kết bạn hiện có và tránh việc lời mời bị thu hồi rồi worker tạo lại notification cũ. So với gửi Broadcast trực tiếp trong transaction nghiệp vụ, outbox cho phép retry và theo dõi lỗi độc lập.

### 3. Nguồn sự kiện và chống trùng

Mở rộng `notifications` với `source_kind`, `source_id`, `event_key`, `template_version`, `opened_at`; unique event key gồm recipient/type/source incarnation. `source_id` là id lời mời, like hoặc comment, không chỉ actor/post. Cặp like có unique constraint hiện tại; mỗi lần bỏ thích rồi thích lại là incarnation mới. Retry cùng nguồn không tạo inbox thứ hai. Trạng thái đọc không đổi vì job phát lại.

| Sự kiện | Inbox |
| --- | --- |
| Tạo pending request | Một `friend_request` cho addressee nếu bật nhóm kết bạn |
| Accept | Một `friend_accept` cho requester; mark request đã đọc |
| Decline | Giữ lịch sử request, mark đã đọc |
| Cancel | Xóa request notification đúng source id |
| Insert like | Một `like` cho chủ post; không tự thông báo |
| Delete like | Xóa notification của đúng like id |
| Insert comment (kể cả reply) | Một `comment` cho chủ post, không tự thông báo; chưa fan-out cho tác giả comment cha |
| Edit comment | Không tạo notification mới |
| Delete comment/post | Xóa notification nguồn tương ứng; có invalidation cho recipient |

Xóa profile giữ cách xử lý FK hiện có; DTO chịu được actor null. Người dùng banned không được đọc/ghi notification; producer không tạo cho actor/recipient bị ban hoặc block hai chiều. Danh sách và unread count dùng cùng bộ lọc, đồng thời kiểm tra quyền xem post nguồn. Không coi việc nguồn tồn tại là đủ quyền đọc.

Hàng legacy không có source id được giữ nguyên. Chỉ backfill khi liên kết nguồn là duy nhất và có bằng chứng; không đoán liên kết từ cặp actor/recipient. Cleanup legacy tuân thủ hành vi cũ, test riêng; uniqueness cho hàng mới dùng index có điều kiện để không phá dữ liệu cũ.

### 4. Outbox và worker

Thêm bảng private `notification_delivery_jobs`: id/event_id, recipient_id, channel, status (`pending`, `retry`, `published`, `dead_letter`), attempts, last_dispatch_id, next_attempt_at, created_at, published_at, safe_error_code. `event_id` định danh job ổn định; `last_dispatch_id` ghi lần phát gần nhất, không dùng làm khóa dedupe inbox. Không lưu nội dung bình luận hoặc token. Các recipient đã xóa được cleanup bằng FK; bỏ qua banned recipient mà không phát payload.

Worker lấy tối đa 100 job đến hạn, dùng transaction advisory lock để chỉ một lượt worker chạy ở quy mô hiện tại, giới hạn thời gian chạy. Chọn hàng theo `(next_attempt_at,id)`; khóa hàng trong transaction. Gom các job đã claim của cùng recipient thành một invalidation; không đánh dấu job phát sinh ngoài batch là đã xử lý. Payload do worker tạo chỉ gồm `schema_version` và `dispatch_id`; Supabase có thể thêm metadata riêng. Không gửi actor, nội dung hoặc unread count chưa kiểm quyền.

#### Xác nhận publication trong transaction

Không coi việc `realtime.send()` trả về bình thường là bằng chứng thành công: phiên bản hosted đã kiểm tra có `EXCEPTION WHEN OTHERS THEN RAISE WARNING`, nên caller không nhận được lỗi INSERT. Thêm adapter chỉ worker được gọi, thực hiện cho từng nhóm recipient:

1. Tạo UUID `dispatch_id` mới cho mỗi lần thử của nhóm, tách biệt với các event/job id. Không tái sử dụng ID khi retry hoặc requeue. Ghi cùng ID vào `last_dispatch_id` của các job trong nhóm khi kết quả lần thử được lưu.
2. Xác nhận chưa có bản ghi dùng correlation ID này; nếu có thì không công nhận đó là publication mới. Gọi `realtime.send` với payload có `dispatch_id`, event cố định `notifications_changed`, topic `notifications:<recipient_id>` và `private := true`.
3. Trong cùng transaction, kiểm tra đúng một hàng `realtime.messages` khớp `payload->>'dispatch_id'`, topic, event, `private = true`, `extension = 'broadcast'` và schema version dự kiến. Không nhầm `dispatch_id` trong payload với cột `realtime.messages.id` do Supabase tự sinh; không dùng chỉ topic, timestamp hoặc một hàng từ lần phát trước làm bằng chứng.
4. Chỉ sau xác nhận mới cập nhật `published`, `published_at` và mốc giới hạn tần suất cho các job đã claim của nhóm. Không cập nhật job đến sau hoặc job của recipient khác.
5. Thiếu/sai/nhiều bản ghi xác nhận phải raise lỗi có mã an toàn `broadcast_unconfirmed`; lỗi đọc bảng hoặc lỗi gọi trực tiếp cũng được coi là lần thử thất bại. Adapter không sửa function hoặc cấu trúc schema Realtime do Supabase quản lý.

Lời gọi send, kiểm tra và cập nhật trạng thái nằm trong cùng subtransaction. Nếu kiểm tra thất bại, rollback mọi publication/phần cập nhật của nhóm rồi handler bên ngoài subtransaction tăng attempts, lưu `last_dispatch_id` và safe_error_code, chuyển retry/dead-letter trong transaction worker. Sau tối đa 5 lần thử, giữ dead-letter; backoff cơ sở 5/10/20/40 giây cộng jitter tối đa 20%. Crash trước commit rollback cả claim và thay đổi, job vẫn có thể được lấy lại. Published chỉ xác nhận bản ghi đã được ghi vào cơ chế Broadcast khi transaction commit, không có nghĩa thiết bị đã nhận/đọc; không có client ACK delivery trong phạm vi này.

Preflight phải chứng minh worker role được đọc các cột cần kiểm tra và adapter hoạt động với schema Realtime thực tế, kể cả failure injection nơi send chỉ WARNING. Không cấp thêm quyền đọc `realtime.messages` hoặc gọi adapter cho Android/anon/authenticated để giải quyết lỗi của worker. Quyền xác thực kênh hiện có và quyền vận hành adapter là hai mục đích riêng. Nếu không thể kiểm chứng, không bật scheduler hoặc đánh dấu task dispatch hoàn thành; cập nhật khả năng tương thích trước rollout.

Đề xuất lịch Cron mỗi 5 giây, tối đa một tín hiệu/recipient/5 giây bằng trạng thái dispatch phía server; giới hạn batch và lịch retry chống vòng lặp nóng. Đây là giá trị cấu hình ban đầu để review/đo tải, không phải số liệu sách. Mục tiêu thử nghiệm foreground bình thường: cập nhật trong 10 giây; soft realtime cho phép trễ khi backlog tăng. Không drop inbox vì rate limit; mọi thay đổi đã commit luôn đọc được qua RPC.

Supabase Cron sử dụng pg_cron và hỗ trợ lịch theo giây trên phiên bản phù hợp: [Cron](https://supabase.com/docs/guides/cron), [quickstart](https://supabase.com/docs/guides/cron/quickstart). Kiểm tra phiên bản/cấu hình đích trước khi chọn lịch; nếu không hỗ trợ thì dừng bước rollout scheduler và báo blocker, không hứa latency bằng lịch một phút. Worker SQL có thể được gọi thủ công trong test; không cần HTTP secret hoặc service-role key trên Android.

Metrics riêng cho vận hành: pending/retry/dead-letter count, tuổi job cũ nhất, attempts, published latency và số notification opened. Cảnh báo ban đầu: dead-letter > 0 hoặc oldest due > 60 giây; runbook nêu câu truy vấn, ngưỡng và thao tác requeue có kiểm soát. Không tuyên bố đã gửi cảnh báo ra Slack/email. Giữ published jobs 7 ngày, dead-letter đến khi vận hành xử lý; cleanup theo batch. Không tự xóa inbox theo tuổi trong phiên bản này.

### 5. Private Realtime và quyền

Private topic `notifications:<auth.uid()>`; policy trên `realtime.messages` chỉ cho nhận topic đúng user đủ điều kiện, không cấp quyền client broadcast. Không dùng topic name hoặc client filter như authorization. Thu hồi direct table access và mutation grants của notifications/preferences/jobs, chỉ expose RPC đã kiểm `auth.uid()`, ownership và eligibility. Private helpers có search_path rỗng, revoke EXECUTE của PUBLIC/anon/authenticated; worker/scheduler dùng role vận hành phù hợp.

Broadcast giúp tránh phải expose bảng inbox và không cần gửi raw DELETE record. Nội dung luôn lấy qua RPC; banned/block/post visibility được kiểm lại mỗi lần đọc. Thay đổi block/ban/post visibility phải enqueue invalidation cho những recipient bị ảnh hưởng theo batch/index có giới hạn; không chỉ subscribe bảng notifications rồi bỏ sót thay đổi quyền. Không gửi chi tiết block direction hoặc status riêng tư.

Tài liệu: [Broadcast](https://supabase.com/docs/guides/realtime/broadcast), [authorization](https://supabase.com/docs/guides/realtime/authorization). Kiểm chứng private topic APIs trên SDK 3.1.4 trước code chính; không nâng SDK mặc định. Kiểm tra tác động tới chat trước khi chỉnh cấu hình public/private toàn project.

### 6. RPC và trạng thái Android

Giữ signatures RPC cũ cho client hiện tại. Thêm `list_my_notifications_v2(p_filter,p_limit,p_cursor_created_at,p_cursor_id)` với filter all/unread, default 20/max 50, cursor đôi và unread count toàn bộ tập được phép xem. Old list dùng cùng visibility logic. Thêm `get_my_notification_preferences`, `set_my_notification_preferences` và `open_my_notification(p_id)`; RPC mở ghi opened_at lần đầu và mark read idempotently. Mark-all hiện tại tiếp tục lấy tập unread tại thời điểm transaction server thực hiện; thông báo commit sau snapshot đó vẫn unread.

Một owner state ở phạm vi Home/session quản lý subscription và unread count; Notification screen dùng cùng state, không gọi Home để tự ghi đè badge bằng snapshot cũ. Scope bắt đầu/dừng theo foreground và session, hủy channel/job cũ và xóa state khi logout. Không chỉ subscribe khi màn thông báo đang mở.

Đăng ký collector trước khi subscribe, chờ subscribed rồi đọc snapshot; sự kiện trong lúc tải đặt dirty flag để có lượt đọc bù. Duy trì tối đa một refresh và một pending refresh gộp; reconnect và foreground luôn đọc lại. Không chỉ tăng/giảm badge theo sự kiện. Generation/session guard loại response cũ; coroutine cancellation tiếp tục được ném ra.

Sau invalidation, tải lại cửa sổ đang hiển thị bằng RPC có giới hạn từng trang và giới hạn tổng 200 hàng, giữ scroll anchor nếu còn; nếu cửa sổ đã vượt giới hạn thì đánh dấu dữ liệu cần làm mới và cho người dùng reset từ đầu. Không nối trang đầu mới với cursor cũ một cách mù quáng. Khi có thêm invalidation trong quá trình reconcile, đọc bù trước khi coi state đã cập nhật. Không duplicate theo id; delete/read trên trang sau phải được phản ánh.

Đánh dấu đọc có optimistic state và đối soát server khi lỗi/timeout; request refresh cũ không được phục hồi unread đã đọc. Mở nguồn dùng profile/post navigation có sẵn; nguồn bị xóa/không còn quyền trả lỗi chung và refresh. Không hiển thị nội dung được nhúng vào payload Realtime.

### 7. Preferences và templates

Preferences private theo `(user_id,channel,category)`; v1 chỉ `in_app` và categories `friendship`, `like`, `comment`. Mặc định bật để bảo toàn hành vi hiện tại. Opt-out ngăn notification mới từ thời điểm setting đã commit; lịch sử cũ còn xem được, bật lại không backfill. Serialize producer và thay đổi preferences bằng hàng state của recipient (tạo/khóa hàng mặc định một cách race-safe) để có thứ tự transaction rõ ràng. Thao tác nghiệp vụ vẫn thành công khi notification được chủ động suppress.

Template key dùng type + version; render bằng Android resources với actor display name được escape đúng UI, không đưa content tự do thành HTML. Giữ generic fallback cho type/version cũ không hiểu. Không cần remote template editor. Tracking chỉ first-open của notification được phép đọc; không log email/comment body, không tính mark-all là open.

## Risks / Trade-offs

- [Worker chia sẻ database] -> batch/time limits, metrics, index và load test; tách worker service nếu tải thực tế yêu cầu.
- [Outbox tăng dung lượng] -> cleanup published theo lô; giữ lỗi để xử lý, không xóa pending vì hết tuổi.
- [Realtime best effort] -> inbox bền vững, RPC reconciliation khi resume/reconnect; không cam kết exactly-once.
- [send nuốt lỗi hoặc schema Realtime thay đổi] -> adapter xác nhận correlation ID trong cùng transaction, xử lý thiếu bằng chứng như thất bại và kiểm thử với worker role trước rollout; không sửa schema managed để ép tương thích.
- [Thu hồi quyền bảng ảnh hưởng consumer ngoài repo] -> inventory trước rollout; app hiện tại đã dùng RPC, không tự coi mọi consumer đều tương thích.
- [Legacy source không rõ] -> giữ hàng và test compatibility, không backfill đoán.
- [Spec kết bạn đang hoạt động] -> khi sync/archive, requirement thông báo nhận ngoại lệ preferences từ change này; proposal cũ có đoạn non-goal thông báo đã lệch phần bổ sung, cần hòa giải có chủ đích.

## Migration Plan

1. Preflight schema/grants/functions/history, source uniqueness, notifications legacy, extensions, Cron và SDK private channel; kiểm tra schema/quyền đọc của adapter và send nuốt lỗi, ghi blockers thật.
2. Migration mới bổ sung schema/index/helper/producer/outbox/RPC tương thích; giữ migrations đã chạy. Đồng bộ snapshot từ trạng thái cuối, không lấy snapshot cũ ghi đè function notification.
3. Test disposable DB với users A/B/C/anon, worker lỗi/crash và concurrent producers; sau đó build/unit tests và smoke hai thiết bị.
4. Rollout database trước Android trong môi trường được phép; bật scheduler sau khi policies/worker qua kiểm chứng. Quan sát backlog/latency và không khai báo vận hành hoàn tất khi chưa có scheduler thật.
5. Rollback: disable lịch worker trước, giữ inbox/jobs để phục hồi; client cũ tiếp tục RPC refresh. Nếu cần đảo producer/policy, dùng migration bù đã review, không khôi phục quyền ghi trực tiếp không an toàn hoặc xóa backlog.

## Open Questions

Không cần chọn thêm kênh để viết proposal: user đã chọn in-app. Các giá trị batch/interval/retention là mặc định đề xuất, sẽ xác nhận bằng test tải. Hosted preflight đã xác nhận PostgreSQL 17.6, pg_cron 1.6.4 có sẵn nhưng chưa được cài và hành vi send nuốt lỗi. Adapter publication, worker-role permissions, scheduler thực tế, SDK compile và hai tài khoản vẫn cần kiểm chứng. Người dùng đã đồng ý bổ sung cơ chế xác nhận publication; không cần duyệt lại quyết định này. Reply chỉ báo chủ post ở v1; fan-out cho tác giả comment cha cần yêu cầu bổ sung.
