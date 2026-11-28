
package com.doan.studyhub.admin.ui.users

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.doan.studyhub.core.data.model.AdminUser
import com.doan.studyhub.core.data.repository.AdminUserRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class AdminUserViewModel(
    private val repository: AdminUserRepository = AdminUserRepository()
) : ViewModel() {

    val users = MutableStateFlow<List<AdminUser>>(emptyList())
    val isLoading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    // Role hiện tại của tab: user hoặc admin
    val selectedRole = MutableStateFlow("user")

    // Trạng thái thao tác Ban/Unban
    val actionLoadingUserId = MutableStateFlow<String?>(null)
    val actionSuccess = MutableStateFlow<String?>(null)
    val actionError = MutableStateFlow<String?>(null)

    private var currentSearch: String? = null
    private var currentOffset = 0
    private val pageSize = 50
    private var hasMore = true

    private var requestJob: Job? = null
    private var requestVersion = 0

    fun loadUsers(refresh: Boolean = false) {
        if (!refresh && (isLoading.value || !hasMore)) return

        if (refresh) {
            requestJob?.cancel()
            requestVersion++
            currentOffset = 0
            hasMore = true
        }

        val version = requestVersion
        val requestOffset = if (refresh) 0 else currentOffset
        val requestRole = selectedRole.value

        requestJob = viewModelScope.launch {
            isLoading.value = true
            error.value = null

            try {
                val result = repository.listUsers(
                    search = currentSearch,
                    role = requestRole,
                    limit = pageSize,
                    offset = requestOffset
                )

                if (version != requestVersion) return@launch

                if (refresh) {
                    users.value = result
                } else {
                    users.value = users.value + result
                }

                currentOffset = requestOffset + result.size
                hasMore = result.size >= pageSize

            } catch (e: Exception) {
                if (version == requestVersion) {
                    error.value = e.message
                        ?: "Không thể tải danh sách người dùng."
                }
            } finally {
                if (version == requestVersion) {
                    isLoading.value = false
                }
            }
        }
    }

    fun selectRole(role: String) {
        val normalizedRole = role.lowercase()

        if (normalizedRole !in listOf("user", "admin")) return
        if (selectedRole.value == normalizedRole) return

        selectedRole.value = normalizedRole
        refreshUsers()
    }

    fun searchUsers(query: String) {
        currentSearch = query.trim().takeIf { it.isNotEmpty() }
        refreshUsers()
    }

    fun refreshUsers() {
        loadUsers(refresh = true)
    }

    fun loadMore() {
        loadUsers(refresh = false)
    }

    // Ban user thường
    fun banUser(userId: String) {
        executeUserAction(userId) {
            repository.banUser(userId)

            users.value = users.value.map { user ->
                if (user.id == userId) user.copy(isBanned = true)
                else user
            }

            "Đã ban người dùng thành công."
        }
    }

    // Unban user thường
    fun unbanUser(userId: String) {
        executeUserAction(userId) {
            repository.unbanUser(userId)

            users.value = users.value.map { user ->
                if (user.id == userId) user.copy(isBanned = false)
                else user
            }

            "Đã gỡ ban người dùng thành công."
        }
    }

    private fun executeUserAction(
        userId: String,
        action: suspend () -> String
    ) {
        if (actionLoadingUserId.value != null) return

        // Chỉ cho phép thao tác trên role User
        val targetUser = users.value.firstOrNull { it.id == userId }
            ?: return

        if (!targetUser.role.equals("user", ignoreCase = true)) return

        actionLoadingUserId.value = userId
        actionSuccess.value = null
        actionError.value = null

        viewModelScope.launch {
            try {
                actionSuccess.value = action()

            } catch (e: Exception) {
                actionError.value = e.message
                    ?: "Thao tác người dùng thất bại."
            } finally {
                actionLoadingUserId.value = null
            }
        }
    }

    fun clearActionMessages() {
        actionSuccess.value = null
        actionError.value = null
    }
}
