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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

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

    private sealed class PendingPayload {
        data class Text(val content: String) : PendingPayload()
        data class Image(val fileName: String, val bytes: ByteArray) : PendingPayload()
        data class Document(val fileName: String, val bytes: ByteArray, val fileSizeText: String, val mimeType: String?) : PendingPayload()
    }

    private val pendingPayloads = mutableMapOf<String, PendingPayload>()

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
                .collect { message ->
                    _uiState.update { state ->
                        val existingIndex = state.messages.indexOfFirst {
                            it.id == message.id || (it.id?.startsWith("temp_") == true && it.content == message.content && it.type == message.type)
                        }
                        val updatedList = if (existingIndex != -1) {
                            state.messages.toMutableList().apply { set(existingIndex, message) }
                        } else {
                            state.messages + message
                        }
                        state.copy(messages = updatedList)
                    }
                    if (message.senderId != _uiState.value.currentUserId) {
                        markRead(conversationId)
                    }
                }
        }
    }

    fun sendText(content: String) {
        val convId = _uiState.value.conversationId
        if (convId.isNullOrBlank()) {
            _uiState.update { it.copy(errorMessage = "Chưa kết nối được phòng chat. Vui lòng thử lại!") }
            return
        }
        val trimmed = content.trim()
        if (trimmed.isBlank()) return

        val tempId = "temp_${System.currentTimeMillis()}"
        pendingPayloads[tempId] = PendingPayload.Text(trimmed)

        val tempMsg = ChatMessage(
            id = tempId,
            conversationId = convId,
            senderId = _uiState.value.currentUserId ?: "",
            type = "text",
            content = trimmed,
            status = "sending",
            createdAt = currentUtcIsoString()
        )
        _uiState.update { it.copy(messages = it.messages + tempMsg) }

        executeSendText(convId, tempId, trimmed)
    }

    private fun executeSendText(convId: String, tempId: String, content: String) {
        viewModelScope.launch {
            when (val res = repository.sendTextMessage(convId, content)) {
                is ApiResponse.Success -> {
                    pendingPayloads.remove(tempId)
                    _uiState.update { state ->
                        val idx = state.messages.indexOfFirst { it.id == tempId }
                        val updated = if (idx != -1) {
                            state.messages.toMutableList().apply { set(idx, res.data) }
                        } else {
                            val exists = state.messages.any { it.id == res.data.id }
                            if (exists) state.messages else state.messages + res.data
                        }
                        state.copy(messages = updated)
                    }
                }
                is ApiResponse.Failure -> {
                    markMessageFailed(tempId, res.error.message)
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

        val tempId = "temp_${System.currentTimeMillis()}"
        pendingPayloads[tempId] = PendingPayload.Image(fileName, bytes)

        val tempMsg = ChatMessage(
            id = tempId,
            conversationId = convId,
            senderId = _uiState.value.currentUserId ?: "",
            type = "image",
            content = null,
            status = "sending",
            createdAt = currentUtcIsoString()
        )
        _uiState.update { it.copy(messages = it.messages + tempMsg) }

        executeSendImage(convId, tempId, fileName, bytes)
    }

    private fun executeSendImage(convId: String, tempId: String, fileName: String, bytes: ByteArray) {
        viewModelScope.launch {
            when (val res = repository.sendImageMessage(convId, fileName, bytes)) {
                is ApiResponse.Success -> {
                    pendingPayloads.remove(tempId)
                    _uiState.update { state ->
                        val idx = state.messages.indexOfFirst { it.id == tempId }
                        val updated = if (idx != -1) {
                            state.messages.toMutableList().apply { set(idx, res.data) }
                        } else {
                            val exists = state.messages.any { it.id == res.data.id }
                            if (exists) state.messages else state.messages + res.data
                        }
                        state.copy(messages = updated)
                    }
                }
                is ApiResponse.Failure -> {
                    markMessageFailed(tempId, res.error.message)
                }
            }
        }
    }

    fun sendDocument(fileName: String, bytes: ByteArray, fileSizeText: String, mimeType: String? = null) {
        val convId = _uiState.value.conversationId
        if (convId.isNullOrBlank()) {
            _uiState.update { it.copy(errorMessage = "Chưa kết nối được phòng chat. Vui lòng thử lại!") }
            return
        }

        val tempId = "temp_${System.currentTimeMillis()}"
        pendingPayloads[tempId] = PendingPayload.Document(fileName, bytes, fileSizeText, mimeType)

        val metadata = "$fileName|$fileSizeText"
        val tempMsg = ChatMessage(
            id = tempId,
            conversationId = convId,
            senderId = _uiState.value.currentUserId ?: "",
            type = "document",
            content = metadata,
            status = "sending",
            createdAt = currentUtcIsoString()
        )
        _uiState.update { it.copy(messages = it.messages + tempMsg) }

        executeSendDocument(convId, tempId, fileName, bytes, metadata, mimeType)
    }

    private fun executeSendDocument(
        convId: String,
        tempId: String,
        fileName: String,
        bytes: ByteArray,
        metadata: String,
        mimeType: String?
    ) {
        viewModelScope.launch {
            when (val res = repository.sendDocumentMessage(convId, fileName, bytes, metadata, mimeType)) {
                is ApiResponse.Success -> {
                    pendingPayloads.remove(tempId)
                    _uiState.update { state ->
                        val idx = state.messages.indexOfFirst { it.id == tempId }
                        val updated = if (idx != -1) {
                            state.messages.toMutableList().apply { set(idx, res.data) }
                        } else {
                            val exists = state.messages.any { it.id == res.data.id }
                            if (exists) state.messages else state.messages + res.data
                        }
                        state.copy(messages = updated)
                    }
                }
                is ApiResponse.Failure -> {
                    markMessageFailed(tempId, res.error.message)
                }
            }
        }
    }

    fun retrySendMessage(failedMessage: ChatMessage) {
        val convId = _uiState.value.conversationId ?: return
        val tempId = failedMessage.id ?: return

        // Cập nhật trạng thái thành 'sending'
        _uiState.update { state ->
            val idx = state.messages.indexOfFirst { it.id == tempId }
            if (idx != -1) {
                val updatedMsg = state.messages[idx].copy(status = "sending")
                val updatedList = state.messages.toMutableList().apply { set(idx, updatedMsg) }
                state.copy(messages = updatedList, errorMessage = null)
            } else {
                state
            }
        }

        when (val payload = pendingPayloads[tempId]) {
            is PendingPayload.Text -> executeSendText(convId, tempId, payload.content)
            is PendingPayload.Image -> executeSendImage(convId, tempId, payload.fileName, payload.bytes)
            is PendingPayload.Document -> executeSendDocument(convId, tempId, payload.fileName, payload.bytes, "${payload.fileName}|${payload.fileSizeText}", payload.mimeType)
            null -> {
                // Nếu payload không còn trong ram (ví dụ reload) nhưng là text message
                val content = failedMessage.content
                if (failedMessage.type == "text" && !content.isNullOrBlank()) {
                    executeSendText(convId, tempId, content)
                } else {
                    _uiState.update { it.copy(errorMessage = "Không thể gửi lại tệp này. Vui lòng chọn lại tệp!") }
                }
            }
        }
    }

    private fun markMessageFailed(tempId: String, errorMsg: String) {
        _uiState.update { state ->
            val idx = state.messages.indexOfFirst { it.id == tempId }
            val updated = if (idx != -1) {
                val failed = state.messages[idx].copy(status = "failed")
                state.messages.toMutableList().apply { set(idx, failed) }
            } else {
                state.messages
            }
            state.copy(errorMessage = errorMsg, messages = updated)
        }
    }

    fun refreshReadStatus() {
        _uiState.value.conversationId?.let { markRead(it) }
    }

    private fun markRead(conversationId: String) {
        viewModelScope.launch {
            repository.markRead(conversationId)
        }
    }

    fun clearErrorMessage() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun currentUtcIsoString(): String {
        val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return parser.format(Date())
    }

    override fun onCleared() {
        super.onCleared()
        realtimeJob?.cancel()
    }
}
