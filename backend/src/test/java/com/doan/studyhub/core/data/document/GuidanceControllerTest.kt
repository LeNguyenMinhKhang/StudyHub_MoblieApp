package com.doan.studyhub.core.data.document

import com.doan.studyhub.common.*
import com.doan.studyhub.core.data.model.*
import com.doan.studyhub.core.data.remote.GroupDocumentRemoteDataSource
import com.doan.studyhub.core.data.repository.GroupDocumentRepository
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/**
 * Client logic of "follow the guide" against an in-memory server with the SQL contract's
 * semantics (one lease per call, generation fencing, idempotent release). The SQL itself is
 * covered by supabase/tests/group_calls (GuidanceTest).
 */
class GuidanceControllerTest {
    private val call = "00000000-0000-0000-0000-000000000301"
    private val v1 = "00000000-0000-0000-0000-000000000401"
    private val v2 = "00000000-0000-0000-0000-000000000402"
    private val me = "00000000-0000-0000-0000-000000000001"
    private val other = "00000000-0000-0000-0000-000000000002"
    private val api = SafeApiCall(logger = ErrorLogger { _, _, _, _ -> })
    private val template: DocumentGuidance = Json.decodeFromJsonElement<DocumentGuidance>(
        Json.parseToJsonElement(requireNotNull(javaClass.getResource("/group_documents/guidance_payloads.json")).readText())
            .jsonObject.getValue("claim").jsonObject.getValue("guidance"))

    /** Server state; [holder]/[generation]/[sequence] mirror group_document_guidance. */
    private inner class Server : GroupDocumentRemoteDataSource() {
        var holder: String? = null
        var generation = 0L
        var sequence = 0L
        var version = v1
        var page = 0
        var callOpen = true
        var leaseLive = true
        var networkDown = false
        val updates = mutableListOf<Triple<Long, String, Int>>()
        val releases = mutableListOf<Long?>()
        var asUser = me

        fun view(viewer: String) = if (holder == null || !leaseLive) GuidanceState(null) else GuidanceState(template.copy(
            callId = call, guide = template.guide.copy(id = holder!!), versionId = version, pageIndex = page,
            documentId = if (version == v1) "d1" else "d2", generation = generation, sequence = sequence, isMe = holder == viewer))

        private fun check() {
            if (networkDown) throw IOException("offline")
            if (!callOpen) throw DomainException(ErrorType.CALL_UNAVAILABLE)
        }

        override suspend fun guidance(callId: String): GuidanceState { check(); return view(asUser) }
        override suspend fun claimGuidance(callId: String, versionId: String, pageIndex: Int): GuidanceState {
            check()
            if (holder != null && holder != asUser && leaseLive) throw DomainException(ErrorType.GUIDANCE_TAKEN)
            if (holder != asUser) generation++
            holder = asUser; leaseLive = true; version = versionId; page = pageIndex; sequence++
            return view(asUser)
        }
        override suspend fun updateGuidance(callId: String, generation: Long, versionId: String, pageIndex: Int): GuidanceState {
            check()
            if (holder != asUser || this.generation != generation) throw DomainException(ErrorType.GUIDANCE_LOST)
            updates += Triple(generation, versionId, pageIndex)
            if (version != versionId || page != pageIndex) sequence++
            version = versionId; page = pageIndex; leaseLive = true
            return view(asUser)
        }
        override suspend fun releaseGuidance(callId: String, generation: Long?): GuidanceRelease {
            check()
            releases += generation
            val ok = holder == asUser && (generation == null || generation == this.generation)
            if (ok) holder = null
            return GuidanceRelease(ok)
        }

        /** Another member is guiding (taking over an expired lease bumps the generation). */
        fun otherGuides(onVersion: String = v1, onPage: Int = 3) {
            if (holder != other) generation++
            holder = other; leaseLive = true; version = onVersion; page = onPage; sequence++
        }
    }

