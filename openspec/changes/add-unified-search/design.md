## Context

Kết quả khảo sát nhánh `feat/searching` ngày 2026-11-06, base `main` tại `eca1186`:

- **Chưa có màn hình Tìm kiếm.** Hiện chỉ có:
  - `ProfileLookupDialogFragment` tra cứu đúng nguyên username qua RPC `get_public_profile_by_username`.
  - `Subject.kt` tải mọi môn đang hoạt động rồi lọc trong bộ nhớ.
  - `PostCreateFragment` có hộp thoại chọn môn.
- **Điều hướng.** `Home.kt` dùng `childFragmentManager` với `requestRoot()`/`openDetail()`, không dùng `nav_graph`. Thanh đầu trang có `btnHeaderSubject`, `btnHeaderFriend`, `btnHeaderChat`, `btnHeaderNotification`.
- **Quyền riêng tư bài viết** được ghép vào `posts.type` dạng `<thể loại>:<quyền>`, xem `PostTypeUtils` và migration `20261028_post_visibility.sql`. Policy `posts_select_all using (true)` và `post_files_select_all using (true)` cho cả `anon` đọc (`20261021_allow_read_profiles_and_posts.sql`). `PostRemoteDataSource.getPosts` tải về rồi lọc quyền trong app, và chỉ lấy 100 bạn đầu tiên.
- **Profiles.** Migration `20261018` thu hồi quyền đọc bảng `profiles`, nhưng `20261021` cấp lại `select` cho cả `anon`/`authenticated`. Grants thực tế trên hosted chưa xác minh.
- **Blocks.** `blocks` chỉ cho người chặn đọc dòng của mình, nên client không biết chiều bị chặn.
- **Helper dùng lại được** (change kết bạn, đã deploy hosted):
  - `studyhub_private.friend_actor()` kiểm session và trạng thái bị khóa, trả mã lỗi `F0005`/`F0006`.
  - `studyhub_private.friend_peer_available(actor, target)` kiểm người bị khóa và chặn hai chiều.
  - `ExceptionMapper` đã ánh xạ `F0001` thành `VALIDATION_FAILED`, `F0005` thành `UNAUTHENTICATED`, `F0006` thành `ACCOUNT_BANNED`.
- **Extension.** Repo chỉ bật `pgcrypto`, chưa có `pg_trgm`/`unaccent`. Lịch sử migration trên hosted (`igajdsukpotsplzjjqls`) lệch với repo (ghi chú trong tasks của change kết bạn).
- **UI.** `material` 1.12 đã có `TabLayout`; `Friend.kt` là mẫu màn có tab bằng ViewBinding. `PostAdapter`/`SubjectAdapter` dùng lại được. Test hiện có: `FriendshipViewModelTest`, `NotificationViewModelTest`, `SafeApiCallTest`…
- Chưa truy cập database đang chạy. Docker Desktop đang tắt nên chưa đo được độ giống trigram trên dữ liệu thật.

## Goals / Non-Goals

**Goals:**
- Màn Tìm kiếm bốn tab, tìm không dấu và chịu lỗi gõ.
- Kiểm quyền trên server (quyền riêng tư bài, người bị khóa, chặn hai chiều, môn đang hoạt động).
- Xếp hạng ổn định, phân trang có giới hạn, trạng thái lỗi phục hồi được.
- Có test SQL theo ma trận tài khoản và test ViewModel.

**Non-Goals:**
- Hashtag, lịch sử tìm kiếm, gợi ý "Có phải bạn muốn tìm", tìm trong nội dung file.
- Sửa RLS hoặc grants của `posts`/`post_files`/`profiles` cho toàn hệ thống.
- Tách quyền riêng tư thành cột riêng, Realtime.
- Thêm dependency Android, production rollout khi chưa được yêu cầu.

## Decisions

### 1. Mỗi tab một RPC `SECURITY DEFINER`

| RPC | Input | Item trả về |
| --- | --- | --- |
| `search_profiles` | `p_query text, p_limit int default 20, p_offset int default 0` | `id, username, display_name, avatar_url` |
| `search_posts` | như trên | Các cột của `posts`: `id, author_id, subject_id, type, title, content, best_comment_id, created_at, updated_at` |
| `search_subjects` | như trên | `id, name, description, is_active, created_at` |
| `search_documents` | như trên | `id, file_name, file_type, file_size, created_at, post_id, post_title, author_display_name` |

