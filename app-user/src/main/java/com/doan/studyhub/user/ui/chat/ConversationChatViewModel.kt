package com.doan.studyhub.user.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.doan.studyhub.common.ApiError
import com.doan.studyhub.common.ApiResponse
import com.doan.studyhub.core.data.model.ChatMessage
import com.doan.studyhub.core.data.model.MessageSender
import com.doan.studyhub.core.data.repository.ChatRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
import java.util.UUID

/** Title shown in a group conversation header. */
data class ChatHeader(val title: String, val memberCount: Int = 0)

data class ConversationUiState(
    val isLoading: Boolean = false,
    val isSending: Boolean = false,
    val conversationId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val errorMessage: String? = null,
    val currentUserId: String? = null,
    /** Public profiles of other senders (group conversations only). */
    val senders: Map<String, MessageSender> = emptyMap(),
    val header: ChatHeader? = null,
    /** The user lost access (left, removed, archived): the screen must close. */
    val inaccessible: Boolean = false
)

typealias DirectChatUiState = ConversationUiState

/**
 * Sending, retry, realtime and read-state logic shared by direct and group conversations.
 * Subclasses only decide how the conversation id is resolved.
 */
abstract class ConversationChatViewModel(
    protected val repository: ChatRepository,
    private val externalScope: CoroutineScope?,
    private val showsReadReceipts: Boolean,
    private val tracksSenders: Boolean
) : ViewModel() {

    protected val mutableState = MutableStateFlow(ConversationUiState(currentUserId = repository.getCurrentUserId()))
    val uiState: StateFlow<ConversationUiState> = mutableState.asStateFlow()

    protected val scope: CoroutineScope get() = externalScope ?: viewModelScope

    private var realtimeJob: Job? = null
    private val requestedSenders = mutableSetOf<String>()

    private sealed class PendingPayload {
        data class Text(val content: String) : PendingPayload()
        data class Image(val fileName: String, val bytes: ByteArray) : PendingPayload()
        data class Document(val fileName: String, val bytes: ByteArray, val fileSizeText: String, val mimeType: String?) : PendingPayload()
    }

    private val pendingPayloads = mutableMapOf<String, PendingPayload>()

    /** Loads history, starts realtime and marks the conversation read. */
    protected suspend fun openConversation(conversationId: String) {
        mutableState.update { it.copy(conversationId = conversationId) }
        loadMessages(conversationId)
        startObservingRealtime(conversationId)
        markRead(conversationId)
    }

    /** Called for every failed repository call so subclasses can react (e.g. lost access). */
    protected open fun onFailure(error: ApiError) = Unit

    private suspend fun loadMessages(conversationId: String) {
        when (val res = repository.getMessages(conversationId)) {
            is ApiResponse.Success -> {
                mutableState.update { state ->
                    state.copy(
                        isLoading = false,
                        messages = computeEffectiveMessages(res.data, state.currentUserId),
                        errorMessage = null
                    )
                }
                loadSenders(res.data)
            }
            is ApiResponse.Failure -> {
                mutableState.update { it.copy(isLoading = false, errorMessage = res.error.message) }
                onFailure(res.error)
            }
        }
    }

    private fun startObservingRealtime(conversationId: String) {
        realtimeJob?.cancel()
        realtimeJob = scope.launch {
            repository.observeMessages(conversationId)
                .catch { cause -> if (cause is CancellationException) throw cause }
                .collect { message ->
                    mutableState.update { state ->
                        val existingIndex = state.messages.indexOfFirst { matchesIncoming(it, message) }
                        val updatedList = if (existingIndex != -1) {
                            state.messages.toMutableList().apply { set(existingIndex, message) }
                        } else {
                            state.messages + message
                        }
                        state.copy(messages = computeEffectiveMessages(updatedList, state.currentUserId))
                    }
                    loadSenders(listOf(message))
                    if (message.senderId != mutableState.value.currentUserId) {
                        markRead(conversationId)
                    }
                }
        }
    }

    /** A realtime row replaces the same message, or our own optimistic copy of it. */
    private fun matchesIncoming(existing: ChatMessage, incoming: ChatMessage): Boolean {
        if (existing.id != null && existing.id == incoming.id) return true
        return existing.id?.startsWith(TEMP_PREFIX) == true &&
            existing.senderId == incoming.senderId &&
            existing.type == incoming.type &&
            existing.content == incoming.content
    }

    fun sendText(content: String) {
        val trimmed = content.trim()
        if (trimmed.isBlank()) return
        val convId = requireConversation() ?: return
        val tempId = enqueueTemp(convId, "text", trimmed, PendingPayload.Text(trimmed))
        executeSendText(convId, tempId, trimmed)
    }

    fun sendImage(fileName: String, bytes: ByteArray) {
        val convId = requireConversation() ?: return
        val tempId = enqueueTemp(convId, "image", null, PendingPayload.Image(fileName, bytes))
        executeSendImage(convId, tempId, fileName, bytes)
    }

    fun sendDocument(fileName: String, bytes: ByteArray, fileSizeText: String, mimeType: String? = null) {
        val convId = requireConversation() ?: return
        val metadata = "$fileName|$fileSizeText"
        val tempId = enqueueTemp(convId, "document", metadata, PendingPayload.Document(fileName, bytes, fileSizeText, mimeType))
        executeSendDocument(convId, tempId, fileName, bytes, metadata, mimeType)
    }

    private fun requireConversation(): String? {
        val convId = mutableState.value.conversationId
        if (convId.isNullOrBlank()) {
            mutableState.update { it.copy(errorMessage = "Chưa kết nối được phòng chat. Vui lòng thử lại!") }
            return null
        }
        return convId
    }

    private fun enqueueTemp(convId: String, type: String, content: String?, payload: PendingPayload): String {
        val tempId = "$TEMP_PREFIX${UUID.randomUUID()}"
        pendingPayloads[tempId] = payload
        val tempMsg = ChatMessage(
            id = tempId,
            conversationId = convId,
            senderId = mutableState.value.currentUserId ?: "",
            type = type,
            content = content,
            status = "sending",
            createdAt = currentUtcIsoString()
        )
        mutableState.update { it.copy(messages = it.messages + tempMsg) }
        return tempId
    }

    private fun executeSendText(convId: String, tempId: String, content: String) =
        dispatch(tempId) { repository.sendTextMessage(convId, content) }

    private fun executeSendImage(convId: String, tempId: String, fileName: String, bytes: ByteArray) =
        dispatch(tempId) { repository.sendImageMessage(convId, fileName, bytes) }

    private fun executeSendDocument(
        convId: String, tempId: String, fileName: String, bytes: ByteArray, metadata: String, mimeType: String?
    ) = dispatch(tempId) { repository.sendDocumentMessage(convId, fileName, bytes, metadata, mimeType) }

    private fun dispatch(tempId: String, send: suspend () -> ApiResponse<ChatMessage>) {
        scope.launch {
            when (val res = send()) {
                is ApiResponse.Success -> {
                    pendingPayloads.remove(tempId)
                    mutableState.update { state ->
                        val idx = state.messages.indexOfFirst { it.id == tempId }
                        val updated = if (idx != -1) {
                            state.messages.toMutableList().apply { set(idx, res.data) }
                        } else if (state.messages.any { it.id == res.data.id }) {
                            state.messages
                        } else {
                            state.messages + res.data
                        }
                        state.copy(messages = computeEffectiveMessages(updated, state.currentUserId))
                    }
                }
                is ApiResponse.Failure -> {
                    markMessageFailed(tempId, res.error.message)
                    onFailure(res.error)
                }
            }
        }
    }

    fun retrySendMessage(failedMessage: ChatMessage) {
        val convId = mutableState.value.conversationId ?: return
        val tempId = failedMessage.id ?: return

        mutableState.update { state ->
            val idx = state.messages.indexOfFirst { it.id == tempId }
            if (idx != -1) {
                val updatedList = state.messages.toMutableList().apply { set(idx, state.messages[idx].copy(status = "sending")) }
                state.copy(messages = updatedList, errorMessage = null)
            } else {
                state
            }
        }

        when (val payload = pendingPayloads[tempId]) {
            is PendingPayload.Text -> executeSendText(convId, tempId, payload.content)
            is PendingPayload.Image -> executeSendImage(convId, tempId, payload.fileName, payload.bytes)
            is PendingPayload.Document -> executeSendDocument(
                convId, tempId, payload.fileName, payload.bytes, "${payload.fileName}|${payload.fileSizeText}", payload.mimeType
            )
            null -> {
                val content = failedMessage.content
                if (failedMessage.type == "text" && !content.isNullOrBlank()) {
                    executeSendText(convId, tempId, content)
                } else {
                    mutableState.update { it.copy(errorMessage = "Không thể gửi lại tệp này. Vui lòng chọn lại tệp!") }
                }
            }
        }
    }

    private fun markMessageFailed(tempId: String, errorMsg: String) {
        mutableState.update { state ->
            val idx = state.messages.indexOfFirst { it.id == tempId }
            val updated = if (idx != -1) {
                state.messages.toMutableList().apply { set(idx, state.messages[idx].copy(status = "failed")) }
            } else {
                state.messages
            }
            state.copy(errorMessage = errorMsg, messages = updated)
        }
    }

    open fun refreshReadStatus() {
        mutableState.value.conversationId?.let { markRead(it) }
    }

    protected fun markRead(conversationId: String) {
        scope.launch { repository.markRead(conversationId) }
    }

    fun clearErrorMessage() {
        mutableState.update { it.copy(errorMessage = null) }
    }

    /** Looks up sender profiles once per id; failures are retried on the next message. */
    private fun loadSenders(messages: List<ChatMessage>) {
        if (!tracksSenders) return
        val state = mutableState.value
        val missing = messages.map { it.senderId }
            .filter { it.isNotBlank() && it != state.currentUserId && it !in requestedSenders && it !in state.senders }
            .distinct()
        if (missing.isEmpty()) return
        requestedSenders += missing
        scope.launch {
            when (val res = repository.getSenderProfiles(missing)) {
                is ApiResponse.Success -> mutableState.update { it.copy(senders = it.senders + res.data) }
                is ApiResponse.Failure -> requestedSenders -= missing.toSet()
            }
        }
    }

    private fun currentUtcIsoString(): String {
        val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return parser.format(Date())
    }

    /** Direct chat infers "read" from a later reply; group chat shows no read receipts. */
    private fun computeEffectiveMessages(messages: List<ChatMessage>, currentUserId: String?): List<ChatMessage> {
        if (!showsReadReceipts || currentUserId.isNullOrBlank() || messages.isEmpty()) return messages

        val lastReceivedTime = messages
            .filter { it.senderId != currentUserId }
            .mapNotNull { it.createdAt?.takeIf { time -> time.isNotBlank() } }
            .maxOrNull()

        return messages.map { msg ->
            val msgCreatedAt = msg.createdAt
            if (msg.senderId == currentUserId && msg.status == "sent" && lastReceivedTime != null &&
                !msgCreatedAt.isNullOrBlank() && msgCreatedAt <= lastReceivedTime
            ) {
                msg.copy(status = "read")
            } else {
                msg
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        realtimeJob?.cancel()
    }

    private companion object {
        const val TEMP_PREFIX = "temp_"
    }
}
