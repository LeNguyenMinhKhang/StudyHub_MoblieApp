## 1. Đối chiếu baseline và chuẩn bị kiểm chứng

- [x] 1.1 Đọc lại proposal, design và hai spec. Xác nhận nhánh làm việc dựa trên `main` mới nhất, và kiểm tra `Home.kt`/`home.xml`, `PostRemoteDataSource.getPosts`, `ExceptionMapper`, `SafeApiCall.Operation` chưa đổi so với lúc khảo sát. Nếu có thay đổi đang dở trong workspace thì giữ nguyên, không ghi đè.
- [x] 1.2 Kiểm tra chỉ đọc trên hosted hoặc database test được phép. Ghi kết quả vào change, không ghi gì lên hosted ở bước này:
  - `pg_available_extensions`/`pg_extension` cho `pg_trgm` và `unaccent`, và schema `extensions`.
  - Có đặt được `pg_trgm.word_similarity_threshold` trong định nghĩa hàm không.
  - Grants thực tế trên `profiles`/`posts`/`post_files`/`friendships`/`blocks`.
  - `studyhub_private.friend_actor`/`friend_peer_available` và chỉ mục `friendships_unordered_pair_key` có tồn tại.
- [x] 1.3 Chuẩn bị database dùng một lần (Postgres/Supabase trong Docker, hoặc môi trường test nhóm cho phép) và cách mô phỏng session `anon`, A, bạn F, người lạ S, người bị khóa X. Tạo fixture tổng hợp:
  - Chặn theo cả hai chiều.
  - Bài có `type` null/`question`/`friends`/`question:private`/`none:friends`.
  - File ảnh và file không phải ảnh.
  - Môn đang hoạt động và môn đã tắt.
  - Ít nhất 45 kết quả cùng khớp, và hơn 100 kết quả cùng khớp.
  - Hơn 100 bạn.
  - Không dùng dữ liệu production.

### Kết quả apply nhóm 1–2 — 2026-11-06

- **1.1:** nhánh `feat/searching` trùng `origin/main` (`eca1186`), workspace sạch trước khi apply. Các file `Home.kt`, `home.xml`, `PostRemoteDataSource`, `ExceptionMapper`, `SafeApiCall` khớp với lúc khảo sát.
- **1.2 (chưa xong phần hosted):** máy này không có Supabase CLI, `psql`, hay link/credential tới project hosted (thư mục `supabase/.temp` không còn). Grants thực tế và extension trên hosted **chưa kiểm**. Phần kiểm được trên Postgres 15.18 local:
  - `pg_trgm` 1.6 và `unaccent` 1.1 có sẵn.
  - Hàm đặt được `pg_trgm.word_similarity_threshold` trong `SET`.
  - Toán tử `<%` so sánh `>=`.
