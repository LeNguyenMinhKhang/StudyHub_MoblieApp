package com.doan.studyhub.admin.ui.posts

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.doan.studyhub.admin.databinding.ItemAdminPostBinding
import com.doan.studyhub.core.data.model.AdminPost

class AdminPostAdapter(
    private val onDetailClick: (AdminPost) -> Unit,
    private val onDeleteClick: (AdminPost) -> Unit
) : RecyclerView.Adapter<AdminPostAdapter.PostViewHolder>() {

    private val posts = mutableListOf<AdminPost>()

    fun submitList(newPosts: List<AdminPost>) {
        posts.clear()
        posts.addAll(newPosts)
        notifyDataSetChanged()
    }

    inner class PostViewHolder(
        private val binding: ItemAdminPostBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(post: AdminPost) = with(binding) {
            tvPostAuthor.text =
                post.authorDisplayName?.takeIf { it.isNotBlank() }
                    ?: post.authorUsername
                            ?: "Người dùng"

            tvPostEmail.text = post.authorEmail ?: "Không có email"

            tvPostTitle.text =
                post.title.ifBlank { "(Không có tiêu đề)" }

            tvPostContent.text =
                post.content?.takeIf { it.isNotBlank() }
                    ?: "(Không có nội dung)"

            tvPostSubject.text =
                "Môn học: ${post.subjectName ?: "Không phân loại"}"

            tvPostDate.text =
                "Ngày đăng: ${post.createdAt.replace("T", " ").take(16)}"

            tvPostStats.text =
                "${post.commentCount} bình luận  •  " +
                        "${post.likeCount} lượt thích  •  " +
                        "${post.fileCount} tệp"

            btnPostDetail.setOnClickListener {
                onDetailClick(post)
            }

            btnDeletePost.setOnClickListener {
                onDeleteClick(post)
            }

            root.setOnClickListener {
                onDetailClick(post)
            }
        }
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): PostViewHolder {
        val binding = ItemAdminPostBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PostViewHolder(binding)
    }

    override fun onBindViewHolder(
        holder: PostViewHolder,
        position: Int
    ) {
        holder.bind(posts[position])
    }

    override fun getItemCount(): Int = posts.size
}