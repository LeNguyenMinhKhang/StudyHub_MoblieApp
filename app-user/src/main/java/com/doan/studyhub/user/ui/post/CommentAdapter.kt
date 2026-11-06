
package com.doan.studyhub.user.ui.post

import com.doan.studyhub.user.R
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.doan.studyhub.core.data.model.CommentWithAuthor
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
    private val onMarkBestAnswerClick: (CommentWithAuthor) -> Unit = {}
) : RecyclerView.Adapter<CommentAdapter.CommentViewHolder>() {

    private val items = mutableListOf<CommentWithAuthor>()

    private var currentUserId: String? = null
    private var postOwnerId: String? = null

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
            !author?.displayName.isNullOrBlank() ->
                author?.displayName

            !author?.username.isNullOrBlank() ->
                "@${author?.username}"

            else -> "Người dùng"
        }

        binding.tvCommentContent.text = comment.content
        binding.tvCommentTime.text = formatTime(comment.createdAt)

        AvatarImageLoader.showCommitted(
            fragment = fragment,
            view = binding.imgCommentAvatar,
            remoteUrl = author?.avatarUrl
        )

        // Hiển thị nhãn câu trả lời hay nhất.
        binding.tvBestAnswer.visibility =
            if (comment.isBestAnswer) View.VISIBLE else View.GONE

        // Ưu tiên nền xanh cho câu trả lời hay nhất.
        // Câu trả lời thông thường sử dụng nền trung tính.
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



        // Thụt câu trả lời vào bằng khoảng cách từ avatar
        // đến chữ "Trả lời" của bình luận gốc.
        val indent = if (comment.parentId != null) {
            (48 * binding.root.resources.displayMetrics.density).toInt()
        } else {
            0
        }

        binding.root.setPaddingRelative(
            indent,
            (10 * binding.root.resources.displayMetrics.density).toInt(),
            binding.root.paddingEnd,
            (10 * binding.root.resources.displayMetrics.density).toInt()
        )

        val isCommentOwner =
            !currentUserId.isNullOrBlank() &&
                    currentUserId == comment.userId

        val isPostOwner =
            !postOwnerId.isNullOrBlank() &&
                    currentUserId == postOwnerId

        // Người viết bình luận được sửa và xóa.
        binding.btnEditComment.visibility =
            if (isCommentOwner) View.VISIBLE else View.GONE

        binding.btnDeleteComment.visibility =
            if (isCommentOwner || isPostOwner) View.VISIBLE else View.GONE

        // Chủ bài viết được chọn bình luận hoặc câu trả lời hay nhất.
        binding.btnMarkBestAnswer.visibility =
            if (isPostOwner) {
                View.VISIBLE
            } else {
                View.GONE
            }

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
