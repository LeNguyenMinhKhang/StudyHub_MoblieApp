## Why

Tab Nhóm học tập hiện chỉ là màn hình giữ chỗ; học viên chưa thể quản lý nhóm, gọi học cùng nhau hoặc cùng ghi chú trên tài liệu. Change cung cấp nhóm theo môn học, lời mời có chấp nhận, gọi thoại/video và tài liệu PDF cộng tác có ghi chú được lưu lại sau cuộc họp.

## What Changes

- Tạo nhóm học tập với tên, đúng một môn học và mô tả tùy chọn; người tạo tự động là trưởng nhóm. Cho phép nhóm ban đầu chỉ có người tạo.
- Hiển thị nhóm của tôi, chi tiết nhóm, danh sách thành viên và lời mời nhận được trong tab Nhóm học tập.
- Trưởng nhóm mời bạn bè, xem lời mời đang chờ và thu hồi; người nhận xem thông tin tối thiểu để chấp nhận hoặc từ chối.
- Thành viên được rời nhóm; trưởng nhóm được xóa thành viên, chuyển quyền và giải tán nhóm. Nhóm hoạt động luôn có đúng một trưởng nhóm là thành viên.
- Tách dữ liệu nhóm học tập khỏi hội thoại: quyền nhóm và lời mời được thực thi phía server qua RLS/RPC, với xử lý giao dịch và yêu cầu gửi lại an toàn.
- Danh sách nhóm/lời mời làm mới khi mở màn hình, quay lại ứng dụng hoặc kéo để tải lại; trạng thái cuộc gọi được tải lại định kỳ khi chi tiết nhóm đang hiển thị. Lời mời nằm trong tab Nhóm, chưa tích hợp chuông thông báo hoặc push.
- Thêm gọi thoại/video trong nhóm bằng LiveKit Cloud: bắt đầu/tham gia/rời cuộc gọi, bật/tắt mic/camera, đổi camera, chọn loa/tai nghe và kết nối lại. Mỗi nhóm có tối đa một cuộc gọi đang mở; mặc định demo tối đa 6 người trong một cuộc gọi, không giới hạn số thành viên nhóm theo con số này.
- Thêm tài liệu PDF riêng của nhóm: upload, mở và đọc ngay trong StudyHub; nhiều thành viên cùng dùng bút vẽ, highlight vùng và note chữ; sửa/xóa ghi chú theo quyền. Ghi chú gắn với đúng phiên bản tài liệu, dùng được cả trong và ngoài cuộc gọi và còn nguyên sau khi meeting kết thúc.
- Đồng bộ trực tiếp các thay đổi đã lưu, chống trùng khi retry, phát hiện xung đột khi sửa cùng ghi chú và khôi phục trạng thái khi kết nối lại. Hiển thị rõ đang lưu/đã lưu/chưa đồng bộ/xung đột.
- Xuất bản PDF có đánh dấu từ một snapshot xác định, giữ file gốc; note chữ được xuất đầy đủ với vị trí tham chiếu. Bản đầu xuất PDF phẳng để xem ở ứng dụng khác; không hứa giữ lớp chữ tìm kiếm, form hoặc chữ ký số của PDF gốc trong bản xuất.
- Tài liệu cộng tác thay Present màn hình trong phạm vi đã chốt. Mỗi người đọc/zoom độc lập; có tùy chọn theo trang của người hướng dẫn trong cuộc gọi. Quyền cùng ghi chú không phụ thuộc vào một người đang hướng dẫn.
- Cấp token và điều khiển phòng qua Supabase Edge Functions phía server; chỉ thành viên hợp lệ được vào cuộc gọi. Thu hồi quyền media khi rời/bị xóa khỏi nhóm, tài khoản bị khóa hoặc nhóm giải tán; không đưa secret LiveKit vào Android.
- Các lựa chọn đề xuất để review: chỉ mời bạn bè đã chấp nhận tại thời điểm gửi lời mời; mỗi nhóm thuộc một môn học và không đổi môn sau khi tạo; hai vai trò trưởng nhóm/thành viên; không tự hết hạn lời mời. Đây là mặc định thiết kế, chưa phải các quyết định đã được người dùng xác nhận riêng.
- Ngoài phạm vi: screen sharing/MediaProjection, sửa nội dung gốc PDF hoặc Word/PowerPoint, OCR/text-selection highlight, client web/laptop, recording, nhắn tin chữ nhóm, gọi 1-1, remote control, đổ chuông khi app đóng, bài viết nhóm, nhóm công khai, xin tham gia, link/mã mời và nhiều cấp quản trị. Người dùng có thể chuyển Word/slide sang PDF trước khi upload.

## Capabilities

### New Capabilities
- `study-groups`: Tạo/quản lý nhóm, gọi thoại/video, kho PDF nhóm, ghi chú cộng tác bền vững và xuất PDF có đánh dấu.
- `study-group-membership`: Thành viên/lời mời/chuyển trưởng nhóm; quyền media, tài liệu và ghi chú, cùng thu hồi truy cập khi mất membership.

### Modified Capabilities
- Không sửa requirement của canonical spec hiện có. Không mở rộng contract chat 1-1 hoặc thông báo trong change này.

## Impact

- `app-user/.../ui/group/Group.kt`, `fragment_group.xml`: thay placeholder bằng danh sách nhóm và lời mời; thêm màn hình tạo/chi tiết/thành viên và ViewModel, UiState theo Fragment/XML/ViewBinding hiện có; dùng `Home.openDetail()` / `closeDetail()`.
- `backend/.../core/data/{model,remote,repository}/`: thêm model nhóm/thành viên/lời mời, GroupRemoteDataSource và GroupRepository; dùng ApiResponse/SafeApiCall và mở rộng mã lỗi có kiểm soát. Tái sử dụng repository môn học và bạn bè.
- `supabase/migrations/`: migration mới cho bảng, index, constraint, RLS và RPC; đồng bộ phần liên quan trong `supabase/schema.sql` khi apply, giữ thay đổi ngoài phạm vi đang có.
- `supabase/tests/study_groups/`, kiểm thử Kotlin và smoke test Android cho luồng nhiều tài khoản, quyền và tình huống cạnh tranh.
- Thêm LiveKit Android SDK tương thích, foreground service/permission microphone/camera và bộ quản lý cuộc gọi; không thêm quyền mediaProjection. Giữ API chat 1-1 hiện có.
- Thêm phiên gọi/admission/control jobs và Edge Functions token/webhook; thêm tài liệu, phiên bản, annotation/revision/op ledger, private Storage và kênh đồng bộ được phân quyền. Kotlin backend quản lý dữ liệu; app-user render PDF, annotation overlay và export.
- Thêm viewer/editor PDF trong `ui/group/`, data layer tài liệu, kiểm thử tọa độ đa thiết bị, reconnect/conflict, private Storage và xuất PDF độc lập; dùng API PDF nền tảng trước, kiểm chứng giới hạn bộ nhớ/chất lượng khi apply.
- Dùng LiveKit Cloud Build cho demo trong hạn mức, theo dõi minutes/bandwidth và lỗi quota. Cần cấu hình project/secret phía server để nghiệm thu media thực; tài liệu kế hoạch không tự tạo tài khoản, mua gói hoặc deploy hosted.
