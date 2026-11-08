package com.doan.studyhub.user.ui.block

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.doan.studyhub.core.data.model.BlockedUser
import com.doan.studyhub.user.databinding.ItemBlockedUserBinding
import com.doan.studyhub.user.ui.profile.AvatarImageLoader

data class BlockedUserRow(
    val item: BlockedUser,
    val busy: Boolean = false,
    val error: String? = null
)

class BlockedUsersAdapter(
    private val fragment: androidx.fragment.app.Fragment,
    private val onOpen: (BlockedUser) -> Unit,
    private val onUnblock: (BlockedUser) -> Unit
) : ListAdapter<BlockedUserRow, BlockedUsersAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(
            ItemBlockedUserBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(
        private val binding: ItemBlockedUserBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: BlockedUserRow) {
            val peer = row.item.peer

            binding.tvBlockedName.text = peer.displayName
            binding.tvBlockedUsername.text = "@${peer.username}"
            binding.tvBlockedRowError.isVisible = row.error != null
            binding.tvBlockedRowError.text = row.error

            AvatarImageLoader.showCommitted(
                fragment,
                binding.imgBlockedAvatar,
                peer.avatarUrl
            )

            binding.layoutBlockedPeer.setOnClickListener {
                onOpen(row.item)
            }

            binding.btnUnblock.isVisible = true
            binding.btnUnblock.isEnabled = !row.busy
            binding.btnUnblock.setOnClickListener {
                onUnblock(row.item)
            }

            binding.progressUnblock.isVisible = row.busy
        }
    }

    private object Diff : DiffUtil.ItemCallback<BlockedUserRow>() {
        override fun areItemsTheSame(
            oldItem: BlockedUserRow,
            newItem: BlockedUserRow
        ) = oldItem.item.id == newItem.item.id

        override fun areContentsTheSame(
            oldItem: BlockedUserRow,
            newItem: BlockedUserRow
        ) = oldItem == newItem
    }
}

class ItemBlockedUserBinding(binding: Any) {

}
