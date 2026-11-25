package com.doan.studyhub.user.ui.chat

import com.doan.studyhub.core.data.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class ChatItemsTest {
    private val utc = TimeZone.getTimeZone("UTC")

    private fun msg(id: String, sender: String, at: String?, status: String = "sent") =
        ChatMessage(id = id, conversationId = "c", senderId = sender, type = "text", content = id, status = status, createdAt = at)

    private fun build(vararg messages: ChatMessage, group: Boolean = true) =
        buildChatItems(messages.toList(), "me", group, utc)

    private fun bubbles(items: List<ChatItem>) = items.filterIsInstance<ChatItem.Bubble>()

    @Test fun threeQuickMessagesFromOneSenderFormOneRun() {
        val items = build(
            msg("1", "alice", "2026-11-25T10:00:00Z"),
            msg("2", "alice", "2026-11-25T10:01:00Z"),
            msg("3", "alice", "2026-11-25T10:02:00Z")
        )
        val b = bubbles(items)
        assertEquals(listOf(RunPosition.FIRST, RunPosition.MIDDLE, RunPosition.LAST), b.map { it.position })
        assertEquals(listOf(true, false, false), b.map { it.showSenderName })
        assertEquals(listOf(false, false, true), b.map { it.showAvatar })
        assertEquals(listOf(false, false, true), b.map { it.showTime })
        assertEquals(3, items.size)
    }

    @Test fun directConversationNeverShowsSenderName() {
        val b = bubbles(build(msg("1", "alice", "2026-11-25T10:00:00Z"), group = false))
        assertFalse(b.single().showSenderName)
        assertTrue(b.single().showAvatar)
    }

    @Test fun runBreaksAfterFiveMinutesButNotExactlyAtFive() {
        val atFive = bubbles(build(msg("1", "a", "2026-11-25T10:00:00Z"), msg("2", "a", "2026-11-25T10:05:00Z")))
        assertEquals(listOf(RunPosition.FIRST, RunPosition.LAST), atFive.map { it.position })
        val past = bubbles(build(msg("1", "a", "2026-11-25T10:00:00Z"), msg("2", "a", "2026-11-25T10:05:01Z")))
        assertEquals(listOf(RunPosition.SINGLE, RunPosition.SINGLE), past.map { it.position })
    }

    @Test fun senderChangeBreaksTheRun() {
        val b = bubbles(build(
            msg("1", "alice", "2026-11-25T10:00:00Z"),
            msg("2", "bob", "2026-11-25T10:00:30Z"),
            msg("3", "alice", "2026-11-25T10:01:00Z")
        ))
        assertEquals(listOf(RunPosition.SINGLE, RunPosition.SINGLE, RunPosition.SINGLE), b.map { it.position })
        assertTrue(b.all { it.showSenderName && it.showAvatar })
    }

    @Test fun separatorAppearsAfterMoreThanFifteenMinutesAndStartsNewRun() {
        val items = build(msg("1", "a", "2026-11-25T10:00:00Z"), msg("2", "a", "2026-11-25T10:20:00Z"))
        assertTrue(items[1] is ChatItem.TimeSeparator)
        assertEquals(3, items.size)
        assertEquals(listOf(RunPosition.SINGLE, RunPosition.SINGLE), bubbles(items).map { it.position })
    }

    @Test fun noSeparatorAtExactlyFifteenMinutes() {
        val items = build(msg("1", "a", "2026-11-25T10:00:00Z"), msg("2", "b", "2026-11-25T10:15:00Z"))
        assertEquals(2, items.size)
    }

    @Test fun separatorAppearsOnADifferentDayEvenWhenClose() {
        val items = build(msg("1", "a", "2026-11-25T23:58:00Z"), msg("2", "a", "2026-11-26T00:01:00Z"))
        assertTrue(items[1] is ChatItem.TimeSeparator)
    }

    @Test fun firstMessageHasNoSeparator() {
        assertTrue(build(msg("1", "a", "2026-11-25T10:00:00Z")).first() is ChatItem.Bubble)
    }

    @Test fun ownStatusShownOnLatestOnlyExceptSendingAndFailed() {
        val b = bubbles(build(
            msg("1", "me", "2026-11-25T10:00:00Z"),
            msg("2", "me", "2026-11-25T10:01:00Z", status = "failed"),
            msg("3", "alice", "2026-11-25T10:02:00Z"),
            msg("4", "me", "2026-11-25T10:03:00Z"),
            msg("5", "me", "2026-11-25T10:04:00Z", status = "sending")
        ))
        assertEquals(listOf(false, true, false, false, true), b.map { it.showStatus })
        assertFalse(b[2].isMine)
        assertFalse(b.first { it.message.id == "3" }.showStatus)
    }

    @Test fun lastOwnMessageShowsStatusWhenSent() {
        val b = bubbles(build(msg("1", "me", "2026-11-25T10:00:00Z"), msg("2", "me", "2026-11-25T10:01:00Z")))
        assertEquals(listOf(false, true), b.map { it.showStatus })
    }

    @Test fun unparsableTimesNeverSplitRunsOrAddSeparators() {
        val items = build(msg("1", "a", null), msg("2", "a", "garbage"), msg("3", "a", "2026-11-25T10:00:00Z"))
        assertEquals(3, items.size)
        assertEquals(listOf(RunPosition.FIRST, RunPosition.MIDDLE, RunPosition.LAST), bubbles(items).map { it.position })
    }

    @Test fun parsesServerTimestampFormats() {
        val z = parseChatTime("2026-11-25T10:00:00Z")
        assertEquals(z, parseChatTime("2026-11-25T10:00:00.036702+00:00"))
        assertNull(parseChatTime(null))
        assertNull(parseChatTime("short"))
    }

    @Test fun emptyListGivesNoItems() {
        assertTrue(buildChatItems(emptyList(), "me", true, utc).isEmpty())
    }

    @Test fun bubbleKeyFallsBackWhenIdMissing() {
        val bubble = bubbles(build(ChatMessage(null, "c", "a", "text", "x", null, "sent", "2026-11-25T10:00:00Z"))).single()
        assertEquals("2026-11-25T10:00:00Z_a", bubble.key)
    }
}
