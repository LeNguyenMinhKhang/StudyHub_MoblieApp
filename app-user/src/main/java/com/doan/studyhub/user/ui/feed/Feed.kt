package com.doan.studyhub.user.ui.feed

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.doan.studyhub.core.data.local.AvatarMode
import com.doan.studyhub.core.data.local.AvatarSelection
import com.doan.studyhub.core.data.model.PostDetail
import com.doan.studyhub.core.data.repository.AuthRepository
import com.doan.studyhub.core.data.repository.PostRepository
import com.doan.studyhub.user.R
import com.doan.studyhub.user.databinding.FragmentFeedBinding
import com.doan.studyhub.user.ui.auth.AuthViewModel
import com.doan.studyhub.user.ui.home.Home
import com.doan.studyhub.user.ui.post.PostAdapter
import com.doan.studyhub.user.ui.post.PostCreateFragment
import com.doan.studyhub.user.ui.post.PostDetailFragment
import com.doan.studyhub.user.ui.profile.AvatarImageLoader
import com.doan.studyhub.user.ui.profile.ProfileFragment
import com.doan.studyhub.user.ui.subject.SubjectDetailFragment
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class Feed : Fragment() {

    private var _binding: FragmentFeedBinding? = null
    private val binding get() = requireNotNull(_binding)

    private val authViewModel: AuthViewModel by activityViewModels()
    private val postRepository = PostRepository()
    private val authRepository = AuthRepository()

    private lateinit var adapter: PostAdapter
    private var currentFilterType: String? = null
    private var currentUserId: String? = null

    // Pagination state
    private val pageSize = 15L
    private var currentOffset = 0L
    private var isLoading = false
    private var hasMore = true
    private val loadedPosts = mutableListOf<PostDetail>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFeedBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupAdapter()
        setupSwipeRefresh()
        setupScrollListener()
        setupFilterButtons()
        setupEmptyStateActions()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                authViewModel.uiState.collect { state ->
                    val profile = state.profile
                    if (profile != null) {
                        currentUserId = profile.id
                        adapter.updateCurrentUserId(profile.id, profile)
                        AvatarImageLoader.show(
                            fragment = this@Feed,
                            view = binding.imgFeedUserAvatar,
                            selection = AvatarSelection(AvatarMode.REMOTE),
                            remoteUrl = profile.avatarUrl
                        )
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            currentUserId = authRepository.getCurrentUserId()
            adapter.updateCurrentUserId(currentUserId, authViewModel.uiState.value.profile)
            loadFeed(isRefresh = false)
        }

        binding.cardCreatePostPrompt.setOnClickListener {
            openCreatePost()
        }
    }

    private fun setupAdapter() {
        val initialProfile = authViewModel.uiState.value.profile
        adapter = PostAdapter(
            fragment = this,
            currentUserId = initialProfile?.id,
            currentUserProfile = initialProfile,
            onPostClick = { detail ->
                detail.post.id?.let { postId ->
                    parentFragmentManager.beginTransaction()
                        .replace(R.id.containerMain, PostDetailFragment.newInstance(postId))
                        .addToBackStack(null)
                        .commit()
                }
            },
            onSubjectClick = { subjectId ->
                parentFragmentManager.beginTransaction()
                    .replace(R.id.containerMain, SubjectDetailFragment.newInstance(subjectId))
                    .addToBackStack(null)
                    .commit()
            },
            onEditClick = { detail ->
                detail.post.id?.let { postId ->
                    parentFragmentManager.beginTransaction()
                        .replace(R.id.containerMain, PostCreateFragment.newInstance(postId))
                        .addToBackStack(null)
                        .commit()
                }
            },
            onDeleteClick = { detail ->
                detail.post.id?.let { postId ->
                    deletePost(postId)
                }
            },
            onAuthorClick = { authorId ->
                (parentFragment as? Home)?.openDetail(ProfileFragment.forUser(authorId))
            }
        )

        binding.rvFeedPosts.layoutManager = LinearLayoutManager(requireContext())
        binding.rvFeedPosts.adapter = adapter
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefreshFeed.setColorSchemeResources(
            R.color.primary,
            R.color.secondary,
            R.color.accent
        )
        binding.swipeRefreshFeed.setOnRefreshListener {
            loadFeed(isRefresh = true)
        }
    }

    private fun setupScrollListener() {
        val layoutManager = binding.rvFeedPosts.layoutManager as LinearLayoutManager
        binding.rvFeedPosts.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (dy <= 0) return

                val visibleItemCount = layoutManager.childCount
                val totalItemCount = layoutManager.itemCount
                val firstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition()

                if (!isLoading && hasMore) {
                    if ((visibleItemCount + firstVisibleItemPosition) >= totalItemCount - 2 && firstVisibleItemPosition >= 0) {
                        loadMorePosts()
                    }
                }
            }
        })
    }

    private fun setupFilterButtons() {
        updateFilterButtonStyles()

        binding.btnFilterAll.setOnClickListener {
            if (currentFilterType != null) {
                currentFilterType = null
                updateFilterButtonStyles()
                loadFeed(isRefresh = false)
            }
        }
        binding.btnFilterKnowledge.setOnClickListener {
            if (currentFilterType != "knowledge") {
                currentFilterType = "knowledge"
                updateFilterButtonStyles()
                loadFeed(isRefresh = false)
            }
        }
        binding.btnFilterQuestion.setOnClickListener {
            if (currentFilterType != "question") {
                currentFilterType = "question"
                updateFilterButtonStyles()
                loadFeed(isRefresh = false)
            }
        }
        binding.btnFilterDocument.setOnClickListener {
            if (currentFilterType != "document") {
                currentFilterType = "document"
                updateFilterButtonStyles()
                loadFeed(isRefresh = false)
            }
        }
    }

    private fun updateFilterButtonStyles() {
        val activeBg = ContextCompat.getColor(requireContext(), R.color.primary_container)
        val activeTextColor = ContextCompat.getColor(requireContext(), R.color.primary)
        val inactiveTextColor = ContextCompat.getColor(requireContext(), R.color.text_secondary)
        val strokeColor = ContextCompat.getColor(requireContext(), R.color.border_subtle)

        val buttonConfigs = listOf(
            binding.btnFilterAll to null,
            binding.btnFilterKnowledge to "knowledge",
            binding.btnFilterQuestion to "question",
            binding.btnFilterDocument to "document"
        )

        buttonConfigs.forEach { (button, type) ->
            val isSelected = currentFilterType == type
            val matButton = button as? MaterialButton
            if (matButton != null) {
                if (isSelected) {
                    matButton.backgroundTintList = ColorStateList.valueOf(activeBg)
                    matButton.setTextColor(activeTextColor)
                    matButton.strokeColor = ColorStateList.valueOf(Color.TRANSPARENT)
                } else {
                    matButton.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                    matButton.setTextColor(inactiveTextColor)
                    matButton.strokeColor = ColorStateList.valueOf(strokeColor)
                }
            }
        }
    }

    private fun setupEmptyStateActions() {
        binding.btnFeedEmptyCreatePost.setOnClickListener {
            openCreatePost()
        }
        binding.btnFeedEmptyRefresh.setOnClickListener {
            loadFeed(isRefresh = true)
        }
    }

    private fun loadFeed(isRefresh: Boolean = false) {
        if (isLoading) return
        isLoading = true

        if (isRefresh) {
            currentOffset = 0L
            hasMore = true
            if (!binding.swipeRefreshFeed.isRefreshing && loadedPosts.isEmpty()) {
                binding.progressFeed.isVisible = true
            }
        } else {
            currentOffset = 0L
            hasMore = true
            loadedPosts.clear()
            adapter.submitList(emptyList())
            binding.progressFeed.isVisible = true
            binding.layoutFeedEmpty.isVisible = false
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val posts = postRepository.getPosts(
                    type = currentFilterType,
                    limit = pageSize,
                    offset = 0L
                )
                loadedPosts.clear()
                loadedPosts.addAll(posts)
                currentOffset = posts.size.toLong()
                hasMore = posts.size.toLong() == pageSize

                adapter.submitList(loadedPosts.toList())
                updateEmptyState()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Không thể tải bảng tin: ${e.message}", Toast.LENGTH_SHORT).show()
                updateEmptyState()
            } finally {
                isLoading = false
                _binding?.progressFeed?.isVisible = false
                _binding?.swipeRefreshFeed?.isRefreshing = false
            }
        }
    }

    private fun loadMorePosts() {
        if (isLoading || !hasMore) return
        isLoading = true
        binding.layoutLoadMore.isVisible = true

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val newPosts = postRepository.getPosts(
                    type = currentFilterType,
                    limit = pageSize,
                    offset = currentOffset
                )
                if (newPosts.isNotEmpty()) {
                    loadedPosts.addAll(newPosts)
                    currentOffset += newPosts.size
                    adapter.addPosts(newPosts)
                }
                if (newPosts.size.toLong() < pageSize) {
                    hasMore = false
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Không thể tải thêm bài viết", Toast.LENGTH_SHORT).show()
            } finally {
                isLoading = false
                _binding?.layoutLoadMore?.isVisible = false
            }
        }
    }

    private fun updateEmptyState() {
        val isEmpty = loadedPosts.isEmpty()
        binding.layoutFeedEmpty.isVisible = isEmpty
        binding.rvFeedPosts.isVisible = !isEmpty
    }

    private fun deletePost(postId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                postRepository.deletePost(postId)
                Toast.makeText(requireContext(), "Đã xóa bài viết", Toast.LENGTH_SHORT).show()
                loadedPosts.removeAll { it.post.id == postId }
                adapter.removePost(postId)
                updateEmptyState()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Không thể xóa bài viết: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openCreatePost() {
        parentFragmentManager.beginTransaction()
            .replace(R.id.containerMain, PostCreateFragment.newInstance())
            .addToBackStack(null)
            .commit()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
