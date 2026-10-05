# HƯỚNG DẪN CẤU TRÚC THƯ MỤC DỰ ÁN STUDYHUB

**Vị trí file:** `D:\Project\StudyHub\PROJECT_STRUCTURE.md`

Tài liệu này giải thích chi tiết vai trò của từng thư mục, module và cấu trúc code trong đồ án **StudyHub**. Các thành viên trong nhóm hãy đọc kỹ để nắm rõ vị trí cần thêm/sửa mã nguồn khi làm việc cùng nhau.

---

## 🏗️ 1. Cấu Trúc Tổng Quan (3-Module Architecture)

Dự án StudyHub được thiết kế tối giản, chia làm **3 module chính**:

```text
StudyHub/
├── app-user/       # 📱 Frontend ứng dụng cho Học viên / Người dùng
├── app-admin/      # 🛡️ Frontend ứng dụng cho Quản trị viên / Admin
└── backend/        # 💾 Tầng Dữ liệu Kết nối Backend Supabase (Dùng chung)
```

### 💡 Giải thích mô hình Frontend & Backend:
- **`app-user` & `app-admin` (Frontend)**: Chứa toàn bộ giao diện màn hình (Views/Fragments, XML layouts, ViewModels, Navigation UI) hiển thị cho người dùng tương tác.
- **`backend` (Mobile Data & API Layer)**: Đóng vai trò làm cầu nối giữa Frontend Android và **Backend Supabase (Cloud Database & Authentication)**. Nơi đây thực hiện các câu truy vấn Supabase, quản lý luồng dữ liệu và gửi kết quả về cho Frontend hiển thị.

---

## 📂 2. Chi Tiết Vai Trò Của Các Module

### 📱 `app-user/` (Frontend cho Học viên)
Chứa toàn bộ màn hình và luồng giao diện dành cho học viên.

- `src/main/java/com/doan/studyhub/user/`:
  - `ui/`: Chứa giao diện phân theo từng chức năng (Feature packages):
    - `auth/`: Màn hình Đăng nhập (`SignInFragment`), Đăng ký (`SignUpFragment`), Quên mật khẩu (`ForgotPasswordFragment`), `AuthViewModel`, `AuthUiState`.
    - `home/`: Màn hình Trang chủ (`HomeFragment`) và Navigation Drawer.
    - `post/`: Quản lý bài viết học tập.
    - `group/`: Quản lý nhóm học tập.
    - `subject/`: Môn học / Tài liệu.
    - `chat/`: Nhắn tin / Thảo luận nhóm.
    - `friend/`: Quản lý bạn bè.
    - `notification/`: Thông báo.
    - `profile/`: Trang cá nhân học viên.
    - `settings/`: Cài đặt tài khoản.
- `src/main/res/`:
  - `drawable/`: Icon vector, hình ảnh logo (`logo1.png`, `logo2.png`...).
  - `layout/`: File giao diện XML (`sign_in.xml`, `sign_up.xml`, `home.xml`...).
  - `navigation/`: File sơ đồ chuyển màn hình `nav_graph.xml`.
  - `values/`: Định nghĩa màu sắc (`colors.xml`), chủ đề (`themes.xml`).

---

### 🛡️ `app-admin/` (Frontend cho Quản trị viên)
Chứa giao diện và chức năng quản lý hệ thống dành cho Admin.

- `src/main/java/com/doan/studyhub/admin/`:
  - `ui/auth/`: Đăng nhập cho Admin.
  - `ui/dashboard/`: Bảng thống kê hệ thống.
  - `ui/users/`: Quản lý danh sách người dùng.
  - `ui/subjects/`: Quản lý danh mục môn học.
  - `ui/reports/`: Xử lý báo cáo vi phạm.
- `src/main/res/`: Chứa giao diện XML và tài nguyên của app Admin.

---

### 💾 `backend/` (Tầng Kết Nối Backend Supabase)
Quản lý tất cả thao tác kết nối cơ sở dữ liệu Supabase dùng chung cho cả `app-user` và `app-admin`.

- `src/main/java/com/doan/studyhub/`:
  - `common/Resource.kt`: Lớp hỗ trợ trạng thái dữ liệu trả về cho UI (`Loading`, `Success`, `Error`).
  - `core/data/`:
    - `SupabaseClient.kt`: Khởi tạo kết nối Supabase Backend.
    - `model/`: Khai báo các lớp dữ liệu (`Profile.kt`, `Post.kt`...).
    - `remote/`: Thực hiện các câu lệnh API / Query đến Supabase (`AuthRemoteDataSource.kt`...).
    - `repository/`: Cung cấp dữ liệu cho ViewModel (`AuthRepository.kt`...).

---

## 🤝 3. Quy Trình Phối Hợp Làm Việc (Dành Cho Thành Viên Nhóm)

Khi bạn nhận làm một chức năng mới:

1. **Tạo Model Dữ liệu**: Thêm Data Class trong `backend/src/main/.../data/model/`.
2. **Gọi Backend Supabase**:
   - Thêm câu lệnh truy vấn Supabase trong `backend/src/main/.../data/remote/`.
   - Cung cấp hàm xử lý dữ liệu trong `backend/src/main/.../data/repository/`.
3. **Thiết Kế Giao Diện Frontend**:
   - Vẽ file layout XML trong `app-user/src/main/res/layout/` (hoặc `app-admin`).
   - Khai báo ID màn hình mới trong `nav_graph.xml`.
4. **Viết Fragment & ViewModel**:
   - Tạo Fragment và ViewModel trong `app-user/src/main/.../ui/<tên_chức_năng>/`.
