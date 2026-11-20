package com.doan.studyhub.core.data.document

import com.doan.studyhub.common.ApiResponse
import com.doan.studyhub.common.ErrorType
import com.doan.studyhub.core.data.model.DocumentGuidance
import com.doan.studyhub.core.data.model.DocumentSignal
import com.doan.studyhub.core.data.model.GuidanceLimits
import com.doan.studyhub.core.data.repository.GroupDocumentRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class GuidanceUiState(
    /** The call ended, was lost or access was revoked: guidance UI is hidden and nothing is sent. */
    val closed: Boolean = false,
    /** Live guide as the server last reported it (may be the user). Null when nobody guides. */
    val guidance: DocumentGuidance? = null,
    /** The user chose to follow another member's page. Off by default: everyone reads independently. */
    val following: Boolean = false,
    /** The user holds the guide lease and pushes their page. */
    val guiding: Boolean = false,
    val message: ErrorType? = null
) {
    /** Somebody else guides. */
    val otherGuide: DocumentGuidance? get() = guidance?.takeIf { !it.isMe }
}

/** What following should do on the viewer right now. */
sealed interface FollowAction {
    /** Show [page] of the open version. Zoom and rotation are never forced. */
    data class Page(val page: Int, val sequence: Long) : FollowAction
    /** The guide is on another document version; offer to open it, never switch silently. */
    data class OtherVersion(val versionId: String, val documentId: String, val sequence: Long) : FollowAction
}

/**
 * Optional "follow the guide" for one open call. Navigation only: it never restricts annotating
 * (the server grants annotation rights by membership, not by guide). The guide holds a lease
 * fenced by a generation, renews it by heartbeat and moves followers by updating its page.
 *
 * Reads arrive by realtime invalidation ([onSignal]) with a fallback poll while [start]ed.
 * A lost lease, an ended call or lost access stops pushing immediately; network failures are
 * retried on the next heartbeat and never reported as success.
 */
