
package com.doan.studyhub.user.ui.post

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.doan.studyhub.core.data.local.AvatarMode
import com.doan.studyhub.core.data.local.AvatarSelection
import com.doan.studyhub.core.data.model.CommentWithAuthor
import com.doan.studyhub.core.data.model.PostDetail
import com.doan.studyhub.core.data.repository.AuthRepository
import com.doan.studyhub.core.data.repository.CommentRepository
import com.doan.studyhub.core.data.repository.PostRepository
import com.doan.studyhub.core.data.remote.ProfileRemoteDataSource
import com.doan.studyhub.user.R
import com.doan.studyhub.user.databinding.FragmentPostDetailBinding
import com.doan.studyhub.user.ui.home.Home
import com.doan.studyhub.user.ui.profile.AvatarImageLoader
import com.doan.studyhub.user.ui.profile.ProfileFragment
import kotlinx.coroutines.launch

class PostDetailFragment : Fragment() {

    private var _binding: FragmentPostDetailBinding? = null
    private val binding get() = requireNotNull(_binding)

    private val postRepository = PostRepository()
    private val authRepository = AuthRepository()
    private val commentRepository = CommentRepository()
    private val profileRemoteDataSource = ProfileRemoteDataSource()

    private var postId: String = ""

    private lateinit var fileAdapter: DetailFileAdapter
    private lateinit var commentAdapter: CommentAdapter

    private var replyingToCommentId: String? = null
    private var replyingToCommentName: String? = null

    private var currentUserId: String? = null
    private var postOwnerId: String? = null

    private val commentRootIdById = mutableMapOf<String, String>()

    companion object {
        private const val ARG_POST_ID = "post_id"

        fun newInstance(postId: String) = PostDetailFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_POST_ID, postId)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPostDetailBinding.inflate(
            inflater,
            container,
            false
        )
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        postId = arguments?.getString(ARG_POST_ID).orEmpty()

        fileAdapter = DetailFileAdapter()
        binding.rvDetailFiles.layoutManager =
            LinearLayoutManager(requireContext())
        binding.rvDetailFiles.adapter = fileAdapter

        commentAdapter = CommentAdapter(
            fragment = this,
            onReplyClick = ::startReply,
            onEditClick = ::showEditCommentDialog,
            onDeleteClick = ::confirmDeleteComment,
            onMarkBestAnswerClick = ::confirmMarkBestAnswer
        )

        binding.rvDetailComments.layoutManager =
            LinearLayoutManager(requireContext())
        binding.rvDetailComments.adapter = commentAdapter

        binding.btnBackPostDetail.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        binding.btnSendDetailComment.setOnClickListener {
            sendComment()
        }

        binding.btnCancelReply.setOnClickListener {
            cancelReply()
        }

