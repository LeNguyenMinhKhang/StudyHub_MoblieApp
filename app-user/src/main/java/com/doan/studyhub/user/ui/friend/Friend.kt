package com.doan.studyhub.user.ui.friend

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.doan.studyhub.core.data.model.FriendshipAction
import com.doan.studyhub.core.data.model.FriendshipListItem
import com.doan.studyhub.core.data.model.FriendshipListKind
import com.doan.studyhub.user.R
import com.doan.studyhub.user.databinding.FragmentFriendBinding
import com.doan.studyhub.user.ui.home.Home
import com.doan.studyhub.user.ui.profile.ProfileFragment
import com.doan.studyhub.user.ui.profile.ProfileLookupDialogFragment
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch

class Friend : Fragment() {
    private var _binding: FragmentFriendBinding? = null
    private val binding get() = requireNotNull(_binding)
    private val viewModel: FriendViewModel by viewModels()
    private lateinit var adapter: FriendshipAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentFriendBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        adapter = FriendshipAdapter(this, onOpen = { openProfile(it.peer.id) }, onAction = ::perform)
        binding.rvFriends.layoutManager = LinearLayoutManager(requireContext())
        binding.rvFriends.adapter = adapter
        setupTabs()

        binding.btnFindPeople.setOnClickListener { ProfileLookupDialogFragment().show(childFragmentManager, "profile_lookup") }
        childFragmentManager.setFragmentResultListener(ProfileLookupDialogFragment.RESULT_KEY, viewLifecycleOwner) { _, result ->
            result.getString(ProfileLookupDialogFragment.RESULT_PROFILE_ID)?.let(::openProfile)
        }
        binding.btnFriendsRetry.setOnClickListener { viewModel.refresh() }
        binding.btnFriendsLoadMore.setOnClickListener { viewModel.loadMore() }

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

    private fun setupTabs() {
        val tabs = binding.tabsFriend
        FriendshipListKind.entries.forEach { kind ->
            tabs.addTab(tabs.newTab().setText(tabLabel(kind)).setTag(kind), kind == viewModel.uiState.value.selected)
        }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) { (tab.tag as? FriendshipListKind)?.let(viewModel::select) }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    private fun render(state: FriendUiState) {
        val tab = state.tab
        binding.progressFriends.isVisible = tab.loading && tab.items.isEmpty()
        val status = tab.error ?: if (tab.loaded && tab.items.isEmpty()) getString(emptyLabel(state.selected)) else null
        binding.tvFriendsStatus.isVisible = status != null
        binding.tvFriendsStatus.text = status
        binding.btnFriendsRetry.isVisible = tab.error != null && !tab.loading
        binding.btnFriendsLoadMore.isVisible = tab.cursor != null
        binding.btnFriendsLoadMore.isEnabled = !tab.loading
        adapter.submitList(tab.items.map { item ->
            FriendRow(item, item.id in state.busy, item.id in state.unresolved, state.rowErrors[item.id])
        })
    }

    private fun perform(item: FriendshipListItem, action: FriendshipAction) {
        if (action == FriendshipAction.REMOVE) {
            AlertDialog.Builder(requireContext())
                .setMessage(R.string.friendship_remove_confirm)
                .setNegativeButton(R.string.profile_cancel, null)
                .setPositiveButton(R.string.friendship_remove) { _, _ -> viewModel.act(item, action) }
                .show()
        } else viewModel.act(item, action)
    }

    private fun openProfile(id: String) {
        (parentFragment as? Home)?.openDetail(ProfileFragment.forUser(id))
    }

    private fun tabLabel(kind: FriendshipListKind) = when (kind) {
        FriendshipListKind.FRIENDS -> R.string.friends_tab_friends
        FriendshipListKind.INCOMING -> R.string.friends_tab_incoming
        FriendshipListKind.OUTGOING -> R.string.friends_tab_outgoing
    }

    private fun emptyLabel(kind: FriendshipListKind) = when (kind) {
        FriendshipListKind.FRIENDS -> R.string.friends_empty_friends
        FriendshipListKind.INCOMING -> R.string.friends_empty_incoming
        FriendshipListKind.OUTGOING -> R.string.friends_empty_outgoing
    }

    override fun onDestroyView() {
        binding.rvFriends.adapter = null
        _binding = null
        super.onDestroyView()
    }
}
