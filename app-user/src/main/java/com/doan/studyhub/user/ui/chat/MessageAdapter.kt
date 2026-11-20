package com.doan.studyhub.user.ui.chat

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.doan.studyhub.core.data.model.ChatMessage
import com.doan.studyhub.user.R
import com.doan.studyhub.user.databinding.ItemMessageReceivedBinding
import com.doan.studyhub.user.databinding.ItemMessageSentBinding
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class DocumentInfo(
    val name: String,
    val sizeText: String,
    val extension: String
)

class MessageAdapter(
    private val currentUserId: String,
    private val onImageClick: (String) -> Unit = {},
    private val onDocumentClick: (url: String, fileName: String) -> Unit = { _, _ -> },
    private val onRetryClick: (ChatMessage) -> Unit = {}
) : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(DiffCallback) {

    companion object {
        private const val VIEW_TYPE_SENT = 1
        private const val VIEW_TYPE_RECEIVED = 2
    }

    override fun getItemViewType(position: Int): Int {
        val item = getItem(position)
        return if (item.senderId == currentUserId) VIEW_TYPE_SENT else VIEW_TYPE_RECEIVED
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_SENT) {
            val binding = ItemMessageSentBinding.inflate(inflater, parent, false)
            SentViewHolder(binding)
        } else {
            val binding = ItemMessageReceivedBinding.inflate(inflater, parent, false)
            ReceivedViewHolder(binding)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        if (holder is SentViewHolder) {
            holder.bind(item)
        } else if (holder is ReceivedViewHolder) {
            holder.bind(item)
        }
    }

    inner class SentViewHolder(private val binding: ItemMessageSentBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ChatMessage) {
            val isImage = item.type == "image" && !item.fileUrl.isNullOrBlank()
            val isDocument = item.type == "document" && !item.fileUrl.isNullOrBlank()
            val isText = !isImage && !isDocument

            binding.tvMessage.isVisible = isText
            binding.ivMessageImage.isVisible = isImage
            binding.layoutDocument.isVisible = isDocument

            if (isText) {
                binding.tvMessage.text = item.content ?: ""
            } else if (isImage) {
                Glide.with(binding.root)
                    .load(item.fileUrl)
                    .transform(RoundedCorners(24))
                    .into(binding.ivMessageImage)

                binding.ivMessageImage.setOnClickListener {
                    item.fileUrl?.let(onImageClick)
                }
            } else if (isDocument) {
                val doc = parseDocumentInfo(item.content)
                binding.tvDocName.text = doc.name
                binding.tvDocSize.text = doc.sizeText

                binding.layoutDocument.setOnClickListener {
                    item.fileUrl?.let { url -> onDocumentClick(url, doc.name) }
                }
            }

            binding.tvTime.text = formatTime(item.createdAt)

            // Status icon: sending, sent, read, failed
            when (item.status) {
                "sending" -> {
                    binding.ivStatus.setImageResource(R.drawable.ic_message_sending)
                    binding.ivStatus.setOnClickListener(null)
                }
                "failed" -> {
                    binding.ivStatus.setImageResource(R.drawable.ic_message_failed)
                    binding.ivStatus.setOnClickListener { onRetryClick(item) }
                }
                "read", "seen" -> {
                    binding.ivStatus.setImageResource(R.drawable.ic_message_read_circle)
                    binding.ivStatus.setOnClickListener(null)
                }
                else -> {
                    // "sent"
                    binding.ivStatus.setImageResource(R.drawable.ic_message_sent_circle)
                    binding.ivStatus.setOnClickListener(null)
                }
            }
        }
    }

    inner class ReceivedViewHolder(private val binding: ItemMessageReceivedBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ChatMessage) {
            val isImage = item.type == "image" && !item.fileUrl.isNullOrBlank()
            val isDocument = item.type == "document" && !item.fileUrl.isNullOrBlank()
            val isText = !isImage && !isDocument

            binding.tvMessage.isVisible = isText
            binding.ivMessageImage.isVisible = isImage
            binding.layoutDocument.isVisible = isDocument

            if (isText) {
                binding.tvMessage.text = item.content ?: ""
            } else if (isImage) {
                Glide.with(binding.root)
                    .load(item.fileUrl)
                    .transform(RoundedCorners(24))
                    .into(binding.ivMessageImage)

                binding.ivMessageImage.setOnClickListener {
                    item.fileUrl?.let(onImageClick)
                }
            } else if (isDocument) {
                val doc = parseDocumentInfo(item.content)
                binding.tvDocName.text = doc.name
                binding.tvDocSize.text = doc.sizeText

                binding.layoutDocument.setOnClickListener {
                    item.fileUrl?.let { url -> onDocumentClick(url, doc.name) }
                }
            }

            binding.tvTime.text = formatTime(item.createdAt)
        }
    }

    private fun parseDocumentInfo(content: String?): DocumentInfo {
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
        if (createdAt.isNullOrBlank()) return ""
        return try {
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val date = parser.parse(createdAt.take(19)) ?: return ""
            val outputFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            outputFormat.format(date)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ""
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return (oldItem.id != null && oldItem.id == newItem.id) ||
                    (oldItem.createdAt != null && oldItem.createdAt == newItem.createdAt && oldItem.senderId == newItem.senderId)
        }

        override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem == newItem
        }
    }
}
