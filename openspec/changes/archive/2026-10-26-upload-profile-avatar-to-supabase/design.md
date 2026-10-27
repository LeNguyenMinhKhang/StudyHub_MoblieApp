## Context

Yêu cầu tiếp nối profile local-avatar đã archive, dùng cơ chế upload ảnh của post. Tại thời điểm khảo sát, checkout là `codex/ai-workflow-skills`: chỉ có nền auth và profile placeholder; `openspec/specs` trống. Baseline đọc trực tiếp bằng `git show main:<path>` là `3f3d39612a5e9b04544e6f3633f632354ca4e2f1`. Các đường dẫn bên dưới mô tả file trên baseline đó, không khẳng định chúng tồn tại trong checkout proposal.

| Baseline path | Hành vi đã xác minh / thay đổi dự kiến |
| --- | --- |
| `backend/src/main/java/com/doan/studyhub/core/data/remote/PostRemoteDataSource.kt` | Upload bytes, lấy public URL, insert `post_files`; thử `documents` rồi `posts`. Chỉ tham khảo cơ chế Storage. |
| `backend/src/main/java/com/doan/studyhub/core/data/local/LocalAvatarDataSource.kt` | Xử lý ảnh và lưu local theo account; giữ staging, thay vai trò committed override. |
| `backend/src/main/java/com/doan/studyhub/core/data/repository/AvatarRepository.kt` | `commit`/`hide` đang ghi local; chuyển orchestration sang Storage + RPC qua `ApiResponse`. |
| `backend/src/main/java/com/doan/studyhub/core/data/remote/ProfileRemoteDataSource.kt` | RPC text chỉ nhận username/displayName/bio; bổ sung phương thức riêng cập nhật avatar. |
| `app-user/src/main/java/com/doan/studyhub/user/ui/profile/EditProfileViewModel.kt` | Text lưu trước, avatar sau, có partial save; giữ ngữ nghĩa này cho remote avatar. |
| `app-user/src/main/java/com/doan/studyhub/user/ui/profile/AvatarImageLoader.kt` | LOCAL/HIDDEN đang ghi đè remote; committed avatar chuyển sang remote, local chỉ cho draft preview. |
| `supabase/schema.sql` | Có `avatar_url`, bucket post public và policy ghi/xóa chỉ kiểm tra bucket; cần bucket avatar và policy độc lập. |

Đã đọc proposal/spec archive từ Git. Không có canonical spec để sửa bằng MODIFIED; spec mới `remote-profile-avatar` mô tả trọn hành vi thay thế. Live schema, bucket và RLS chưa được kiểm tra; SQL trong Git không chứng minh môi trường đã deploy.

## Goals / Non-Goals

**Goals:** avatar lưu tập trung, xem được qua URL như ảnh post; chọn/preview an toàn; upload/thay/xóa có kết quả chính xác; chỉ chủ tài khoản được ghi; giữ kiến trúc Kotlin + Supabase + Fragment/XML và `ApiResponse` của baseline.

**Non-Goals:** sửa upload hoặc bảo mật post, refactor Storage dùng chung toàn dự án, camera/crop UI, đồng bộ realtime, tự upload ảnh local cũ, viết lại profile/auth để bù phần thiếu của nhánh hiện tại, chạy migration production.

## Decisions

### 1. Bucket riêng, URL là nguồn hiển thị sau lưu

Dùng public bucket `avatars` và key `{authUserId}/{UUID}.jpg`. Mỗi lần thay ảnh dùng key mới, không upsert để tránh cache cũ và ghi đè một object đang được dùng. Upload content type `image/jpeg`, giới hạn bucket tối đa 10 MiB và MIME JPEG; client kiểm tra cả ảnh sau encode trước upload. Lưu URL HTTPS vào cột `profiles.avatar_url` đã có, không tạo `post_files`.

Public là cùng mô hình đọc với ảnh post: ai có URL đều có thể tải ảnh, không phải chỉ người đã đăng nhập. Nếu review yêu cầu private avatar thì phải sửa design/spec sang signed URL trước apply. Dùng `documents` sẽ bị ảnh hưởng policy hiện tại quá rộng; bucket riêng tách quyền mà không đổi post. Không fallback bucket khi lỗi vì sẽ che lỗi cấu hình/quyền.

