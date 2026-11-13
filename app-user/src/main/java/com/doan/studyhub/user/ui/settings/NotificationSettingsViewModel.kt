package com.doan.studyhub.user.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.doan.studyhub.common.ApiResponse
import com.doan.studyhub.core.data.model.NotificationCategory
import com.doan.studyhub.core.data.model.NotificationPreferences
import com.doan.studyhub.core.data.repository.NotificationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class NotificationSettingsUiState(
    /** Last server-confirmed preferences; never an unconfirmed local choice. */
    val confirmed: NotificationPreferences? = null,
    val loading: Boolean = false,
    /** Requested values whose save is still in flight. */
    val saving: Map<NotificationCategory, Boolean> = emptyMap(),
    val error: String? = null
)

class NotificationSettingsViewModel @JvmOverloads constructor(
    private val repository: NotificationRepository = NotificationRepository(),
    private val externalScope: CoroutineScope? = null
) : ViewModel() {
    private val scope get() = externalScope ?: viewModelScope
    private val mutable = MutableStateFlow(NotificationSettingsUiState())
    val uiState = mutable.asStateFlow()
    private var owner = repository.currentUserId()
    private var session = 0
    // Saves are serialized so full-preference responses cannot arrive out of order.
    private val saveLock = Mutex()

    init { scope.launch { repository.sessionChanges().collect { syncSession() } } }

    fun load() {
        if (!syncSession()) return
        val mySession = session
        mutable.update { it.copy(loading = true, error = null) }
        scope.launch {
            val result = saveLock.withLock { repository.preferences() }
            if (!syncSession() || mySession != session) return@launch
            mutable.update {
                when (result) {
                    is ApiResponse.Success -> it.copy(confirmed = result.data, loading = false)
                    is ApiResponse.Failure -> it.copy(loading = false, error = result.error.message)
                }
            }
        }
    }

    fun setEnabled(category: NotificationCategory, enabled: Boolean) {
        val state = mutable.value
        if (!syncSession() || state.confirmed == null || category in state.saving) return
        if (state.confirmed.isEnabled(category) == enabled) return
        val mySession = session
        mutable.update { it.copy(saving = it.saving + (category to enabled), error = null) }
        scope.launch {
            val result = saveLock.withLock { repository.setPreference(category, enabled) }
            if (!syncSession() || mySession != session) return@launch
            when (result) {
                is ApiResponse.Success -> mutable.update {
                    it.copy(confirmed = result.data, saving = it.saving - category)
                }
                is ApiResponse.Failure -> {
                    // Outcome may be unknown (timeout): keep the last confirmed value and re-read the server.
                    mutable.update { it.copy(saving = it.saving - category, error = result.error.message) }
                    reconcile(mySession)
                }
            }
        }
    }

    private fun reconcile(mySession: Int) = scope.launch {
        val result = saveLock.withLock { repository.preferences() }
        if (!syncSession() || mySession != session) return@launch
        if (result is ApiResponse.Success) mutable.update { it.copy(confirmed = result.data) }
    }

    private fun syncSession(): Boolean {
        val current = repository.currentUserId()
        if (current != owner) {
            owner = current
            session++
            mutable.value = NotificationSettingsUiState()
        }
        return current != null
    }
}
