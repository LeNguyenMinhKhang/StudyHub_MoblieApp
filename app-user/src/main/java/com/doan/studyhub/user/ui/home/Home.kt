package com.doan.studyhub.user.ui.home

import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.doan.studyhub.common.ApiResponse
import com.doan.studyhub.core.data.model.Profile
import com.doan.studyhub.core.data.repository.NotificationRepository
import com.doan.studyhub.core.data.repository.AvatarRepository
import com.doan.studyhub.core.data.local.AvatarMode
import com.doan.studyhub.core.data.local.AvatarSelection
import com.doan.studyhub.user.R
import com.doan.studyhub.user.databinding.HomeBinding
import com.doan.studyhub.user.ui.auth.AuthViewModel
import com.doan.studyhub.user.ui.chat.Chat
import com.doan.studyhub.user.ui.feed.Feed
import com.doan.studyhub.user.ui.friend.Friend
import com.doan.studyhub.user.ui.group.Group
import com.doan.studyhub.user.ui.notification.Notification
import com.doan.studyhub.user.ui.post.Post
import com.doan.studyhub.user.ui.profile.EditProfileFragment
import com.doan.studyhub.user.ui.profile.AvatarImageLoader
import com.doan.studyhub.user.ui.profile.ProfileFragment
import com.doan.studyhub.user.ui.settings.Settings
import com.doan.studyhub.user.ui.subject.Subject
import kotlinx.coroutines.launch

class Home : Fragment() {
    private var _binding: HomeBinding? = null
    private val binding get() = requireNotNull(_binding)
    private val auth: AuthViewModel by activityViewModels()
    private lateinit var avatars: AvatarRepository
    private var avatarRequest = 0
    private val notifications = NotificationRepository()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = HomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        avatars = AvatarRepository(requireContext())
        if (state == null) showRoot(Feed())
        setupListeners()
        observeState()
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else if (childFragmentManager.backStackEntryCount > 0) {
                    closeDetail()
                } else {
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        refreshUnreadNotifications()
    }

    fun showUnreadNotifications(count: Int) {
        val binding = _binding ?: return
        val hasUnread = count > 0
        binding.viewHamburgerBadge.isVisible = hasUnread
        binding.tvHeaderNotificationBadge.isVisible = hasUnread
        binding.tvNavNotificationBadge.isVisible = hasUnread

        val countText = if (count > 99) "99+" else count.toString()
        binding.tvHeaderNotificationBadge.text = countText
        binding.tvNavNotificationBadge.text = countText
        binding.tvNavNotificationBadge.contentDescription = getString(R.string.notification_unread_count, count)
        binding.tvHeaderNotificationBadge.contentDescription = getString(R.string.notification_unread_count, count)
    }

