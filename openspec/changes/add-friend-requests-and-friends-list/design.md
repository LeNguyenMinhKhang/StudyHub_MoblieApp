## Context

Khảo sát checkout ngày 2026-10-26:

- `app-user/.../ui/friend/Friend.kt` chỉ inflate `fragment_friend.xml`; drawer trong `Home.kt` đã mở `Friend()` và cung cấp `openDetail()`/back stack.
- `ProfileFragment.forUser(id)` hiển thị profile công khai. `ProfileLookupDialogFragment` gọi `ProfileRepository.findByUsername()` nhưng callback đang ép parent thành `ProfileFragment`; cần tháo liên kết này để dùng từ màn Bạn bè.
- `backend/.../core/data/remote/ProfileRemoteDataSource.kt` đã dùng RPC. `ProfileRepository`, `ApiResponse.kt` và `SafeApiCall.kt` là mẫu hiện hành; hướng dẫn cũ nhắc `Resource` không phản ánh code này. `Operation` được khai báo trong `SafeApiCall.kt`.
- `supabase/schema.sql` có `friendships(id, requester_id, addressee_id, status, created_at)`, trạng thái `pending|accepted`, unique theo cặp có hướng và RLS cho các bên liên quan. Chưa có check khác chính mình hoặc unique theo cặp vô hướng. Policy insert chỉ kiểm requester nên chưa ép `pending`; update chưa mô tả đầy đủ chuyển trạng thái và trường bất biến.
- Có `blocks` và `profiles.is_banned`. Policy đọc blocks chỉ cho người chặn, do đó client không thể tự kiểm tra block chiều ngược. Snapshot và migration profile có grants khác nhau; không suy ra quyền đang triển khai từ riêng một file.
- Có thay đổi avatar đang nằm trong workspace. Chỉ tích hợp khả năng hiển thị avatar đã có; không ghi đè thay đổi đó. Không có canonical spec trong `openspec/specs/`.
- Chưa truy cập database đang chạy và chưa chạy build/test trong giai đoạn proposal.

## Goals / Non-Goals

**Goals:** Hoàn thiện lời mời nhận/đã gửi, quan hệ bạn bè hai chiều, danh sách riêng của người đăng nhập, profile actions, phân trang và lỗi có thể phục hồi; bảo vệ invariants tại database; có test quyền và concurrency.

**Non-Goals:** Realtime/push notification, chat, gợi ý, tìm kiếm toàn văn, danh sách bạn bè công khai, lịch sử từ chối, thời hạn lời mời, màn hình block, sửa quyền profile/post toàn hệ thống, nâng SDK, production rollout trong thao tác propose/apply thông thường.

## Decisions

### 1. Một hàng cho một cặp người dùng

Giữ bảng `friendships` và hai trạng thái hiện có. Thêm check `requester_id <> addressee_id` và unique expression index trên `least(requester_id, addressee_id), greatest(requester_id, addressee_id)`. Không tạo hàng đảo chiều khi chấp nhận. `requester_id`, `addressee_id`, `id`, `created_at` bất biến trong vòng đời hàng; thời điểm tạo lời mời cũng là khóa sắp xếp, không được mô tả thành thời điểm chấp nhận.

| Trạng thái hiện tại | Người thực hiện / hành động | Kết quả |
| --- | --- | --- |
| Không có hàng | A gửi B | Tạo A → B, pending |
| A → B pending | A gửi lại | Trả quan hệ hiện tại, không tạo thêm |
| A → B pending | B gửi A | Trả incoming cho B, không tự accept |
| A → B pending | B chấp nhận | Cập nhật chính hàng đó thành accepted |
| A → B pending | B từ chối / A thu hồi | Xóa hàng pending |
| A ↔ B accepted | A hoặc B gửi lại | Trả accepted, không thay đổi |
| A ↔ B accepted | A hoặc B hủy kết bạn | Xóa hàng accepted |

Sau xóa, người dùng có thể chủ động gửi lời mời mới. Không có trạng thái persisted `declined`/`cancelled`; không cung cấp lịch sử hoặc retry tạo lời mời tự động. Phương án bảng `friend_requests` riêng cộng bảng hai chiều không cần thiết cho phạm vi này và tạo thêm giao dịch đồng bộ.

