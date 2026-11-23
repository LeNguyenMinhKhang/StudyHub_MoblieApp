package com.doan.studyhub.admin.ui.posts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.doan.studyhub.core.data.model.AdminPost
import com.doan.studyhub.core.data.repository.AdminPostRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

class AdminPostViewModel : ViewModel() {

    private val repository = AdminPostRepository()

    private val pageSize = 20

    private var currentSearch = ""
    private var currentOffset = 0
    private var hasMore = true

    // Bộ lọc bài viết theo tác giả
    private var currentAuthorId: String? = null

    private val _posts = MutableStateFlow<List<AdminPost>>(emptyList())
    val posts: StateFlow<List<AdminPost>> = _posts.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // Thiết lập bộ lọc tác giả
    fun setAuthorFilter(authorId: String?) {
        if (currentAuthorId == authorId) return

        currentAuthorId = authorId
        currentSearch = ""
        currentOffset = 0
        hasMore = true

        _posts.value = emptyList()
        _error.value = null
    }

    // Tải danh sách bài viết
    fun loadPosts(refresh: Boolean = false) {
        if (_isLoading.value) return
        if (!refresh && !hasMore) return

        if (refresh) {
            currentOffset = 0
            hasMore = true
        }

        _isLoading.value = true
        _error.value = null

        viewModelScope.launch {
            try {
                val result = repository.getPosts(
                    search = currentSearch.ifBlank { null },
                    limit = pageSize,
                    offset = currentOffset,
                    authorId = currentAuthorId
                )

                if (refresh) {
                    _posts.value = result
                } else {
                    _posts.value = _posts.value + result
                }

                currentOffset += result.size
                hasMore = result.size == pageSize

            } catch (e: Exception) {
                _error.value = e.message ?: "Không thể tải danh sách bài viết."
            } finally {
                _isLoading.value = false
            }
        }
    }

    // Tìm kiếm bài viết
    fun searchPosts(query: String) {
        currentSearch = query.trim()
        currentOffset = 0
        hasMore = true
        loadPosts(refresh = true)
    }

    // Tải thêm khi cuộn xuống cuối danh sách
    fun loadMore() {
        if (_isLoading.value || !hasMore) return
        loadPosts(refresh = false)
    }

    // Xóa bài viết
    fun deletePost(postId: String) {
        if (_isLoading.value) return

        _isLoading.value = true
        _error.value = null

        viewModelScope.launch {
            try {
                val deleted = repository.deletePost(postId)

                if (deleted) {
                    _posts.value = _posts.value.filter { it.id != postId }
                    currentOffset = _posts.value.size
                    hasMore = true
                } else {
                    _error.value =
                        "Không thể xóa bài viết hoặc bài viết không tồn tại."
                }

            } catch (e: Exception) {
                _error.value = e.message ?: "Đã xảy ra lỗi khi xóa bài viết."
            } finally {
                _isLoading.value = false
            }
        }
    }

    // Tải lại khi người dùng nhấn thử lại
    fun refreshPosts() {
        loadPosts(refresh = true)
    }

    // Lấy dữ liệu chi tiết bài viết từ Supabase
    suspend fun getPostDetail(postId: String): JsonObject? {
        return repository.getPostDetail(postId)
    }
}