    private fun repo(server: Server) = GroupDocumentRepository(server, api, { me }, { "s" })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun tearDown() { scope.cancel() }

    private suspend fun waitFor(timeoutMs: Long = 3_000, condition: () -> Boolean) {
        withTimeout(timeoutMs) { while (!condition()) delay(5) }
    }

    private fun controller(server: Server, following: Boolean = false) =
        GuidanceController(call, repo(server), scope, pollMs = 20, heartbeatMs = 30, initiallyFollowing = following)

    @Test fun readsIndependentlyByDefaultAndNeverMovesWithoutOptIn() = runBlocking {
        val server = Server().apply { otherGuides(v1, 4) }
        val c = controller(server)
        c.refresh()
        assertFalse(c.state.value.following)
        assertEquals(other, c.state.value.otherGuide?.guide?.id)
        assertNull(c.followAction(v1)) // default: free reading, the guide exists but nothing moves
        c.stop()
    }

    @Test fun followAppliesEachGuideMoveOnceAndKeepsManualNavigationUntilNextMove() = runBlocking {
        val server = Server().apply { otherGuides(v1, 4) }
        val c = controller(server)
        c.refresh()
        c.setFollowing(true)
        val first = c.followAction(v1) as FollowAction.Page
        assertEquals(4, first.page)
        c.markFollowed(first.sequence)
        assertNull(c.followAction(v1)) // the viewer may now scroll away freely; nothing is forced again
        server.otherGuides(v1, 7); c.refresh()
        val second = c.followAction(v1) as FollowAction.Page
        assertEquals(7, second.page)
        assertTrue(second.sequence > first.sequence)
        // Turning follow off stops all movement; turning it on re-applies the current page.
        c.markFollowed(second.sequence); c.setFollowing(false)
        server.otherGuides(v1, 9); c.refresh()
        assertNull(c.followAction(v1))
        c.setFollowing(true)
        assertEquals(9, (c.followAction(v1) as FollowAction.Page).page)
        c.stop()
    }

    @Test fun guideOnAnotherVersionIsOfferedNotSwitchedSilently() = runBlocking {
        val server = Server().apply { otherGuides(v2, 1) }
        val c = controller(server, following = true)
        c.refresh()
        val action = c.followAction(v1) as FollowAction.OtherVersion
        assertEquals(v2, action.versionId)
        assertEquals("d2", action.documentId)
        // Once the viewer is on that version the same guidance becomes a plain page move.
        assertTrue(c.followAction(v2) is FollowAction.Page)
        c.stop()
    }

    @Test fun claimBeginsGuidingStopsFollowingAndPushesNavigationInOrder() = runBlocking {
        val server = Server()
        val c = controller(server, following = true)
        c.claim(v1, 0)
        assertTrue(c.state.value.guiding)
        assertFalse(c.state.value.following) // guiding and following are mutually exclusive
        c.setFollowing(true)
        assertFalse(c.state.value.following)
        c.navigated(v1, 1); c.navigated(v1, 2); c.navigated(v1, 5)
        waitFor { server.page == 5 }
        assertTrue(server.updates.all { it.first == server.generation }) // always fenced by the claimed generation
        assertTrue(server.updates.map { it.third }.zipWithNext().all { (a, b) -> a <= b }) // never out of order
        assertNull(c.followAction(v1)) // own page is not a follow target
        // Idle: the heartbeat keeps renewing without changing the sequence.
        val seq = server.sequence
        val count = server.updates.size
        waitFor { server.updates.size >= count + 2 }
        assertEquals(seq, server.sequence)
        c.stop()
    }