Unique expression index là lựa chọn áp dụng cho cặp vô hướng; PostgreSQL hỗ trợ unique index trên biểu thức để ràng buộc dữ liệu. [PostgreSQL: Indexes on Expressions](https://www.postgresql.org/docs/current/indexes-expressional.html).

### 2. RPC là đường đọc/ghi của tính năng, quyền được kiểm tra tại server

Tạo các RPC tên duy nhất, DTO được khai báo rõ trong migration và Kotlin:

| RPC dự kiến | Input | Output / quy tắc |
| --- | --- | --- |
| `get_friendship_state` | `p_target_id` | Snapshot viewer-relative: self, none, outgoing_pending, incoming_pending, friends, unavailable; kèm friendship_id khi khả dụng |
| `list_my_friendships` | `p_kind` = friends/incoming/outgoing, `p_limit`, cursor thời gian + id | Trang dòng quan hệ + public identity của phía còn lại |
| `send_friend_request` | `p_target_id` | Snapshot sau khi gửi hoặc khi quan hệ đã tồn tại |
| `accept_friend_request` | `p_friendship_id` | Chỉ addressee, pending → accepted; cùng id đã accepted trả trạng thái hiện tại cho người nhận |
| `decline_friend_request` | `p_friendship_id` | Chỉ addressee của pending; xóa và trả none |
| `cancel_friend_request` | `p_friendship_id` | Chỉ requester của pending; xóa và trả none |
| `remove_friendship` | `p_friendship_id` | Chỉ một trong hai bên của accepted; xóa và trả none |

Các RPC lấy actor từ `auth.uid()`, không nhận requester/actor do client chỉ định. Kiểm tra session và profile actor chưa bị khóa. Send/accept kiểm tra target tồn tại, chưa bị khóa và không có block theo bất kỳ chiều nào. State trả unavailable nếu target không khả dụng; list loại các peer bị khóa hoặc đang bị block. Trả thông báo chung cho target không khả dụng, không tiết lộ ai chặn ai. Cleanup decline/cancel/remove vẫn cho phép bên hợp lệ xóa hàng có peer bị khóa/block; actor bị khóa không được thao tác.

Block là bộ lọc/điều kiện tại thời điểm đọc và kiểm tra giao dịch; phiên bản này không xóa quan hệ sẵn có khi một block xuất hiện. Nếu sau đó block bị gỡ, quan hệ chưa bị xóa hiển thị lại. Không sửa lifecycle của bảng blocks; thay đổi block đồng thời sẽ được phản ánh ở lần refresh tiếp theo. Không đưa ra bảo đảm giao dịch chung giữa block và kết bạn ở phạm vi này.

Dùng các function `SECURITY DEFINER` có `search_path = ''`, tên bảng đầy đủ schema và kiểm tra quyền trong thân hàm; cần quyền đọc blocks hai chiều và hạn chế payload profile. Revoke EXECUTE của từng function mới khỏi PUBLIC/anon, chỉ grant authenticated; private helper không được client execute. Revoke mọi quyền bảng friendships của PUBLIC/anon/authenticated: cả đọc và ghi tính năng đi qua RPC, không có đường table API để bỏ qua bộ lọc peer khả dụng. Xóa các policy write cũ; giữ table RLS bật và policy select involved như lớp phòng vệ nếu sau này thay grants. Kiểm thử cả RPC lẫn table API, tránh chỉ chứng minh việc UI ẩn nút.

SECURITY DEFINER có thể vượt RLS tùy chủ sở hữu; authorization trong hàm, projection cố định và grants là bắt buộc, không coi RLS tự bảo vệ lời gọi này. Các nguyên tắc search_path, kiểm ownership và thu hẹp EXECUTE được mô tả tại [Supabase: Secure a database function](https://supabase.com/docs/guides/database/functions#secure-a-database-function).

Không mở rộng quyền đọc profiles. Query mới chỉ trả `id`, `username`, `display_name`, `avatar_url` của peer cùng id/status/direction/created_at của quan hệ; không trả email, role hoặc cờ quản trị. Phương án client đọc rồi insert/update trực tiếp bị loại vì không giải quyết quyền block chiều ngược và race check-then-write.

### 3. Chống race, thao tác cũ và retry không rõ kết quả

Send dùng insert có xử lý unique conflict rồi đọc lại cặp; mọi đường ghi phải dựa trên constraint và guard trong transaction. Mutation theo friendship_id khóa hàng, kiểm tra actor + trạng thái rồi mới đổi/xóa; không cập nhật theo target_id đơn thuần vì nút cũ có thể tác động lời mời mới.

- Send ngược chiều đồng thời tạo tối đa một pending; bên thua nhận trạng thái incoming, phải chủ động accept.
- Accept cạnh tranh cancel/decline: chỉ chuyển trạng thái hợp lệ thắng; nếu accept thắng thì cancel/decline không được xóa accepted. Nếu delete thắng thì accept báo stale, không tạo lại.
- Remove lặp lại hoặc request id đã xóa trả lỗi không còn khả dụng chung, không suy ra owner của id. Request mới có id mới nên không bị thao tác cũ ảnh hưởng.
- RPC trả snapshot có thẩm quyền sau mutation. Khi network timeout, UI không khẳng định thành công và không tự lặp mutation; đọc lại state/list, rồi cho người dùng quyết định thao tác tiếp.
- Dùng mã lỗi máy đọc được cho unavailable/stale/validation/permission; mapping theo operation trong `ExceptionMapper`, không parse nội dung lỗi SQL. Cancellation tiếp tục được ném ra qua `SafeApiCall`.

### 4. Danh sách có giới hạn và dữ liệu công khai theo lô

`list_my_friendships` chỉ lấy quan hệ của auth.uid(), với accepted hoặc pending đúng chiều. Một query join hai đầu profiles chọn peer, tránh gọi RPC profile cho từng dòng. Trang mặc định 20, giới hạn server tối đa 50; reject limit <= 0, kind/cursor sai, giới hạn mọi query cả khi caller không truyền limit.

Sắp xếp `created_at DESC, id DESC`, keyset cursor cùng hai trường; cursor là cặp đầy đủ hoặc không có. Response có items và next cursor (null nếu hết), không yêu cầu total count. Thêm indexes `(requester_id, status, created_at DESC, id DESC)` và `(addressee_id, status, created_at DESC, id DESC)`; kiểm tra query plan trên dữ liệu test phù hợp. Client gộp theo friendship_id, không tạo bản sao khi reload; refresh và mutation thành công reset trang liên quan. Đây không phải snapshot bất biến giữa nhiều request: thay đổi mới xuất hiện khi refresh. Không dùng offset vì xóa/accept làm dịch trang.

### 5. Backend theo pattern đã có

Thêm `Friendship.kt` (row/enum/snapshot), `FriendshipListItem.kt` (public identity + quan hệ), kiểu page/cursor và input tối thiểu trong `backend/.../core/data/model/`; map snake_case bằng kotlinx.serialization. DTO friendship không dùng `Profile` đầy đủ.

`FriendshipRemoteDataSource.kt` chứa RPC và decode. `FriendshipRepository.kt` cung cấp phương thức theo use case, `ApiResponse<T>`, session provider/fake remote có thể inject và SafeApiCall. Validate UUID, input và self nhằm phản hồi sớm, vẫn để server quyết định quyền. Giữ đúng kiểu Success/Failure; loading ở UI. Bổ sung operation trong `common/SafeApiCall.kt`, lỗi cần thiết trong `ErrorType.kt` và mapping trong `ExceptionMapper.kt` theo contract mới, không đổi hành vi avatar/profile.

Version catalog hiện có Supabase 3.1.4, Ktor 3.1.1 và serialization 1.8.0; triển khai theo API đã dùng trong repo, không nâng dependency để làm feature.

### 6. Fragment/XML và trạng thái UI tách khỏi profile/posts

Giữ tên `Friend` để không đổi drawer. Chuyển sang ViewBinding, thêm `FriendViewModel`, `FriendUiState`, `FriendshipAdapter`, layout dòng, ba tab Bạn bè/Nhận/Đã gửi, nút Tìm người, làm mới và tải thêm. Mỗi tab có state/cursor riêng; mỗi hàng có trạng thái mutation riêng để không chặn toàn màn hình. Dùng RecyclerView/ListAdapter và resource strings/content descriptions hiện có.

Refactor lookup dialog trả id qua Fragment Result API cho parent; cả `Friend` và `ProfileFragment` đăng ký nhận và mở `ProfileFragment.forUser(id)` qua `Home.openDetail()`. Giữ tìm username chính xác theo RPC hiện có; không thêm API directory. Kết quả là chính mình thì mở profile chủ tài khoản và không có social actions.

Thêm `FriendshipViewModel`/state nhỏ trong `ui/friend/` cho social section của profile, không nhét tải bạn bè vào `ProfileViewModel` hoặc làm refresh bài viết mỗi lần trạng thái quan hệ thay đổi. So sánh target với session user thực tế, không chỉ dựa vào `ProfileUiState.isOwner` vì profile mở bằng explicit id hiện chưa phân loại owner đầy đủ.

| State quan hệ | Action profile |
| --- | --- |
| self | Không có hành động kết bạn |
| none | Kết bạn |
| outgoing_pending | Đã gửi lời mời + Thu hồi |
| incoming_pending | Chấp nhận + Từ chối |
| friends | Bạn bè + Hủy kết bạn (dialog xác nhận) |
| unavailable | Thông báo chung, không cho gửi/accept |

Initial load/error state không được coi là none; chỉ bật mutation khi đã biết quan hệ. Lỗi hàng giữ dữ liệu đang hiển thị, mở lại action và hiển thị thông báo an toàn. Lỗi tải thêm giữ các trang đã có; empty chỉ xuất hiện sau tải thành công. Hủy kết bạn cần xác nhận, cancel dialog không gọi server.

Collection theo `repeatOnLifecycle`, binding/adapter cleanup theo view lifecycle. Re-fetch quan hệ khi profile trở lại foreground; refresh tab khi mở lại màn Bạn bè, sau mutation và theo nút làm mới. Không cần realtime hay cache toàn cục. Capture session user + request generation để bỏ kết quả cũ; khi logout/chuyển tài khoản xóa danh sách/cursor/action state và không áp dụng kết quả của session trước. Dùng `AvatarImageLoader.showCommitted` cho URL peer + placeholder; không dùng ảnh local của actor cho người khác.

### 7. Notification rows cho lời mời (bổ sung 2026-10-27)

Người nhận cần biết có lời mời mà không phải mở profile người gửi. `send_friend_request` chỉ chèn một hàng `friend_request` cho B khi thực sự tạo lời mời mới (gửi lặp không tạo thêm); `accept` chèn `friend_accept` cho người gửi và đánh dấu đã đọc lời mời; `decline` đánh dấu đã đọc; `cancel` xóa thông báo lời mời để B không thấy lời mời đã rút. Mọi ghi diễn ra trong cùng transaction với thay đổi quan hệ. Client đọc qua `list_my_notifications` (cursor đôi, default 20/max 50, kèm `unread_count`, ẩn actor bị ban/block hai chiều) và `mark_notifications_read(p_ids uuid[] | null)`; cả hai là SECURITY DEFINER, search_path rỗng, chỉ authenticated. Không có realtime/push: app đọc lại khi mở màn Thông báo, khi quay lại Home và khi mở drawer.

## Risks / Trade-offs

- [Dữ liệu cũ có self-row/cặp đảo chiều] → Preflight đọc và báo số lượng; migration dừng trước thay đổi nếu có vi phạm, không tự chọn/xóa bản ghi thật. Kế hoạch xử lý dữ liệu riêng chỉ cần khi thực tế phát hiện lỗi.
- [Grants/schema thực tế lệch file SQL] → Đối chiếu catalog, policies, function signatures và migration history trên môi trường được phép; kiểm thử bằng role thực, không chỉ owner.
- [Function definer tăng đặc quyền] → Input tối thiểu, auth.uid, actor/status guards, projection cố định, revoke/grant từng function, kiểm thử người thứ ba và anon.
- [Không realtime] → Thay đổi từ thiết bị khác hiển thị khi mở lại/làm mới; state cũ phải bị server từ chối và được reload.
- [Có thể gửi lại sau từ chối] → Ghi rõ semantics v1; không có cooldown/anti-spam trong change này. Tôn trọng blocks đã có.
- [Phân trang thay đổi trong lúc đọc] → Keyset, dedupe id và reset khi mutation; không hứa snapshot toàn danh sách.
- [Chồng file với avatar] → Giới hạn sửa social section/result callback, giữ nguyên công việc avatar của workspace và test hồi quy.

## Migration Plan

1. Xác minh schema/RLS/grants/functions, dữ liệu self/duplicate, FK/caller ngoài repo trên môi trường test được phép. Repo hiện không có `supabase/config.toml`; chọn runner SQL/disposable database rõ ràng, không giả định `supabase test db` đã sẵn sàng.
2. Viết migration timestamp mới dưới `supabase/migrations/`, có preflight fail-fast trong transaction, constraints/indexes/RPC/grants/policies và đồng bộ đúng phần friendships của `supabase/schema.sql`. Không chạy lại toàn snapshot trên database đang có dữ liệu.
3. Apply trên database test; kiểm tra quyền anon/A/B/C, banned/block, tối thiểu 45 dòng để qua nhiều trang và race bằng hai connection độc lập. Kiểm tra cài mới lẫn upgrade từ schema cũ, không tự normalize dữ liệu vi phạm.
4. Build/test data layer và UI; smoke hai tài khoản và lần lượt gửi/nhận/accept/decline/cancel/remove, trở về profile, offline/timeout và đổi tài khoản. Deploy database trước app khi rollout được giao riêng.
5. Rollback app về bản chưa hiển thị feature hoặc vô hiệu hóa feature nếu lỗi. Giữ dữ liệu/constraints an toàn; rollback database chỉ theo migration đảo có review, không khôi phục broad write grants như biện pháp tạm và không tự xóa quan hệ đã tạo.

## Open Questions

Không còn câu hỏi sản phẩm chặn việc lập artifacts; phạm vi mặc định đã chọn là username chính xác, không auto-accept, xóa khi decline/cancel/remove, cho gửi lại và refresh thủ công. Các dữ kiện phải xác minh trong task đầu: môi trường database test khả dụng, quyền/schema đang triển khai, có dữ liệu vi phạm hoặc caller friendships ngoài repo hay không. Nếu phát hiện khác biệt, cập nhật thiết kế/migration trước bước áp dụng tương ứng; proposal không khẳng định schema live đã khớp.