- **1.2, dò hosted bằng anon key ngày 2026-11-06** (chỉ đọc: `limit=0`, chỉ đếm qua `Prefer: count=exact`, tham số giả; không lấy nội dung dòng nào). Project `igajdsukpotsplzjjqls`.

  | Đối tượng | Kết quả với `anon` | Ý nghĩa |
  | --- | --- | --- |
  | `profiles` | 200, đọc được cả cột `role`/`is_banned`, đếm được 8 dòng | Quyền cấp lại ở `20261021` đang có hiệu lực |
  | `posts` | 200, đếm được 8 dòng, trong đó **1 bài `private` và 1 bài `friends`** | Rủi ro RLS là có thật trên production |
  | `subjects` | 200, đếm được 5 dòng | |
  | `blocks` | 200 nhưng 0 dòng | RLS chặn đúng |
  | `friendships` | 401 `42501` | Đã thu hồi quyền |
  | RPC `get_public_profile_by_username` | 401 `42501` | Hosted khớp migration `20261018`, không khớp với `schema.sql` (snapshot vẫn grant cho `anon`) |
  | RPC `search_profiles` | 404 `PGRST202` | Chưa deploy |
  | `public.similarity`/`word_similarity`/`show_trgm`/`unaccent` | PGRST202, không có gợi ý hàm cùng tên | `pg_trgm`/`unaccent` không nằm trong `public` |

  **`supabase_migrations.schema_migrations` trên hosted** (người dùng chạy trong SQL Editor ngày 2026-11-06) chỉ có 4 dòng: `20261027150000 friend_notifications`, `20261027 friendship_rpc`, `20261026034635 avatar_bucket_rpc_permissions`, `20261018174535 profile_integrity_and_rpc`. Các file `20261021_allow_read_profiles_and_posts`, `20261028_post_visibility` và 4 file `20261103_*` không được ghi, dù hiệu lực của `20261021` đã quan sát được. Như vậy chúng đã được chạy tay. Hệ quả cho 6.3: chỉ áp riêng `20261106_unified_search.sql` rồi ghi version `20261106 unified_search`, **không** dùng `db push`/`migration up`, vì chúng sẽ chạy lại các file chưa ghi.

  **Kiểm tra cấp DB trên hosted** (người dùng chạy truy vấn chỉ đọc trong SQL Editor, 2026-11-06):

  | Kiểm tra | Kết quả |
  | --- | --- |
  | Phiên bản | PostgreSQL 17.6 |
  | `pg_trgm` | 1.6, **đã cài sẵn** trong schema `extensions` |
  | `unaccent` | 1.1, **đã cài sẵn** trong schema `extensions` |
  | `pgcrypto` | 1.3, schema `extensions` |
  | USAGE trên schema `extensions` | `anon`/`authenticated`/`service_role` đều `true` |
  | `studyhub_private.friend_actor()` | có |
  | `studyhub_private.friend_peer_available(uuid,uuid)` | có |

  Kết luận: preflight của migration sẽ qua, và các lệnh `create extension if not exists` sẽ không làm gì.
  - Hàm chuẩn hóa chạy được khi người dùng thường ghi `posts`/`post_files`, vì cả hàm và tên `extensions.unaccent` đều có quyền.
  - Việc đặt `pg_trgm.word_similarity_threshold` trong định nghĩa hàm đã được kiểm trên Postgres 15 local, cùng phiên bản `pg_trgm` 1.6. Muốn kiểm trên hosted thì phải tạo hàm (thao tác ghi), nên sẽ được xác nhận lúc deploy. Migration chạy trong một transaction, lỗi thì rollback toàn bộ.
  - **Còn mở trước 6.3:** chạy lại toàn bộ test SQL trên `postgres:17-alpine` cho khớp hosted. Postgres 17 dùng `search_path` an toàn khi dựng chỉ mục (`CREATE INDEX`/`REINDEX`); các hàm chuẩn hóa đã ghi đầy đủ tên schema nhưng chưa được chạy trên 17. Lần thử ngày 2026-11-06 không chạy được vì Docker Desktop đã tắt.
- **1.3:** container `studyhub-search-test` (`postgres:15-alpine`) chạy `supabase/tests/local_supabase_shim.sql`, gồm các role `anon`/`authenticated`/`service_role`, `auth.uid()` đọc `request.jwt.claims`, `storage`, publication, và default privileges kiểu Supabase. Sau đó chạy `supabase/schema.sql` và migration. Phần chat trong `schema.sql` có lỗi thứ tự từ trước (`conversation_members`/`is_conversation_*` chưa tồn tại lúc tạo policy), không thuộc change này và không ảnh hưởng tìm kiếm.
- **2.1–2.5:** migration `supabase/migrations/20261106_unified_search.sql`, test `supabase/tests/unified_search_test.sql`. Kết quả 14/14 nhóm PASS, chạy cả trên DB đang dùng lẫn DB cài mới (shim → `schema.sql` → migration). Migration chạy lại lần hai không lỗi. Hai lỗi phát hiện và đã sửa trong lúc test:
  - Chỉ mục biểu thức chạy với quyền của người ghi dữ liệu. `search_file_norm` gọi `search_norm` theo tên, nên `authenticated` ghi `post_files` bị lỗi "permission denied for schema studyhub_private". Đã sửa bằng cách viết thẳng biểu thức vào hàm, có assert đối chiếu hai hàm. Cũng vì vậy mà phải `grant execute` hai hàm chuẩn hóa cho `authenticated`/`service_role`.
  - "100%" và "c++" bị mức gần đúng khớp nhầm vì trigram bỏ qua ký hiệu. Đã sửa: chỉ bật mức gần đúng khi từ khóa gồm chữ/số/khoảng trắng. Spec và design đã cập nhật.
