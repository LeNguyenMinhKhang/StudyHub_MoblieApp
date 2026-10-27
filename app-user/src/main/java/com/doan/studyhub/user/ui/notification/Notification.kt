package com.doan.studyhub.user.ui.notification

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.doan.studyhub.core.data.model.AppNotification
import com.doan.studyhub.user.R
import com.doan.studyhub.user.databinding.FragmentNotificationBinding
import com.doan.studyhub.user.ui.home.Home
import com.doan.studyhub.user.ui.post.PostDetailFragment
import com.doan.studyhub.user.ui.profile.ProfileFragment
import kotlinx.coroutines.launch

class Notification : Fragment() {
    private var _binding: FragmentNotificationBinding? = null
    private val binding get() = requireNotNull(_binding)
    private val viewModel: NotificationViewModel by viewModels()
    private lateinit var adapter: NotificationAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentNotificationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        adapter = NotificationAdapter(this, ::open)
        binding.rvNotifications.layoutManager = LinearLayoutManager(requireContext())
        binding.rvNotifications.adapter = adapter
        binding.btnMarkAllRead.setOnClickListener { viewModel.markAllRead() }
        binding.btnNotificationsRetry.setOnClickListener { viewModel.refresh() }
        binding.btnNotificationsLoadMore.setOnClickListener { viewModel.loadMore() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::render)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private fun render(state: NotificationUiState) {
        binding.progressNotifications.isVisible = state.loading && state.items.isEmpty()
        val status = state.error ?: if (state.loaded && state.items.isEmpty()) getString(R.string.notification_empty) else null
        binding.tvNotificationsStatus.isVisible = status != null
        binding.tvNotificationsStatus.text = status
        binding.btnNotificationsRetry.isVisible = state.error != null && !state.loading
        binding.btnMarkAllRead.isVisible = state.unreadCount > 0
        binding.btnNotificationsLoadMore.isVisible = state.cursor != null
        binding.btnNotificationsLoadMore.isEnabled = !state.loading
        adapter.submitList(state.items)
        (parentFragment as? Home)?.showUnreadNotifications(state.unreadCount)
    }

    private fun open(item: AppNotification) {
        viewModel.markRead(item)
        val home = parentFragment as? Home ?: return
        val postId = item.postId
        val actorId = item.actor?.id
        when {
            postId != null -> home.openDetail(PostDetailFragment.newInstance(postId))
            actorId != null -> home.openDetail(ProfileFragment.forUser(actorId))
        }
    }

    override fun onDestroyView() {
        binding.rvNotifications.adapter = null
        _binding = null
        super.onDestroyView()
    }
}
