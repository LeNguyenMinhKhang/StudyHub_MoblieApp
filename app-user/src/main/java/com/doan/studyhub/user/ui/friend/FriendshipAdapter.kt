package com.doan.studyhub.user.ui.friend

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.doan.studyhub.core.data.model.FriendshipAction
import com.doan.studyhub.core.data.model.FriendshipListItem
import com.doan.studyhub.user.R
import com.doan.studyhub.user.databinding.ItemFriendshipBinding
import com.doan.studyhub.user.ui.profile.AvatarImageLoader

data class FriendRow(val item: FriendshipListItem, val busy: Boolean, val locked: Boolean, val error: String?) {
    val actions: List<FriendshipAction> get() = when {
        item.status == "accepted" -> listOf(FriendshipAction.REMOVE)
        item.direction == "incoming" -> listOf(FriendshipAction.ACCEPT, FriendshipAction.DECLINE)
        else -> listOf(FriendshipAction.CANCEL)
    }
}

class FriendshipAdapter(
    private val fragment: Fragment,
    private val onOpen: (FriendshipListItem) -> Unit,
    private val onAction: (FriendshipListItem, FriendshipAction) -> Unit
) : ListAdapter<FriendRow, FriendshipAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemFriendshipBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(private val binding: ItemFriendshipBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: FriendRow) {
            val peer = row.item.peer
            binding.tvFriendName.text = peer.displayName
            binding.tvFriendUsername.text = "@${peer.username}"
            AvatarImageLoader.showCommitted(fragment, binding.imgFriendAvatar, peer.avatarUrl)
            binding.layoutFriendPeer.setOnClickListener { onOpen(row.item) }
            binding.progressFriendRow.isVisible = row.busy
            binding.tvFriendRowError.isVisible = row.error != null
            binding.tvFriendRowError.text = row.error
            // Primary is the trailing button; incoming rows show Decline before Accept.
            val buttons = listOf(binding.btnFriendPrimary, binding.btnFriendSecondary)
            buttons.forEachIndexed { index, button ->
                val action = row.actions.getOrNull(index)
                button.isVisible = action != null
                button.isEnabled = action != null && !row.busy && !row.locked
                if (action != null) {
                    button.setText(label(action))
                    button.setOnClickListener { onAction(row.item, action) }
                }
            }
        }
    }

    private fun label(action: FriendshipAction) = when (action) {
        FriendshipAction.ACCEPT -> R.string.friends_accept
        FriendshipAction.DECLINE -> R.string.friends_decline
        FriendshipAction.CANCEL -> R.string.friendship_cancel
        FriendshipAction.REMOVE -> R.string.friendship_remove
        FriendshipAction.SEND -> R.string.friendship_send
    }

    private object Diff : DiffUtil.ItemCallback<FriendRow>() {
        override fun areItemsTheSame(old: FriendRow, new: FriendRow) = old.item.id == new.item.id
        override fun areContentsTheSame(old: FriendRow, new: FriendRow) = old == new
    }
}
