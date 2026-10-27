package com.doan.studyhub.user.ui.post

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
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

    fun submitList(newList: List<PostDetail>) {
        posts.clear()
        posts.addAll(newList)
        notifyDataSetChanged()
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

            // Type badge
            when (post.type) {
                "question" -> {
                    binding.tvPostTypeBadge.isVisible = true
                    binding.tvPostTypeBadge.text = "Hỏi đáp"
                    binding.tvPostTypeBadge.setBackgroundResource(R.drawable.bg_urgent_pill)
                }
                "document" -> {
                    binding.tvPostTypeBadge.isVisible = true
                    binding.tvPostTypeBadge.text = "Tài liệu"
                    binding.tvPostTypeBadge.setBackgroundResource(R.drawable.bg_success_box)
                }
                "knowledge" -> {
                    binding.tvPostTypeBadge.isVisible = true
                    binding.tvPostTypeBadge.text = "Kiến thức"
                    binding.tvPostTypeBadge.setBackgroundResource(R.drawable.bg_streak_pill)
                }
                else -> {
                    // Do NOT display type tag if not explicitly chosen
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

            itemView.setOnClickListener {
                onPostClick(item)
            }
        }

        private fun showPopupMenu(view: View, item: PostDetail) {
            val popup = PopupMenu(view.context, view)
            popup.menu.add("Chỉnh sửa bài viết")
            popup.menu.add("Xóa bài viết")
            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.title) {
                    "Chỉnh sửa bài viết" -> {
                        onEditClick(item)
                        true
                    }
                    "Xóa bài viết" -> {
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
