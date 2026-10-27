package com.doan.studyhub.user.ui.notification

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.doan.studyhub.core.data.model.AppNotification
import com.doan.studyhub.user.R
import com.doan.studyhub.user.databinding.ItemNotificationBinding
import com.doan.studyhub.user.ui.profile.AvatarImageLoader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class NotificationAdapter(
    private val fragment: Fragment,
    private val onClick: (AppNotification) -> Unit
) : ListAdapter<AppNotification, NotificationAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemNotificationBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(private val binding: ItemNotificationBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: AppNotification) {
            val context = binding.root.context
            val name = item.actor?.displayName ?: context.getString(R.string.notification_someone)
            binding.tvNotificationText.text = context.getString(message(item.type), name)
            binding.tvNotificationTime.text = relativeTime(item.createdAt)
            binding.viewUnreadDot.isVisible = !item.isRead
            binding.root.setCardBackgroundColor(
                ContextCompat.getColor(context, if (item.isRead) R.color.surface_card else R.color.primary_container)
            )
            AvatarImageLoader.showCommitted(fragment, binding.imgNotificationAvatar, item.actor?.avatarUrl)
            binding.root.setOnClickListener { onClick(item) }
        }
    }

    private fun message(type: String) = when (type) {
        "friend_request" -> R.string.notification_friend_request
        "friend_accept" -> R.string.notification_friend_accept
        "like" -> R.string.notification_like
        "comment" -> R.string.notification_comment
        "best_answer" -> R.string.notification_best_answer
        "group_added" -> R.string.notification_group_added
        "new_post" -> R.string.notification_new_post
        else -> R.string.notification_generic
    }

    private fun relativeTime(createdAt: String): String {
        // Supabase serializes timestamptz in UTC; minSdk 24 has no java.time without desugaring.
        val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val millis = runCatching { parser.parse(createdAt.take(19))?.time }.getOrNull() ?: return ""
        return DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    }

    private object Diff : DiffUtil.ItemCallback<AppNotification>() {
        override fun areItemsTheSame(old: AppNotification, new: AppNotification) = old.id == new.id
        override fun areContentsTheSame(old: AppNotification, new: AppNotification) = old == new
    }
}
