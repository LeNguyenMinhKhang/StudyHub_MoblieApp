
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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import kotlinx.coroutines.launch
import com.doan.studyhub.core.data.local.AvatarMode
import com.doan.studyhub.core.data.local.AvatarSelection
import com.doan.studyhub.core.data.model.PostDetail
import com.doan.studyhub.core.data.model.Profile
import com.doan.studyhub.core.data.repository.AuthRepository
import com.doan.studyhub.user.R
import com.doan.studyhub.user.databinding.ItemPostBinding
import com.doan.studyhub.user.ui.profile.AvatarImageLoader
import androidx.appcompat.app.AlertDialog
import com.doan.studyhub.core.data.model.PostReactionSummary
import com.doan.studyhub.core.data.repository.PostRepository

class PostAdapter(
    private val fragment: Fragment,
    var currentUserId: String? = null,
    var currentUserProfile: Profile? = null,
    private val onPostClick: (PostDetail) -> Unit,
    private val onSubjectClick: (String) -> Unit,
    private val onEditClick: (PostDetail) -> Unit,
    private val onDeleteClick: (PostDetail) -> Unit,
    private val onAuthorClick: ((String) -> Unit)? = null,

    // Callback xử lý Like từ Fragment
    private val onLikeClick: ((
        PostDetail,
        (Result<Boolean>) -> Unit
    ) -> Unit)? = null,

    // Callback xử lý Bookmark từ Fragment
    private val onBookmarkClick: ((
        PostDetail,
        (Result<Boolean>) -> Unit
    ) -> Unit)? = null,

    // Callback khi click vào Hashtag chip
    private val onHashtagClick: ((String) -> Unit)? = null
) : RecyclerView.Adapter<PostAdapter.ViewHolder>() {

    private val posts = mutableListOf<PostDetail>()

    // Những bài viết đang gửi yêu cầu Like/Unlike
    private val pendingLikePostIds = mutableSetOf<String>()

    // Những bài viết đang gửi yêu cầu Bookmark
    private val pendingBookmarkPostIds = mutableSetOf<String>()

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
            pendingLikePostIds.remove(postId)
            notifyItemRemoved(index)
        }
    }

    fun updateCurrentUserId(
        newUserId: String?,
        profile: Profile? = null
    ) {
        currentUserId = newUserId

        if (profile != null) {
            currentUserProfile = profile
        }

        notifyDataSetChanged()
    }

    // Cập nhật trạng thái Like sau khi Supabase trả kết quả
    private fun updatePostReaction(
        postId: String,
        summary: PostReactionSummary
    ) {
        val index = posts.indexOfFirst {
            it.post.id == postId
        }

        if (index == -1) return

        val oldPost = posts[index]

        posts[index] = oldPost.copy(
            likeCount = summary.totalCount,
            isLikedByCurrentUser = summary.currentUserReaction == "like",
            reactionCounts = summary.countsByType,
            currentUserReaction = summary.currentUserReaction
        )

        notifyItemChanged(index)
    }

    // Cập nhật trạng thái Bookmark sau khi Supabase trả kết quả
    fun updatePostBookmark(
        postId: String,
        isSaved: Boolean
    ) {
        val index = posts.indexOfFirst {
            it.post.id == postId
        }

        if (index == -1) return

        val oldPost = posts[index]
        posts[index] = oldPost.copy(
            isSavedByCurrentUser = isSaved
        )

        notifyItemChanged(index)
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): ViewHolder {
        val binding = ItemPostBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )

        return ViewHolder(binding)
    }

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int
    ) {
        holder.bind(posts[position])
    }

    override fun getItemCount(): Int = posts.size

    inner class ViewHolder(
        private val binding: ItemPostBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: PostDetail) {
            val post = item.post

            val author = item.author
                ?: if (
                    !currentUserId.isNullOrBlank() &&
                    post.authorId == currentUserId
                ) {
                    currentUserProfile
                } else {
                    null
                }

            val subject = item.subject

            val authorName = when {
                !author?.displayName.isNullOrBlank() ->
                    author.displayName

                !author?.username.isNullOrBlank() ->
                    "@${author.username}"

                !currentUserId.isNullOrBlank() &&
                        post.authorId == currentUserId -> {
                    currentUserProfile?.displayName
                        ?.takeIf { it.isNotBlank() }
                        ?: currentUserProfile?.username
                            ?.takeIf { it.isNotBlank() }
                        ?: "Người dùng"
                }

                else -> author?.displayName
                    ?.takeIf { it.isNotBlank() }
                    ?: author?.username
                        ?.takeIf { it.isNotBlank() }
                        ?.let { "@$it" }
                    ?: "Người dùng"
            }

            binding.tvPostAuthorName.text = authorName
            binding.tvPostTime.text = post.createdAt?.take(10) ?: ""

            // Visibility Icon
            val (category, visibility) =
                com.doan.studyhub.core.data.model.PostTypeUtils.parse(post.type)

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
                    binding.tvPostTypeBadge.setBackgroundResource(
                        R.drawable.bg_urgent_pill
                    )
                }

                com.doan.studyhub.core.data.model.PostTypeUtils.CATEGORY_DOCUMENT -> {
                    binding.tvPostTypeBadge.isVisible = true
                    binding.tvPostTypeBadge.text = "Tài liệu"
                    binding.tvPostTypeBadge.setBackgroundResource(
                        R.drawable.bg_success_box
                    )
                }

                com.doan.studyhub.core.data.model.PostTypeUtils.CATEGORY_KNOWLEDGE -> {
                    binding.tvPostTypeBadge.isVisible = true
                    binding.tvPostTypeBadge.text = "Kiến thức"
                    binding.tvPostTypeBadge.setBackgroundResource(
                        R.drawable.bg_streak_pill
                    )
                }

                else -> {
                    binding.tvPostTypeBadge.isVisible = false
                }
            }

            // Subject tag
            val isDefaultSubject =
                subject == null ||
                        subject.name.contains("Chung", ignoreCase = true)

            if (!isDefaultSubject) {
                binding.tvPostSubjectTag.isVisible = true
                binding.tvPostSubjectTag.text = "📚 ${subject!!.name}"

                binding.tvPostSubjectTag.setOnClickListener {
                    onSubjectClick(subject.id)
                }
            } else {
                binding.tvPostSubjectTag.isVisible = false
                binding.tvPostSubjectTag.setOnClickListener(null)
            }

            // Title & Content
            binding.tvPostTitle.text = post.title
            binding.tvPostContent.text = post.content ?: ""
            binding.tvPostContent.isVisible = !post.content.isNullOrBlank()

            // Hashtags
            if (item.hashtags.isNotEmpty()) {
                binding.chipGroupPostHashtags.isVisible = true
                binding.chipGroupPostHashtags.removeAllViews()
                val context = binding.root.context
                item.hashtags.forEach { tag ->
                    val chip = com.google.android.material.chip.Chip(context).apply {
                        text = "#$tag"
                        isClickable = true
                        isCheckable = false
                        textSize = 12f
                        setChipBackgroundColorResource(R.color.surface_variant)
                        setTextColor(ContextCompat.getColor(context, R.color.primary))
                        setOnClickListener {
                            onHashtagClick?.invoke(tag)
                        }
                    }
                    binding.chipGroupPostHashtags.addView(chip)
                }
            } else {
                binding.chipGroupPostHashtags.isVisible = false
                binding.chipGroupPostHashtags.removeAllViews()
            }

            // Image Preview
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
                binding.tvPostFileCount.text =
                    "${item.files.size} file / tài liệu đính kèm"
            } else {
                binding.layoutPostFileBadge.isVisible = false
            }

            // Avatar
            val avatarUrl = author?.avatarUrl
                ?: if (
                    !currentUserId.isNullOrBlank() &&
                    post.authorId == currentUserId
                ) {
                    currentUserProfile?.avatarUrl
                } else {
                    null
                }

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

            // Owner options
            val activeUserId = currentUserId
                ?.takeIf { it.isNotBlank() }
                ?: AuthRepository().getCurrentUserIdSync()

            val isOwner = !activeUserId.isNullOrBlank() &&
                    !post.authorId.isNullOrBlank() &&
                    post.authorId.trim().equals(
                        activeUserId.trim(),
                        ignoreCase = true
                    )

            binding.btnPostMore.isVisible = isOwner

            binding.btnPostMore.setOnClickListener { view ->
                showPopupMenu(view, item)
            }

            // Like: sử dụng dữ liệu thật từ PostDetail
            // Reactions: 5 cảm xúc
            val postId = post.id.orEmpty()

            binding.tvPostLikeCount.text = item.likeCount.toString()
            binding.tvPostCommentCount.text = item.commentCount.toString()

            updateReactionUi(item.currentUserReaction)

            binding.btnPostLike.isEnabled =
                postId.isNotBlank() && !pendingLikePostIds.contains(postId)

            binding.btnPostLike.setOnClickListener {
                if (postId.isNotBlank()) {
                    performPostReaction(postId, "like")
                }
            }

            binding.btnPostLike.setOnLongClickListener {
                if (postId.isNotBlank()) {
                    showReactionPicker(it, postId)
                }
                true
            }

