package com.doan.studyhub.user.ui.post

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.doan.studyhub.core.data.model.PostFile
import com.doan.studyhub.user.R
import com.doan.studyhub.user.databinding.ItemDetailFileBinding

class DetailFileAdapter : RecyclerView.Adapter<DetailFileAdapter.ViewHolder>() {

    private val files = mutableListOf<PostFile>()

    fun submitList(newList: List<PostFile>) {
        files.clear()
        files.addAll(newList)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDetailFileBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(files[position])
    }

    override fun getItemCount(): Int = files.size

    inner class ViewHolder(private val binding: ItemDetailFileBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(file: PostFile) {
            binding.tvDetailFileName.text = file.fileName
            binding.tvDetailFileSize.text = AttachedFileAdapter.formatSize(file.fileSize ?: 0)

            val iconRes = when {
                file.fileType?.startsWith("image/") == true -> R.drawable.ic_image
                else -> R.drawable.ic_assignment
            }
            binding.imgDetailFileType.setImageResource(iconRes)

            binding.btnOpenDownloadFile.setOnClickListener {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(file.fileUrl))
                    itemView.context.startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(
                        itemView.context,
                        "Không thể mở file: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
}
