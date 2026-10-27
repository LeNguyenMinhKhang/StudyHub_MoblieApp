package com.doan.studyhub.user.ui.profile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.doan.studyhub.core.data.repository.AvatarRepository
import com.doan.studyhub.core.data.repository.PostRepository
import com.doan.studyhub.core.data.model.FriendshipAction
import com.doan.studyhub.core.data.model.FriendshipState
import com.doan.studyhub.user.R
import com.doan.studyhub.user.databinding.FragmentProfileBinding
import com.doan.studyhub.user.ui.auth.AuthViewModel
import com.doan.studyhub.user.ui.home.Home
import com.doan.studyhub.user.ui.friend.FriendshipUiState
import com.doan.studyhub.user.ui.friend.FriendshipViewModel
import com.doan.studyhub.user.ui.post.PostAdapter
import com.doan.studyhub.user.ui.post.PostCreateFragment
import com.doan.studyhub.user.ui.post.PostDetailFragment
import com.doan.studyhub.user.ui.subject.SubjectDetailFragment
import kotlinx.coroutines.launch

class ProfileFragment : Fragment() {
    private var _binding: FragmentProfileBinding? = null
    private val binding get() = requireNotNull(_binding)
    private val viewModel: ProfileViewModel by viewModels()
    private val friendship: FriendshipViewModel by viewModels()
    private val auth: AuthViewModel by activityViewModels()
    private val postRepository = PostRepository()
    private lateinit var avatars: AvatarRepository
    private lateinit var postAdapter: PostAdapter
    private val targetId: String? get() = arguments?.getString(ARG_USER_ID)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        avatars = AvatarRepository(requireContext())
        setupPostAdapter()

