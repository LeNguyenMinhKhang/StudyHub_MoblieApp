
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

    private var currentSearch: String? = null
    private var currentOffset = 0
    private val pageSize = 50
    private var hasMore = true

    private var requestJob: Job? = null
    private var requestVersion = 0

    fun loadUsers(refresh: Boolean = false) {
        // Không cho tải thêm khi đang tải hoặc đã hết dữ liệu
        if (!refresh && (isLoading.value || !hasMore)) return

        if (refresh) {
            requestJob?.cancel()
            requestVersion++
            currentOffset = 0
            hasMore = true
        }

        val version = requestVersion
        val requestOffset = if (refresh) 0 else currentOffset

        requestJob = viewModelScope.launch {
            isLoading.value = true
            error.value = null

            try {
                val result = repository.listUsers(
                    search = currentSearch,
                    limit = pageSize,
                    offset = requestOffset
                )

                // Chỉ nhận kết quả của request mới nhất
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
                    error.value =
                        e.message ?: "Không thể tải danh sách người dùng."
                }
            } finally {
                if (version == requestVersion) {
                    isLoading.value = false
                }
            }
        }
    }

    fun searchUsers(query: String) {
        currentSearch = query.trim().takeIf { it.isNotEmpty() }
        loadUsers(refresh = true)
    }

    fun refreshUsers() {
        loadUsers(refresh = true)
    }

    fun loadMore() {
        loadUsers(refresh = false)
    }
}
