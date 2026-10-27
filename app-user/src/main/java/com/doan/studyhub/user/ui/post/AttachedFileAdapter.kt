package com.doan.studyhub.user.ui.post

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.doan.studyhub.user.R
import com.doan.studyhub.user.databinding.ItemAttachedFileBinding
import java.util.Locale

data class SelectedFile(
    val uri: Uri? = null,
    val name: String,
    val size: Long,
    val type: String? = null,
    val remoteUrl: String? = null,
    val remoteFileId: String? = null
)

class AttachedFileAdapter(
    private val onRemoveClick: (SelectedFile) -> Unit
) : RecyclerView.Adapter<AttachedFileAdapter.ViewHolder>() {

    private val items = mutableListOf<SelectedFile>()

    fun submitList(newList: List<SelectedFile>) {
        items.clear()
        items.addAll(newList)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAttachedFileBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemAttachedFileBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(file: SelectedFile) {
            binding.tvAttachedFileName.text = file.name
            binding.tvAttachedFileSize.text = formatSize(file.size)

            val iconRes = when {
                file.type?.startsWith("image/") == true -> R.drawable.ic_image
                else -> R.drawable.ic_assignment
            }
            binding.imgAttachedFileType.setImageResource(iconRes)

            binding.btnRemoveAttachedFile.setOnClickListener {
                onRemoveClick(file)
            }
        }
    }

    companion object {
        fun formatSize(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val kb = bytes / 1024.0
            val mb = kb / 1024.0
            return if (mb >= 1.0) {
                String.format(Locale.US, "%.1f MB", mb)
            } else {
                String.format(Locale.US, "%.0f KB", kb)
            }
        }
    }
}
