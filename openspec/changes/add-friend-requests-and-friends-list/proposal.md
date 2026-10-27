## Why

Mục “Bạn bè & Tìm kiếm” hiện chỉ hiển thị placeholder, nên người dùng chưa thể gửi lời mời, xác nhận kết bạn hoặc xem những người đã kết nối. Hoàn thiện luồng này trên bảng `friendships` hiện có sẽ tạo quan hệ bạn bè hai chiều, có kiểm soát quyền và không sinh bản ghi trùng khi thao tác đồng thời.

## What Changes

- Thay placeholder bằng ba danh sách: Bạn bè, Lời mời nhận và Lời mời đã gửi; có tải trang, làm mới, trạng thái đang tải/rỗng/lỗi và thử lại.
- Cho phép tìm người bằng username chính xác qua chức năng profile hiện có, mở trang cá nhân và gửi lời mời từ trang của người khác.
- Cho phép người nhận chấp nhận hoặc từ chối lời mời; người gửi thu hồi lời mời; một trong hai người hủy kết bạn sau bước xác nhận.
- Hiển thị trạng thái quan hệ trên profile: chưa kết bạn, đã gửi lời mời, đã nhận lời mời, bạn bè hoặc không khả dụng; không hiện hành động kết bạn với chính mình.
- Duy trì một quan hệ duy nhất cho mỗi cặp người dùng, không phân biệt chiều; lời mời ngược chiều không tự động chấp nhận. Từ chối/thu hồi/hủy kết bạn xóa quan hệ hiện tại, không lưu lịch sử trong phiên bản này.
- Bổ sung RPC và ràng buộc database để kiểm tra người thực hiện, trạng thái, tài khoản bị khóa và block theo cả hai chiều. Giới hạn dữ liệu trả về ở thông tin công khai cần cho danh sách.
- **BREAKING (data access)**: Các client không được đọc/ghi trực tiếp bảng `friendships`; chuyển truy cập quan hệ sang RPC được cấp quyền rõ ràng. Chưa tìm thấy consumer truy cập trực tiếp trong checkout; phải xác minh consumer ngoài repo trước rollout.

## Capabilities

### New Capabilities

- `friend-requests`: Gửi, xem lời mời nhận/đã gửi, chấp nhận, từ chối, thu hồi; quyền và xử lý cạnh tranh giữa các thao tác.
- `friends-list`: Danh sách bạn bè hai chiều, phân trang, mở profile, hủy kết bạn và đồng bộ trạng thái quan hệ trên UI.

### Modified Capabilities

Không có. `openspec/specs/` hiện không chứa canonical spec; các capability mới không sửa nội dung change đã archive. Tích hợp profile giữ nguyên contract tra cứu username và hành vi avatar.

## Impact

- `app-user`: `ui/friend/Friend.kt`, các ViewModel/state/adapter mới trong `ui/friend/`, `fragment_friend.xml`, layout dòng danh sách và strings; tích hợp `ProfileFragment.kt`, `fragment_profile.xml`, `ProfileLookupDialogFragment.kt` và luồng `Home.openDetail()`.
- `backend`: model quan hệ/dòng danh sách, `FriendshipRemoteDataSource`, `FriendshipRepository`, operation và error mapping cần thiết. Giữ `ApiResponse`, `SafeApiCall`, coroutine và Supabase client hiện có; không thêm Spring hay framework mới.
- Supabase: migration mới cho constraints/indexes, RPC, grants/RLS; đồng bộ phần friendships trong `supabase/schema.sql`. Có bước kiểm tra dữ liệu cũ trước khi thêm unique index, không tự xóa/gộp dữ liệu thật.
- Kiểm chứng khi triển khai: test SQL với người gửi/người nhận/người thứ ba/anon, test race, repository/ViewModel, build Android và smoke test hai tài khoản.
- Ngoài phạm vi: gợi ý bạn bè, tìm kiếm toàn văn, thông báo/push, realtime, chat, màn hình block, xem danh sách bạn bè của người khác, nâng dependency/toolchain và triển khai production. Đây là proposal; chưa sửa code runtime hoặc chạy migration.
