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
import com.doan.studyhub.user.databinding.ItemMessageReceivedBinding
import com.doan.studyhub.user.databinding.ItemMessageSentBinding
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class MessageAdapter(
    private val currentUserId: String,
    private val onImageClick: (String) -> Unit = {}
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
            binding.tvMessage.isVisible = !isImage
            binding.ivMessageImage.isVisible = isImage

            if (isImage) {
                Glide.with(binding.root)
                    .load(item.fileUrl)
                    .transform(RoundedCorners(24))
                    .into(binding.ivMessageImage)

                binding.ivMessageImage.setOnClickListener {
                    item.fileUrl?.let(onImageClick)
                }
            } else {
                binding.tvMessage.text = item.content ?: ""
            }

            binding.tvTime.text = formatTime(item.createdAt)
        }
    }

    inner class ReceivedViewHolder(private val binding: ItemMessageReceivedBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ChatMessage) {
            val isImage = item.type == "image" && !item.fileUrl.isNullOrBlank()
            binding.tvMessage.isVisible = !isImage
            binding.ivMessageImage.isVisible = isImage

            if (isImage) {
                Glide.with(binding.root)
                    .load(item.fileUrl)
                    .transform(RoundedCorners(24))
                    .into(binding.ivMessageImage)

                binding.ivMessageImage.setOnClickListener {
                    item.fileUrl?.let(onImageClick)
                }
            } else {
                binding.tvMessage.text = item.content ?: ""
            }

            binding.tvTime.text = formatTime(item.createdAt)
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