class GuidanceController(
    val callId: String,
    private val repo: GroupDocumentRepository,
    private val scope: CoroutineScope,
    private val pollMs: Long = 5_000L,
    private val heartbeatMs: Long = GuidanceLimits.HEARTBEAT_SECONDS * 1_000L,
    initiallyFollowing: Boolean = false,
    /** Outlives [scope] (e.g. a cleared ViewModel) so leaving the viewer still releases the lease. */
    private val releaseScope: CoroutineScope = scope
) {
    private val mutable = MutableStateFlow(GuidanceUiState(following = initiallyFollowing))
    val state: StateFlow<GuidanceUiState> = mutable.asStateFlow()
    private val lock = Mutex()
    @Volatile private var generation: Long? = null
    @Volatile private var target: Pair<String, Int>? = null
    private var poller: Job? = null
    private var sender: Job? = null
    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var lastApplied = -1L
    @Volatile private var stopped = false

    /** Starts the fallback poll (call while the viewer is in the foreground). */
    fun start() {
        if (stopped || poller?.isActive == true) return
        poller = scope.launch {
            while (isActive) { refresh(); delay(pollMs) }
        }
    }

    /** Foreground left: stop polling, keep the lease heartbeat so followers are not dropped. */
    fun pause() { poller?.cancel(); poller = null }

    fun onSignal(signal: DocumentSignal) {
        if (signal is DocumentSignal.GuidanceChanged && signal.callId == callId && !stopped) scope.launch { refresh() }
        if (signal == DocumentSignal.Subscribed && !stopped) scope.launch { refresh() } // catch up after a gap
    }

    suspend fun refresh() {
        if (stopped) return
        when (val result = repo.guidance(callId)) {
            is ApiResponse.Success -> lock.withLock {
                val guidance = result.data.guidance
                var message: ErrorType? = null
                if (generation != null && guidance != null && !guidance.isMe) {
                    // Somebody took the lease over: stop pushing and fall back to following/free reading.
                    dropGuiding(); message = ErrorType.GUIDANCE_LOST
                }
                mutable.value = mutable.value.copy(guidance = guidance, message = message ?: mutable.value.message,
                    guiding = generation != null)
            }
            is ApiResponse.Failure -> if (callEnded(result.error.type)) close()
        }
    }

    fun setFollowing(on: Boolean) {
        if (stopped) return
        lastApplied = -1L // re-apply the guide's current page right away
        mutable.value = mutable.value.copy(following = on && generation == null)
    }

    /** What to do on the viewer for [openVersionId], once per guide move. Null when nothing is due. */
    fun followAction(openVersionId: String): FollowAction? {
        val s = mutable.value
        val guide = s.otherGuide ?: return null
        if (!s.following || s.closed || guide.sequence <= lastApplied) return null
        return if (guide.versionId == openVersionId) FollowAction.Page(guide.pageIndex, guide.sequence)
        else FollowAction.OtherVersion(guide.versionId, guide.documentId, guide.sequence)
    }

    fun markFollowed(sequence: Long) { if (sequence > lastApplied) lastApplied = sequence }

    /** Becomes the guide at [versionId]/[page]. Another live guide is never displaced. */
    suspend fun claim(versionId: String, page: Int) {
        if (stopped) return
        lock.withLock {
            when (val result = repo.claimGuidance(callId, versionId, page)) {
                is ApiResponse.Success -> {
                    val guidance = result.data.guidance
                    generation = guidance?.generation
                    target = versionId to page
                    mutable.value = mutable.value.copy(guidance = guidance, guiding = generation != null, following = false, message = null)
                    if (generation != null) startSender()
                }
                is ApiResponse.Failure -> {
                    if (callEnded(result.error.type)) { closeLocked(); return }
                    mutable.value = mutable.value.copy(message = result.error.type)
                }
            }
        }
        if (mutable.value.message == ErrorType.GUIDANCE_TAKEN) refresh() // show who guides
    }

    /** The guide moved to another page/version; coalesced, ordered pushes. Followers' zoom is untouched. */
    fun navigated(versionId: String, page: Int) {
        if (generation == null || stopped) return
        target = versionId to page
        wake.trySend(Unit)
    }

    /** Stops guiding. Idempotent; a stale generation just reports released = false. */
    suspend fun release() {
        val held = lock.withLock {
            val g = generation
            dropGuiding()
            mutable.value = mutable.value.copy(guiding = false, message = null)
            g
        }
        if (held != null && !stopped) repo.releaseGuidance(callId, held)
        refresh()
    }

    fun consumeMessage() { mutable.value = mutable.value.copy(message = null) }

    /** Ends all work. A held lease is released best-effort; otherwise it expires on the server. */
    fun stop() {
        if (stopped) return
        stopped = true
        poller?.cancel(); sender?.cancel()
        val held = generation
        generation = null
        if (held != null) releaseScope.launch { withContext(NonCancellable) { repo.releaseGuidance(callId, held) } }
    }

    private fun startSender() {
        sender?.cancel()
        sender = scope.launch {
            while (isActive) {
                // Immediately on navigation, otherwise as a lease heartbeat.
                withTimeoutOrNull(heartbeatMs) { wake.receive() }
                push()
            }
        }
    }

    private suspend fun push() {
        lock.withLock {
            val g = generation ?: return
            val (version, page) = target ?: return
            when (val result = repo.updateGuidance(callId, g, version, page)) {
                is ApiResponse.Success -> mutable.value = mutable.value.copy(guidance = result.data.guidance)
                is ApiResponse.Failure -> when {
                    result.error.type == ErrorType.GUIDANCE_LOST || result.error.type == ErrorType.GUIDANCE_TAKEN -> {
                        dropGuiding()
                        mutable.value = mutable.value.copy(guiding = false, message = ErrorType.GUIDANCE_LOST)
                    }
                    callEnded(result.error.type) -> closeLocked()
                    // Transient (network, server): keep the lease generation and retry on the next beat.
                    else -> Unit
                }
            }
        }
    }

    private fun dropGuiding() {
        generation = null; target = null
        sender?.cancel(); sender = null
    }

    private fun callEnded(type: ErrorType) = type == ErrorType.CALL_UNAVAILABLE || type == ErrorType.CALL_ENDING ||
        type == ErrorType.GROUP_UNAVAILABLE || type == ErrorType.GROUP_ACCOUNT_UNAVAILABLE ||
        type == ErrorType.UNAUTHENTICATED

    private suspend fun close() = lock.withLock { closeLocked() }

    private fun closeLocked() {
        dropGuiding()
        poller?.cancel(); poller = null
        mutable.value = GuidanceUiState(closed = true)
    }
}