    @Test fun liveGuideCannotBeDisplacedAndTheClaimerLearnsWho() = runBlocking {
        val server = Server().apply { otherGuides(v1, 2) }
        val c = controller(server)
        c.claim(v1, 0)
        assertFalse(c.state.value.guiding)
        assertEquals(ErrorType.GUIDANCE_TAKEN, c.state.value.message)
        assertEquals(other, c.state.value.otherGuide?.guide?.id)
        assertEquals(other, server.holder)
        // An expired lease can be taken over.
        server.leaseLive = false
        c.consumeMessage()
        c.claim(v1, 6)
        assertTrue(c.state.value.guiding)
        assertEquals(me, server.holder)
        c.stop()
    }

    @Test fun losingTheLeaseStopsPushingAndFallsBackToReading() = runBlocking {
        val server = Server()
        val c = controller(server)
        c.claim(v1, 0)
        server.otherGuides(v1, 3) // lease expired, someone else took over
        c.navigated(v1, 1)
        waitFor { !c.state.value.guiding }
        assertEquals(ErrorType.GUIDANCE_LOST, c.state.value.message)
        val sent = server.updates.size
        c.navigated(v1, 2); delay(120)
        assertEquals(sent, server.updates.size) // nothing is pushed any more
        assertEquals(3, server.page) // and the new guide was not overwritten
        c.stop()
    }

    @Test fun refreshNoticingAnotherGuideDropsOurGeneration() = runBlocking {
        val server = Server()
        val c = controller(server)
        c.claim(v1, 0)
        server.otherGuides(v1, 8)
        c.refresh()
        assertFalse(c.state.value.guiding)
        assertEquals(ErrorType.GUIDANCE_LOST, c.state.value.message)
        c.stop()
    }

    @Test fun networkFailuresKeepTheLeaseAndRetryWithoutClaimingSuccess() = runBlocking {
        val server = Server()
        val c = controller(server)
        c.claim(v1, 0)
        server.networkDown = true
        c.navigated(v1, 4)
        delay(120)
        assertTrue(c.state.value.guiding) // transient: the generation is kept
        assertNotEquals(4, server.page) // nothing reached the server
        server.networkDown = false
        waitFor { server.page == 4 } // the pending move is delivered by the next beat
        c.stop()
    }

    @Test fun endedCallAccessLossOrSessionSwitchClosesEverything() = runBlocking {
        val server = Server()
        val c = controller(server)
        c.start()
        c.claim(v1, 0)
        server.callOpen = false
        waitFor { c.state.value.closed }
        assertFalse(c.state.value.guiding)
        assertNull(c.state.value.guidance)
        val sent = server.updates.size
        c.navigated(v1, 3); delay(100)
        assertEquals(sent, server.updates.size)
        c.setFollowing(true)
        assertNull(c.followAction(v1))
        c.stop()
    }

    @Test fun releaseIsIdempotentAndFencedByGeneration() = runBlocking {
        val server = Server()
        val c = controller(server)
        c.claim(v1, 0)
        val generation = server.generation
        c.release()
        assertFalse(c.state.value.guiding)
        assertNull(server.holder)
        assertEquals(listOf<Long?>(generation), server.releases)
        c.release() // nothing held: no request at all
        assertEquals(1, server.releases.size)
        c.stop()
    }

    @Test fun stopReleasesAHeldLeaseBestEffort() = runBlocking {
        val server = Server()
        val c = controller(server)
        c.claim(v1, 0)
        val generation = server.generation
        c.stop()
        waitFor { server.releases.isNotEmpty() }
        assertEquals(generation, server.releases.single())
    }

    @Test fun signalsRefreshOnlyForThisCall() = runBlocking {
        val server = Server()
        val c = controller(server)
        server.otherGuides(v1, 2)
        c.onSignal(DocumentSignal.GuidanceChanged("00000000-0000-0000-0000-0000000009ff"))
        c.onSignal(DocumentSignal.LibraryChanged)
        delay(60)
        assertNull(c.state.value.guidance) // other calls' and library signals are ignored
        c.onSignal(DocumentSignal.GuidanceChanged(call))
        waitFor { c.state.value.otherGuide != null }
        c.stop()
    }
}