    private fun refreshUnreadNotifications() {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = notifications.list(limit = 1)
            if (result is ApiResponse.Success) showUnreadNotifications(result.data.unreadCount)
        }
    }

    fun openDetail(fragment: Fragment) {
        childFragmentManager.beginTransaction()
            .replace(R.id.containerMain, fragment)
            .addToBackStack("profile_detail")
            .commit()
        binding.drawerLayout.closeDrawer(GravityCompat.START)
    }

    fun closeDetail() {
        val current = childFragmentManager.findFragmentById(R.id.containerMain)
        if (current is EditProfileFragment) current.requestLeave(::popDetail) else popDetail()
    }

    fun popDetail() { childFragmentManager.popBackStack() }

    fun onProfileUpdated(profile: Profile) { auth.updateProfileSnapshot(profile) }

    private fun requestRoot(fragment: Fragment) {
        val current = childFragmentManager.findFragmentById(R.id.containerMain)
        if (current is EditProfileFragment) current.requestLeave { showRoot(fragment) } else showRoot(fragment)
    }

    private fun showRoot(fragment: Fragment) {
        childFragmentManager.popBackStackImmediate(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        childFragmentManager.beginTransaction().replace(R.id.containerMain, fragment).commit()
        binding.drawerLayout.closeDrawer(GravityCompat.START)

        val tag = when (fragment) {
            is Feed -> "feed"
            is Subject -> "subject"
            is Post -> "post"
            is Group -> "group"
            is Friend -> "friend"
            is Chat -> "chat"
            is Notification -> "notification"
            is Settings -> "settings"
            else -> ""
        }
        updateDrawerSelection(tag)
    }

    private fun updateDrawerSelection(tag: String) {
        val binding = _binding ?: return
        val items = listOf(
            Triple(binding.btnNavFeed, binding.tvNavFeedText, "feed"),
            Triple(binding.btnNavSubject, binding.tvNavSubjectText, "subject"),
            Triple(binding.btnNavPost, binding.tvNavPostText, "post"),
            Triple(binding.btnNavGroup, binding.tvNavGroupText, "group"),
            Triple(binding.btnNavFriend, binding.tvNavFriendText, "friend"),
            Triple(binding.btnNavChat, binding.tvNavChatText, "chat"),
            Triple(binding.btnNavNotification, binding.tvNavNotificationText, "notification"),
            Triple(binding.btnNavSettings, binding.tvNavSettingsText, "settings")
        )
        items.forEach { (layout, textView, itemTag) ->
            val isSelected = itemTag == tag
            if (isSelected) {
                layout.setBackgroundResource(R.drawable.bg_drawer_item_selected)
                textView.setTypeface(null, Typeface.BOLD)
            } else {
                layout.setBackgroundResource(android.R.color.transparent)
                textView.setTypeface(null, Typeface.NORMAL)
            }
        }
    }

    private fun setupListeners() {
        binding.btnHamburger.setOnClickListener { binding.drawerLayout.openDrawer(GravityCompat.START) }
        binding.drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) = refreshUnreadNotifications()
        })

        // Top Bar Quick Access Action Icons
        binding.btnHeaderSubject.setOnClickListener { requestRoot(Subject()) }
        binding.btnHeaderFriend.setOnClickListener { requestRoot(Friend()) }
        binding.btnHeaderChat.setOnClickListener { requestRoot(Chat()) }
        binding.btnHeaderNotification.setOnClickListener { requestRoot(Notification()) }

        // Hamburger Navigation Drawer Items
        binding.layoutDrawerHeader.setOnClickListener { requestRoot(ProfileFragment()) }
        binding.btnNavFeed.setOnClickListener { requestRoot(Feed()) }
        binding.btnNavSubject.setOnClickListener { requestRoot(Subject()) }
        binding.btnNavPost.setOnClickListener { requestRoot(Post()) }
        binding.btnNavGroup.setOnClickListener { requestRoot(Group()) }
        binding.btnNavFriend.setOnClickListener { requestRoot(Friend()) }
        binding.btnNavChat.setOnClickListener { requestRoot(Chat()) }
        binding.btnNavNotification.setOnClickListener { requestRoot(Notification()) }
        binding.btnNavSettings.setOnClickListener { requestRoot(Settings()) }
        binding.btnDrawerSignOut.setOnClickListener {
            val action = {
                binding.drawerLayout.closeDrawer(GravityCompat.START)
                auth.signOut()
            }
            val current = childFragmentManager.findFragmentById(R.id.containerMain)
            if (current is EditProfileFragment) current.requestLeave(action) else action()
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    auth.uiState.collect { state ->
                        val profile = state.profile
                        binding.tvDrawerName.text = profile?.displayName?.ifBlank { "Học viên" } ?: "Người dùng"
                        binding.tvDrawerEmail.text = profile?.let { "@${it.username}" } ?: "Chưa cập nhật thông tin"
                        refreshAvatar(profile)
                        if (state.errorMessage != null && state.isAuthenticated) {
                            Toast.makeText(requireContext(), state.errorMessage, Toast.LENGTH_SHORT).show()
                            auth.clearMessages()
                        }
                        if (!state.isAuthenticated && !state.isCheckingSession && state.errorMessage == null &&
                            findNavController().currentDestination?.id == R.id.homeFragment) {
                            auth.clearMessages()
                            findNavController().navigate(R.id.action_main_to_signIn)
                        }
                    }
                }
                launch { avatars.revision.collect { refreshAvatar(auth.uiState.value.profile) } }
            }
        }
    }

    private fun refreshAvatar(profile: Profile?) {
        val request = ++avatarRequest
        AvatarImageLoader.show(this, binding.imgDrawerAvatar, AvatarSelection(AvatarMode.HIDDEN), null)
        if (profile == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            val selection = avatars.selection(profile.id)
            if (_binding != null && request == avatarRequest && auth.uiState.value.profile?.id == profile.id) {
                AvatarImageLoader.show(this@Home, binding.imgDrawerAvatar, selection, profile.avatarUrl)
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
