
package com.doan.studyhub.admin.ui.users

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.doan.studyhub.admin.R
import com.doan.studyhub.admin.databinding.ItemAdminUserBinding
import com.doan.studyhub.core.data.model.AdminUser

class AdminUserAdapter(
    private val onUserClick: (AdminUser) -> Unit,
    private val onBanClick: (AdminUser) -> Unit,
    private val onUnbanClick: (AdminUser) -> Unit
) : RecyclerView.Adapter<AdminUserAdapter.UserViewHolder>() {

    private val users = mutableListOf<AdminUser>()
    private var loadingUserId: String? = null

    fun submitList(newUsers: List<AdminUser>) {
        users.clear()
        users.addAll(newUsers)
        notifyDataSetChanged()
    }

    fun setLoadingUser(userId: String?) {
        loadingUserId = userId
        notifyDataSetChanged()
    }

    inner class UserViewHolder(
        private val binding: ItemAdminUserBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(user: AdminUser) = with(binding) {

            // Display name
            tvUserDisplayName.text =
                user.displayName.ifBlank { "Người dùng chưa đặt tên" }

            // Username
            tvUserUsername.text = "@${user.username}"

            // Email
            tvUserEmail.text = user.email ?: "Không có email"

            // Role
            val isAdmin = user.role.equals("admin", ignoreCase = true)
            tvUserRole.text = if (isAdmin) "ADMIN" else "USER"

            // Status
            tvUserStatus.text =
                if (user.isBanned) "Đã bị khóa" else "Hoạt động"

            // Avatar
            Glide.with(ivUserAvatar)
                .load(user.avatarUrl.takeIf { !it.isNullOrBlank() })
                .placeholder(R.drawable.ic_group)
                .error(R.drawable.ic_group)
                .fallback(R.drawable.ic_group)
                .circleCrop()
                .into(ivUserAvatar)

            // Chỉ User thường mới được hiện nút Ban / Unban
            val isActionLoading = loadingUserId == user.id

            if (isAdmin) {
                btnBanUser.visibility = View.GONE
                btnUnbanUser.visibility = View.GONE
            } else {
                btnBanUser.visibility =
                    if (!user.isBanned) View.VISIBLE else View.GONE

                btnUnbanUser.visibility =
                    if (user.isBanned) View.VISIBLE else View.GONE

                btnBanUser.isEnabled = !isActionLoading
                btnUnbanUser.isEnabled = !isActionLoading

                btnBanUser.alpha = if (isActionLoading) 0.5f else 1f
                btnUnbanUser.alpha = if (isActionLoading) 0.5f else 1f
            }

// Click thông tin User để xem các bài viết
            root.setOnClickListener {
                onUserClick(user)
            }

            layoutUserInfo.setOnClickListener {
                onUserClick(user)
            }

            ivUserAvatar.setOnClickListener {
                onUserClick(user)
            }

// Ban
            btnBanUser.setOnClickListener {
                if (!isAdmin && !user.isBanned && !isActionLoading) {
                    onBanClick(user)
                }
            }

// Unban
            btnUnbanUser.setOnClickListener {
                if (!isAdmin && user.isBanned && !isActionLoading) {
                    onUnbanClick(user)
                }
            }

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
