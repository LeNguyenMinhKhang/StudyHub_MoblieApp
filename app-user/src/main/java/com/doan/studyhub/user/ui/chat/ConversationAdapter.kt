
package com.doan.studyhub.user.ui.chat

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.doan.studyhub.core.data.model.ConversationSummary
import com.doan.studyhub.user.R
import com.doan.studyhub.user.databinding.ItemConversationBinding
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class ConversationAdapter(
    private val onItemClick: (ConversationSummary) -> Unit
) : ListAdapter<ConversationSummary, ConversationAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemConversationBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemConversationBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ConversationSummary) {
            // Tên cuộc hội thoại: cá nhân hoặc nhóm
            binding.tvDisplayName.text = item.title

            // Nội dung tin nhắn cuối
            binding.tvLastMessage.text = when (item.lastMessageType) {
                "image" -> "[Hình ảnh]"
                "file" -> "[Tập tin đính kèm]"
                else -> item.lastMessageContent ?: "Chưa có tin nhắn nào"
            }

            // Thời gian tin nhắn cuối
            binding.tvTime.text = formatRelativeTime(item.lastMessageAt)

            // Số tin nhắn chưa đọc
            binding.tvUnreadBadge.isVisible = item.unreadCount > 0

            if (item.unreadCount > 0) {
                binding.tvUnreadBadge.text =
                    if (item.unreadCount > 99) "99+"
                    else item.unreadCount.toString()
            }

            // Avatar cá nhân hoặc nhóm
            Glide.with(binding.root)
                .load(item.avatarUrl)
                .circleCrop()
                .placeholder(R.drawable.ic_person)
                .error(R.drawable.ic_person)
                .into(binding.ivAvatar)

            binding.root.setOnClickListener {
                onItemClick(item)
            }
        }

        private fun formatRelativeTime(createdAt: String?): String {
            if (createdAt.isNullOrBlank()) return ""

            return try {
                val parser = SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss",
                    Locale.US
                ).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }

                val millis = parser.parse(createdAt.take(19))?.time
                    ?: return ""

                DateUtils.getRelativeTimeSpanString(
                    millis,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE
                ).toString()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ""
            }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<ConversationSummary>() {

        override fun areItemsTheSame(
            oldItem: ConversationSummary,
            newItem: ConversationSummary
        ): Boolean {
            return oldItem.conversationId == newItem.conversationId
        }

        override fun areContentsTheSame(
            oldItem: ConversationSummary,
            newItem: ConversationSummary
        ): Boolean {
            return oldItem == newItem
        }
    }
}