- Mỗi RPC trả `jsonb {items: [...], has_more: bool}`, giống cách `list_my_friendships` trả jsonb.
- Người thực hiện lấy từ `studyhub_private.friend_actor()`.
- Đặt `search_path = ''`, ghi đầy đủ tên schema ở mọi chỗ.
- Thu hồi EXECUTE khỏi `public`/`anon`, chỉ cấp cho `authenticated`. Các helper `studyhub_private` không lộ ra client.
- Cần `SECURITY DEFINER` để đọc `blocks` theo cả hai chiều và đọc `friendships`, vì change kết bạn đã thu hồi quyền đọc trực tiếp bảng này. Bù lại, RPC phải có danh sách cột trả về cố định và tự kiểm quyền trong thân hàm.

**Phương án đã loại:**
- **Client tự `ilike` trên bảng.** Lộ bài riêng tư qua API, phân trang lệch sau khi lọc trong app, không kiểm được chặn chiều ngược.
- **Một RPC `search(kind)` chung.** Mỗi tab trả một kiểu dữ liệu khác nhau nên khó định kiểu trong Kotlin, và quyền khó đọc hơn.
- **Supabase full-text search.** Postgres không có từ điển tiếng Việt và không chịu lỗi gõ.

### 2. Chuẩn hóa bằng hàm IMMUTABLE bọc `unaccent`

Tạo các extension `pg_trgm` và `unaccent` trong schema `extensions`, theo quy ước Supabase.

`studyhub_private.search_norm(text)` thực hiện theo thứ tự:
1. `extensions.unaccent('extensions.unaccent'::regdictionary, x)`. Bộ luật mặc định đã đổi đ thành d.
2. `lower`.
3. Thay `[_./-]+` bằng khoảng trắng.
4. Gộp khoảng trắng liên tiếp và `btrim`.

- Hàm được khai báo `IMMUTABLE STRICT PARALLEL SAFE` để dùng được trong chỉ mục. `unaccent` gốc chỉ là STABLE, nên dùng dạng có tham số dictionary là cách làm phổ biến.
- `studyhub_private.search_file_norm(text)` bỏ phần đuôi file cuối (`\.[A-Za-z0-9]{1,8}$`) trước khi gọi `search_norm`.
- Sau chuẩn hóa chỉ còn ký tự ASCII, nên trigram không phụ thuộc collation/ctype của database.
- Từ khóa được chuẩn hóa bằng đúng hàm này. Sau đó escape `\`, `%`, `_` cho `LIKE ... ESCAPE '\'`, dù `_` đã thành khoảng trắng.
- RPC kiểm `length(p_query) > 100` thì trả `F0001`. Nếu `length(norm) < 2` thì trả trang rỗng ngay.

**Phương án đã loại:**
- **Cột generated hoặc cột lưu sẵn bản chuẩn hóa.** Cần sửa schema bảng và trigger. Chỉ mục biểu thức đủ cho quy mô hiện tại.
- **Chuẩn hóa ở client.** Không bảo đảm khớp với dữ liệu, và client có thể bỏ qua.

### 3. Ba mức khớp và ngưỡng trigram

Với từ khóa đã chuẩn hóa `q`, cụm escape `qe` và trường `f` đã chuẩn hóa:

- **tier 1:** `f = q or f like qe || '%'`.
- **tier 2:** `f like '%' || qe || '%'`.
- **tier 3:** `length(q) >= 4`, `q` chỉ gồm chữ/số/khoảng trắng, và `q OPERATOR(extensions.<%) f`. Trigram bỏ qua ký hiệu, nên nếu không có điều kiện này thì "100%" sẽ khớp gần đúng với "100 câu hỏi" và "c++" sẽ khớp với "c". Từ khóa có ký hiệu vì vậy chỉ khớp theo nghĩa đen (phát hiện khi viết test, 2026-11-06). `<%` là toán tử word similarity, so `q` với đoạn liên tục giống nhất trong `f`, nên hợp với tên dài và tiêu đề.

