## Why

Ảnh đại diện hiện chỉ được lưu trên thiết bị trong tính năng profile đã triển khai ở nhánh `main`, nên người khác và thiết bị khác không thấy ảnh mới. Cần áp dụng nguyên lý upload ảnh của post: đưa ảnh lên Supabase Storage và lưu URL vào dữ liệu profile để hiển thị thống nhất sau khi tải lại profile.

## What Changes

- Upload, thay thế và xóa avatar trên Supabase Storage; lưu URL HTTPS vào `profiles.avatar_url`.
- Giữ chọn ảnh, preview và xử lý ảnh an toàn hiện có: đầu vào tối đa 10 MiB, chỉnh hướng, cạnh dài tối đa 1024 px, JPEG quality 85, chạy ngoài main thread.
- Dùng bucket `avatars` riêng với đường dẫn theo tài khoản; kiểm soát ghi/xóa bằng quyền phía server. Tái sử dụng Supabase client và cách upload/lấy URL của post, không dùng `post_files` cho avatar.
- Thêm RPC cập nhật avatar của chính tài khoản đăng nhập, giữ nguyên RPC sửa thông tin cá nhân và contract `ApiResponse` của baseline profile.
- Phản ánh kết quả lưu vào profile, drawer và các nơi hiển thị avatar; hỗ trợ lỗi upload, lưu một phần, retry và chuyển tài khoản.
- **BREAKING (hành vi)**: Sau chuyển đổi, avatar đã lưu lấy từ server; xóa ảnh áp dụng cho tài khoản trên các thiết bị sau khi refresh, thay cho chỉ ẩn trên máy. Ảnh local cũ không tự upload và không tiếp tục ghi đè ảnh remote; cho phép chủ tài khoản chủ động chọn dùng ảnh cũ để upload.

## Capabilities

### New Capabilities

- `remote-profile-avatar`: Chọn/xử lý ảnh, upload và công bố avatar, thay thế/xóa, quyền truy cập, đồng bộ hiển thị và chuyển tiếp từ avatar local.

### Modified Capabilities

Không có canonical spec trong `openspec/specs/` tại checkout hiện tại hoặc baseline `main` đã kiểm tra. Capability mới thay thế hành vi runtime của `local-profile-avatar` trong change đã archive; giữ nguyên archive, không viết delta MODIFIED trỏ đến canonical spec chưa tồn tại.

## Impact

- Baseline triển khai: `main` tại `3f3d39612a5e9b04544e6f3633f632354ca4e2f1`, đã đọc code bằng Git. Nhánh hiện tại `codex/ai-workflow-skills` thiếu post/profile hoàn chỉnh và SQL; phải đưa change sang baseline có các tính năng này trước khi apply, không tái xây dựng chúng trong phạm vi avatar.
- `backend`: avatar repository/local image processing, remote data source mới cho Storage, profile remote/repository, lỗi và operation liên quan avatar.
- `app-user`: edit/profile ViewModels, image loader, trạng thái auth/drawer và các consumer avatar đã có.
- Supabase: migration bucket/RLS và RPC avatar, rà soát quyền trực tiếp trên `profiles.avatar_url`, đồng bộ schema snapshot; không chạy migration production trong proposal.
- Kiểm tra repository/state, xử lý ảnh trên Android, SQL authorization trên môi trường test và smoke test hai tài khoản/hai thiết bị.
- Không đổi luồng upload post, không thêm camera/crop UI, không nâng toolchain hoặc thêm dependency nếu chưa có nhu cầu được chứng minh. Public avatar đọc được bởi người có URL, tương tự ảnh post; đây là quyết định cần review trước apply.
