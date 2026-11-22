package com.doan.studyhub.admin

import android.content.Intent
import com.doan.studyhub.admin.ui.auth.AdminLoginActivity
import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.activity.viewModels
import androidx.fragment.app.Fragment
import com.doan.studyhub.admin.databinding.ActivityMainBinding
import com.doan.studyhub.admin.ui.auth.AdminAuthViewModel
import com.doan.studyhub.admin.ui.dashboard.DashboardFragment
import com.doan.studyhub.admin.ui.subjects.AdminSubjectFragment
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
            switchFragment(DashboardFragment(), "Tổng quan hệ thống")
        }
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNavAdmin) { view, insets ->
            view.setPadding(0, 0, 0, 0)
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBarInset = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBarInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val density = resources.displayMetrics.density

            val extraTopPx = (8 * density).toInt()
            val headerBottomPx = (12 * density).toInt()
            val navBottomMarginPx = (16 * density).toInt()

            binding.layoutAdminHeader.setPadding(
                binding.layoutAdminHeader.paddingLeft,
                statusBarInset.top + extraTopPx,
                binding.layoutAdminHeader.paddingRight,
                headerBottomPx
            )

            binding.bottomNavAdmin.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = navBarInset.bottom + navBottomMarginPx
            }

            insets
        }
    }

    private fun setupListeners() {
        binding.btnAdminLogout.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Đăng xuất quản trị")
                .setMessage("Bạn có chắc chắn muốn đăng xuất?")
                .setPositiveButton("Đăng xuất") { _, _ ->
                    authViewModel.signOut {
                        val intent = Intent(this, AdminLoginActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }
                        startActivity(intent)
                        finish()
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
                    switchFragment(UsersFragment(), "Quản lý người dùng")
                    true
                }

                R.id.nav_subjects -> {
                    switchFragment(AdminSubjectFragment(), "Quản lý môn học")
                    true
                }

                // Các chức năng chưa có source code, tạm chưa mở
                R.id.nav_posts,
                R.id.nav_reports -> false

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
}
