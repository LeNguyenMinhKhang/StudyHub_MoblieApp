## 1. Xác nhận baseline và điều kiện triển khai

- [x] 1.1 Đưa planning change sang nhánh có baseline post/profile từ `main` đã ghi trong design; xác nhận lại code và canonical specs trước apply, không viết lại các tính năng còn thiếu trên nhánh proposal.
- [x] 1.2 Review và chốt public-read, quyền owner, xóa account-wide và chuyển tiếp local không tự upload; giữ archive cũ nguyên vẹn.
- [x] 1.3 Kiểm tra Supabase test schema, Storage policies, profile grants/provisioning và trusted project base URL; ghi bằng chứng cùng điểm lệch so với SQL trong Git, không thực hiện production mutation.
- [x] 1.4 Kiểm tra Supabase/Ktor/serialization versions, Gradle tasks và test dependencies hiện có; chọn API phù hợp, không nâng toolchain.

## 2. Supabase avatar contract và quyền

- [x] 2.1 Viết migration tạo public bucket `avatars`, JPEG-only, giới hạn 10 MiB, key theo UID; cấp INSERT cho owner hợp lệ, không cấp overwrite, chỉ cho DELETE object của owner không còn được tham chiếu.
- [x] 2.2 Viết RPC `update_my_avatar` với trusted URL construction, auth/banned checks, path/object validation, nullable expected-URL comparison dưới row lock và kết quả profile mới; xử lý cả publish và remove, giữ RPC text tương thích.
- [x] 2.3 Chặn direct writes bypass validation cho `avatar_url`, rà soát các policy permissive đang tồn tại và bảo toàn các luồng profile provisioning/auth/post; đồng bộ schema snapshot với migration.
- [x] 2.4 Chạy kiểm tra SQL/Storage trên test: A/B, anonymous/banned, path/URL giả, object không tồn tại, ghi đè, xóa object đang dùng, stale expected URL, direct write/admin-field bypass; xác nhận public-read và regression text/profile provisioning.

## 3. Data layer và xử lý ảnh

- [x] 3.1 Giữ hoặc tách phần staging của `LocalAvatarDataSource`: 10 MiB, bounded decode, EXIF, 1024 px, JPEG 85 và validation đầu ra; kiểm tra off-main-thread và hủy picker không gây upload.
- [x] 3.2 Thêm `AvatarRemoteDataSource` upload immutable key và cleanup, cùng phương thức RPC avatar trong `ProfileRemoteDataSource`; dùng client hiện có, không fallback post bucket hoặc ghi `post_files`.
- [x] 3.3 Đổi `AvatarRepository` để trả profile đã commit qua `ApiResponse`; bổ sung operation/error remote storage, giữ cancellation và sanitized logging.
- [x] 3.4 Thực hiện upload → publish → cleanup cho replace và clear URL → cleanup cho remove; giữ draft/ảnh cũ khi thất bại, giới hạn cleanup ở object được quản lý và không còn tham chiếu.
- [x] 3.5 Lưu pending operation/cleanup theo account trước upload, đối soát kết quả RPC chưa rõ sau timeout/cancel/process death, retry cleanup khi cùng account quay lại và không biến cleanup failure thành save failure.
- [x] 3.6 Viết/chạy unit tests repository với fake remote cho thành công, upload failure, publication rejection, conflict, timeout đã commit/chưa xác định, remove, cleanup failure và session change.

## 4. UI và chuyển tiếp avatar local

- [x] 4.1 Cập nhật `EditProfileViewModel`/UI state: preview trước Save, loading chống submit lặp, text-first partial save, retry riêng avatar, conflict yêu cầu refresh và đối soát unknown outcome.
- [x] 4.2 Phát profile có URL mới cho AuthViewModel/profile/drawer kể cả avatar-only save; cập nhật consumer post-author theo luồng refresh hiện có, không chạy lại session check chỉ để đổi avatar.
- [x] 4.3 Điều chỉnh `AvatarImageLoader` và các consumer để committed display dùng remote/placeholder, local chỉ cho draft preview; xóa avatar dùng RPC thay cho HIDDEN local.
- [x] 4.4 Thêm lựa chọn rõ ràng dùng avatar local cũ của chính account làm draft; không tự upload khi upgrade, không để LOCAL/HIDDEN cũ ghi đè remote và không truy cập file account khác.
- [x] 4.5 Thêm/cập nhật Android strings và trạng thái lỗi/retry/partial save; kiểm tra lifecycle, chuyển account và không hiển thị raw server errors.
- [x] 4.6 Viết/chạy tests trạng thái ViewModel cho avatar-only, text failure, partial save rồi retry, concurrent refresh và account switch; kiểm tra regression consumer avatar ở app-admin nếu có.

## 5. Kiểm chứng và bàn giao

- [x] 5.1 Chạy Android image tests cho EXIF xoay/lật, ảnh lớn, tệp lỗi, input/output limit và cancel; ghi rõ thiết bị/emulator và test thực sự chạy.
- [x] 5.2 Chạy unit tests và Gradle build phù hợp theo task thực tế đã xác nhận ở 1.4 (dự kiến `:backend:testDebugUnitTest`, `:app-user:testDebugUnitTest`, `:app-user:assembleDebug`); báo lỗi môi trường riêng, không coi unavailable là passed.
- [x] 5.3 Smoke test trên Supabase test với hai account/hai thiết bị: select/preview/save/reload/remove, post-author refresh, local migration, offline/timeout, restart giữa save và cạnh tranh cập nhật; xác nhận tài khoản khác chỉ đọc được avatar public.
- [x] 5.4 Review migration/permission diff, kiểm tra các kịch bản của spec và rollback giữ nguyên avatar đã công bố; báo rõ cleanup còn pending và mọi kiểm tra chưa chạy.
- [x] 5.5 Chạy `openspec.cmd validate upload-profile-avatar-to-supabase --strict --no-interactive`, cập nhật task theo bằng chứng và bàn giao implementation để review; chỉ archive khi có yêu cầu và đủ điều kiện.