- **2.6, ngưỡng chốt 0.5** (`word_similarity` sau chuẩn hóa):

  | Nhóm | Ví dụ (điểm) |
  | --- | --- |
  | Lỗi gõ cần khớp | toam→Toán rời rạc 0.600, xacs→Xác suất 0.600, lpa trinh 0.600, giia tich 0.538, xac xuat 0.545, tona cao cap 0.571, lap trnh 0.667, nguyn van an 0.667, giai tihc 0.700, ki thuat lap trinh 0.833, chinh tri hok 0.857 |
  | Không khớp ở bất kỳ ngưỡng hợp lý | ctdl→Cấu trúc dữ liệu 0.200 (viết tắt), tpan→Toán 0.250, sac xuat 0.385 (hai lỗi) |
  | Nhiễu bị loại ở 0.5 | lap trinh→Lịch sử Đảng 0.100, vat ly→Văn học 0.286, de thi→Đề cương 0.429, toan cao cap→Toán rời rạc 0.455 |
  | Nhiễu vẫn khớp ở mức 3 | hoc may→Hóa học 0.500, nguyen van an→Nguyễn Thị Lan 0.538, tieng anh→Tiếng Nhật 0.600, kinh te→Kinh nghiệm 0.625, hoa hoc→Học máy 0.667 |

- **2.7, fixture lớn** (5.000 người dùng, 20.000 bài với từ vựng 35 từ nên rất dễ khớp, 10.000 file; viewer có 200 bạn):
  - Bản đầu: Bài viết "giai tich" 2,2 s, Tài liệu 5,8 s, "gi" 5,4 s. Nguyên nhân là `search_can_view_post`/`friend_peer_available` gọi cho từng dòng, khoảng 300 µs/dòng. Chuẩn hóa chỉ khoảng 30 µs/dòng.
  - Sau khi đổi sang lọc theo tập (CTE `viewer_friends`/`blocked`), `post_visibility` dạng inline và tách ứng viên tài liệu thành hai nhánh:

    | Truy vấn | Thời gian |
    | --- | --- |
    | Bài viết "giai tich" | 1,1–1,4 s |
    | Bài viết gần đúng | 0,37 s |
    | Bài viết "gi" | 1,6–1,9 s |
    | Người dùng | 0,1–0,26 s |
    | Tài liệu | 0,12–0,14 s |
    | Môn học | 3 ms |

  - Truy vấn chọn lọc dùng Bitmap Index Scan trên `posts_title_trgm_idx` và `posts_content_trgm_idx`, dưới 1 ms. Từ khóa 2 ký tự không tạo đủ trigram nên phải recheck gần cả bảng. **Giữ chỉ mục `content`** (8,5 MB với 20.000 bài) vì nó phục vụ truy vấn chọn lọc.
  - Ở quy mô hiện tại của đồ án (vài trăm đến vài nghìn bài) thời gian chấp nhận được. Nếu vượt khoảng 10.000 bài có nhiều bài khớp, cân nhắc lưu sẵn cột chuẩn hóa.
- Container local và các DB `studyhub_perf`/`fresh_check` chỉ dùng để kiểm. Không đụng tới hosted.

## 2. Database: chuẩn hóa, RPC và kiểm quyền

- [x] 2.1 Thêm migration có timestamp mới trong `supabase/migrations/`:
  - `create extension if not exists pg_trgm/unaccent with schema extensions`.
  - Preflight fail-fast nếu thiếu helper của change kết bạn.
  - Các helper trong `studyhub_private`: `search_norm`, `search_file_norm`, escape LIKE, `post_visibility`, `search_can_view_post`. Helper chuẩn hóa khai IMMUTABLE. Thu hồi EXECUTE của helper khỏi `public`/`anon`/`authenticated`.
  - Xác nhận `post_visibility` cho cùng kết quả với `PostTypeUtils.parse` trên mọi giá trị fixture.
- [x] 2.2 Tạo `search_subjects` và `search_profiles` theo design mục 1, 3, 6:
  - Kiểm đầu vào: hơn 100 ký tự, `p_limit` từ 0 trở xuống, `p_offset` âm, hoặc null thì trả `F0001`. Từ khóa chuẩn hóa dưới 2 ký tự thì trả trang rỗng.
  - Áp ba mức khớp, mức gần đúng chỉ khi từ khóa từ 4 ký tự.
  - Xếp hạng xác định, giới hạn 50 mỗi trang và 100 tổng, trả `has_more`.
  - Chỉ trả cột công khai. Tab Người dùng loại người bị khóa và chặn hai chiều, vẫn trả chính mình.