Cách đặt ngưỡng:
- Đặt bằng `set pg_trgm.word_similarity_threshold = '0.5'` trong định nghĩa từng RPC, để toán tử dùng được chỉ mục GIN và client không đổi được ngưỡng.
- **Đã hiệu chỉnh (task 2.6): chọn 0.5.** Các lỗi gõ hay gặp ("xac xuat" 0.545, "giia tich" 0.538, "tona cao cap" 0.571) sẽ bị bỏ sót ở 0.6, mặc định của Postgres. So với 0.4, ngưỡng 0.5 loại thêm nhiễu như "de thi"→"Đề cương" (0.429). `<%` so sánh `>=`. Bảng số liệu ghi trong `tasks.md`.
- Nếu hosted không cho đặt GUC này trong hàm, dùng phương án dự phòng `extensions.word_similarity(q, f) >= 0.4`, chấp nhận không dùng chỉ mục cho riêng mức 3.

Các trường chỉ tìm chứa từ khóa (`posts.content`, `subjects.description`, `posts.title` trong tab Tài liệu) chỉ xét tier 1–2. Mức tier của một bản ghi là mức tốt nhất trong các trường của nó.

Xếp hạng theo thứ tự ưu tiên:
1. `tier asc`.
2. `exact_accent desc`: chuỗi gốc `lower(raw)` chứa `lower(btrim(p_query))`.
3. `greatest(word_similarity(q, f_i)) desc`.
4. Khóa phụ của tab: `created_at desc` cho bài viết/tài liệu, `name asc` cho môn học, `username asc` cho người dùng.
5. `id asc`.

**Phương án đã loại:**
- **`similarity` (`%`) trên toàn chuỗi.** Bị phạt khi trường dài hơn từ khóa.
- **Levenshtein (`fuzzystrmatch`).** Không có chỉ mục, phải tính cho từng dòng.
- **Bật tìm gần đúng cho từ khóa 2–3 ký tự.** Quá ít trigram nên kết quả nhiễu.

### 4. Chỉ mục

Tạo chỉ mục GIN `extensions.gin_trgm_ops` trên các biểu thức:
- `search_norm(profiles.username)`, `search_norm(profiles.display_name)`.
- `search_norm(posts.title)`, `search_norm(posts.content)`.
- `search_norm(subjects.name)`.
- `search_file_norm(post_files.file_name)`.

`gin_trgm_ops` hỗ trợ `LIKE`, `=` và `<%`. Truy vấn phải dùng đúng cùng biểu thức thì chỉ mục mới được dùng. Từ khóa 2 ký tự không tạo đủ một trigram, nên có thể quét tuần tự. Chấp nhận được ở quy mô đồ án; task 2.7 sẽ kiểm bằng `EXPLAIN`. Chỉ mục trên `content` là lớn nhất và có thể bỏ nếu kế hoạch truy vấn cho thấy không cần.

### 5. Quyền xem bài viết tính trên server

`studyhub_private.post_visibility(type text) returns text immutable` sao y luật của `PostTypeUtils.parse`. Hàm viết dạng SQL-standard body (`RETURN`), nên tên được gắn lúc tạo hàm và planner inline được:
- Nếu có `:` thì lấy phần sau dấu `:` đầu tiên, nếu không thì lấy cả chuỗi.
- `lower(btrim)` phần đó.
- Kết quả là `friends` hoặc `private` thì giữ nguyên, mọi giá trị khác thành `public`.

Mỗi RPC `search_posts`/`search_documents` tính một lần mỗi lần gọi hai CTE `materialized`:
- `viewer_friends`: bạn `accepted` của người tìm, dùng chỉ mục phân trang friendships.
- `blocked`: người chặn hoặc bị chặn theo hai chiều.

Sau đó join `profiles` của tác giả. Một bài được trả khi:
- `author_id = actor`, hoặc
- tác giả không bị khóa, không nằm trong `blocked`, và quyền là `public` hoặc (`friends` và tác giả nằm trong `viewer_friends`).

Như vậy không còn giới hạn 100 bạn. Bản nháp đầu dùng helper `search_can_view_post` gọi `friend_peer_available` cho từng dòng. Đo trên 20.000 bài mất khoảng 300 µs/dòng, tức 6 giây cho mỗi lần quét, nên đã thay bằng cách lọc theo tập ở trên (task 2.7).

