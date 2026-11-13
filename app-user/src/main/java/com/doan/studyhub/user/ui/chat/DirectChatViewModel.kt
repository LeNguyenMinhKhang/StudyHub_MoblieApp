package com.doan.studyhub.user.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.doan.studyhub.common.ApiResponse
import com.doan.studyhub.core.data.model.ChatMessage
import com.doan.studyhub.core.data.repository.ChatRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DirectChatUiState(
    val isLoading: Boolean = false,
    val isSending: Boolean = false,
    val conversationId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val errorMessage: String? = null,
    val currentUserId: String? = null
)

class DirectChatViewModel(
    private val repository: ChatRepository = ChatRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow(DirectChatUiState())
    val uiState: StateFlow<DirectChatUiState> = _uiState.asStateFlow()

    private var realtimeJob: Job? = null

    init {
        _uiState.update { it.copy(currentUserId = repository.getCurrentUserId()) }
    }

    fun initChat(conversationId: String?, targetUserId: String?) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            val resolvedConvId = if (!conversationId.isNullOrBlank()) {
                conversationId
            } else if (!targetUserId.isNullOrBlank()) {
                when (val res = repository.getOrCreateDirectChat(targetUserId)) {
                    is ApiResponse.Success -> res.data.conversationId
                    is ApiResponse.Failure -> {
                        _uiState.update { it.copy(isLoading = false, errorMessage = res.error.message) }
                        return@launch
                    }
                }
            } else {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Thiếu thông tin cuộc trò chuyện") }
                return@launch
            }

            _uiState.update { it.copy(conversationId = resolvedConvId) }
            loadMessages(resolvedConvId)
            startObservingRealtime(resolvedConvId)
            markRead(resolvedConvId)
        }
    }

    private suspend fun loadMessages(conversationId: String) {
        when (val res = repository.getMessages(conversationId)) {
            is ApiResponse.Success -> {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        messages = res.data,
                        errorMessage = null
                    )
                }
            }
            is ApiResponse.Failure -> {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = res.error.message
                    )
                }
            }
        }
    }

    private fun startObservingRealtime(conversationId: String) {
        realtimeJob?.cancel()
        realtimeJob = viewModelScope.launch {
            repository.observeMessages(conversationId)
                .catch { /* Realtime error or reconnection handled gracefully */ }
                .collect { newMessage ->
                    _uiState.update { state ->
                        val exists = state.messages.any { it.id == newMessage.id }
                        if (exists) state
                        else state.copy(messages = state.messages + newMessage)
                    }
                    markRead(conversationId)
                }
        }
    }

    fun sendText(content: String) {
        val convId = _uiState.value.conversationId
        if (convId.isNullOrBlank()) {
            _uiState.update { it.copy(errorMessage = "Chưa kết nối được phòng chat. Vui lòng thử lại!") }
            return
        }
        if (content.isBlank()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSending = true, errorMessage = null) }
            when (val res = repository.sendTextMessage(convId, content)) {
                is ApiResponse.Success -> {
                    _uiState.update { state ->
                        val exists = state.messages.any { it.id == res.data.id }
                        val updated = if (exists) state.messages else state.messages + res.data
                        state.copy(isSending = false, messages = updated)
                    }
                }
                is ApiResponse.Failure -> {
                    _uiState.update { it.copy(isSending = false, errorMessage = res.error.message) }
                }
            }
        }
    }

    fun sendImage(fileName: String, bytes: ByteArray) {
        val convId = _uiState.value.conversationId
        if (convId.isNullOrBlank()) {
            _uiState.update { it.copy(errorMessage = "Chưa kết nối được phòng chat. Vui lòng thử lại!") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSending = true, errorMessage = null) }
            when (val res = repository.sendImageMessage(convId, fileName, bytes)) {
                is ApiResponse.Success -> {
                    _uiState.update { state ->
                        val exists = state.messages.any { it.id == res.data.id }
                        val updated = if (exists) state.messages else state.messages + res.data
                        state.copy(isSending = false, messages = updated)
                    }
                }
                is ApiResponse.Failure -> {
                    _uiState.update { it.copy(isSending = false, errorMessage = res.error.message) }
                }
            }
        }
    }

    private fun markRead(conversationId: String) {
        viewModelScope.launch {
            repository.markRead(conversationId)
        }
    }

    fun clearErrorMessage() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        realtimeJob?.cancel()
    }
}