### 2. Giữ pipeline ảnh và phân tách data layer

Giữ `stage(uri)` ngoài main thread: stream-limit 10 MiB, decode có giới hạn, chỉnh EXIF, resize tối đa 1024 px, re-encode JPEG quality 85. Preview từ draft; không upload khi mới chọn ảnh. Đọc draft/upload trong dispatcher phù hợp, không đưa ContentResolver/network vào Fragment.

Thêm `AvatarRemoteDataSource` cho upload/delete và dùng `ProfileRemoteDataSource` cho RPC. `AvatarRepository` điều phối và trả `ApiResponse` với profile mới; mở rộng operation/error phù hợp remote storage, không dùng thông báo thiếu bộ nhớ thiết bị cho mọi lỗi upload. Rethrow `CancellationException`, log có trace nhưng không log bytes/token hay thông tin nhạy cảm. Không đổi contract chung của post hoặc trở lại `Resource` do nhánh proposal cũ hơn.

### 3. Quyền phía server và RPC avatar riêng

Đề xuất RPC `update_my_avatar(p_object_path text, p_expected_avatar_url text)` trả profile mới. Null path nghĩa là xóa. RPC lấy chủ thể bằng `auth.uid()`, từ chối unauthenticated/banned, kiểm tra path theo UID và object JPEG tồn tại trong `avatars`, tạo URL từ base URL dự án được cấu hình tin cậy ở server; không nhận URL tùy ý từ client. Cách cấu hình base URL phải xác minh với môi trường test trong preflight và không chứa secret.

Khóa hàng profile khi cập nhật và so sánh nullable expected URL (`IS NOT DISTINCT FROM`) để phát hiện cập nhật từ thiết bị khác; conflict trả lỗi có cấu trúc, yêu cầu refresh rồi thử lại. Chỉ cập nhật avatar và timestamp do DB quản lý; không đổi text/admin fields. Giữ nguyên RPC `update_my_profile` cho tương thích client text.

Storage INSERT chỉ cho authenticated, account hợp lệ và prefix đúng UID; không cấp UPDATE khi dùng immutable key. DELETE chỉ cho object của chính UID và không còn được profile tham chiếu. RPC chỉ cho công bố object mới chưa được dùng trong profile (hoặc idempotent cùng URL); UI không cho khôi phục một key cũ đang chờ cleanup. Policy tổng thể phải được kiểm tra để không có policy permissive khác mở quyền bucket này.

Rà soát direct table grants/RLS và write paths của profile: client không được vượt validation qua UPDATE/INSERT trực tiếp `avatar_url`. Dùng column grants hoặc guard phía DB phù hợp sau preflight; phải giữ các trường hợp tạo profile/auth/post hiện có hoạt động. Đây là phạm vi quyền avatar, không đại tu toàn bộ RLS. Kiểm tra A/B, anonymous, banned, URL ngoài bucket và các trường admin trên DB test.

### 4. Thứ tự lưu và khôi phục lỗi

Replacement: giữ draft + owner + expected URL → upload key mới → RPC publish → cập nhật profile/UI → dọn object cũ thuộc bucket avatar của ứng dụng. Upload hoặc RPC thất bại xác định: ảnh cũ còn nguyên, giữ draft retry; object upload mới chưa được publish được dọn best effort. Không xóa URL avatar bên ngoài hệ thống hoặc gallery source.

Timeout/cancellation sau khi gửi RPC là kết quả chưa rõ: không xóa ngay object mới. Đọc lại profile của cùng owner để đối soát; URL mới khớp thì ghi nhận thành công. Nếu không thể đối soát, giữ trạng thái cần kiểm tra lại và record cleanup, không báo thành công giả hoặc retry mù. Trước cleanup phải xác minh object không còn được tham chiếu, policy DELETE cũng bảo vệ object đang dùng.

