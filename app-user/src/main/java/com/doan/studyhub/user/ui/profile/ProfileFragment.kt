
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
import com.doan.studyhub.core.data.model.FriendshipAction
import com.doan.studyhub.core.data.model.FriendshipState
import com.doan.studyhub.core.data.repository.AvatarRepository
import com.doan.studyhub.core.data.repository.PostRepository
import com.doan.studyhub.user.R
import com.doan.studyhub.user.databinding.FragmentProfileBinding
import com.doan.studyhub.user.ui.auth.AuthViewModel
import com.doan.studyhub.user.ui.block.BlockUiState
import com.doan.studyhub.user.ui.block.BlockViewModel
import com.doan.studyhub.user.ui.chat.DirectChatFragment
import com.doan.studyhub.user.ui.friend.FriendshipUiState
import com.doan.studyhub.user.ui.friend.FriendshipViewModel
import com.doan.studyhub.user.ui.home.Home
import com.doan.studyhub.user.ui.post.PostAdapter
import com.doan.studyhub.user.ui.post.PostCreateFragment
import com.doan.studyhub.user.ui.post.PostDetailFragment
import com.doan.studyhub.user.ui.subject.SubjectDetailFragment
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = requireNotNull(_binding)

    private val viewModel: ProfileViewModel by viewModels()
    private val friendship: FriendshipViewModel by viewModels()
    private val block: BlockViewModel by viewModels()
    private val auth: AuthViewModel by activityViewModels()

    private val postRepository = PostRepository()

    private lateinit var avatars: AvatarRepository
    private lateinit var postAdapter: PostAdapter

    private val targetId: String?
        get() = arguments?.getString(ARG_USER_ID)

    private var lastBlockObservation: Pair<String, Boolean>? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        state: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        super.onViewCreated(view, state)

        avatars = AvatarRepository(requireContext())
        setupPostAdapter()
        setupListeners()
        observeViewModels()

        viewModel.load(targetId)
    }

    private fun setupListeners() {

        binding.btnRetry.setOnClickListener {
            viewModel.load(targetId)
        }

        binding.btnEditProfile.setOnClickListener {
            (parentFragment as? Home)?.openDetail(EditProfileFragment())
        }

        binding.btnFindProfile.setOnClickListener {
            ProfileLookupDialogFragment().show(
                childFragmentManager,
                "profile_lookup"
            )
        }

        childFragmentManager.setFragmentResultListener(
            ProfileLookupDialogFragment.RESULT_KEY,
            viewLifecycleOwner
        ) { _, result ->
            result.getString(
                ProfileLookupDialogFragment.RESULT_PROFILE_ID
            )?.let(::openPublicProfile)
        }

        binding.btnFriendshipRetry.setOnClickListener {
            friendship.refresh()
        }

        binding.btnFriendshipPrimary.setOnClickListener {
            performFriendshipAction(0)
        }

        binding.btnFriendshipSecondary.setOnClickListener {
            performFriendshipAction(1)
        }

        binding.btnDirectChat.setOnClickListener {
            val target = viewModel.uiState.value.profile
                ?: return@setOnClickListener

            // Kiểm tra lại trạng thái chặn trước khi mở chat.
            val blockState = block.uiState.value.snapshot

            if (blockState == null || blockState.isBlocked) {
                Toast.makeText(
                    requireContext(),
                    "Không thể nhắn tin với người dùng này.",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            val chatFragment = DirectChatFragment.newInstance(
                targetUserId = target.id,
                targetName = target.displayName,
                targetAvatar = target.avatarUrl
            )

            (parentFragment as? Home)?.openDetail(chatFragment)
        }

        binding.btnProfileBlock.setOnClickListener {
            val snapshot = block.uiState.value.snapshot
                ?: return@setOnClickListener

            if (snapshot.blockedByMe) {
                confirmUnblockUser()
            } else if (!snapshot.isBlocked) {
                confirmBlockUser()
            }
        }
    }

    private fun observeViewModels() {

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                friendship.uiState.collect { state ->
                    renderFriendship(state)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    render(state)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                block.uiState.collect { state ->
                    renderBlock(state)

                    val snapshot = state.snapshot

                    if (snapshot != null) {
                        val current = snapshot.targetId to snapshot.isBlocked
                        val previous = lastBlockObservation

                        if (
                            previous != null &&
                            previous.first == current.first &&
                            previous.second != current.second
                        ) {
                            friendship.refresh()
                        }

                        lastBlockObservation = current
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                avatars.revision.collect {
                    viewModel.uiState.value.profile?.let {
                        showAvatar(it.id, it.avatarUrl)
                    }
                }
            }
        }

        if (targetId == null) {
            viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    auth.uiState.collect { state ->
                        state.profile?.let {
                            viewModel.updateOwn(it)
                            postAdapter.updateCurrentUserId(it.id, it)
                        }
                    }
                }
            }
        }
    }

    private fun setupPostAdapter() {

        val currentUserId = auth.uiState.value.profile?.id
            ?: com.doan.studyhub.core.data.repository.AuthRepository()
                .getCurrentUserIdSync()

        postAdapter = PostAdapter(
            fragment = this,
            currentUserId = currentUserId,
            currentUserProfile = auth.uiState.value.profile,

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
                val currentProfileId =
                    viewModel.uiState.value.profile?.id ?: targetId

                if (
                    authorId.isNotBlank() &&
                    authorId != currentProfileId
                ) {
                    openPublicProfile(authorId)
                }
            },

            onLikeClick = { postDetail, onResult ->
                val postId = postDetail.post.id

                if (postId.isNullOrBlank()) {
                    onResult(
                        Result.failure(
                            IllegalStateException(
                                "Không tìm thấy ID bài viết."
                            )
                        )
                    )
                } else {
                    viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            val isLiked =
                                postRepository.toggleLike(postId)

                            onResult(Result.success(isLiked))
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            onResult(Result.failure(e))
                        }
                    }
                }
            },

            onBookmarkClick = { detail, onResult ->
                val postId = detail.post.id
                if (postId.isNullOrBlank()) {
                    onResult(
                        Result.failure(
                            IllegalStateException("Không tìm thấy ID bài viết.")
                        )
                    )
                } else {
                    viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            val isSaved = postRepository.toggleSavePost(postId)
                            onResult(Result.success(isSaved))
                            if (selectedProfileTab == 1 && !isSaved) {
                                postAdapter.removePost(postId)
                                binding.tvProfilePostsEmpty.isVisible = postAdapter.itemCount == 0
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
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
                    tab = com.doan.studyhub.core.data.model.SearchTab.POSTS
                )
            }
        )

        binding.rvProfilePosts.layoutManager =
            LinearLayoutManager(requireContext())

        binding.rvProfilePosts.adapter = postAdapter
        binding.rvProfilePosts.isNestedScrollingEnabled = false
    }

    override fun onResume() {
        super.onResume()

        refreshPosts()
        friendship.refresh()

        val profile = viewModel.uiState.value.profile

        if (profile != null && !viewModel.uiState.value.isOwner) {
            block.refreshState()
        }
    }

    private fun refreshPosts() {
        val activeProfileId =
            viewModel.uiState.value.profile?.id ?: targetId

        if (!activeProfileId.isNullOrBlank()) {
            loadPostsForSelectedTab()
        }
    }

    private var selectedProfileTab: Int = 0
    private var loadPostsJob: Job? = null
    private var postsLoadedFor: String? = null

    private fun loadPostsForSelectedTab() {
        val activeProfileId = viewModel.uiState.value.profile?.id ?: targetId
        if (activeProfileId.isNullOrBlank()) return

        if (selectedProfileTab == 1) {
            loadSavedPosts()
        } else {
            loadUserPosts(activeProfileId)
        }
    }

    private fun loadSavedPosts() {
        loadPostsJob?.cancel()

        loadPostsJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val saved = postRepository.getSavedPosts()
                postAdapter.submitList(saved)
                binding.tvProfilePostsEmpty.text = "Bạn chưa lưu bài viết nào."
                binding.tvProfilePostsEmpty.isVisible = saved.isEmpty()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ProfileFragment", "Error loading saved posts", e)
            }
        }
    }

    private fun loadUserPosts(userId: String) {
        val cleanUserId = userId.trim()
        if (cleanUserId.isBlank()) return

        postsLoadedFor = cleanUserId
        loadPostsJob?.cancel()

        loadPostsJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val posts = postRepository.getPosts(
                    authorId = cleanUserId
                )

                postAdapter.submitList(posts)
                binding.tvProfilePostsEmpty.text = "Chưa có bài viết nào."
                binding.tvProfilePostsEmpty.isVisible = posts.isEmpty()

            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e(
                    "ProfileFragment",
                    "Error loading user posts",
                    e
                )
            }
        }
    }

    private fun deletePost(postId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                postRepository.deletePost(postId)

                Toast.makeText(
                    requireContext(),
                    "Đã xóa bài viết",
                    Toast.LENGTH_SHORT
                ).show()

                viewModel.uiState.value.profile?.id?.let {
                    loadUserPosts(it)
                }

            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Không thể xóa bài viết: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    fun openPublicProfile(id: String) {
        (parentFragment as? Home)?.openDetail(forUser(id))
    }

    private fun render(state: ProfileUiState) {

        binding.progressProfile.isVisible = state.isLoading
        binding.profileContent.isVisible = state.profile != null

        binding.tvProfileStatus.isVisible =
            state.errorMessage != null || state.unavailable

        binding.tvProfileStatus.text =
            state.errorMessage
                ?: if (state.unavailable) {
                    getString(R.string.profile_unavailable)
                } else {
                    ""
                }

        binding.btnRetry.isVisible =
            state.errorMessage != null

        state.profile?.let { profile ->

            binding.tvProfileName.text = profile.displayName
            binding.tvProfileUsername.text = "@${profile.username}"

            binding.tvProfileBio.text =
                profile.bio?.takeIf(String::isNotBlank)
                    ?: getString(R.string.profile_empty_bio)

            binding.tvProfileJoined.text =
                profile.createdAt
                    ?.take(10)
                    ?.takeIf {
                        it.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))
                    }
                    ?.let {
                        getString(R.string.profile_joined, it)
                    } ?: ""

            binding.btnEditProfile.isVisible = state.isOwner
            binding.btnFindProfile.isVisible = state.isOwner

            if (state.isOwner) {
                renderBlock(block.uiState.value)
            } else {
                block.show(profile.id)
                renderBlock(block.uiState.value)
            }

            friendship.show(profile.id)
            renderFriendship(friendship.uiState.value)

            showAvatar(profile.id, profile.avatarUrl)

            val currentLoggedUserId =
                auth.uiState.value.profile?.id

            postAdapter.updateCurrentUserId(currentLoggedUserId)

            if (state.isOwner) {
                binding.tvProfilePostsHeader.isVisible = false
                binding.tabLayoutProfile.isVisible = true
                if (binding.tabLayoutProfile.tabCount == 0) {
                    binding.tabLayoutProfile.addTab(binding.tabLayoutProfile.newTab().setText("Bài viết của tôi"))
                    binding.tabLayoutProfile.addTab(binding.tabLayoutProfile.newTab().setText("Đã lưu"))
                    binding.tabLayoutProfile.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
                        override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                            selectedProfileTab = tab?.position ?: 0
                            loadPostsForSelectedTab()
                        }
                        override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
                        override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                            loadPostsForSelectedTab()
                        }
                    })
                }
            } else {
                binding.tabLayoutProfile.isVisible = false
                binding.tvProfilePostsHeader.isVisible = true
            }

            if (postsLoadedFor != profile.id) {
                loadPostsForSelectedTab()
            }
        }
    }

    private fun performFriendshipAction(index: Int) {
        // Không thực hiện hành động nếu một trong hai bên đã chặn.
        if (block.uiState.value.snapshot?.isBlocked != false) return

        val action =
            friendship.uiState.value.actions.getOrNull(index) ?: return

        if (action == FriendshipAction.REMOVE) {
            AlertDialog.Builder(requireContext())
                .setMessage(R.string.friendship_remove_confirm)
                .setNegativeButton(R.string.profile_cancel, null)
                .setPositiveButton(R.string.friendship_remove) { _, _ ->
                    friendship.act(action)
                }
                .show()
        } else {
            friendship.act(action)
        }
    }

    private fun renderFriendship(state: FriendshipUiState) {

        val profile = viewModel.uiState.value
        val blockSnapshot = block.uiState.value.snapshot
        val isBlocked = blockSnapshot?.isBlocked == true

        val isPublicProfile =
            profile.profile != null &&
                    !profile.isOwner &&
                    state.snapshot?.state != FriendshipState.SELF

        binding.friendshipSection.isVisible = isPublicProfile

        binding.progressFriendship.isVisible =
            isPublicProfile && (state.loading || state.busy)

        binding.tvFriendshipStatus.text =
            if (isBlocked) {
                if (blockSnapshot?.blockedByMe == true) {
                    "Bạn đã chặn người dùng này."
                } else {
                    "Người dùng này đã chặn bạn."
                }
            } else {
                state.error ?: getString(
                    when (state.snapshot?.state) {
                        FriendshipState.OUTGOING_PENDING ->
                            R.string.friendship_sent

                        FriendshipState.INCOMING_PENDING ->
                            R.string.friendship_received

                        FriendshipState.FRIENDS ->
                            R.string.friendship_connected

                        FriendshipState.UNAVAILABLE ->
                            R.string.profile_unavailable

                        else ->
                            R.string.friendship_title
                    }
                )
            }

        binding.btnFriendshipRetry.isVisible =
            isPublicProfile &&
                    !isBlocked &&
                    state.error != null &&
                    !state.loading &&
                    !state.busy

        listOf(
            binding.btnFriendshipPrimary,
            binding.btnFriendshipSecondary
        ).forEachIndexed { index, button ->

            val action = state.actions.getOrNull(index)

            button.isVisible =
                isPublicProfile && !isBlocked && action != null

            button.isEnabled =
                !state.loading && !state.busy && !isBlocked

            if (action != null) {
                button.setText(
                    when (action) {
                        FriendshipAction.SEND ->
                            R.string.friendship_send

                        FriendshipAction.ACCEPT ->
                            R.string.friendship_accept

                        FriendshipAction.DECLINE ->
                            R.string.friendship_decline

                        FriendshipAction.CANCEL ->
                            R.string.friendship_cancel

                        FriendshipAction.REMOVE ->
                            R.string.friendship_remove
                    }
                )
            }
        }

        binding.btnDirectChat.isVisible =
            isPublicProfile &&
                    blockSnapshot != null &&
                    !isBlocked
    }

    private fun renderBlock(state: BlockUiState) {

        val profileState = viewModel.uiState.value

        val isPublicProfile =
            profileState.profile != null &&
                    !profileState.isOwner

        val snapshot = state.snapshot

        binding.progressBlock.isVisible =
            isPublicProfile && (state.loading || state.busy)

        binding.btnProfileBlock.isVisible =
            isPublicProfile &&
                    snapshot != null &&
                    (snapshot.blockedByMe || !snapshot.blockedMe)

        binding.btnProfileBlock.isEnabled =
            !state.loading && !state.busy

        binding.btnProfileBlock.text =
            if (snapshot?.blockedByMe == true) {
                "Bỏ chặn"
            } else {
                "Chặn người dùng"
            }

        binding.btnDirectChat.isVisible =
            isPublicProfile &&
                    snapshot != null &&
                    !snapshot.isBlocked

        if (isPublicProfile && snapshot?.isBlocked == true) {
            binding.tvFriendshipStatus.text =
                if (snapshot.blockedByMe) {
                    "Bạn đã chặn người dùng này."
                } else {
                    "Người dùng này đã chặn bạn."
                }

            binding.btnFriendshipRetry.isVisible = false
            binding.btnFriendshipPrimary.isVisible = false
            binding.btnFriendshipSecondary.isVisible = false
        }
    }

    private fun confirmBlockUser() {
        val profile = viewModel.uiState.value.profile ?: return

        AlertDialog.Builder(requireContext())
            .setTitle("Chặn người dùng")
            .setMessage(
                "Bạn có chắc chắn muốn chặn ${profile.displayName} không?\n\n" +
                        "Hai người sẽ không thể nhắn tin hoặc tương tác kết bạn với nhau."
            )
            .setNegativeButton("Hủy", null)
            .setPositiveButton("Chặn") { _, _ ->
                block.blockUser()
            }
            .show()
    }

    private fun confirmUnblockUser() {
        val profile = viewModel.uiState.value.profile ?: return

        AlertDialog.Builder(requireContext())
            .setTitle("Bỏ chặn người dùng")
            .setMessage(
                "Bạn có chắc chắn muốn bỏ chặn ${profile.displayName} không?"
            )
            .setNegativeButton("Hủy", null)
            .setPositiveButton("Bỏ chặn") { _, _ ->
                block.unblockUser()
            }
            .show()
    }

    private fun showAvatar(id: String, url: String?) {
        if (
            _binding != null &&
            viewModel.uiState.value.profile?.id == id
        ) {
            AvatarImageLoader.showCommitted(
                this@ProfileFragment,
                binding.imgProfileAvatar,
                url
            )
        }
    }

    override fun onDestroyView() {
        loadPostsJob?.cancel()
        loadPostsJob = null
        postsLoadedFor = null
        lastBlockObservation = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_USER_ID = "user_id"

        fun forUser(id: String) =
            ProfileFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_USER_ID, id)
                }
            }
    }
}
