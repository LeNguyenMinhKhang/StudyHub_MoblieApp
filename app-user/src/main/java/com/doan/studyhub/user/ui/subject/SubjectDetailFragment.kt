package com.doan.studyhub.user.ui.subject

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.doan.studyhub.core.data.repository.AuthRepository
import com.doan.studyhub.core.data.repository.PostRepository
import com.doan.studyhub.core.data.repository.SubjectRepository
import com.doan.studyhub.user.R
import com.doan.studyhub.user.ui.post.PostAdapter
import com.doan.studyhub.user.ui.post.PostCreateFragment
import com.doan.studyhub.user.ui.post.PostDetailFragment
import com.doan.studyhub.user.ui.home.Home
import com.doan.studyhub.user.ui.profile.ProfileFragment
import kotlinx.coroutines.launch

class SubjectDetailFragment : Fragment() {

    private val subjectRepository = SubjectRepository()
    private val postRepository = PostRepository()
    private val authRepository = AuthRepository()

    private var subjectId: String = ""
    private var isFollowed = false

    private lateinit var nameText: TextView
    private lateinit var descriptionText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var followButton: Button
    private lateinit var emptyText: TextView
    private lateinit var recyclerView: RecyclerView

    private lateinit var postAdapter: PostAdapter

    companion object {
        private const val ARG_SUBJECT_ID = "subject_id"

        fun newInstance(subjectId: String) =
            SubjectDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SUBJECT_ID, subjectId)
                }
            }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(
            R.layout.fragment_subject_detail,
            container,
            false
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        nameText = view.findViewById(R.id.txtSubjectDetailName)
        descriptionText = view.findViewById(R.id.txtSubjectDetailDescription)
        progress = view.findViewById(R.id.progressSubjectDetail)
        errorText = view.findViewById(R.id.txtSubjectDetailError)
        followButton = view.findViewById(R.id.btnFollowSubjectDetail)
        emptyText = view.findViewById(R.id.tvSubjectPostsEmpty)
        recyclerView = view.findViewById(R.id.rvSubjectPosts)

        subjectId = arguments?.getString(ARG_SUBJECT_ID).orEmpty()

        view.findViewById<Button>(R.id.btnBackSubjectDetail)
            .setOnClickListener {
                parentFragmentManager.popBackStack()
            }

        followButton.setOnClickListener {
            toggleFollow()
        }

        setupPostAdapter()

        if (subjectId.isBlank()) {
            showError("Không tìm thấy mã môn học.")
        } else {
            loadSubjectDetail()
            loadSubjectPosts()
        }
    }

    private fun setupPostAdapter() {
        val currentUserId = authRepository.getCurrentUserIdSync()
        postAdapter = PostAdapter(
            fragment = this@SubjectDetailFragment,
            currentUserId = currentUserId,
            onPostClick = { detail ->
                detail.post.id?.let { postId ->
                    parentFragmentManager.beginTransaction()
                        .replace(R.id.containerMain, PostDetailFragment.newInstance(postId))
                        .addToBackStack(null)
                        .commit()
                }
            },
            onSubjectClick = { },
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

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = postAdapter
    }

    private fun loadSubjectPosts() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val posts = postRepository.getPosts(subjectId = subjectId)
                postAdapter.submitList(posts)
                emptyText.isVisible = posts.isEmpty()
            } catch (e: Exception) {
                // Ignore error in subject detail posts
            }
        }
    }

    private fun deletePost(postId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                postRepository.deletePost(postId)
                Toast.makeText(requireContext(), "Đã xóa bài viết", Toast.LENGTH_SHORT).show()
                loadSubjectPosts()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Không thể xóa bài viết: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadSubjectDetail() {
        viewLifecycleOwner.lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            errorText.visibility = View.GONE
            followButton.isEnabled = false

            try {
                val subject = subjectRepository.getSubjectById(subjectId)

                if (subject == null) {
                    showError("Môn học không tồn tại hoặc đã ngừng hoạt động.")
                    return@launch
                }

                nameText.text = subject.name
                descriptionText.text =
                    subject.description?.takeIf { it.isNotBlank() }
                        ?: "Môn học chưa có mô tả."

                val userId = authRepository.getCurrentUserId()

                if (userId != null) {
                    isFollowed =
                        subjectRepository.getFollowedSubjectIds(userId)
                            .contains(subjectId)
                }

                updateFollowButton()
            } catch (e: Exception) {
                showError("Không thể tải chi tiết môn học: ${e.message.orEmpty()}")
            } finally {
                progress.visibility = View.GONE
                followButton.isEnabled = true
            }
        }
    }

    private fun toggleFollow() {
        viewLifecycleOwner.lifecycleScope.launch {
            val userId = authRepository.getCurrentUserId()

            if (userId == null) {
                Toast.makeText(
                    requireContext(),
                    "Bạn cần đăng nhập để theo dõi môn học.",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            followButton.isEnabled = false

            try {
                if (isFollowed) {
                    subjectRepository.unfollowSubject(userId, subjectId)
                    isFollowed = false
                } else {
                    subjectRepository.followSubject(userId, subjectId)
                    isFollowed = true
                }

                updateFollowButton()
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Thao tác thất bại: ${e.message.orEmpty()}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                followButton.isEnabled = true
            }
        }
    }

    private fun updateFollowButton() {
        followButton.text =
            if (isFollowed) "Bỏ theo dõi" else "Theo dõi"
    }

    private fun showError(message: String) {
        errorText.text = message
        errorText.visibility = View.VISIBLE
    }
}
