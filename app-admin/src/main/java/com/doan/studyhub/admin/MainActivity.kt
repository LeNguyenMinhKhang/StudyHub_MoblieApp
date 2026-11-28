package com.doan.studyhub.admin

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import com.doan.studyhub.admin.databinding.ActivityMainBinding
import com.doan.studyhub.admin.ui.auth.AdminAuthViewModel
import com.doan.studyhub.admin.ui.auth.AdminLoginActivity
import com.doan.studyhub.admin.ui.dashboard.DashboardFragment
import com.doan.studyhub.admin.ui.posts.PostsFragment
import com.doan.studyhub.admin.ui.reports.ReportsFragment
import com.doan.studyhub.admin.ui.subjects.SubjectsFragment
import com.doan.studyhub.admin.ui.users.UsersFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val authViewModel: AdminAuthViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets()
        setupListeners()
        setupBottomNav()

        if (savedInstanceState == null) {
            // Mặc định mở tab Dashboard đầu tiên
            switchFragment(DashboardFragment(), "Tổng quan hệ thống")
        }
    }

    private fun applyWindowInsets() {
        // Chặn BottomNavigationView tự cộng thêm padding đáy (vì đã được đẩy lên bằng margin)
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNavAdmin) { v, insets ->
            v.setPadding(0, 0, 0, 0)
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBarInset = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBarInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val density = resources.displayMetrics.density

            val extraTopPx = (8 * density).toInt()
            val headerBottomPx = (12 * density).toInt()
            val navBottomMarginPx = (16 * density).toInt()

            // Đẩy nội dung header xuống dưới thanh trạng thái (status bar: pin, sóng, giờ)
            binding.layoutAdminHeader.setPadding(
                binding.layoutAdminHeader.paddingLeft,
                statusBarInset.top + extraTopPx,
                binding.layoutAdminHeader.paddingRight,
                headerBottomPx
            )

            // Đẩy toàn bộ thanh điều hướng lên trên thanh cử chỉ / 3 nút điều hướng của máy
            binding.bottomNavAdmin.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = navBarInset.bottom + navBottomMarginPx
            }

            insets
        }
    }

    private fun setupListeners() {
        binding.btnAdminLogout.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Đăng xuất Quản trị")
                .setMessage("Bạn có chắc chắn muốn đăng xuất khỏi tài khoản Quản trị viên?")
                .setPositiveButton("Đăng xuất") { _, _ ->
                    val loadingDialog = android.app.ProgressDialog(this).apply {
                        setMessage("Đang đăng xuất...")
                        setCancelable(false)
                        show()
                    }
                    authViewModel.signOut {
                        try {
                            loadingDialog.dismiss()
                        } catch (_: Exception) {}
                        navigateToLogin()
                    }
                }
                .setNegativeButton("Hủy", null)
                .show()
        }
    }

    private fun setupBottomNav() {
        binding.bottomNavAdmin.isItemActiveIndicatorEnabled = false
        binding.bottomNavAdmin.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_dashboard -> {
                    switchFragment(DashboardFragment(), "Tổng quan hệ thống")
                    true
                }
                R.id.nav_users -> {
                    switchFragment(UsersFragment(), "Quản lý Người dùng")
                    true
                }
                R.id.nav_posts -> {
                    switchFragment(PostsFragment(), "Quản lý Bài viết & Tài liệu")
                    true
                }
                R.id.nav_subjects -> {
                    switchFragment(SubjectsFragment(), "Quản lý Môn học")
                    true
                }
                R.id.nav_reports -> {
                    switchFragment(ReportsFragment(), "Báo cáo Vi phạm")
                    true
                }
                else -> false
            }
        }
    }

    private fun switchFragment(fragment: Fragment, subtitle: String) {
        binding.tvAdminSubtitle.text = subtitle
        supportFragmentManager.beginTransaction()
            .replace(R.id.containerAdmin, fragment)
            .commit()
    }

    // Mở danh sách bài viết của một User cụ thể
    fun openPostsForUser(authorId: String, authorName: String) {
        switchFragment(
            PostsFragment.newInstance(authorId, authorName),
            "Bài viết của $authorName"
        )
    }

    private fun navigateToLogin() {
        val intent = Intent(this, AdminLoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }
}
