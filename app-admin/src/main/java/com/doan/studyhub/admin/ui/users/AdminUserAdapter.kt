package com.doan.studyhub.admin.ui.users

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.doan.studyhub.admin.databinding.ItemAdminUserBinding
import com.doan.studyhub.core.data.model.AdminUser

class AdminUserAdapter :
    RecyclerView.Adapter<AdminUserAdapter.UserViewHolder>() {

    private val users = mutableListOf<AdminUser>()

    fun submitList(newUsers: List<AdminUser>) {
        users.clear()
        users.addAll(newUsers)
        notifyDataSetChanged()
    }

    inner class UserViewHolder(
        private val binding: ItemAdminUserBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(user: AdminUser) {
            binding.tvUserDisplayName.text =
                user.displayName.ifBlank { "Người dùng chưa đặt tên" }

            binding.tvUserUsername.text =
                "@${user.username}"

            binding.tvUserEmail.text =
                user.email ?: "Không có email"

            binding.tvUserRole.text =
                if (user.role.equals("admin", ignoreCase = true)) {
                    "ADMIN"
                } else {
                    "USER"
                }

            binding.tvUserStatus.text =
                if (user.isBanned) "Đã bị khóa" else "Hoạt động"
        }
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): UserViewHolder {
        val binding = ItemAdminUserBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return UserViewHolder(binding)
    }

    override fun onBindViewHolder(
        holder: UserViewHolder,
        position: Int
    ) {
        holder.bind(users[position])
    }

    override fun getItemCount(): Int = users.size
}