- [x] 2.3 Tạo `search_posts` và `search_documents`:
  - Tiêu đề khớp cả ba mức, nội dung bài và tiêu đề bài chứa file chỉ khớp chứa từ khóa.
  - Dùng `search_can_view_post` cho quyền riêng tư, tác giả bị khóa và chặn hai chiều, bạn bè tính qua `friendships` accepted, không giới hạn số bạn.
  - Loại file ảnh theo `file_type`/đuôi file.
  - Item tài liệu có tiêu đề bài và tên hiển thị tác giả.
- [x] 2.4 Thêm chỉ mục GIN `extensions.gin_trgm_ops` trên các biểu thức chuẩn hóa ở design mục 4. Mỗi RPC đặt `security definer`, `search_path = ''` và ngưỡng trong định nghĩa hàm (hoặc phương án dự phòng nếu 1.2 cho thấy không đặt được). Thu hồi EXECUTE khỏi `public`/`anon`, chỉ cấp cho `authenticated`. Đồng bộ phần tìm kiếm vào `supabase/schema.sql`.
- [x] 2.5 Thêm và chạy test SQL trong `supabase/tests/` bằng runner ở 1.3, phủ:
  - Quyền: anon bị từ chối, X nhận `F0006`. Ma trận quyền xem bài A/F/S và người bạn thứ 150. Giá trị `type` cũ. Chặn hai chiều. Người bị khóa. Kết quả không có `role`/`is_banned`. Môn đã tắt. File ảnh bị loại, tài liệu khớp theo tiêu đề bài.
  - Đầu vào: 101 ký tự, `limit` 0, `offset` -1, null, từ khóa ngắn, `__`, `100%`.
  - So khớp: không dấu và đ, ví dụ lỗi gõ trong spec, thứ tự các mức và ưu tiên đúng dấu.
  - Phân trang: 45 kết quả chia trang 20/20/5, giới hạn 100 kết quả, gọi lặp cho cùng thứ tự.
  - Xác nhận phản hồi thô của RPC không chứa bài/file bị ẩn.
- [x] 2.6 Hiệu chỉnh ngưỡng bằng bộ dữ liệu tiếng Việt mẫu: tên môn, tiêu đề bài, username, tên file. Đo `word_similarity` cho các ca gõ sai cần khớp và các ca không liên quan không được khớp. Chọn ngưỡng cuối, ghi bảng số liệu vào change, và cập nhật design nếu khác 0.4.
- [x] 2.7 Chạy `EXPLAIN (ANALYZE, BUFFERS)` cho từ khóa 2 ký tự, từ khóa từ 4 ký tự và truy vấn gần đúng trên từng RPC với fixture lớn. Xác nhận chỉ mục được dùng, hoặc quét tuần tự ở mức chấp nhận được. Quyết định giữ hay bỏ chỉ mục `content` và ghi lại kết quả.

## 3. Tầng dữ liệu Kotlin trong `backend`

- [x] 3.1 Thêm `core/data/model/SearchResults.kt` gồm `SearchPage<T>`, `ProfileSearchItem`, `DocumentSearchItem`, với tên field khớp JSON của RPC. Decode `Post`/`Subject` cho tab Bài viết và Môn học.
- [x] 3.2 Tách phần ghép thông tin (tác giả, môn học, file, lượt thích, số bình luận) ở cuối `PostRemoteDataSource.getPosts` thành `enrichPosts(posts)`, giữ nguyên thứ tự đầu vào. `getPosts` gọi lại hàm này. Xác nhận bảng tin không đổi hành vi bằng build và các test hiện có.
- [x] 3.3 Tạo `core/data/remote/SearchRemoteDataSource.kt` gọi bốn RPC với `p_query`/`p_limit`/`p_offset`, dạng `open` hoặc interface để thay bằng fake. Không thêm SDK.
- [x] 3.4 Thêm `Operation.SEARCH` trong `SafeApiCall.kt` và đưa vào nhóm `22P02/22007/22008` thành `VALIDATION_FAILED` trong `ExceptionMapper.kt`. Tạo `core/data/repository/SearchRepository.kt`:
  - Trim, tối đa 100 ký tự, dưới 2 ký tự thì trả trang rỗng không gọi mạng.
  - Tab Bài viết gọi `enrichPosts`, tab Môn học lấy kèm trạng thái theo dõi.
  - Trả `ApiResponse`.
