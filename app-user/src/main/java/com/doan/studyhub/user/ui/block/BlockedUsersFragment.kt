package com.doan.studyhub.user.ui.block

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
import androidx.recyclerview.widget.RecyclerView
import com.doan.studyhub.user.databinding.FragmentBlockedUsersBinding
import com.doan.studyhub.user.ui.home.Home
import com.doan.studyhub.user.ui.profile.ProfileFragment
import kotlinx.coroutines.launch

class BlockedUsersFragment : Fragment() {

    private var _binding: FragmentBlockedUsersBinding? = null
    private val binding get() = requireNotNull(_binding)

    private val viewModel: BlockViewModel by viewModels()

    private lateinit var blockedAdapter: BlockedUsersAdapter
    private lateinit var blockedLayoutManager: LinearLayoutManager

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        state: Bundle?
    ): View {
        _binding = FragmentBlockedUsersBinding.inflate(
            inflater,
            container,
            false
        )
        return binding.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        super.onViewCreated(view, state)

        setupRecyclerView()
        setupListeners()
        observeState()
    }

    // --------------------------------------------------
    // RecyclerView
    // --------------------------------------------------

    private fun setupRecyclerView() {
        blockedLayoutManager = LinearLayoutManager(requireContext())

        blockedAdapter = BlockedUsersAdapter(
            fragment = this,

            onOpen = { item ->
                openProfile(item.peer.id)
            },

            onUnblock = { item ->
                confirmUnblock(
                    userId = item.peer.id,
                    displayName = item.peer.displayName
                )
            }
        )

        binding.rvBlockedUsers.apply {
            layoutManager = blockedLayoutManager
            adapter = blockedAdapter
            setHasFixedSize(true)

            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(
                    recyclerView: RecyclerView,
                    dx: Int,
                    dy: Int
                ) {
                    super.onScrolled(recyclerView, dx, dy)

                    if (dy <= 0) return

                    val lastVisible =
                        blockedLayoutManager.findLastVisibleItemPosition()

                    if (
                        lastVisible >= blockedAdapter.itemCount - 3 &&
                        blockedAdapter.itemCount > 0
                    ) {
                        viewModel.loadMoreBlockedUsers()
                    }
                }
            })
        }
    }

    // --------------------------------------------------
    // Listeners
    // --------------------------------------------------

    private fun setupListeners() {

        // Back to Settings
        binding.btnBackBlockedUsers.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        // Retry loading
        binding.btnRetryBlockedUsers.setOnClickListener {
            viewModel.refreshBlockedUsers()
        }
    }

    // --------------------------------------------------
    // Observe ViewModel
    // --------------------------------------------------

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    render(state)
                }
            }
        }
    }

    // --------------------------------------------------
    // Render UI
    // --------------------------------------------------

    private fun render(state: BlockUiState) {
        val hasUsers = state.blockedUsers.isNotEmpty()

        binding.progressBlockedUsers.isVisible =
            state.loadingList && !hasUsers

        binding.tvBlockedUsersError.isVisible =
            state.listError != null

        binding.tvBlockedUsersError.text =
            state.listError

        binding.btnRetryBlockedUsers.isVisible =
            state.listError != null && !state.loadingList

        binding.tvBlockedUsersEmpty.isVisible =
            !state.loadingList &&
                    !hasUsers &&
                    state.listError == null

        binding.rvBlockedUsers.isVisible = hasUsers

        binding.progressBlockedMore.isVisible =
            state.loadingMore

        val rows = state.blockedUsers.map { item ->
            BlockedUserRow(
                item = item,
                busy = item.peer.id in state.unblockingIds,
                error = state.blockedRowErrors[item.peer.id]
            )
        }

        blockedAdapter.submitList(rows)
    }

    // --------------------------------------------------
    // Open profile
    // --------------------------------------------------

    private fun openProfile(userId: String) {
        (parentFragment as? Home)?.openDetail(
            ProfileFragment.forUser(userId)
        )
    }

    // --------------------------------------------------
    // Unblock user
    // --------------------------------------------------

    private fun confirmUnblock(
        userId: String,
        displayName: String
    ) {
        AlertDialog.Builder(requireContext())
            .setTitle("Bỏ chặn người dùng")
            .setMessage(
                "Bạn có chắc chắn muốn bỏ chặn $displayName không?"
            )
            .setNegativeButton("Hủy", null)
            .setPositiveButton("Bỏ chặn") { _, _ ->
                viewModel.unblockFromList(userId)
            }
            .show()
    }

    // --------------------------------------------------
    // Lifecycle
    // --------------------------------------------------

    override fun onResume() {
        super.onResume()

        // Reload when opening the screen or returning from a profile.
        viewModel.refreshBlockedUsers()
    }

    override fun onDestroyView() {
        binding.rvBlockedUsers.adapter = null
        _binding = null
        super.onDestroyView()
    }
}