Removal: RPC đặt `avatar_url = null` trước, rồi dọn object cũ; lỗi cleanup không đảo ngược kết quả lưu. Khi thành công, trả profile mới dù chỉ thay/xóa ảnh. Text + ảnh: lưu text trước; nếu text lỗi không publish avatar, nếu text đã lưu nhưng ảnh lỗi thì báo partial save và retry chỉ phần ảnh.

Ghi record thao tác/cleanup theo account trước upload (key, expected URL, trạng thái); thử đối soát và dọn lại khi cùng account mở tính năng hoặc retry. Không thực hiện cleanup bằng session account khác. Process death hoặc người dùng không quay lại có thể để lại object mồ côi; không hứa transaction xuyên Storage/Postgres hay garbage collection tức thời. Giới hạn cleanup ở key được app ghi nhận, không liệt kê/xóa toàn bucket.

### 5. Hiển thị và tương thích local cũ

Sau save, trả và phát profile mới cho AuthViewModel/profile/drawer; consumer post đang hiển thị avatar được làm mới theo cơ chế refresh hiện có. Thiết bị khác và public profile đọc URL mới khi tải lại; không thêm realtime. Trong editor vẫn preview draft, còn màn hình thường dùng URL server hoặc placeholder.

LOCAL/HIDDEN cũ không được ưu tiên hơn remote sau nâng cấp. Giữ file local cũ theo account để có lựa chọn rõ ràng “Dùng ảnh trên thiết bị” trong editor; chỉ upload sau thao tác chọn và Lưu. Không tự công bố ảnh từng là riêng tư. Chỉ dọn file đã được dùng thành công hoặc người dùng chủ động bỏ; tài khoản B không đọc bản local của A. Session đổi giữa request: không phát kết quả A vào UI B; cleanup của A chờ session phù hợp.

## Risks / Trade-offs

- Public URL có thể được tải hoặc cache bên ngoài → ghi rõ trong review; xóa không thu hồi bản sao đã tải và có thể chịu độ trễ cache.
- Storage + Postgres không có transaction chung → immutable key, kiểm tra expected URL, đối soát timeout và cleanup best effort; chấp nhận orphan nếu app không quay lại.
- Schema thực tế lệch Git → preflight bắt buộc trước migration; chưa coi SQL hiện tại là đã deploy.
- Nhánh hiện tại thiếu implementation baseline → tích hợp planning change vào nhánh có baseline rồi mới apply; không tự chuyển nhánh trong proposal.
- Thu hẹp quyền profile có thể ảnh hưởng auth/post → kiểm tra tất cả write paths và regression trên môi trường test trước phát hành.
- Client cũ vẫn giữ local override → bảo đảm không phá RPC text; đồng bộ avatar cam kết cho phiên bản mới, không thể thay hành vi client đã cài cũ từ server.

## Migration Plan

1. Review public-read, chuyển tiếp local và baseline; đưa planning change sang nhánh có post/profile nêu trên, kiểm tra lại diff/schema.
2. Đọc cấu hình Supabase test, grants/policies/provisioning thực tế và phiên bản SDK trong version catalog. Thiết lập trusted base URL, migration bucket/RPC/permissions và test SQL trước app.
3. Deploy migration qua quy trình dự án sau review riêng; xác nhận text RPC/auth/post không bị ảnh hưởng. Không thực thi production trong bước proposal.
4. Phát hành app đổi avatar orchestration/UI; giữ draft/local cũ để lựa chọn có chủ đích, không bulk upload.
5. Smoke test hai account/hai thiết bị và lỗi mạng; kiểm tra orphan/cleanup logs đã sanitize.
6. Rollback app về phiên bản trước nếu cần, giữ bucket/object/URL đã công bố và RPC tương thích; không drop bucket hoặc xóa avatar người dùng. Rollback quyền/RPC bằng migration được review, không bật lại grant rộng một cách tự động.

## Open Questions

Không có quyết định sản phẩm đang chặn việc hoàn thiện proposal: mặc định public avatar và không tự upload local đã được chọn để review. Các điều kiện trước apply là baseline phù hợp, phê duyệt thiết kế quyền/public-read và xác minh live/test schema + trusted base URL. Thiếu môi trường test phải được báo rõ, không đánh dấu task SQL/E2E đã hoàn tất.
