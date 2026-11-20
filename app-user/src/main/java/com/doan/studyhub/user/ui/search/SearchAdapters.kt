package com.doan.studyhub.user.ui.search

import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.doan.studyhub.core.data.model.DocumentSearchItem
import com.doan.studyhub.core.data.model.ProfileSearchItem
import com.doan.studyhub.user.R
import com.doan.studyhub.user.databinding.ItemSearchDocumentBinding
import com.doan.studyhub.user.databinding.ItemSearchUserBinding
import com.doan.studyhub.user.ui.profile.AvatarImageLoader

class SearchUserAdapter(
    private val fragment: Fragment,
    private val onOpen: (ProfileSearchItem) -> Unit
) : ListAdapter<ProfileSearchItem, SearchUserAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemSearchUserBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(private val binding: ItemSearchUserBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ProfileSearchItem) {
            binding.tvSearchUserName.text = item.displayName
            binding.tvSearchUserUsername.text = "@${item.username}"
            AvatarImageLoader.showCommitted(fragment, binding.imgSearchUserAvatar, item.avatarUrl)
            binding.layoutSearchUser.setOnClickListener { onOpen(item) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<ProfileSearchItem>() {
        override fun areItemsTheSame(old: ProfileSearchItem, new: ProfileSearchItem) = old.id == new.id
        override fun areContentsTheSame(old: ProfileSearchItem, new: ProfileSearchItem) = old == new
    }
}

class SearchDocumentAdapter(
    private val onOpen: (DocumentSearchItem) -> Unit
) : ListAdapter<DocumentSearchItem, SearchDocumentAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemSearchDocumentBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(private val binding: ItemSearchDocumentBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: DocumentSearchItem) {
            val context = binding.root.context
            binding.tvSearchDocumentName.text = item.fileName
            binding.tvSearchDocumentPost.text = context.getString(R.string.search_document_post, item.postTitle)
            val author = item.authorDisplayName.orEmpty()
            val size = item.fileSize?.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(context, it) }
            binding.tvSearchDocumentMeta.text = when {
                size != null && author.isNotBlank() -> context.getString(R.string.search_document_meta, author, size)
                size != null -> size
                else -> author
            }
            binding.imgSearchDocumentType.setImageResource(
                if (item.fileName.endsWith(".pdf", ignoreCase = true)) R.drawable.ic_assignment else R.drawable.ic_folder
            )
            binding.layoutSearchDocument.setOnClickListener { onOpen(item) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<DocumentSearchItem>() {
        override fun areItemsTheSame(old: DocumentSearchItem, new: DocumentSearchItem) = old.id == new.id
        override fun areContentsTheSame(old: DocumentSearchItem, new: DocumentSearchItem) = old == new
    }
}
