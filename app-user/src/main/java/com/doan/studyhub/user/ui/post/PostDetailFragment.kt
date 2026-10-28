package com.doan.studyhub.user.ui.post

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.doan.studyhub.core.data.local.AvatarMode
import com.doan.studyhub.core.data.local.AvatarSelection
import com.doan.studyhub.core.data.model.PostDetail
import com.doan.studyhub.core.data.repository.AuthRepository
import com.doan.studyhub.core.data.repository.PostRepository
import com.doan.studyhub.user.R
import com.doan.studyhub.user.databinding.FragmentPostDetailBinding
import com.doan.studyhub.user.ui.home.Home
import com.doan.studyhub.user.ui.profile.ProfileFragment
import com.doan.studyhub.user.ui.profile.AvatarImageLoader
import kotlinx.coroutines.launch

class PostDetailFragment : Fragment() {

    private var _binding: FragmentPostDetailBinding? = null
    private val binding get() = requireNotNull(_binding)

    private val postRepository = PostRepository()
    private val authRepository = AuthRepository()

    private var postId: String = ""
    private lateinit var fileAdapter: DetailFileAdapter

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
        _binding = FragmentPostDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        postId = arguments?.getString(ARG_POST_ID).orEmpty()

        fileAdapter = DetailFileAdapter()
        binding.rvDetailFiles.layoutManager = LinearLayoutManager(requireContext())
        binding.rvDetailFiles.adapter = fileAdapter

        binding.btnBackPostDetail.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        if (postId.isBlank()) {
            Toast.makeText(requireContext(), "Bài viết không hợp lệ", Toast.LENGTH_SHORT).show()
            parentFragmentManager.popBackStack()
        } else {
            loadPostDetail()
        }
    }

    private fun loadPostDetail() {
        viewLifecycleOwner.lifecycleScope.launch {
            binding.progressPostDetail.isVisible = true
            try {
                val detail = postRepository.getPostDetail(postId)
                if (detail == null) {
                    Toast.makeText(requireContext(), "Bài viết không tồn tại", Toast.LENGTH_SHORT).show()
                    parentFragmentManager.popBackStack()
                    return@launch
                }

                renderPostDetail(detail)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Không thể tải chi tiết bài viết: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                _binding?.progressPostDetail?.isVisible = false
            }
        }
    }

    private fun renderPostDetail(detail: PostDetail) {
        val post = detail.post
        val author = detail.author
        val subject = detail.subject

        val authorName = when {
            !author?.displayName.isNullOrBlank() -> author.displayName
            !author?.username.isNullOrBlank() -> "@${author.username}"
            else -> "Người dùng"
        }
        binding.tvDetailAuthorName.text = authorName
        binding.tvDetailPostTime.text = post.createdAt?.take(10) ?: ""

        val (category, visibility) = com.doan.studyhub.core.data.model.PostTypeUtils.parse(post.type)

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

        val isDefaultSubject = subject == null || subject.name.contains("Chung", ignoreCase = true)
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

        val authorId = post.authorId.trim()
        val authorClickListener = View.OnClickListener {
            if (authorId.isNotBlank()) {
                (parentFragment as? Home)?.openDetail(ProfileFragment.forUser(authorId))
            }
        }
        binding.tvDetailAuthorName.setOnClickListener(authorClickListener)
        binding.imgDetailAuthorAvatar.setOnClickListener(authorClickListener)

        // Post Image (Facebook style)
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

        if (detail.files.isNotEmpty()) {
            binding.tvDetailFilesHeader.isVisible = true
            fileAdapter.submitList(detail.files)
        } else {
            binding.tvDetailFilesHeader.isVisible = false
        }

        // Owner check for Edit / Delete
        viewLifecycleOwner.lifecycleScope.launch {
            val currentUserId = authRepository.getCurrentUserId()
            val isOwner = !currentUserId.isNullOrBlank()
                && !post.authorId.isNullOrBlank()
                && post.authorId.trim().equals(currentUserId.trim(), ignoreCase = true)
            binding.btnEditPostDetail.isVisible = isOwner
            binding.btnDeletePostDetail.isVisible = isOwner

            binding.btnEditPostDetail.setOnClickListener {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.containerMain, PostCreateFragment.newInstance(post.id))
                    .addToBackStack(null)
                    .commit()
            }

            binding.btnDeletePostDetail.setOnClickListener {
                confirmDeletePost()
            }
        }
    }

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

    private fun deletePost() {
        viewLifecycleOwner.lifecycleScope.launch {
            binding.progressPostDetail.isVisible = true
            try {
                postRepository.deletePost(postId)
                Toast.makeText(requireContext(), "Đã xóa bài viết", Toast.LENGTH_SHORT).show()
                parentFragmentManager.popBackStack()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Không thể xóa bài viết: ${e.message}", Toast.LENGTH_SHORT).show()
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