        binding.btnRetryDetailComments.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                loadComments()
            }
        }

        if (postId.isBlank()) {
            Toast.makeText(
                requireContext(),
                "Bài viết không hợp lệ",
                Toast.LENGTH_SHORT
            ).show()

            parentFragmentManager.popBackStack()
        } else {
            loadPostDetail()
        }
    }

    // Tải chi tiết bài viết.
    private fun loadPostDetail() {
        viewLifecycleOwner.lifecycleScope.launch {
            binding.progressPostDetail.isVisible = true

            try {
                val detail = postRepository.getPostDetail(postId)

                if (detail == null) {
                    Toast.makeText(
                        requireContext(),
                        "Bài viết không tồn tại",
                        Toast.LENGTH_SHORT
                    ).show()

                    parentFragmentManager.popBackStack()
                    return@launch
                }

                // Lấy ID tài khoản đang đăng nhập trước khi tải bình luận.
                currentUserId = authRepository.getCurrentUserId()
                postOwnerId = detail.post.authorId

                // Truyền quyền cho CommentAdapter trước khi hiển thị bình luận.
                commentAdapter.setPermissions(
                    userId = currentUserId,
                    ownerId = postOwnerId
                )

                // Hiển thị bài viết và tải bình luận sau khi đã xác định quyền.
                renderPostDetail(detail)
                loadComments()

            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Không thể tải chi tiết bài viết: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                _binding?.progressPostDetail?.isVisible = false
            }
        }
    }

    // Hiển thị thông tin bài viết.
    private fun renderPostDetail(detail: PostDetail) {
        val post = detail.post
        val author = detail.author
        val subject = detail.subject

        val authorName = when {
            !author?.displayName.isNullOrBlank() ->
                author.displayName

            !author?.username.isNullOrBlank() ->
                "@${author.username}"

            else -> "Người dùng"
        }

        binding.tvDetailAuthorName.text = authorName
        binding.tvDetailPostTime.text = post.createdAt?.take(10) ?: ""

        val (category, visibility) =
            com.doan.studyhub.core.data.model.PostTypeUtils.parse(post.type)

        when (visibility) {
            com.doan.studyhub.core.data.model.PostTypeUtils.VISIBILITY_PRIVATE -> {
                binding.imgDetailVisibility.setImageResource(R.drawable.ic_lock)
                binding.imgDetailVisibility.contentDescription = "Chỉ mình tôi"
            }

            com.doan.studyhub.core.data.model.PostTypeUtils.VISIBILITY_FRIENDS -> {
                binding.imgDetailVisibility.setImageResource(R.drawable.ic_group)
                binding.imgDetailVisibility.contentDescription = "Bạn bè"
            }

            else -> {
                binding.imgDetailVisibility.setImageResource(R.drawable.ic_public)
                binding.imgDetailVisibility.contentDescription = "Công khai"
            }
        }

        when (category) {
            com.doan.studyhub.core.data.model.PostTypeUtils.CATEGORY_QUESTION -> {
                binding.tvDetailTypeBadge.isVisible = true
                binding.tvDetailTypeBadge.text = "Hỏi đáp"
                binding.tvDetailTypeBadge.setBackgroundResource(R.drawable.bg_urgent_pill)
            }

            com.doan.studyhub.core.data.model.PostTypeUtils.CATEGORY_DOCUMENT -> {
                binding.tvDetailTypeBadge.isVisible = true
                binding.tvDetailTypeBadge.text = "Tài liệu"
                binding.tvDetailTypeBadge.setBackgroundResource(R.drawable.bg_success_box)
            }

            com.doan.studyhub.core.data.model.PostTypeUtils.CATEGORY_KNOWLEDGE -> {
                binding.tvDetailTypeBadge.isVisible = true
                binding.tvDetailTypeBadge.text = "Kiến thức"
                binding.tvDetailTypeBadge.setBackgroundResource(R.drawable.bg_streak_pill)
            }

            else -> {
                binding.tvDetailTypeBadge.isVisible = false
            }
        }

        val isDefaultSubject =
            subject == null || subject.name.contains("Chung", ignoreCase = true)

        if (!isDefaultSubject) {
            binding.tvDetailSubjectTag.isVisible = true
            binding.tvDetailSubjectTag.text = "📚 Môn học: ${subject!!.name}"
        } else {
            binding.tvDetailSubjectTag.isVisible = false
        }

        binding.tvDetailTitle.text = post.title
        binding.tvDetailContent.text = post.content ?: ""

        AvatarImageLoader.show(
            fragment = this,
            view = binding.imgDetailAuthorAvatar,
            selection = AvatarSelection(AvatarMode.REMOTE),
            remoteUrl = author?.avatarUrl
        )

        // Mở trang cá nhân của tác giả.
        val authorId = post.authorId.trim()

        val authorClickListener = View.OnClickListener {
            if (authorId.isNotBlank()) {
                (parentFragment as? Home)?.openDetail(
                    ProfileFragment.forUser(authorId)
                )
            }
        }

        binding.tvDetailAuthorName.setOnClickListener(authorClickListener)
        binding.imgDetailAuthorAvatar.setOnClickListener(authorClickListener)

        // Ảnh bài viết.
        val imageFile = detail.files.firstOrNull { file ->
            file.fileType?.startsWith("image/") == true ||
                    file.fileName.endsWith(".png", true) ||
                    file.fileName.endsWith(".jpg", true) ||
                    file.fileName.endsWith(".jpeg", true) ||
                    file.fileName.endsWith(".webp", true) ||
                    file.fileUrl.contains(".png", true) ||
                    file.fileUrl.contains(".jpg", true) ||
                    file.fileUrl.contains(".jpeg", true) ||
                    file.fileUrl.contains(".webp", true)
        }

        if (imageFile != null && imageFile.fileUrl.isNotBlank()) {
            binding.cardDetailImagePreview.isVisible = true

            com.bumptech.glide.Glide.with(this)
                .load(imageFile.fileUrl)
                .into(binding.imgDetailImagePreview)
        } else {
            binding.cardDetailImagePreview.isVisible = false
        }

        // File đính kèm.
        if (detail.files.isNotEmpty()) {
            binding.tvDetailFilesHeader.isVisible = true
            fileAdapter.submitList(detail.files)
        } else {
            binding.tvDetailFilesHeader.isVisible = false
        }

        // Kiểm tra quyền của người dùng.
        viewLifecycleOwner.lifecycleScope.launch {
            val userId = authRepository.getCurrentUserId()

            currentUserId = userId
            postOwnerId = post.authorId

            commentAdapter.setPermissions(
                userId = currentUserId,
                ownerId = postOwnerId
            )

            // Kiểm tra quyền của chủ bài viết.

            val isOwner = !userId.isNullOrBlank() &&
                    !post.authorId.isNullOrBlank() &&
                    post.authorId.trim()
                        .equals(userId.trim(), ignoreCase = true)

            binding.btnEditPostDetail.isVisible = isOwner
            binding.btnDeletePostDetail.isVisible = isOwner

            binding.btnEditPostDetail.setOnClickListener {
                parentFragmentManager.beginTransaction()
                    .replace(
                        R.id.containerMain,
                        PostCreateFragment.newInstance(post.id)
                    )
                    .addToBackStack(null)
                    .commit()
            }

            binding.btnDeletePostDetail.setOnClickListener {
                confirmDeletePost()
            }
        }
    }

    // Tải danh sách bình luận và tác giả.

    // Tải danh sách bình luận và tác giả.
    private suspend fun loadComments() {
        binding.progressDetailComments.isVisible = true
        binding.tvDetailCommentsEmpty.isVisible = false
        binding.tvDetailCommentsError.isVisible = false
        binding.btnRetryDetailComments.isVisible = false

        try {
            val comments = commentRepository.getComments(postId)

            val sortedComments = comments.sortedBy {
                it.createdAt.orEmpty()
            }

            commentRootIdById.clear()

            sortedComments.forEach { comment ->
                val commentId = comment.id ?: return@forEach

                val rootId = comment.parentId?.let { parentId ->
                    commentRootIdById[parentId] ?: parentId
                } ?: commentId

                commentRootIdById[commentId] = rootId
            }

            val userIds = comments.map {
                it.userId
            }.distinct()

            val authorsById =
                mutableMapOf<String, com.doan.studyhub.core.data.model.PublicProfile?>()

            for (userId in userIds) {
                authorsById[userId] = try {
                    profileRemoteDataSource.getPublicById(userId)
                } catch (_: Exception) {
                    null
                }
            }

            val commentItems = comments.map { comment ->
                CommentWithAuthor(
                    comment = comment,
                    author = authorsById[comment.userId]
                )
            }

            val repliesByParent = commentItems.groupBy {
                it.comment.parentId
            }

            val orderedComments = mutableListOf<CommentWithAuthor>()
            val visitedIds = mutableSetOf<String>()

            fun appendCommentAndReplies(item: CommentWithAuthor) {
                val commentId = item.comment.id ?: return

                if (!visitedIds.add(commentId)) return

                orderedComments.add(item)

                val replies = repliesByParent[commentId].orEmpty()

                replies.forEach { reply ->
                    appendCommentAndReplies(reply)
                }
            }

            commentItems
                .filter { it.comment.parentId == null }
                .sortedBy { it.comment.createdAt.orEmpty() }
                .forEach { rootComment ->
                    appendCommentAndReplies(rootComment)
                }

            commentItems.forEach { item ->
                val commentId = item.comment.id

                if (commentId != null && commentId !in visitedIds) {
                    appendCommentAndReplies(item)
                }
            }

            commentAdapter.submitList(orderedComments)

            binding.tvDetailCommentCount.text =
                "${comments.size} bình luận"

            binding.tvDetailCommentsEmpty.isVisible =
                comments.isEmpty()

            binding.rvDetailComments.isVisible =
                comments.isNotEmpty()

        } catch (e: Exception) {
            binding.tvDetailCommentsError.text =
                "Không thể tải bình luận. Vui lòng kiểm tra kết nối và thử lại."

            binding.tvDetailCommentsError.isVisible = true
            binding.btnRetryDetailComments.isVisible = true
            binding.rvDetailComments.isVisible = false
            binding.tvDetailCommentsEmpty.isVisible = false

        } finally {
            _binding?.progressDetailComments?.isVisible = false
        }
    }


    // Bắt đầu trả lời một bình luận.
    private fun startReply(item: CommentWithAuthor) {
        val selectedCommentId = item.comment.id ?: return

        replyingToCommentId =
            commentRootIdById[selectedCommentId]
                ?: item.comment.parentId
                        ?: selectedCommentId

        replyingToCommentName = when {
            !item.author?.displayName.isNullOrBlank() ->
                item.author?.displayName

            !item.author?.username.isNullOrBlank() ->
                "@${item.author?.username}"

            else -> "Người dùng"
        }

        binding.edtDetailComment.hint =
            "Đang trả lời ${replyingToCommentName}..."

        binding.btnCancelReply.isVisible = true
        binding.edtDetailComment.requestFocus()
    }

    // Hủy chế độ trả lời.
    private fun cancelReply() {
        replyingToCommentId = null
        replyingToCommentName = null

        binding.edtDetailComment.hint = "Viết bình luận..."
        binding.btnCancelReply.isVisible = false
    }

    // Gửi bình luận hoặc câu trả lời.
    private fun sendComment() {
        val content = binding.edtDetailComment.text
            ?.toString()
            ?.trim()
            .orEmpty()

        if (content.isBlank()) {
            binding.edtDetailComment.error =
                "Vui lòng nhập nội dung bình luận"
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            binding.btnSendDetailComment.isEnabled = false
            binding.progressSendComment.isVisible = true
            binding.btnSendDetailComment.text = ""

            try {
                commentRepository.createComment(
                    postId = postId,
                    content = content,
                    parentId = replyingToCommentId
                )

                binding.edtDetailComment.text?.clear()
                cancelReply()

                Toast.makeText(
                    requireContext(),
                    "Đã gửi bình luận",
                    Toast.LENGTH_SHORT
                ).show()

                loadComments()

            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Không thể gửi bình luận: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()

            } finally {
                _binding?.let { currentBinding ->
                    currentBinding.btnSendDetailComment.isEnabled = true
                    currentBinding.progressSendComment.isVisible = false
                    currentBinding.btnSendDetailComment.text = "Gửi"
                }
            }
        }
    }

    // Hiển thị hộp thoại sửa bình luận.
    private fun showEditCommentDialog(item: CommentWithAuthor) {
        val commentId = item.comment.id ?: return

        val editText = EditText(requireContext()).apply {
            setText(item.comment.content)
            setSelection(text.length)
            hint = "Nhập nội dung bình luận"
            setPadding(48, 24, 48, 24)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Sửa bình luận")
            .setView(editText)
            .setPositiveButton("Lưu", null)
            .setNegativeButton("Hủy", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener {
                            val content = editText.text.toString().trim()

                            if (content.isBlank()) {
                                editText.error = "Nội dung không được để trống"
                                return@setOnClickListener
                            }

                            dialog.dismiss()

                            viewLifecycleOwner.lifecycleScope.launch {
                                try {
                                    commentRepository.updateComment(
                                        commentId = commentId,
                                        content = content
                                    )

                                    loadComments()

                                    Toast.makeText(
                                        requireContext(),
                                        "Đã cập nhật bình luận",
                                        Toast.LENGTH_SHORT
                                    ).show()

                                } catch (e: Exception) {
                                    Toast.makeText(
                                        requireContext(),
                                        "Không thể sửa bình luận: ${e.message}",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                }

                dialog.show()
            }
    }

    // Xác nhận xóa bình luận.
    private fun confirmDeleteComment(item: CommentWithAuthor) {
        val commentId = item.comment.id ?: return

        AlertDialog.Builder(requireContext())
            .setTitle("Xóa bình luận")
            .setMessage(
                "Bạn có chắc chắn muốn xóa bình luận này không?"
            )
            .setPositiveButton("Xóa") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        commentRepository.deleteComment(commentId)

                        loadComments()

                        Toast.makeText(
                            requireContext(),
                            "Đã xóa bình luận",
                            Toast.LENGTH_SHORT
                        ).show()

                    } catch (e: Exception) {
                        Toast.makeText(
                            requireContext(),
                            "Không thể xóa bình luận: ${e.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    // Xác nhận chọn câu trả lời hay nhất.
    private fun confirmMarkBestAnswer(item: CommentWithAuthor) {
        val commentId = item.comment.id ?: return

        if (currentUserId.isNullOrBlank() ||
            postOwnerId.isNullOrBlank() ||
            !currentUserId.equals(postOwnerId, ignoreCase = true)
        ) {
            Toast.makeText(
                requireContext(),
                "Chỉ chủ bài viết mới có quyền chọn câu trả lời hay nhất",
                Toast.LENGTH_SHORT
            ).show()
            return
        }



        AlertDialog.Builder(requireContext())
            .setTitle("Chọn câu trả lời hay nhất")
            .setMessage(
                "Bạn có chắc chắn muốn chọn bình luận này làm câu trả lời hay nhất không?"
            )
            .setPositiveButton("Xác nhận") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        commentRepository.markBestAnswer(
                            postId = postId,
                            commentId = commentId
                        )

                        loadComments()

                        Toast.makeText(
                            requireContext(),
                            "Đã chọn câu trả lời hay nhất",
                            Toast.LENGTH_SHORT
                        ).show()

                    } catch (e: Exception) {
                        Toast.makeText(
                            requireContext(),
                            "Không thể chọn câu trả lời hay nhất: ${e.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    // Xác nhận xóa bài viết.
    private fun confirmDeletePost() {
        AlertDialog.Builder(requireContext())
            .setTitle("Xóa bài viết")
            .setMessage("Bạn có chắc chắn muốn xóa bài viết này không?")
            .setPositiveButton("Xóa") { _, _ ->
                deletePost()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    // Xóa bài viết.
    private fun deletePost() {
        viewLifecycleOwner.lifecycleScope.launch {
            binding.progressPostDetail.isVisible = true

            try {
                postRepository.deletePost(postId)

                Toast.makeText(
                    requireContext(),
                    "Đã xóa bài viết",
                    Toast.LENGTH_SHORT
                ).show()

                parentFragmentManager.popBackStack()

            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Không thể xóa bài viết: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                _binding?.progressPostDetail?.isVisible = false
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
