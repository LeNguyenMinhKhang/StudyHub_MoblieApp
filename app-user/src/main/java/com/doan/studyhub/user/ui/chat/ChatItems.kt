package com.doan.studyhub.user.ui.chat

import com.doan.studyhub.core.data.model.ChatMessage
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Position of a bubble inside a run of consecutive messages from the same sender. */
enum class RunPosition { SINGLE, FIRST, MIDDLE, LAST }

sealed interface ChatItem {
    val key: String

    data class TimeSeparator(val epochMillis: Long) : ChatItem {
        override val key: String get() = "time_$epochMillis"
    }

    data class Bubble(
        val message: ChatMessage,
        val isMine: Boolean,
        val position: RunPosition,
        /** Group conversations: sender name above the first bubble of a received run. */
        val showSenderName: Boolean,
        /** Sender avatar beside the last bubble of a received run. */
        val showAvatar: Boolean,
        /** Own messages: sending/failed always, sent/read only on the latest one. */
        val showStatus: Boolean,
        /** Time label on the last bubble of a run. */
        val showTime: Boolean
    ) : ChatItem {
        override val key: String get() = message.id ?: "${message.createdAt}_${message.senderId}"
    }
}

const val RUN_GAP_MS = 5 * 60 * 1000L
const val SEPARATOR_GAP_MS = 15 * 60 * 1000L

/** Server timestamps are ISO-8601 in UTC (`2026-11-25T15:53:20.036702+00:00`); null when unparseable. */
fun parseChatTime(createdAt: String?): Long? {
    if (createdAt.isNullOrBlank() || createdAt.length < 19) return null
    return try {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .parse(createdAt.take(19))?.time
    } catch (e: java.text.ParseException) {
        null
    }
}

private fun sameDay(a: Long, b: Long, zone: TimeZone): Boolean {
    val first = Calendar.getInstance(zone).apply { timeInMillis = a }
    val second = Calendar.getInstance(zone).apply { timeInMillis = b }
    return first.get(Calendar.YEAR) == second.get(Calendar.YEAR) &&
        first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR)
}

/**
 * Turns messages (oldest first) into list rows: time separators and bubbles grouped into runs.
 * Messages without a parsable time never break a run and never get a separator.
 */
fun buildChatItems(
    messages: List<ChatMessage>,
    currentUserId: String?,
    isGroup: Boolean,
    zone: TimeZone = TimeZone.getDefault()
): List<ChatItem> {
    if (messages.isEmpty()) return emptyList()
    val times = messages.map { parseChatTime(it.createdAt) }

    fun separatorBefore(index: Int): Boolean {
        if (index == 0) return false
        val now = times[index] ?: return false
        val before = times[index - 1] ?: return false
        return now - before > SEPARATOR_GAP_MS || !sameDay(now, before, zone)
    }

    /** Message [index] continues the run of the previous one. */
    fun linkedToPrevious(index: Int): Boolean {
        if (index == 0 || separatorBefore(index)) return false
        if (messages[index].senderId != messages[index - 1].senderId) return false
        val now = times[index]
        val before = times[index - 1]
        return now == null || before == null || now - before <= RUN_GAP_MS
    }

    val lastMineIndex = messages.indexOfLast { it.senderId == currentUserId }
    val items = ArrayList<ChatItem>(messages.size + 4)
    for (index in messages.indices) {
        val message = messages[index]
        if (separatorBefore(index)) items += ChatItem.TimeSeparator(requireNotNull(times[index]))

        val hasPrevious = linkedToPrevious(index)
        val hasNext = index + 1 < messages.size && linkedToPrevious(index + 1)
        val position = when {
            hasPrevious && hasNext -> RunPosition.MIDDLE
            hasPrevious -> RunPosition.LAST
            hasNext -> RunPosition.FIRST
            else -> RunPosition.SINGLE
        }
        val mine = message.senderId == currentUserId
        val endsRun = position == RunPosition.LAST || position == RunPosition.SINGLE
        val startsRun = position == RunPosition.FIRST || position == RunPosition.SINGLE
        items += ChatItem.Bubble(
            message = message,
            isMine = mine,
            position = position,
            showSenderName = isGroup && !mine && startsRun,
            showAvatar = !mine && endsRun,
            showStatus = mine && (message.status == "sending" || message.status == "failed" || index == lastMineIndex),
            showTime = endsRun
        )
    }
    return items
}
