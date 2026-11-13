## Why

StudyHub đã có thông báo kết bạn nhưng chưa sinh thông báo thích/bình luận và chưa đồng bộ danh sách/badge theo thời gian thực. Áp dụng các nguyên tắc độ tin cậy của Chương 10 trong `SystemDesignInterview.pdf` (trang PDF 151-164) để thông báo được lưu bền vững, chống trùng, có retry, tùy chọn nhận và khả năng giám sát.

## What Changes

- Hoàn thiện trung tâm thông báo trong app cho `friend_request`, `friend_accept`, `like`, `comment`: lọc tất cả/chưa đọc, phân trang, làm mới, đánh dấu đã đọc và điều hướng tới nguồn.
- Sinh inbox notification tại server trong cùng transaction với thao tác nghiệp vụ; không tạo thông báo cho tự tương tác, nguồn không khả dụng hoặc người nhận tắt loại thông báo đó.
- Thêm outbox bền vững và worker gửi tín hiệu Realtime theo người nhận, retry có giới hạn và lưu trạng thái lỗi cuối để vận hành có thể xử lý lại. Inbox vẫn truy cập được khi worker/kết nối Realtime ngừng hoạt động.
- Mỗi lần phát có mã `dispatch_id` riêng; worker chỉ đánh dấu `published` sau khi xác nhận đúng bản ghi Broadcast trong cùng transaction. Lỗi bị `realtime.send()` chuyển thành WARNING vẫn phải đi qua retry/dead-letter.
- Dùng private Broadcast chỉ báo dữ liệu thay đổi; nội dung/badge đọc qua RPC có kiểm tra quyền. Tải bù khi subscribe/reconnect/foreground và hủy dữ liệu phiên cũ khi đổi tài khoản.
- Thêm tùy chọn nhận theo nhóm kết bạn/thích/bình luận; template có phiên bản, giới hạn tần suất tín hiệu, số liệu backlog/retry/latency và lượt mở thông báo được ghi nhận không trùng.
- **BREAKING (table access)**: thu hồi quyền client đọc/ghi trực tiếp `notifications`; dùng RPC cho dữ liệu, private channel cho tín hiệu. Kiểm kê consumer trước rollout.
- Giữ phạm vi người dùng đã chọn: thông báo trong app. Push FCM/APNs, SMS, email, lịch gửi, hệ thống đa triệu thông báo/ngày và dịch vụ analytics bên ngoài là hướng mở rộng, không phải yêu cầu triển khai lần này.

## Capabilities

### New Capabilities

- `notification-center`: inbox riêng, thông báo tương tác, bộ lọc, cursor, đọc/điều hướng và quyền truy cập.
- `notification-delivery`: transactional outbox, private Realtime, lifecycle, retry, chống trùng và quan sát vận hành.
- `notification-preferences`: tùy chọn nhận theo loại, template và kiểm soát tần suất.

### Modified Capabilities

Không sửa canonical capability hiện có. `friend-requests` còn nằm trong change đang hoạt động `add-friend-requests-and-friends-list`; giữ nguyên invariant tạo inbox cùng transaction và xử lý thu hồi. Tùy chọn opt-out bổ sung ngoại lệ cho việc tạo thông báo; cần đồng bộ ngoại lệ này khi hợp nhất spec của hai change, không sửa âm thầm artifacts của change kia.

## Impact

- `backend/.../core/data/model/AppNotification.kt`, `remote/NotificationRemoteDataSource.kt`, `repository/NotificationRepository.kt`: contract mở rộng, đọc/ghi settings, observation và session handling; giữ `ApiResponse`.
- `app-user/.../ui/notification/*`, `ui/home/Home.kt`, `ui/settings/Settings.kt`, notification/settings layouts và strings: trạng thái chung, bộ lọc, lifecycle, cài đặt và điều hướng.
- Migration mới, `supabase/schema.sql`, worker SQL và tài liệu vận hành: inbox/source identity, outbox, policies, RPCs, template metadata, scheduler và metrics. Không sửa migration đã áp dụng.
- Tận dụng Supabase Kotlin 3.1.4 và Realtime đã cài; xác minh API private Broadcast trên phiên bản đang dùng. Scheduler dự kiến Supabase Cron, phải kiểm tra hỗ trợ ở môi trường đích trước rollout.
- Test SQL/worker, repository/ViewModel, build Android và kiểm thử hai tài khoản/mất mạng/đổi tài khoản. Proposal không chạy migration hosted hoặc chứng minh hành vi thiết bị.
