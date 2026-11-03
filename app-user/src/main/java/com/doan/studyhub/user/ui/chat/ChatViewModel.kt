package com.doan.studyhub.user.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.doan.studyhub.common.ApiResponse
import com.doan.studyhub.core.data.model.ConversationSummary
import com.doan.studyhub.core.data.repository.ChatRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatUiState(
    val isLoading: Boolean = false,
    val conversations: List<ConversationSummary> = emptyList(),
    val errorMessage: String? = null
)

class ChatViewModel(
    private val repository: ChatRepository = ChatRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        loadConversations()
    }

    fun loadConversations(isPullToRefresh: Boolean = false) {
        viewModelScope.launch {
            if (!isPullToRefresh) {
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            }
            when (val response = repository.getConversations()) {
                is ApiResponse.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            conversations = response.data,
                            errorMessage = null
                        )
                    }
                }
                is ApiResponse.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = response.error.message
                        )
                    }
                }
            }
        }
    }

    fun clearErrorMessage() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}