        binding.btnRetry.setOnClickListener { viewModel.load(targetId) }
        binding.btnEditProfile.setOnClickListener { (parentFragment as? Home)?.openDetail(EditProfileFragment()) }
        binding.btnFindProfile.setOnClickListener { ProfileLookupDialogFragment().show(childFragmentManager, "profile_lookup") }
        childFragmentManager.setFragmentResultListener(ProfileLookupDialogFragment.RESULT_KEY, viewLifecycleOwner) { _, result ->
            result.getString(ProfileLookupDialogFragment.RESULT_PROFILE_ID)?.let(::openPublicProfile)
        }
        binding.btnFriendshipRetry.setOnClickListener { friendship.refresh() }
        binding.btnFriendshipPrimary.setOnClickListener { performFriendshipAction(0) }
        binding.btnFriendshipSecondary.setOnClickListener { performFriendshipAction(1) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                friendship.uiState.collect(::renderFriendship)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::render)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                avatars.revision.collect { viewModel.uiState.value.profile?.let { showAvatar(it.id, it.avatarUrl) } }
            }
        }
        if (targetId == null) viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                auth.uiState.collect { state ->
                    state.profile?.let {
                        viewModel.updateOwn(it)
                        postAdapter.updateCurrentUserId(it.id)
                    }
                }
            }
        }
        viewModel.load(targetId)
    }

    private fun setupPostAdapter() {
        val currentUserId = auth.uiState.value.profile?.id
            ?: com.doan.studyhub.core.data.repository.AuthRepository().getCurrentUserIdSync()
        postAdapter = PostAdapter(
            fragment = this,
            currentUserId = currentUserId,
            currentUserProfile = auth.uiState.value.profile,
            onPostClick = { detail ->
                detail.post.id?.let { postId ->
                    (parentFragment as? Home)?.openDetail(PostDetailFragment.newInstance(postId))
                }
            },
            onSubjectClick = { subjectId ->
                (parentFragment as? Home)?.openDetail(SubjectDetailFragment.newInstance(subjectId))
            },
            onEditClick = { detail ->
                detail.post.id?.let { postId ->
                    (parentFragment as? Home)?.openDetail(PostCreateFragment.newInstance(postId))
                }
            },
            onDeleteClick = { detail ->
                detail.post.id?.let { postId ->
                    deletePost(postId)
                }
            },
            onAuthorClick = { authorId ->
                val currentProfileId = viewModel.uiState.value.profile?.id ?: targetId
                if (authorId.isNotBlank() && authorId != currentProfileId) {
                    openPublicProfile(authorId)
                }
            }
        )
        binding.rvProfilePosts.layoutManager = LinearLayoutManager(requireContext())
        binding.rvProfilePosts.adapter = postAdapter
        binding.rvProfilePosts.isNestedScrollingEnabled = false
    }

    override fun onResume() {
        super.onResume()
        refreshPosts()
        friendship.refresh()
    }

    private fun refreshPosts() {
        val activeProfileId = viewModel.uiState.value.profile?.id ?: targetId
        if (!activeProfileId.isNullOrBlank()) {
            loadUserPosts(activeProfileId)
        }
    }

    private var loadPostsJob: kotlinx.coroutines.Job? = null
    private var postsLoadedFor: String? = null

    private fun loadUserPosts(userId: String) {
        val cleanUserId = userId.trim()
        if (cleanUserId.isBlank()) return

        postsLoadedFor = cleanUserId
        loadPostsJob?.cancel()
        loadPostsJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val posts = postRepository.getPosts(authorId = cleanUserId)
                postAdapter.submitList(posts)
                binding.tvProfilePostsEmpty.isVisible = posts.isEmpty()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ProfileFragment", "Error loading user posts", e)
            }
        }
    }

    private fun deletePost(postId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                postRepository.deletePost(postId)
                Toast.makeText(requireContext(), "Đã xóa bài viết", Toast.LENGTH_SHORT).show()
                viewModel.uiState.value.profile?.id?.let { loadUserPosts(it) }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Không thể xóa bài viết: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun openPublicProfile(id: String) {
        (parentFragment as? Home)?.openDetail(forUser(id))
    }

    private fun render(state: ProfileUiState) {
        binding.progressProfile.isVisible = state.isLoading
        binding.profileContent.isVisible = state.profile != null
        binding.tvProfileStatus.isVisible = state.errorMessage != null || state.unavailable
        binding.tvProfileStatus.text = state.errorMessage ?: if (state.unavailable) getString(com.doan.studyhub.user.R.string.profile_unavailable) else ""
        binding.btnRetry.isVisible = state.errorMessage != null
        state.profile?.let { profile ->
            binding.tvProfileName.text = profile.displayName
            binding.tvProfileUsername.text = "@${profile.username}"
            binding.tvProfileBio.text = profile.bio?.takeIf(String::isNotBlank) ?: getString(com.doan.studyhub.user.R.string.profile_empty_bio)
            binding.tvProfileJoined.text = profile.createdAt?.take(10)?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
                ?.let { getString(com.doan.studyhub.user.R.string.profile_joined, it) } ?: ""
            binding.btnEditProfile.isVisible = state.isOwner
            binding.btnFindProfile.isVisible = state.isOwner
            friendship.show(profile.id)
            renderFriendship(friendship.uiState.value)
            showAvatar(profile.id, profile.avatarUrl)

            val currentLoggedUserId = auth.uiState.value.profile?.id
            postAdapter.updateCurrentUserId(currentLoggedUserId)
            // render runs on every state emission; reloading each time cancels the in-flight request.
            if (postsLoadedFor != profile.id) loadUserPosts(profile.id)
        }
    }

    private fun performFriendshipAction(index: Int) {
        val action = friendship.uiState.value.actions.getOrNull(index) ?: return
        if (action == FriendshipAction.REMOVE) {
            AlertDialog.Builder(requireContext())
                .setMessage(R.string.friendship_remove_confirm)
                .setNegativeButton(R.string.profile_cancel, null)
                .setPositiveButton(R.string.friendship_remove) { _, _ -> friendship.act(action) }
                .show()
        } else friendship.act(action)
    }

    private fun renderFriendship(state: FriendshipUiState) {
        val profile = viewModel.uiState.value
        binding.friendshipSection.isVisible = profile.profile != null && !profile.isOwner &&
            state.snapshot?.state != FriendshipState.SELF
        binding.progressFriendship.isVisible = state.loading || state.busy
        binding.tvFriendshipStatus.text = state.error ?: getString(when (state.snapshot?.state) {
            FriendshipState.OUTGOING_PENDING -> R.string.friendship_sent
            FriendshipState.INCOMING_PENDING -> R.string.friendship_received
            FriendshipState.FRIENDS -> R.string.friendship_connected
            FriendshipState.UNAVAILABLE -> R.string.profile_unavailable
            else -> R.string.friendship_title
        })
        binding.btnFriendshipRetry.isVisible = state.error != null && !state.loading && !state.busy
        listOf(binding.btnFriendshipPrimary, binding.btnFriendshipSecondary).forEachIndexed { index, button ->
            val action = state.actions.getOrNull(index)
            button.isVisible = action != null
            button.isEnabled = action != null
            if (action != null) button.setText(when (action) {
                FriendshipAction.SEND -> R.string.friendship_send
                FriendshipAction.ACCEPT -> R.string.friendship_accept
                FriendshipAction.DECLINE -> R.string.friendship_decline
                FriendshipAction.CANCEL -> R.string.friendship_cancel
                FriendshipAction.REMOVE -> R.string.friendship_remove
            })
        }
    }

    private fun showAvatar(id: String, url: String?) {
        if (_binding != null && viewModel.uiState.value.profile?.id == id) {
            AvatarImageLoader.showCommitted(this@ProfileFragment, binding.imgProfileAvatar, url)
        }
    }

    override fun onDestroyView() {
        postsLoadedFor = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_USER_ID = "user_id"
        fun forUser(id: String) = ProfileFragment().apply { arguments = Bundle().apply { putString(ARG_USER_ID, id) } }
    }
}