- Giá trị lạ được coi là public để khớp với cách app đang hiển thị trên bảng tin. Tìm kiếm vì thế không lộ hơn bảng tin, và quy ước sẽ đổi cùng change tách cột quyền.
- Ảnh được xác định bằng `file_type like 'image/%'` hoặc tên kết thúc bằng `.png|.jpg|.jpeg|.webp|.gif|.bmp|.heic`, khớp với cách `PostAdapter` nhận ảnh.

### 6. Phân trang offset có giới hạn

- `p_limit` phải ≥ 1 và được giới hạn tối đa 50. `p_offset` phải ≥ 0. Null hoặc âm trả `F0001`.
- Tổng số kết quả có thể truy cập tối đa 100. Nếu `p_offset >= 100` thì trả rỗng; nếu không thì `limit = least(p_limit, 100 - p_offset)`.
- Lấy thêm 1 dòng (`limit + 1`) để tính `has_more`, và `has_more` chỉ đúng khi trang tiếp theo vẫn nằm trong 100 kết quả đầu.

Không dùng cursor theo khóa, vì sắp theo điểm số thực và mức tier, cursor sẽ phức tạp mà ít lợi ở quy mô này. Dữ liệu thay đổi giữa các trang có thể làm lệch, nên client bỏ trùng theo `id`.

### 7. Tầng Kotlin

- **Model.** `backend/.../model/SearchResults.kt` gồm `SearchPage<T>(items, hasMore)`, `ProfileSearchItem`, `DocumentSearchItem`. Tab Bài viết decode `Post`, tab Môn học decode `Subject`.
- **`SearchRemoteDataSource`.** Gọi `postgrest.rpc(...)` với `p_query`, `p_limit`, `p_offset`, cho phép thay bằng fake trong test.
- **`SearchRepository`.**
  - Bọc bằng `SafeApiCall.execute(Operation.SEARCH)`.
  - Kiểm ở client: trim, tối đa 100 ký tự, dưới 2 ký tự thì trả trang rỗng không gọi mạng.
  - Tab Bài viết: gọi `search_posts`, rồi ghép thông tin bằng hàm dùng chung `PostRemoteDataSource.enrichPosts(posts)`, tách từ phần cuối của `getPosts` và giữ nguyên thứ tự hạng. `getPosts` gọi lại hàm này nên hành vi bảng tin không đổi.
  - Tab Môn học: lấy kèm `getFollowedSubjectIds` để hiển thị trạng thái theo dõi.
- **Mã lỗi.** Thêm `Operation.SEARCH` và đưa nó vào nhóm ánh xạ `22P02/22007/22008` thành `VALIDATION_FAILED`. Mã `F0001/F0005/F0006` đã có sẵn ánh xạ.

### 8. UI và state

- **Màn hình.** `ui/search/SearchFragment` dùng ViewBinding, `TabLayout` kèm một `RecyclerView` và `SwipeRefreshLayout`, theo mẫu `Friend.kt`. Không cần ViewPager2.
- **Mở màn hình.** `btnHeaderSearch` trong `home.xml` gọi `Home.openDetail(SearchFragment())`, nên nút back về màn trước. Fragment nằm trên back stack, nên ViewModel giữ query và kết quả khi mở chi tiết rồi quay lại.
- **`SearchViewModel` (`StateFlow<SearchUiState>`).**
  - Mỗi tab có state riêng: `Idle | Loading | Content(items, hasMore, loadingMore, loadMoreError) | Empty | Error`.
  - Chờ 300 ms bằng cách hủy `Job` trước khi gõ tiếp.
  - Có `generation` cho mỗi lần đổi query, để bỏ phản hồi cũ.
  - Mỗi tab chỉ có tối đa một yêu cầu đang chạy, bỏ trùng theo `id`.
  - Đổi query thì xóa state của mọi tab.
- **Adapter và điều hướng.**
  - Người dùng: adapter mới, dùng avatar URL hoặc ảnh mặc định qua `AvatarImageLoader`.
  - Bài viết: `PostAdapter`, giữ like, mở tác giả/môn/chi tiết. Sửa/xóa sẽ mở chi tiết bài, nơi đã có thao tác của chủ bài, để không nhân đôi logic.
  - Môn học: `SubjectAdapter` cùng thao tác theo dõi/bỏ theo dõi hiện có.
  - Tài liệu: adapter mới có biểu tượng loại file, tên file, tiêu đề bài, tác giả, dung lượng; bấm vào thì mở `PostDetailFragment.newInstance(postId)`.