// Hiển thị thống kê các cảm xúc
            val reactionEmojis = listOf(
                "like" to "👍",
                "love" to "❤️",
                "haha" to "😂",
                "sad" to "😢",
                "angry" to "😡"
            )

            val summaryText = reactionEmojis.mapNotNull { (type, emoji) ->
                val count = item.reactionCounts[type] ?: 0
                if (count > 0) "$emoji $count" else null
            }.joinToString("   ")

            binding.tvPostReactionSummary.isVisible =
                item.likeCount > 0 && summaryText.isNotBlank()

            binding.tvPostReactionSummary.text = summaryText

            binding.tvPostReactionSummary.setOnClickListener {
                if (postId.isNotBlank() && item.likeCount > 0) {
                    showReactionFilter(it, postId)
                }
            }

            binding.tvPostReactionSummary.setOnLongClickListener {
                if (postId.isNotBlank() && item.likeCount > 0) {
                    showReactionFilter(it, postId)
                }
                true
            }

            // Comment
            binding.btnPostComment.setOnClickListener {
                onPostClick(item)
            }

            // Bookmark
            val isSaved = item.isSavedByCurrentUser
            updateBookmarkUi(isSaved)

            binding.btnPostBookmark.setOnClickListener {
                if (postId.isBlank()) return@setOnClickListener
                if (pendingBookmarkPostIds.contains(postId)) return@setOnClickListener

                val bookmarkCallback = onBookmarkClick
                if (bookmarkCallback == null) {
                    pendingBookmarkPostIds.add(postId)
                    val targetSaved = !item.isSavedByCurrentUser
                    updateBookmarkUi(targetSaved)

                    fragment.lifecycleScope.launch {
                        try {
                            val result = com.doan.studyhub.core.data.repository.PostRepository().toggleSavePost(postId)
                            updatePostBookmark(postId, result)
                        } catch (e: Exception) {
                            updateBookmarkUi(item.isSavedByCurrentUser)
                            Toast.makeText(
                                fragment.requireContext(),
                                e.message ?: "Không thể lưu bài viết.",
                                Toast.LENGTH_SHORT
                            ).show()
                        } finally {
                            pendingBookmarkPostIds.remove(postId)
                        }
                    }
                    return@setOnClickListener
                }

                pendingBookmarkPostIds.add(postId)
                val targetSaved = !item.isSavedByCurrentUser
                updateBookmarkUi(targetSaved)

                bookmarkCallback(item) { result ->
                    pendingBookmarkPostIds.remove(postId)
                    result.onSuccess { newSaved ->
                        updatePostBookmark(postId, newSaved)
                    }.onFailure { error ->
                        updateBookmarkUi(item.isSavedByCurrentUser)
                        Toast.makeText(
                            fragment.requireContext(),
                            error.message ?: "Không thể thực hiện lưu bài viết.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }

            itemView.setOnClickListener {
                onPostClick(item)
            }
        }

        private fun performPostReaction(
            postId: String,
            reactionType: String
        ) {
            if (pendingLikePostIds.contains(postId)) return

            val context = fragment.context ?: return

            pendingLikePostIds.add(postId)
            binding.btnPostLike.isEnabled = false

            fragment.lifecycleScope.launch {
                try {
                    val repository = PostRepository()

                    repository.setPostReaction(
                        postId = postId,
                        reactionType = reactionType
                    )

                    val summary = repository.getPostReactionSummaries(
                        listOf(postId)
                    )[postId] ?: PostReactionSummary(
                        postId = postId,
                        totalCount = 0,
                        countsByType = mapOf(
                            "like" to 0,
                            "love" to 0,
                            "haha" to 0,
                            "sad" to 0,
                            "angry" to 0
                        ),
                        currentUserReaction = null
                    )

                    updatePostReaction(postId, summary)

                } catch (e: Exception) {
                    if (fragment.isAdded) {
                        Toast.makeText(
                            fragment.requireContext(),
                            e.message ?: "Không thể cập nhật cảm xúc. Vui lòng thử lại.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } finally {
                    pendingLikePostIds.remove(postId)

                    val index = posts.indexOfFirst {
                        it.post.id == postId
                    }

                    if (index != -1) {
                        notifyItemChanged(index)
                    }
                }
            }
        }

        private fun showReactionPicker(
            anchor: View,
            postId: String
        ) {
            val popup = PopupMenu(anchor.context, anchor)

            // Share
            binding.btnPostShare.setOnClickListener {
                fragment.sharePost(item, authorName, currentUserId)
            popup.menu.add(0, 1, 0, "👍 Thích")
            popup.menu.add(0, 2, 1, "❤️ Yêu thích")
            popup.menu.add(0, 3, 2, "😂 Haha")
            popup.menu.add(0, 4, 3, "😢 Buồn")
            popup.menu.add(0, 5, 4, "😡 Phẫn nộ")

            popup.setOnMenuItemClickListener { menuItem ->
                val reactionType = when (menuItem.itemId) {
                    1 -> "like"
                    2 -> "love"
                    3 -> "haha"
                    4 -> "sad"
                    5 -> "angry"
                    else -> return@setOnMenuItemClickListener false
                }

                performPostReaction(postId, reactionType)
                true
            }

            popup.show()
        }


        private fun updateReactionUi(reactionType: String?) {
            val context = binding.root.context

            val (emoji, label) = when (reactionType) {
                "love" -> "❤️" to "Yêu thích"
                "haha" -> "😂" to "Haha"
                "sad" -> "😢" to "Buồn"
                "angry" -> "😡" to "Phẫn nộ"
                else -> "👍" to "Thích"
            }

            val isReacted = reactionType != null

            // Hiển thị emoji tương ứng với cảm xúc đã chọn.
            binding.imgPostLikeIcon.visibility = View.GONE
            binding.tvPostLikeLabel.text = "$emoji $label"

            val color = ContextCompat.getColor(
                context,
                if (isReacted) R.color.primary else R.color.text_secondary
            )

            binding.tvPostLikeLabel.setTextColor(color)
            binding.tvPostLikeCount.setTextColor(color)

            binding.tvPostLikeLabel.setTypeface(
                null,
                if (isReacted) Typeface.BOLD else Typeface.NORMAL
            )

            binding.tvPostLikeCount.setTypeface(
                null,
                if (isReacted) Typeface.BOLD else Typeface.NORMAL
            )
        }


        private fun showReactionFilter(
            anchor: View,
            postId: String
        ) {
            val popup = PopupMenu(anchor.context, anchor)

            popup.menu.add(0, 0, 0, "Tất cả cảm xúc")
            popup.menu.add(0, 1, 1, "👍 Thích")
            popup.menu.add(0, 2, 2, "❤️ Yêu thích")
            popup.menu.add(0, 3, 3, "😂 Haha")
            popup.menu.add(0, 4, 4, "😢 Buồn")
            popup.menu.add(0, 5, 5, "😡 Phẫn nộ")

            popup.setOnMenuItemClickListener { menuItem ->
                val reactionType = when (menuItem.itemId) {
                    0 -> null
                    1 -> "like"
                    2 -> "love"
                    3 -> "haha"
                    4 -> "sad"
                    5 -> "angry"
                    else -> return@setOnMenuItemClickListener false
                }

                loadPostReactors(postId, reactionType)
                true
            }

            popup.show()
        }

        private fun loadPostReactors(
            postId: String,
            reactionType: String?
        ) {
            fragment.lifecycleScope.launch {
                try {
                    val reactors = PostRepository().getPostReactors(
                        postId = postId,
                        reactionType = reactionType,
                        limit = 100,
                        offset = 0
                    )

                    val context = fragment.context ?: return@launch

                    val title = when (reactionType) {
                        "like" -> "Người đã Thích"
                        "love" -> "Người đã Yêu thích"
                        "haha" -> "Người đã Haha"
                        "sad" -> "Người đã Buồn"
                        "angry" -> "Người đã Phẫn nộ"
                        else -> "Tất cả cảm xúc"
                    }

                    if (reactors.isEmpty()) {
                        Toast.makeText(
                            context,
                            "Chưa có người dùng nào.",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@launch
                    }

                    val rows = reactors.map { reactor ->
                        val emoji = when (reactor.reactionType) {
                            "like" -> "👍"
                            "love" -> "❤️"
                            "haha" -> "😂"
                            "sad" -> "😢"
                            "angry" -> "😡"
                            else -> "👍"
                        }

                        val name = reactor.displayName
                            .ifBlank { reactor.username }

                        "$emoji  $name  (@${reactor.username})"
                    }.toTypedArray()

                    AlertDialog.Builder(context)
                        .setTitle(title)
                        .setItems(rows, null)
                        .setPositiveButton("Đóng", null)
                        .show()

                } catch (e: Exception) {
                    val context = fragment.context ?: return@launch

                    Toast.makeText(
                        context,
                        e.message ?: "Không thể tải danh sách cảm xúc.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        private fun updateBookmarkUi(isSaved: Boolean) {
            val context = fragment.requireContext()
            if (isSaved) {
                binding.imgPostBookmarkIcon.setImageResource(R.drawable.ic_bookmark_filled)
                binding.imgPostBookmarkIcon.setColorFilter(
                    ContextCompat.getColor(context, R.color.primary)
                )
                binding.tvPostBookmarkLabel.setTextColor(
                    ContextCompat.getColor(context, R.color.primary)
                )
                binding.tvPostBookmarkLabel.text = "Đã lưu"
            } else {
                binding.imgPostBookmarkIcon.setImageResource(R.drawable.ic_bookmark_outline)
                binding.imgPostBookmarkIcon.setColorFilter(
                    ContextCompat.getColor(context, R.color.text_secondary)
                )
                binding.tvPostBookmarkLabel.setTextColor(
                    ContextCompat.getColor(context, R.color.text_secondary)
                )
                binding.tvPostBookmarkLabel.text = "Lưu"
            }
        }

        private fun updateLikeUi(isLiked: Boolean) {
            val context = fragment.requireContext()

            if (isLiked) {
                binding.imgPostLikeIcon.setImageResource(
                    R.drawable.ic_thumb_up
                )

                binding.imgPostLikeIcon.setColorFilter(
                    ContextCompat.getColor(
                        context,
                        R.color.primary
                    )
                )

                binding.tvPostLikeLabel.setTextColor(
                    ContextCompat.getColor(
                        context,
                        R.color.primary
                    )
                )

                binding.tvPostLikeCount.setTextColor(
                    ContextCompat.getColor(
                        context,
                        R.color.primary
                    )
                )

                binding.tvPostLikeLabel.setTypeface(
                    null,
                    Typeface.BOLD
                )

                binding.tvPostLikeCount.setTypeface(
                    null,
                    Typeface.BOLD
                )
            } else {
                binding.imgPostLikeIcon.setImageResource(
                    R.drawable.ic_thumb_up_outline
                )

                binding.imgPostLikeIcon.setColorFilter(
                    ContextCompat.getColor(
                        context,
                        R.color.text_secondary
                    )
                )

                binding.tvPostLikeLabel.setTextColor(
                    ContextCompat.getColor(
                        context,
                        R.color.text_secondary
                    )
                )

                binding.tvPostLikeCount.setTextColor(
                    ContextCompat.getColor(
                        context,
                        R.color.text_secondary
                    )
                )

                binding.tvPostLikeLabel.setTypeface(
                    null,
                    Typeface.NORMAL
                )

                binding.tvPostLikeCount.setTypeface(
                    null,
                    Typeface.NORMAL
                )
            }
        }

        private fun showPopupMenu(
            view: View,
            item: PostDetail
        ) {
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
