package com.doan.studyhub.user.ui.post

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.doan.studyhub.core.data.local.AvatarMode
import com.doan.studyhub.core.data.local.AvatarSelection
import com.doan.studyhub.core.data.model.PostDetail
import com.doan.studyhub.core.data.model.Profile
import com.doan.studyhub.core.data.repository.AuthRepository
import com.doan.studyhub.user.R
import com.doan.studyhub.user.databinding.ItemPostBinding
import com.doan.studyhub.user.ui.profile.AvatarImageLoader

class PostAdapter(
    private val fragment: Fragment,
    var currentUserId: String? = null,
    var currentUserProfile: Profile? = null,
    private val onPostClick: (PostDetail) -> Unit,
    private val onSubjectClick: (String) -> Unit,
    private val onEditClick: (PostDetail) -> Unit,
    private val onDeleteClick: (PostDetail) -> Unit,
    private val onAuthorClick: ((String) -> Unit)? = null
) : RecyclerView.Adapter<PostAdapter.ViewHolder>() {

    private val posts = mutableListOf<PostDetail>()

    // Frontend in-memory like state (chỉ lưu ID bài viết đã like)
    private val likedPostIds = mutableSetOf<String>()

    fun submitList(newList: List<PostDetail>) {
        posts.clear()
        posts.addAll(newList)
        notifyDataSetChanged()
    }

    fun addPosts(newItems: List<PostDetail>) {
        if (newItems.isEmpty()) return
        val startPos = posts.size
        posts.addAll(newItems)
        notifyItemRangeInserted(startPos, newItems.size)
    }

    fun removePost(postId: String) {
        val index = posts.indexOfFirst { it.post.id == postId }
        if (index != -1) {
            posts.removeAt(index)
            notifyItemRemoved(index)
        }
    }

    fun updateCurrentUserId(newUserId: String?, profile: Profile? = null) {
        currentUserId = newUserId
        if (profile != null) currentUserProfile = profile
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPostBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(posts[position])
    }

    override fun getItemCount(): Int = posts.size

    inner class ViewHolder(private val binding: ItemPostBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: PostDetail) {
            val post = item.post
            val author = item.author ?: if (!currentUserId.isNullOrBlank() && post.authorId == currentUserId) currentUserProfile else null
            val subject = item.subject

            val authorName = when {
                !author?.displayName.isNullOrBlank() -> author.displayName
                !author?.username.isNullOrBlank() -> "@${author.username}"
                !currentUserId.isNullOrBlank() && post.authorId == currentUserId -> {
                    currentUserProfile?.displayName?.takeIf { it.isNotBlank() }
                        ?: currentUserProfile?.username?.takeIf { it.isNotBlank() }
                        ?: "Người dùng"
                }
                else -> author?.displayName?.takeIf { it.isNotBlank() }
                    ?: author?.username?.takeIf { it.isNotBlank() }?.let { "@$it" }
                    ?: "Người dùng"
            }
            binding.tvPostAuthorName.text = authorName
            binding.tvPostTime.text = post.createdAt?.take(10) ?: ""

            // Visibility Icon (Public 🌐 / Friends 👥 / Private 🔒)
            val (category, visibility) = com.doan.studyhub.core.data.model.PostTypeUtils.parse(post.type)

            when (visibility) {
                com.doan.studyhub.core.data.model.PostTypeUtils.VISIBILITY_PRIVATE -> {
                    binding.imgPostVisibility.setImageResource(R.drawable.ic_lock)
                    binding.imgPostVisibility.contentDescription = "Chỉ mình tôi"
                }
                com.doan.studyhub.core.data.model.PostTypeUtils.VISIBILITY_FRIENDS -> {
                    binding.imgPostVisibility.setImageResource(R.drawable.ic_group)
                    binding.imgPostVisibility.contentDescription = "Bạn bè"
                }
                else -> {
                    binding.imgPostVisibility.setImageResource(R.drawable.ic_public)
                    binding.imgPostVisibility.contentDescription = "Công khai"
                }
            }

            // Type badge
            when (category) {
                com.doan.studyhub.core.data.model.PostTypeUtils.CATEGORY_QUESTION -> {
                    binding.tvPostTypeBadge.isVisible = true
                    binding.tvPostTypeBadge.text = "Hỏi đáp"
                    binding.tvPostTypeBadge.setBackgroundResource(R.drawable.bg_urgent_pill)
                }
                com.doan.studyhub.core.data.model.PostTypeUtils.CATEGORY_DOCUMENT -> {
                    binding.tvPostTypeBadge.isVisible = true
                    binding.tvPostTypeBadge.text = "Tài liệu"
                    binding.tvPostTypeBadge.setBackgroundResource(R.drawable.bg_success_box)
                }
                com.doan.studyhub.core.data.model.PostTypeUtils.CATEGORY_KNOWLEDGE -> {
                    binding.tvPostTypeBadge.isVisible = true
                    binding.tvPostTypeBadge.text = "Kiến thức"
                    binding.tvPostTypeBadge.setBackgroundResource(R.drawable.bg_streak_pill)
                }
                else -> {
                    binding.tvPostTypeBadge.isVisible = false
                }
            }

            // Subject tag (Do not show if not selected or if default 'Chung')
            val isDefaultSubject = subject == null || subject.name.contains("Chung", ignoreCase = true)
            if (!isDefaultSubject) {
                binding.tvPostSubjectTag.isVisible = true
                binding.tvPostSubjectTag.text = "📚 ${subject!!.name}"
                binding.tvPostSubjectTag.setOnClickListener {
                    onSubjectClick(subject.id)
                }
            } else {
                binding.tvPostSubjectTag.isVisible = false
            }

            // Title & Content
            binding.tvPostTitle.text = post.title
            binding.tvPostContent.text = post.content ?: ""
            binding.tvPostContent.isVisible = !post.content.isNullOrBlank()

            // Image Preview (if post contains an image file)
            val imageFile = item.files.firstOrNull { file ->
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
                binding.cardPostImagePreview.isVisible = true
                Glide.with(fragment)
                    .load(imageFile.fileUrl)
                    .into(binding.imgPostImagePreview)
            } else {
                binding.cardPostImagePreview.isVisible = false
            }

            // Attachments indicator
            if (item.files.isNotEmpty()) {
                binding.layoutPostFileBadge.isVisible = true
                binding.tvPostFileCount.text = "${item.files.size} file / tài liệu đính kèm"
            } else {
                binding.layoutPostFileBadge.isVisible = false
            }

            // Avatar
            val avatarUrl = author?.avatarUrl ?: if (!currentUserId.isNullOrBlank() && post.authorId == currentUserId) currentUserProfile?.avatarUrl else null
            AvatarImageLoader.show(
                fragment = fragment,
                view = binding.imgPostAuthorAvatar,
                selection = AvatarSelection(AvatarMode.REMOTE),
                remoteUrl = avatarUrl
            )

            val authorClickListener = View.OnClickListener {
                val authorId = post.authorId.trim()
                if (authorId.isNotBlank()) {
                    onAuthorClick?.invoke(authorId)
                }
            }
            binding.tvPostAuthorName.setOnClickListener(authorClickListener)
            binding.imgPostAuthorAvatar.setOnClickListener(authorClickListener)

            // Owner options (Edit / Delete) - Only visible if active logged in user is author
            val activeUserId = currentUserId?.takeIf { it.isNotBlank() }
                ?: AuthRepository().getCurrentUserIdSync()
            val isOwner = !activeUserId.isNullOrBlank()
                && !post.authorId.isNullOrBlank()
                && post.authorId.trim().equals(activeUserId.trim(), ignoreCase = true)
            binding.btnPostMore.isVisible = isOwner
            binding.btnPostMore.setOnClickListener { view ->
                showPopupMenu(view, item)
            }

            // --- FRONTEND LIKE & COMMENT INTERACTION (Kế bên nút, mặc định 1) ---
            val postId = post.id.orEmpty()
            val isLiked = likedPostIds.contains(postId)

            // Mặc định số like và bình luận là 1 (khi like thì chuyển thành 2)
            binding.tvPostLikeCount.text = if (isLiked) "2" else "1"
            binding.tvPostCommentCount.text = "1"

            updateLikeUi(isLiked)

            binding.btnPostLike.setOnClickListener {
                val currentlyLiked = likedPostIds.contains(postId)
                val newLiked = !currentlyLiked
                if (newLiked) {
                    likedPostIds.add(postId)
                    binding.imgPostLikeIcon.animate().scaleX(1.3f).scaleY(1.3f).setDuration(120).withEndAction {
                        binding.imgPostLikeIcon.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                    }.start()
                } else {
                    likedPostIds.remove(postId)
                }
                binding.tvPostLikeCount.text = if (newLiked) "2" else "1"
                updateLikeUi(newLiked)
            }

            binding.btnPostComment.setOnClickListener {
                onPostClick(item)
            }

            binding.btnPostShare.setOnClickListener {
                Toast.makeText(fragment.requireContext(), "Đã sao chép liên kết bài viết", Toast.LENGTH_SHORT).show()
            }

            itemView.setOnClickListener {
                onPostClick(item)
            }
        }

        private fun updateLikeUi(isLiked: Boolean) {
            val context = fragment.requireContext()
            if (isLiked) {
                binding.imgPostLikeIcon.setImageResource(R.drawable.ic_thumb_up)
                binding.imgPostLikeIcon.setColorFilter(ContextCompat.getColor(context, R.color.primary))
                binding.tvPostLikeLabel.setTextColor(ContextCompat.getColor(context, R.color.primary))
                binding.tvPostLikeCount.setTextColor(ContextCompat.getColor(context, R.color.primary))
                binding.tvPostLikeLabel.setTypeface(null, Typeface.BOLD)
                binding.tvPostLikeCount.setTypeface(null, Typeface.BOLD)
            } else {
                binding.imgPostLikeIcon.setImageResource(R.drawable.ic_thumb_up_outline)
                binding.imgPostLikeIcon.setColorFilter(ContextCompat.getColor(context, R.color.text_secondary))
                binding.tvPostLikeLabel.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                binding.tvPostLikeCount.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                binding.tvPostLikeLabel.setTypeface(null, Typeface.NORMAL)
                binding.tvPostLikeCount.setTypeface(null, Typeface.NORMAL)
            }
        }

        private fun showPopupMenu(view: View, item: PostDetail) {
            val popup = PopupMenu(view.context, view)
            popup.menu.add(0, 1, 0, "Chỉnh sửa bài viết")
            popup.menu.add(0, 2, 1, "Xóa bài viết")
            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    1 -> {
                        onEditClick(item)
                        true
                    }
                    2 -> {
                        onDeleteClick(item)
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }
    }
}
