package com.doan.studyhub.user.ui.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.doan.studyhub.core.data.model.ChatMessage
import com.doan.studyhub.core.data.model.MessageSender
import com.doan.studyhub.user.R
import com.doan.studyhub.user.databinding.ItemChatTimeSeparatorBinding
import com.doan.studyhub.user.databinding.ItemMessageReceivedBinding
import com.doan.studyhub.user.databinding.ItemMessageSentBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class DocumentInfo(
    val name: String,
    val sizeText: String,
    val extension: String
)

/**
 * Messenger-style list: bubbles grouped in runs plus time separators ([buildChatItems]).
 * Sender names/avatars come from [updateSenders]; [fallbackSender] covers a direct chat peer.
 */
class MessageAdapter(
    private val onImageClick: (String) -> Unit = {},
    private val onDocumentClick: (url: String, fileName: String) -> Unit = { _, _ -> },
    private val onRetryClick: (ChatMessage) -> Unit = {}
) : ListAdapter<ChatItem, RecyclerView.ViewHolder>(DiffCallback) {

    private var senders: Map<String, MessageSender> = emptyMap()
    private var fallbackSender: MessageSender? = null

    fun updateSenders(senders: Map<String, MessageSender>, fallback: MessageSender?) {
        if (this.senders == senders && this.fallbackSender == fallback) return
        this.senders = senders
        this.fallbackSender = fallback
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount)
    }

    override fun getItemViewType(position: Int): Int = when (val item = getItem(position)) {
        is ChatItem.TimeSeparator -> VIEW_TIME
        is ChatItem.Bubble -> if (item.isMine) VIEW_SENT else VIEW_RECEIVED
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_SENT -> SentViewHolder(ItemMessageSentBinding.inflate(inflater, parent, false))
            VIEW_RECEIVED -> ReceivedViewHolder(ItemMessageReceivedBinding.inflate(inflater, parent, false))
            else -> SeparatorViewHolder(ItemChatTimeSeparatorBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ChatItem.TimeSeparator -> (holder as SeparatorViewHolder).bind(item)
            is ChatItem.Bubble -> if (holder is SentViewHolder) holder.bind(item) else (holder as ReceivedViewHolder).bind(item)
        }
    }

    inner class SeparatorViewHolder(private val binding: ItemChatTimeSeparatorBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ChatItem.TimeSeparator) {
            binding.tvSeparator.text = formatSeparator(item.epochMillis)
        }
    }

    inner class SentViewHolder(private val binding: ItemMessageSentBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ChatItem.Bubble) {
            val message = item.message
            bindContent(
                message, binding.tvMessage, binding.ivMessageImage, binding.layoutDocument,
                binding.tvDocName, binding.tvDocSize
            )
            binding.bubbleContainer.background = BubbleShapes.background(binding.root.context, true, item.position)
            binding.root.updatePadding(top = topPadding(binding.root, item.position))
            binding.tvTime.isVisible = item.showTime
            binding.tvTime.text = formatTime(message.createdAt)

            val status = binding.ivStatus
            status.visibility = if (item.showStatus) View.VISIBLE else View.INVISIBLE
            status.setOnClickListener(null)
            status.isClickable = false
            when (message.status) {
                "sending" -> {
                    status.setImageResource(R.drawable.ic_message_sending)
                    status.contentDescription = status.context.getString(R.string.chat_status_sending)
                }
                "failed" -> {
                    status.setImageResource(R.drawable.ic_message_failed)
                    status.contentDescription = status.context.getString(R.string.chat_status_failed)
                    status.setOnClickListener { onRetryClick(message) }
                }
                "read", "seen" -> {
                    status.setImageResource(R.drawable.ic_message_read_circle)
                    status.contentDescription = status.context.getString(R.string.chat_status_read)
                }
                else -> {
                    status.setImageResource(R.drawable.ic_message_sent_circle)
                    status.contentDescription = status.context.getString(R.string.chat_status_sent)
                }
            }
        }
    }

    inner class ReceivedViewHolder(private val binding: ItemMessageReceivedBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ChatItem.Bubble) {
            val message = item.message
            val sender = senders[message.senderId] ?: fallbackSender
            val context = binding.root.context
            val name = sender?.displayName?.takeIf { it.isNotBlank() } ?: context.getString(R.string.chat_sender_fallback)

            bindContent(
                message, binding.tvMessage, binding.ivMessageImage, binding.layoutDocument,
                binding.tvDocName, binding.tvDocSize
            )
            binding.bubbleContainer.background = BubbleShapes.background(context, false, item.position)
            binding.root.updatePadding(top = topPadding(binding.root, item.position))
            binding.tvTime.isVisible = item.showTime
            binding.tvTime.text = formatTime(message.createdAt)

            binding.tvSenderName.isVisible = item.showSenderName
            binding.tvSenderName.text = name

            binding.ivAvatar.visibility = if (item.showAvatar) View.VISIBLE else View.INVISIBLE
            if (item.showAvatar) {
                binding.ivAvatar.contentDescription = context.getString(R.string.chat_avatar_of, name)
                val initials = InitialsAvatarDrawable(name)
                if (sender?.avatarUrl.isNullOrBlank()) {
                    Glide.with(binding.ivAvatar).clear(binding.ivAvatar)
                    binding.ivAvatar.setImageDrawable(initials)
                } else {
                    Glide.with(binding.ivAvatar).load(sender?.avatarUrl).circleCrop()
                        .placeholder(initials).error(initials).into(binding.ivAvatar)
                }
            } else {
                binding.ivAvatar.contentDescription = null
            }
        }
    }

    private fun topPadding(root: View, position: RunPosition): Int {
        val dp = root.resources.displayMetrics.density
        return ((if (position == RunPosition.FIRST || position == RunPosition.SINGLE) 6 else 1) * dp).toInt()
    }

    private fun bindContent(
        message: ChatMessage,
        tvMessage: TextView,
        ivImage: ImageView,
        layoutDocument: View,
        tvDocName: TextView,
        tvDocSize: TextView
    ) {
        val isImage = message.type == "image" && !message.fileUrl.isNullOrBlank()
        val isDocument = message.type == "document" && !message.fileUrl.isNullOrBlank()
        val isText = !isImage && !isDocument

        tvMessage.isVisible = isText
        ivImage.isVisible = isImage
        layoutDocument.isVisible = isDocument

        when {
            isText -> tvMessage.text = message.content ?: ""
            isImage -> {
                Glide.with(ivImage).load(message.fileUrl).transform(RoundedCorners(24)).into(ivImage)
                ivImage.setOnClickListener { message.fileUrl?.let(onImageClick) }
            }
            else -> {
                val doc = parseDocumentInfo(message.content)
                tvDocName.text = doc.name
                tvDocSize.text = doc.sizeText
                layoutDocument.setOnClickListener { message.fileUrl?.let { url -> onDocumentClick(url, doc.name) } }
            }
        }
    }

    private companion object {
        const val VIEW_SENT = 1
        const val VIEW_RECEIVED = 2
        const val VIEW_TIME = 3
    }

    private object DiffCallback : DiffUtil.ItemCallback<ChatItem>() {
        override fun areItemsTheSame(oldItem: ChatItem, newItem: ChatItem): Boolean = oldItem.key == newItem.key
        override fun areContentsTheSame(oldItem: ChatItem, newItem: ChatItem): Boolean = oldItem == newItem
    }
}

fun parseDocumentInfo(content: String?): DocumentInfo {
    if (content.isNullOrBlank()) {
        return DocumentInfo("Tài liệu đính kèm", "Tệp tài liệu", "FILE")
    }
    return if (content.contains("|")) {
        val parts = content.split("|", limit = 2)
        val name = parts.getOrNull(0)?.trim() ?: "Tài liệu"
        val size = parts.getOrNull(1)?.trim() ?: ""
        val ext = name.substringAfterLast(".", "").uppercase(Locale.ROOT)
        DocumentInfo(name, if (size.isNotBlank()) "$size • $ext" else ext, ext)
    } else {
        val ext = content.substringAfterLast(".", "").uppercase(Locale.ROOT)
        DocumentInfo(content, if (ext.isNotBlank()) ext else "FILE", ext)
    }
}

private fun formatTime(createdAt: String?): String {
    val millis = parseChatTime(createdAt) ?: return ""
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
}

/** Today shows only the time; other days also show the date. */
private fun formatSeparator(epochMillis: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = epochMillis }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    val pattern = if (sameDay) "HH:mm" else "HH:mm dd/MM/yyyy"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(epochMillis))
}
