
package com.doan.studyhub.admin.ui.subjects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.doan.studyhub.core.data.model.Subject
import com.doan.studyhub.core.data.repository.AdminSubjectRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AdminSubjectUiState(
    val subjects: List<Subject> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

class AdminSubjectViewModel(
    private val repository: AdminSubjectRepository = AdminSubjectRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow(AdminSubjectUiState())
    val uiState: StateFlow<AdminSubjectUiState> = _uiState.asStateFlow()

    init {
        loadSubjects()
    }

    // Tải danh sách môn học
    fun loadSubjects() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(isLoading = true, errorMessage = null)
            }

            try {
                val subjects = repository.getAllSubjects()

                _uiState.update {
                    it.copy(
                        subjects = subjects,
                        isLoading = false,
                        errorMessage = null
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = e.message ?: "Không thể tải danh sách môn học."
                    )
                }
            }
        }
    }

    // Tìm kiếm môn học theo tên
    fun updateSearchQuery(query: String) {
        _uiState.update {
            it.copy(searchQuery = query)
        }
    }

    // Thêm môn học
    fun createSubject(name: String, description: String?) {
        viewModelScope.launch {
            try {
                repository.createSubject(
                    name = name.trim(),
                    description = description?.trim()?.ifEmpty { null }
                )
                loadSubjects()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        errorMessage = e.message ?: "Không thể thêm môn học."
                    )
                }
            }
        }
    }

    // Sửa môn học
    fun updateSubject(
        subjectId: String,
        name: String,
        description: String?
    ) {
        viewModelScope.launch {
            try {
                repository.updateSubject(
                    subjectId = subjectId,
                    name = name.trim(),
                    description = description?.trim()?.ifEmpty { null }
                )
                loadSubjects()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        errorMessage = e.message ?: "Không thể cập nhật môn học."
                    )
                }
            }
        }
    }

    // Bật / tắt môn học
    fun updateSubjectStatus(subjectId: String, isActive: Boolean) {
        viewModelScope.launch {
            try {
                repository.updateSubjectStatus(subjectId, isActive)
                loadSubjects()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        errorMessage = e.message ?: "Không thể cập nhật trạng thái môn học."
                    )
                }
            }
        }
    }

    // Xóa thông báo lỗi
    fun clearError() {
        _uiState.update {
            it.copy(errorMessage = null)
        }
    }
}