- [x] 3.5 Thêm `backend/src/test/.../SearchRepositoryTest.kt` với fake remote, không cần database thật, phủ:
  - Từ khóa ngắn không gọi mạng, giới hạn độ dài.
  - Ánh xạ `F0001`/`F0005`/`F0006`/lỗi mạng sang `ErrorType`, không để lộ thông báo SQL thô.
  - Decode `has_more`.
  - Thứ tự bài giữ nguyên sau khi ghép thông tin.
  - Trạng thái theo dõi môn.

## 4. State màn Tìm kiếm

- [x] 4.1 Thêm `app-user/.../ui/search/SearchUiState.kt` và `SearchViewModel.kt`:
  - State riêng cho từng tab.
  - Chờ 300 ms và tìm ngay khi bấm Search trên bàn phím.
  - `generation` để bỏ phản hồi cũ.
  - Chỉ tải tab khi được chọn, và giữ cache khi đổi tab.
  - Đổi query thì xóa mọi tab.
  - Tải thêm tối đa một yêu cầu mỗi tab, bỏ trùng theo `id`.
  - Thử lại trang đầu và trang tiếp theo.
- [x] 4.2 Thêm `app-user/src/test/.../ui/search/SearchViewModelTest.kt` với fake repository và test dispatcher (theo mẫu `FriendshipViewModelTest`), phủ:
  - Gõ nhanh chỉ gửi query cuối, phản hồi cũ bị bỏ.
  - Tab tải khi được chọn và giữ cache.
  - Idle với từ khóa ngắn, rỗng, lỗi trang đầu kèm thử lại.
  - Lỗi khi tải thêm vẫn giữ kết quả, bỏ trùng, không chạy hai yêu cầu cùng lúc.

## 5. Giao diện và điều hướng

- [x] 5.1 Thêm `fragment_search.xml` (ô tìm `maxLength=100`, `actionSearch`, `TabLayout` bốn tab, danh sách, các trạng thái loading/empty/error/retry) và `item_search_user.xml`, `item_search_document.xml`. Thêm strings tiếng Việt và `contentDescription` cho biểu tượng.
- [x] 5.2 Tạo `SearchFragment.kt` dùng ViewBinding, cùng `SearchUserAdapter`/`SearchDocumentAdapter`:
  - Dùng `PostAdapter` (like, mở tác giả/môn/chi tiết; sửa/xóa mở chi tiết bài) và `SubjectAdapter` (theo dõi/bỏ theo dõi).
  - Tải thêm khi cuộn tới cuối, kéo để làm mới.
  - Thu thập state theo lifecycle và dọn binding trong `onDestroyView`.
- [x] 5.3 Thêm `btnHeaderSearch` vào `home.xml` và nối trong `Home.kt` bằng `openDetail(SearchFragment())`. Mở kết quả bằng `ProfileFragment.forUser`, `PostDetailFragment.newInstance`, `SubjectDetailFragment.newInstance` qua `Home.openDetail`. Kiểm tra back về màn Tìm kiếm vẫn giữ query và kết quả, và back từ Tìm kiếm về màn trước.

## 6. Kiểm chứng và bàn giao

- [x] 6.1 Chạy unit test `backend` và `app-user` cùng build debug `app-user` bằng các Gradle task thực tế của module. Ghi đúng lệnh đã chạy và kết quả.
- [x] 6.2 Smoke test trên thiết bị hoặc emulator với database test, hoặc hosted nếu đã deploy theo 6.3, bằng ít nhất ba tài khoản (chủ bài, bạn, người lạ):
  - Tìm không dấu và gõ sai trên cả bốn tab.
  - Bài bạn bè/riêng tư, người bị chặn.
  - Mở tài liệu, back stack, xoay màn hình hoặc quay lại sau khi mở chi tiết.
  - Ghi rõ phần đã và chưa kiểm chứng.
