package com.doan.studyhub.user.ui.feed

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
import com.doan.studyhub.core.data.local.AvatarMode
import com.doan.studyhub.core.data.local.AvatarSelection
import com.doan.studyhub.core.data.repository.AuthRepository
import com.doan.studyhub.core.data.repository.PostRepository
import com.doan.studyhub.user.R
import com.doan.studyhub.user.databinding.FragmentFeedBinding
import com.doan.studyhub.user.ui.auth.AuthViewModel
import com.doan.studyhub.user.ui.post.PostAdapter
import com.doan.studyhub.user.ui.post.PostCreateFragment
import com.doan.studyhub.user.ui.post.PostDetailFragment
import com.doan.studyhub.user.ui.profile.AvatarImageLoader
import com.doan.studyhub.user.ui.subject.SubjectDetailFragment
import com.doan.studyhub.user.ui.home.Home
import com.doan.studyhub.user.ui.profile.ProfileFragment
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
            loadFeed()
        }

        binding.cardCreatePostPrompt.setOnClickListener {
            openCreatePost()
        }

        binding.btnFilterAll.setOnClickListener {
            currentFilterType = null
            loadFeed()
        }
        binding.btnFilterKnowledge.setOnClickListener {
            currentFilterType = "knowledge"
            loadFeed()
        }
        binding.btnFilterQuestion.setOnClickListener {
            currentFilterType = "question"
            loadFeed()
        }
        binding.btnFilterDocument.setOnClickListener {
            currentFilterType = "document"
            loadFeed()
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

    private fun loadFeed() {
        viewLifecycleOwner.lifecycleScope.launch {
            binding.progressFeed.isVisible = true
            binding.tvFeedEmpty.isVisible = false
            try {
                val posts = postRepository.getPosts(type = currentFilterType)
                adapter.submitList(posts)
                binding.tvFeedEmpty.isVisible = posts.isEmpty()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Không thể tải bảng tin: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                _binding?.progressFeed?.isVisible = false
            }
        }
    }

    private fun deletePost(postId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                postRepository.deletePost(postId)
                Toast.makeText(requireContext(), "Đã xóa bài viết", Toast.LENGTH_SHORT).show()
                loadFeed()
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