- `EditText` đặt `maxLength=100`, `imeOptions=actionSearch`. Bấm Search trên bàn phím thì tìm ngay, bỏ qua thời gian chờ.

## Risks / Trade-offs

- **Lộ bài và file riêng tư qua table API.** RLS `posts`/`post_files` vẫn `using (true)`. → Change này chỉ bảo đảm RPC không lộ thêm. Proposal ghi rõ rủi ro còn lại, và cần mở change riêng sửa RLS cùng policy `post_files` insert/delete `true`.
- **Dò quét danh sách người dùng.** Tìm gần đúng có thể bị dùng để liệt kê người dùng. → Chỉ người đã đăng nhập, không bị khóa mới gọi được. Từ khóa tối thiểu 2 ký tự, mỗi trang tối đa 50, tổng tối đa 100. Chỉ trả cột công khai, loại người bị khóa/bị chặn. Chưa có giới hạn tần suất trên server, chấp nhận ở v1.
- **Ngưỡng 0.5 không tách sạch lỗi gõ khỏi nhiễu.** Các từ chung từ, như "kinh te"→"Kinh nghiệm" (0.625), vẫn khớp ở mức 3. → Kết quả mức 3 luôn xếp sau mức 1–2. Ngưỡng có thể chỉnh lại trong RPC nếu dữ liệu thật cho thấy cần.
- **Hàm bọc `unaccent` khai IMMUTABLE.** Nếu bộ luật `unaccent` thay đổi, chỉ mục sẽ lệch. → Ghi chú cần `REINDEX` khi nâng extension.
- **Trigram bỏ qua ký hiệu** như "C++" hay "C#". → Mức 2 vẫn khớp chuỗi đã chuẩn hóa, vì `+`/`#` không bị xóa.
- **Luật quyền riêng tư bị chép ở hai nơi** (Kotlin và SQL). → Test SQL phủ các giá trị `type` cũ. Khi tách cột quyền, sửa cả hai cùng lúc.
- **Hiệu năng quét tuần tự** với từ khóa ngắn hoặc chỉ mục trên `content`. → Kiểm bằng `EXPLAIN` và số dòng thực tế, chỉ thêm/bỏ chỉ mục khi có số liệu.
- **Lịch sử migration hosted lệch.** → Áp migration bằng đúng cách đã dùng cho change kết bạn (Management API cộng ghi version), không `db push --include-all`, và chỉ làm khi người dùng yêu cầu rõ.

## Migration Plan

1. Viết migration mới trong `supabase/migrations/`, có preflight kiểm extension và helper của change kết bạn đã tồn tại. Mọi lệnh idempotent (`if not exists`, `create or replace`).
2. Chạy trên database thử nghiệm: Supabase/Postgres trong Docker, hoặc môi trường test được phép. Chạy test SQL ở mục 2 của tasks.
3. Đồng bộ phần tìm kiếm vào `supabase/schema.sql`.
4. Deploy hosted chỉ khi người dùng yêu cầu rõ: bật extension, áp migration, kiểm grants/chỉ mục/RPC, ghi version.
5. Phát hành app sau khi RPC đã có trên hosted. App cũ không gọi RPC mới nên không bị ảnh hưởng.
6. Rollback: `drop function` cho bốn RPC và các helper `search_*`, `drop index` các chỉ mục trigram. Giữ extension vì vô hại. App ẩn nút tìm kiếm nếu cần.

## Open Questions

- ~~Giá trị ngưỡng cuối cùng~~: đã chốt 0.5 (task 2.6).
- Hosted đã bật `pg_trgm`/`unaccent` chưa, và có đặt được `pg_trgm.word_similarity_threshold` trong định nghĩa hàm không. Sẽ kiểm ở task 1.2, nếu không được thì dùng phương án dự phòng ở mục 3.
- Môi trường chạy test SQL: Docker Desktop cần được bật, hoặc nhóm cung cấp database test.
