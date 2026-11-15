
package com.doan.studyhub.user.ui.post

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.doan.studyhub.core.data.model.CommentWithAuthor
import com.doan.studyhub.user.R
import com.doan.studyhub.user.databinding.ItemCommentBinding
import com.doan.studyhub.user.ui.profile.AvatarImageLoader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class CommentAdapter(
    private val fragment: Fragment,
    private val onReplyClick: (CommentWithAuthor) -> Unit = {},
    private val onEditClick: (CommentWithAuthor) -> Unit = {},
    private val onDeleteClick: (CommentWithAuthor) -> Unit = {},
    private val onMarkBestAnswerClick: (CommentWithAuthor) -> Unit = {},
    private val onReactionClick: (CommentWithAuthor, String) -> Unit = { _, _ -> },
    private val onReactionSummaryClick: (CommentWithAuthor) -> Unit = {}
) : RecyclerView.Adapter<CommentAdapter.CommentViewHolder>() {

    private val items = mutableListOf<CommentWithAuthor>()

    private var currentUserId: String? = null
    private var postOwnerId: String? = null

    private data class ReactionOption(
        val type: String,
        val emoji: String,
        val label: String
    )

    private val reactions = listOf(
        ReactionOption("like", "👍", "Thích"),
        ReactionOption("love", "❤️", "Yêu thích"),
        ReactionOption("haha", "😂", "Haha"),
        ReactionOption("sad", "😢", "Buồn"),
        ReactionOption("angry", "😡", "Phẫn nộ")
    )

    inner class CommentViewHolder(
        val binding: ItemCommentBinding
    ) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): CommentViewHolder {
        val binding = ItemCommentBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return CommentViewHolder(binding)
    }

    override fun onBindViewHolder(
        holder: CommentViewHolder,
        position: Int
    ) {
        val item = items[position]
        val comment = item.comment
        val author = item.author
        val binding = holder.binding

        binding.tvCommentAuthor.text = when {
            !author?.displayName.isNullOrBlank() -> author?.displayName
            !author?.username.isNullOrBlank() -> "@${author?.username}"
            else -> "Người dùng"
        }

        binding.tvCommentContent.text = comment.content
        binding.tvCommentTime.text = formatTime(comment.createdAt)

        AvatarImageLoader.showCommitted(
            fragment = fragment,
            view = binding.imgCommentAvatar,
            remoteUrl = author?.avatarUrl
        )

        binding.tvBestAnswer.visibility =
            if (comment.isBestAnswer) View.VISIBLE else View.GONE

        when {
            comment.isBestAnswer -> {
                binding.root.setBackgroundResource(
                    R.drawable.bg_best_answer_comment
                )
            }

            comment.parentId != null -> {
                binding.root.setBackgroundResource(
                    R.drawable.bg_comment_reply
                )
            }

            else -> {
                binding.root.background = null
            }
        }

        val density = binding.root.resources.displayMetrics.density
        val indent = if (comment.parentId != null) {
            (48 * density).toInt()
        } else {
            0
        }

        binding.root.setPaddingRelative(
            indent,
            (10 * density).toInt(),
            binding.root.paddingEnd,
            (10 * density).toInt()
        )

        val isCommentOwner =
            !currentUserId.isNullOrBlank() &&
                    currentUserId == comment.userId

        val isPostOwner =
            !postOwnerId.isNullOrBlank() &&
                    currentUserId == postOwnerId

        binding.btnEditComment.visibility =
            if (isCommentOwner) View.VISIBLE else View.GONE

        binding.btnDeleteComment.visibility =
            if (isCommentOwner || isPostOwner) View.VISIBLE else View.GONE

        binding.btnMarkBestAnswer.visibility =
            if (isPostOwner) View.VISIBLE else View.GONE

        binding.btnMarkBestAnswer.text =
            if (comment.isBestAnswer) "Bỏ chọn hay nhất" else "Chọn hay nhất"

        binding.btnReplyComment.setOnClickListener {
            onReplyClick(item)
        }

        binding.btnEditComment.setOnClickListener {
            onEditClick(item)
        }

        binding.btnDeleteComment.setOnClickListener {
            onDeleteClick(item)
        }

        binding.btnMarkBestAnswer.setOnClickListener {
            onMarkBestAnswerClick(item)
        }

        bindReaction(binding, item)
    }

    private fun bindReaction(
        binding: ItemCommentBinding,
        item: CommentWithAuthor
    ) {
        val selectedOption = reactions.firstOrNull {
            it.type == item.currentUserReaction
        }

        binding.btnCommentReaction.text = selectedOption?.let {
            "${it.emoji} ${it.label}"
        } ?: "👍 Thích"

        binding.btnCommentReaction.setTextColor(
            if (selectedOption != null) {
                0xFF1877F2.toInt()
            } else {
                0xFF666666.toInt()
            }
        )

        binding.btnCommentReaction.setOnClickListener {
            onReactionClick(item, "like")
        }

        binding.btnCommentReaction.setOnLongClickListener {
            showReactionPicker(item)
            true
        }

        val countText = reactions.mapNotNull { option ->
            val count = item.reactionCounts[option.type] ?: 0
            if (count > 0) "${option.emoji} $count" else null
        }.joinToString("  ")

        binding.tvCommentReactionSummary.text =
            if (item.reactionCount > 0) {
                "$countText   ·   ${item.reactionCount}"
            } else {
                ""
            }

        binding.tvCommentReactionSummary.visibility =
            if (item.reactionCount > 0) View.VISIBLE else View.GONE

        binding.tvCommentReactionSummary.setOnClickListener {
            onReactionSummaryClick(item)
        }
    }

    private fun showReactionPicker(item: CommentWithAuthor) {
        val labels = reactions.map {
            "${it.emoji}  ${it.label}"
        }.toTypedArray()

        AlertDialog.Builder(fragment.requireContext())
            .setTitle("Chọn cảm xúc")
            .setItems(labels) { _, which ->
                onReactionClick(item, reactions[which].type)
            }
            .show()
    }

    override fun getItemCount(): Int = items.size

    fun setPermissions(
        userId: String?,
        ownerId: String?
    ) {
        currentUserId = userId
        postOwnerId = ownerId
        notifyDataSetChanged()
    }

    fun submitList(newItems: List<CommentWithAuthor>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    private fun formatTime(value: String?): String {
        if (value.isNullOrBlank()) return ""

        return try {
            val inputFormat = SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss",
                Locale.getDefault()
            ).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }

            val outputFormat = SimpleDateFormat(
                "dd/MM/yyyy HH:mm",
                Locale.getDefault()
            ).apply {
                timeZone = TimeZone.getDefault()
            }

            val normalized = value.substringBefore(".")
                .removeSuffix("Z")

            val date = inputFormat.parse(normalized)
            date?.let { outputFormat.format(it) } ?: value
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            value
        }
    }
}