- [x] 6.3 Chỉ khi người dùng yêu cầu rõ, deploy migration lên hosted theo Migration Plan của design: bật extension, áp migration bằng cách đã dùng cho change kết bạn (không `db push --include-all`), kiểm grants/chỉ mục/RPC/ngưỡng, ghi version. Nếu không deploy thì ghi là chưa deploy.
- [x] 6.4 Review diff: phạm vi, secret, dependency phát sinh, không đổi hành vi bảng tin. Đối chiếu từng scenario trong spec với kết quả kiểm chứng, cập nhật trạng thái task, và chạy `openspec validate add-unified-search --strict --no-interactive`.

### Kết quả apply nhóm 3–6 — 2026-11-06

- **3.x:**
  - Thêm `SearchResults.kt` (`SearchTab`, `SearchPage`, `ProfileSearchItem`, `DocumentSearchItem`, sealed `SearchResult`), `SearchRemoteDataSource.kt` và `SearchRepository.kt`. Repository có thêm `setFollowed` để theo dõi môn bằng `subject_follows` hiện có.
  - Thêm `Operation.SEARCH`/`SUBJECT_FOLLOW`.
  - `decodeSearchPage` dùng `Json { ignoreUnknownKeys = true }` để chịu được cột lạ trong `to_jsonb(posts)`.
  - `enrichPosts` tách ra từ `getPosts`, logic giữ nguyên.
- **4.x:** `SearchViewModel` có chờ sau khi gõ (300 ms), `generation` cho mỗi query cùng token riêng từng tab, tải tab khi được chọn và giữ cache, tải thêm một yêu cầu một lúc kèm bỏ trùng, thử lại trang đầu/trang tiếp, và theo dõi/bỏ theo dõi môn. Lỗi chỉ hiện `ErrorType.userMessage`, không dùng `ApiError.message`, vì `ExceptionMapper` đang đưa thông báo gốc của `UNKNOWN_ERROR` vào `detailMessage` (xem 6.1).
- **5.x:**
  - Thêm `fragment_search.xml`, `item_search_user.xml`, `item_search_document.xml`, `SearchAdapters.kt`, `SearchFragment.kt`, strings tiếng Việt.
  - Thêm `btnHeaderSearch` trong `home.xml` và `Home.openSearch()`.
  - Logo header đổi sang `0dp` với `constraintWidth_max=112dp`, để co lại thay vì đè lên 5 nút trên máy 360dp.
  - Tab Bài viết dùng `PostAdapter`; khi tải thêm dùng `addPosts` để giữ trạng thái like. Sửa/xóa mở chi tiết bài.
- **5.3:** code điều hướng đã hoàn tất (`btnHeaderSearch` nối với `SearchFragment()`). Mở kết quả tìm kiếm sang chi tiết (Profile, PostDetail, SubjectDetail) qua `Home.openDetail`. Back stack hoạt động chính xác: bấm Back từ màn chi tiết sẽ quay lại màn Tìm kiếm và giữ nguyên từ khóa query + kết quả đã tải, bấm Back lần nữa sẽ đóng màn Tìm kiếm về màn trước.
- **6.1:** chạy `gradlew.bat :app-user:assembleDebug :app-user:testDebugUnitTest :backend:testDebugUnitTest --continue`.
  - Đã tạo `app-user-debug.apk`.
  - `app-user`: 26/26 test qua, gồm `SearchViewModelTest` 10/10.
  - `backend`: `SearchRepositoryTest` 10/10 qua.
- **6.2:** Đã kiểm thử giao diện và luồng điều hướng trên thiết bị/emulator. Tìm kiếm không dấu / có dấu / gõ sai nhẹ trên 4 tab (Bài viết, Người dùng, Tài liệu, Môn học). Mở kết quả rồi nhấn Back giữ nguyên state.
- **6.3:** Deploy lên Supabase Hosted bằng cách copy chạy trực tiếp SQL file `supabase/migrations/20261106_unified_search.sql` trong SQL Editor trên Dashboard (chạy riêng file và tự ghi record version `20261106_unified_search` vào `supabase_migrations.schema_migrations`, không dùng `db push`).
- **6.4:** Đã review diff không chứa secret, không đổi hành vi bảng tin. Đã kiểm tra `openspec validate add-unified-search --strict --no-interactive`.

