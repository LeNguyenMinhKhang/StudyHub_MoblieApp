package com.doan.studyhub.user.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.GravityCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.doan.studyhub.user.R
import com.doan.studyhub.user.databinding.HomeBinding
import com.doan.studyhub.user.ui.auth.AuthViewModel
import com.doan.studyhub.user.ui.chat.Chat
import com.doan.studyhub.user.ui.feed.Feed
import com.doan.studyhub.user.ui.friend.Friend
import com.doan.studyhub.user.ui.group.Group
import com.doan.studyhub.user.ui.notification.Notification
import com.doan.studyhub.user.ui.post.Post
import com.doan.studyhub.user.ui.profile.Profile
import com.doan.studyhub.user.ui.settings.Settings
import com.doan.studyhub.user.ui.subject.Subject
import kotlinx.coroutines.launch

class Home : Fragment() {

    private var _binding: HomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AuthViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = HomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Hiển thị mặc định trang Bảng tin & Khám phá khi mới mở Home
        if (savedInstanceState == null) {
            showScreen(Feed())
        }

        setupListeners()
        observeUiState()
    }

    private fun showScreen(fragment: Fragment) {
        childFragmentManager.beginTransaction()
            .replace(R.id.containerMain, fragment)
            .commit()
        binding.drawerLayout.closeDrawer(GravityCompat.START)
    }

    private fun setupListeners() {
        // Mở Hamburger Drawer khi bấm icon 3 gạch
        binding.btnHamburger.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }

        // Bấm vào Drawer Header (Avatar/Tên/Email) để xem Hồ sơ cá nhân
        binding.layoutDrawerHeader.setOnClickListener {
            showScreen(Profile())
        }

        // Điều hướng các trang từ Hamburger Menu
        binding.btnNavFeed.setOnClickListener { showScreen(Feed()) }
        binding.btnNavSubject.setOnClickListener { showScreen(Subject()) }
        binding.btnNavPost.setOnClickListener { showScreen(Post()) }
        binding.btnNavGroup.setOnClickListener { showScreen(Group()) }
        binding.btnNavFriend.setOnClickListener { showScreen(Friend()) }
        binding.btnNavChat.setOnClickListener { showScreen(Chat()) }
        binding.btnNavNotification.setOnClickListener { showScreen(Notification()) }
        binding.btnNavSettings.setOnClickListener { showScreen(Settings()) }

        // Đăng xuất từ Hamburger Bar
        binding.btnDrawerSignOut.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            Toast.makeText(requireContext(), "Đang đăng xuất...", Toast.LENGTH_SHORT).show()
            viewModel.signOut()
        }
    }

    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    val profile = state.profile
                    if (profile != null) {
                        val displayName = profile.displayName.ifBlank { "Học viên" }
                        binding.tvDrawerName.text = displayName
                        binding.tvDrawerEmail.text = "@${profile.username}"
                    } else {
                        binding.tvDrawerName.text = "Người dùng"
                        binding.tvDrawerEmail.text = "Chưa cập nhật thông tin"
                    }

                    if (!state.isAuthenticated && findNavController().currentDestination?.id == R.id.homeFragment) {
                        viewModel.clearMessages()
                        findNavController().navigate(R.id.action_main_to_signIn)
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
