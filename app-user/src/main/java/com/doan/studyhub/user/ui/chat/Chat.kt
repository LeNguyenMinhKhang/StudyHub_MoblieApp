package com.doan.studyhub.user.ui.chat

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
import com.doan.studyhub.user.databinding.FragmentChatBinding
import com.doan.studyhub.user.ui.home.Home
import kotlinx.coroutines.launch

class Chat : Fragment() {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = requireNotNull(_binding)

    private val viewModel: ChatViewModel by viewModels()
    private lateinit var adapter: ConversationAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupListeners()
        observeState()
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadConversations(isPullToRefresh = true)
    }

    private fun setupRecyclerView() {
        adapter = ConversationAdapter { item ->
            val detailFragment = DirectChatFragment.newInstance(
                conversationId = item.conversationId,
                targetUserId = item.targetUserId,
                targetName = item.targetDisplayName,
                targetAvatar = item.targetAvatarUrl
            )
            val home = parentFragment as? Home
            if (home != null) {
                home.openDetail(detailFragment)
            }
        }

        binding.rvConversations.layoutManager = LinearLayoutManager(requireContext())
        binding.rvConversations.adapter = adapter
    }

    private fun setupListeners() {
        binding.swipeRefreshLayout.setOnRefreshListener {
            viewModel.loadConversations(isPullToRefresh = true)
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.swipeRefreshLayout.isRefreshing = false
                    binding.progressBar.isVisible = state.isLoading && adapter.itemCount == 0

                    val hasData = state.conversations.isNotEmpty()
                    binding.rvConversations.isVisible = hasData
                    binding.layoutEmptyState.isVisible = !state.isLoading && !hasData

                    adapter.submitList(state.conversations)

                    state.errorMessage?.let { msg ->
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                        viewModel.clearErrorMessage()
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
