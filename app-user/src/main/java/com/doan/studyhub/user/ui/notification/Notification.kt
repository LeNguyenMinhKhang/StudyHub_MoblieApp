package com.doan.studyhub.user.ui.notification

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.doan.studyhub.core.data.model.NotificationFilter
import com.doan.studyhub.user.R
import com.doan.studyhub.user.databinding.FragmentNotificationBinding
import com.doan.studyhub.user.ui.home.Home
import com.doan.studyhub.user.ui.post.PostDetailFragment
import com.doan.studyhub.user.ui.profile.ProfileFragment
import kotlinx.coroutines.launch

class Notification : Fragment() {
    private var _binding: FragmentNotificationBinding? = null
    private val binding get() = requireNotNull(_binding)
    // Shared with Home: one session-scoped state feeds both the badges and this list.
    private val viewModel: NotificationViewModel by viewModels(ownerProducer = { requireParentFragment() })
    private lateinit var adapter: NotificationAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentNotificationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        adapter = NotificationAdapter(this, viewModel::open)
        binding.rvNotifications.layoutManager = LinearLayoutManager(requireContext())
        binding.rvNotifications.adapter = adapter
        binding.btnMarkAllRead.setOnClickListener { viewModel.markAllRead() }
        binding.btnNotificationsRetry.setOnClickListener { viewModel.refresh() }
        binding.btnNotificationsLoadMore.setOnClickListener { viewModel.loadMore() }
        binding.btnNotificationsReset.setOnClickListener { viewModel.resetWindow() }
        binding.swipeNotifications.setOnRefreshListener { viewModel.refresh() }
        binding.toggleNotificationFilter.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            viewModel.setFilter(if (id == R.id.btnFilterUnread) NotificationFilter.UNREAD else NotificationFilter.ALL)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect(::render) }
                launch { viewModel.events.collect(::navigate) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private fun render(state: NotificationUiState) {
        val binding = _binding ?: return
        binding.progressNotifications.isVisible = state.loading && state.items.isEmpty() && !binding.swipeNotifications.isRefreshing
        if (!state.loading) binding.swipeNotifications.isRefreshing = false
        val emptyText = if (state.filter == NotificationFilter.UNREAD) R.string.notification_empty_unread else R.string.notification_empty
        val status = state.error ?: if (state.loaded && !state.loading && state.items.isEmpty()) getString(emptyText) else null
        binding.tvNotificationsStatus.isVisible = status != null
        binding.tvNotificationsStatus.text = status
        binding.btnNotificationsRetry.isVisible = state.error != null && !state.loading
        binding.btnMarkAllRead.isVisible = state.unreadCount > 0
        binding.btnNotificationsLoadMore.isVisible = state.cursor != null && !state.windowTruncated
        binding.btnNotificationsLoadMore.isEnabled = !state.loadingMore
        binding.layoutNotificationsStale.isVisible = state.windowTruncated
        val checked = if (state.filter == NotificationFilter.UNREAD) R.id.btnFilterUnread else R.id.btnFilterAll
        if (binding.toggleNotificationFilter.checkedButtonId != checked) binding.toggleNotificationFilter.check(checked)
        adapter.submitList(state.items)
        state.message?.let {
            Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }

    private fun navigate(event: NotificationEvent) {
        val home = parentFragment as? Home ?: return
        when (event) {
            is NotificationEvent.OpenPost -> home.openDetail(PostDetailFragment.newInstance(event.postId))
            is NotificationEvent.OpenProfile -> home.openDetail(ProfileFragment.forUser(event.userId))
        }
    }

    override fun onDestroyView() {
        binding.rvNotifications.adapter = null
        _binding = null
        super.onDestroyView()
    }
}
