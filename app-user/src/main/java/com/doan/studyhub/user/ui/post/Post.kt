package com.doan.studyhub.user.ui.post

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.doan.studyhub.core.data.model.SearchTab
import com.doan.studyhub.core.data.repository.PostRepository
import com.doan.studyhub.user.R
import com.doan.studyhub.user.databinding.FragmentPostBinding
import com.doan.studyhub.user.ui.auth.AuthViewModel
import com.doan.studyhub.user.ui.home.Home
import com.doan.studyhub.user.ui.profile.ProfileFragment
import com.doan.studyhub.user.ui.subject.SubjectDetailFragment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class Post : Fragment() {

    private var _binding: FragmentPostBinding? = null
    private val binding get() = requireNotNull(_binding)

    private val authViewModel: AuthViewModel by activityViewModels()
    private val postRepository = PostRepository()
    private lateinit var adapter: PostAdapter
    private var loadJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPostBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupAdapter()
        setupListeners()
        observeAuth()
        loadSavedPosts()
    }

    private fun setupAdapter() {
        val initialProfile = authViewModel.uiState.value.profile

        adapter = PostAdapter(
            fragment = this,
            currentUserId = initialProfile?.id,
            currentUserProfile = initialProfile,

            onPostClick = { detail ->
                detail.post.id?.let { postId ->
                    (parentFragment as? Home)?.openDetail(
                        PostDetailFragment.newInstance(postId)
                    )
                }
            },

            onSubjectClick = { subjectId ->
                (parentFragment as? Home)?.openDetail(
                    SubjectDetailFragment.newInstance(subjectId)
                )
            },

            onEditClick = { detail ->
                detail.post.id?.let { postId ->
                    (parentFragment as? Home)?.openDetail(
                        PostCreateFragment.newInstance(postId)
                    )
                }
            },

            onDeleteClick = { detail ->
                detail.post.id?.let { postId ->
                    deletePost(postId)
                }
            },

            onAuthorClick = { authorId ->
                (parentFragment as? Home)?.openDetail(
                    ProfileFragment.forUser(authorId)
                )
            },

            onLikeClick = { postDetail, onResult ->
                val postId = postDetail.post.id
                if (postId.isNullOrBlank()) {
                    onResult(Result.failure(IllegalStateException("Không tìm thấy ID bài viết.")))
                } else {
                    viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            val isLiked = postRepository.toggleLike(postId)
                            onResult(Result.success(isLiked))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            onResult(Result.failure(e))
                        }
                    }
                }
            },

            onBookmarkClick = { postDetail, onResult ->
                val postId = postDetail.post.id
                if (postId.isNullOrBlank()) {
                    onResult(Result.failure(IllegalStateException("Không tìm thấy ID bài viết.")))
                } else {
                    viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            val isSaved = postRepository.toggleSavePost(postId)
                            onResult(Result.success(isSaved))
                            if (!isSaved) {
                                adapter.removePost(postId)
                                updateEmptyState(adapter.itemCount == 0)
                                updateCount(adapter.itemCount)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            onResult(Result.failure(e))
                        }
                    }
                }
            },

            onHashtagClick = { tag ->
                (parentFragment as? Home)?.openSearch(
                    query = "#$tag",
                    tab = SearchTab.POSTS
                )
            }
        )

        binding.rvSavedPosts.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSavedPosts.adapter = adapter
    }

    private fun setupListeners() {
        binding.swipeRefreshSavedPosts.setColorSchemeResources(
            R.color.primary,
            R.color.secondary,
            R.color.accent
        )
        binding.swipeRefreshSavedPosts.setOnRefreshListener {
            loadSavedPosts(isPullToRefresh = true)
        }

        binding.btnRetrySavedPosts.setOnClickListener {
            loadSavedPosts()
        }
    }

    private fun observeAuth() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                authViewModel.uiState.collect { state ->
                    adapter.updateCurrentUserId(state.profile?.id, state.profile)
                }
            }
        }
    }

    private fun loadSavedPosts(isPullToRefresh: Boolean = false) {
        loadJob?.cancel()

        if (!isPullToRefresh) {
            binding.progressSavedPosts.isVisible = true
            binding.layoutSavedPostsEmpty.isVisible = false
            binding.layoutSavedPostsError.isVisible = false
        }

        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val savedPosts = postRepository.getSavedPosts()
                adapter.submitList(savedPosts)

                updateEmptyState(savedPosts.isEmpty())
                updateCount(savedPosts.size)
                binding.layoutSavedPostsError.isVisible = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isPullToRefresh && adapter.itemCount == 0) {
                    binding.layoutSavedPostsError.isVisible = true
                    binding.tvSavedPostsErrorMessage.text = e.message ?: "Không thể tải danh sách bài viết đã lưu"
                    binding.layoutSavedPostsEmpty.isVisible = false
                } else {
                    Toast.makeText(
                        requireContext(),
                        e.message ?: "Tải danh sách bài viết đã lưu thất bại",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } finally {
                binding.progressSavedPosts.isVisible = false
                binding.swipeRefreshSavedPosts.isRefreshing = false
            }
        }
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        binding.layoutSavedPostsEmpty.isVisible = isEmpty
        binding.rvSavedPosts.isVisible = !isEmpty
    }

    private fun updateCount(count: Int) {
        binding.tvSavedPostsCount.text = if (count > 0) "$count bài viết đã lưu" else "Danh sách bài viết bạn đã đánh dấu"
    }

    private fun deletePost(postId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                postRepository.deletePost(postId)
                adapter.removePost(postId)
                updateEmptyState(adapter.itemCount == 0)
                updateCount(adapter.itemCount)
                Toast.makeText(requireContext(), "Đã xóa bài viết", Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Không thể xóa: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        loadJob?.cancel()
        loadJob = null
        binding.rvSavedPosts.adapter = null
        _binding = null
        super.onDestroyView()
    }
}
