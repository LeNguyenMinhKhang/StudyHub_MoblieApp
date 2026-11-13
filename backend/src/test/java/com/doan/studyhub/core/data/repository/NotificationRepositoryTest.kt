package com.doan.studyhub.core.data.repository

import com.doan.studyhub.common.ApiResponse
import com.doan.studyhub.common.ErrorLogger
import com.doan.studyhub.common.ErrorType
import com.doan.studyhub.common.ExceptionMapper
import com.doan.studyhub.common.Operation
import com.doan.studyhub.common.SafeApiCall
import com.doan.studyhub.core.data.model.AppNotification
import com.doan.studyhub.core.data.model.NotificationCategory
import com.doan.studyhub.core.data.model.NotificationCursor
import com.doan.studyhub.core.data.model.NotificationFilter
import com.doan.studyhub.core.data.model.NotificationPage
import com.doan.studyhub.core.data.model.NotificationPreferences
import com.doan.studyhub.core.data.model.NotificationSignal
import com.doan.studyhub.core.data.model.NotificationTarget
import com.doan.studyhub.core.data.model.NotificationTemplates
import com.doan.studyhub.core.data.remote.NotificationRemoteDataSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationRepositoryTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val id = "00000000-0000-0000-0000-0000000000a1"

    private class FakeRemote : NotificationRemoteDataSource() {
        val listCalls = mutableListOf<Triple<NotificationFilter, NotificationCursor?, Int>>()
        var listGate: CompletableDeferred<Unit>? = null
        var onList: () -> Unit = {}
        val observed = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        var failObserve = 0
        override suspend fun list(filter: NotificationFilter, cursor: NotificationCursor?, limit: Int): NotificationPage {
            listCalls += Triple(filter, cursor, limit)
            onList()
            listGate?.await()
            return NotificationPage(emptyList())
        }
        override suspend fun open(id: String) = NotificationTarget(id, "like", postId = "p")
        override suspend fun preferences() = NotificationPreferences()
        override suspend fun setPreference(category: NotificationCategory, enabled: Boolean) =
            NotificationPreferences(like = category != NotificationCategory.LIKE || enabled)
        override fun observe(userId: String): Flow<NotificationSignal> = flow {
            observed += userId
            if (failObserve > 0) { failObserve--; throw IllegalStateException("socket closed") }
            try {
                emit(NotificationSignal.Subscribed)
                awaitCancellation()
            } finally {
                cancelled += userId
            }
        }
    }

    /** Yields on the single test thread until [done] holds; fails instead of hanging. */
    private suspend fun settle(done: () -> Boolean) {
        repeat(100) { if (done()) return; yield() }
        throw AssertionError("condition not reached")
    }

    private val quiet = SafeApiCall(logger = ErrorLogger { _, _, _, _ -> })

    private fun repo(remote: FakeRemote, user: () -> String?, sessions: Flow<String?> = MutableStateFlow(user())) =
        NotificationRepository(remote, quiet, user, { sessions }, retryDelayMs = { 0 })

    @Test fun legacyPayloadDecodesWithDefaults() {
        val page = json.decodeFromString<NotificationPage>(
            """{"items":[{"id":"$id","type":"friend_request","is_read":false,"created_at":"2026-11-06T00:00:00+00:00",
               "post_id":null,"actor":null}],"next_cursor":null,"unread_count":1}"""
        )
        val item = page.items.single()
        assertEquals(1, item.templateVersion)
        assertNull(item.sourceKind)
        assertNull(item.openedAt)
        assertTrue(NotificationTemplates.isSupported(item))
    }

    @Test fun v2PayloadDecodesSourceAndTemplate() {
        val item = json.decodeFromString<AppNotification>(
            """{"id":"$id","type":"like","is_read":true,"created_at":"2026-11-06T00:00:00+00:00","post_id":"p",
               "actor":{"id":"$id","username":"a","display_name":"A","avatar_url":null},"source_kind":"like",
               "source_id":"$id","template_version":2,"opened_at":"2026-11-06T00:00:01+00:00"}"""
        )
        assertEquals("like", item.sourceKind)
        assertEquals(2, item.templateVersion)
        assertFalse("unknown template version must use the generic fallback", NotificationTemplates.isSupported(item))
        assertFalse(NotificationTemplates.isSupported(item.copy(templateVersion = 1, type = "best_answer")))
    }

    @Test fun preferencesDecodeAndMissingCategoriesDefaultOn() {
        val prefs = json.decodeFromString<NotificationPreferences>("""{"like":false}""")
        assertFalse(prefs.isEnabled(NotificationCategory.LIKE))
        assertTrue(prefs.isEnabled(NotificationCategory.FRIENDSHIP))
        assertTrue(prefs.isEnabled(NotificationCategory.COMMENT))
    }

    @Test fun malformedCursorAndLimitAreRejectedBeforeNetwork() = runBlocking {
        val remote = FakeRemote()
        val repository = repo(remote, { "user" })
        val badId = repository.list(NotificationCursor("2026-11-06T00:00:00Z", "not-a-uuid"))
        val badTime = repository.list(NotificationCursor("yesterday", id))
        val badLimit = repository.list(limit = 0)
        val badOpen = repository.open("1; drop table")
        listOf(badId, badTime, badLimit, badOpen).forEach {
            assertEquals(ErrorType.VALIDATION_FAILED, (it as ApiResponse.Failure).error.type)
        }
        assertTrue(remote.listCalls.isEmpty())
        assertEquals(ErrorType.VALIDATION_FAILED, (repository.markRead(List(101) { id }) as ApiResponse.Failure).error.type)
    }

    @Test fun filterAndLimitArePassedThroughAndCapped() = runBlocking {
        val remote = FakeRemote()
        repo(remote, { "user" }).list(limit = 500, filter = NotificationFilter.UNREAD)
        assertEquals(Triple(NotificationFilter.UNREAD, null, 50), remote.listCalls.single())
    }

    @Test fun missingSessionIsUnauthenticated() = runBlocking {
        val result = repo(FakeRemote(), { null }).preferences()
        assertEquals(ErrorType.UNAUTHENTICATED, (result as ApiResponse.Failure).error.type)
    }

    @Test fun responseCompletingAfterAccountSwitchIsDiscarded() = runBlocking {
        var user: String? = "a"
        val remote = FakeRemote().apply { onList = { user = "c" } }
        val result = repo(remote, { user }).list()
        assertEquals(ErrorType.UNAUTHENTICATED, (result as ApiResponse.Failure).error.type)
    }

    @Test fun sessionSwitchDisposesOldChannelBeforeOpeningNew() = runBlocking {
        val sessions = MutableStateFlow<String?>("a")
        var user: String? = "a"
        val remote = FakeRemote()
        val repository = repo(remote, { user }, sessions)
        val received = mutableListOf<NotificationSignal>()
        val job = launch { repository.signals().collect { received += it } }
        settle { remote.observed == listOf("a") }
        user = "c"; sessions.value = "c"
        settle { remote.observed == listOf("a", "c") }
        assertEquals(listOf("a"), remote.cancelled)
        user = null; sessions.value = null
        settle { remote.cancelled == listOf("a", "c") }
        assertEquals(listOf("a", "c"), remote.observed)
        job.cancel()
        assertEquals(listOf(NotificationSignal.Subscribed, NotificationSignal.Subscribed), received)
    }

    @Test fun transportFailureReportsDisconnectAndResubscribes() = runBlocking {
        val remote = FakeRemote().apply { failObserve = 1 }
        val signals = repo(remote, { "a" }).signals().take(2).toList()
        assertEquals(listOf(NotificationSignal.Disconnected, NotificationSignal.Subscribed), signals)
        assertEquals(listOf("a", "a"), remote.observed)
    }

    @Test fun notificationErrorsMapToSafeTypes() {
        val mapper = ExceptionMapper()
        assertEquals(ErrorType.NOTIFICATION_UNAVAILABLE, mapper.postgrestType("F0004", Operation.NOTIFICATION_OPEN))
        assertEquals(ErrorType.FRIENDSHIP_STALE, mapper.postgrestType("F0004", Operation.FRIENDSHIP_MUTATE))
        assertEquals(ErrorType.VALIDATION_FAILED, mapper.postgrestType("F0001", Operation.NOTIFICATION_PREFERENCES))
        assertEquals(ErrorType.VALIDATION_FAILED, mapper.postgrestType("22P02", Operation.NOTIFICATION_OPEN))
        assertEquals(ErrorType.PERMISSION_DENIED, mapper.postgrestType("42501", Operation.NOTIFICATION_LIST))
        assertEquals(ErrorType.UNAUTHENTICATED, mapper.postgrestType("F0005", Operation.NOTIFICATION_LIST))
        assertEquals(ErrorType.ACCOUNT_BANNED, mapper.postgrestType("F0006", Operation.NOTIFICATION_MARK_READ))
    }
}
