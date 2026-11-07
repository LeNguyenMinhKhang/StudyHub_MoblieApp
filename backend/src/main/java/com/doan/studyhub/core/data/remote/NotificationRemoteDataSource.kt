package com.doan.studyhub.core.data.remote

import com.doan.studyhub.core.data.SupabaseClient
import com.doan.studyhub.core.data.model.NotificationCategory
import com.doan.studyhub.core.data.model.NotificationCursor
import com.doan.studyhub.core.data.model.NotificationFilter
import com.doan.studyhub.core.data.model.NotificationPage
import com.doan.studyhub.core.data.model.NotificationPreferences
import com.doan.studyhub.core.data.model.NotificationSignal
import com.doan.studyhub.core.data.model.NotificationTarget
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

open class NotificationRemoteDataSource {
    private val client get() = SupabaseClient.client
    private val postgrest get() = client.postgrest

    open suspend fun list(filter: NotificationFilter, cursor: NotificationCursor?, limit: Int): NotificationPage {
        val args = buildJsonObject {
            put("p_filter", filter.wire)
            put("p_limit", limit.coerceIn(1, 50))
            cursor?.let { put("p_cursor_created_at", it.createdAt); put("p_cursor_id", it.id) }
        }
        return try {
            postgrest.rpc("list_my_notifications_v2", args).decodeAs<NotificationPage>()
        } catch (error: PostgrestRestException) {
            // Older database without v2: the legacy RPC only supports the "all" view.
            if (error.code != MISSING_FUNCTION || filter != NotificationFilter.ALL) throw error
            postgrest.rpc("list_my_notifications", buildJsonObject {
                put("p_limit", limit.coerceIn(1, 50))
                cursor?.let { put("p_cursor_created_at", it.createdAt); put("p_cursor_id", it.id) }
            }).decodeAs<NotificationPage>()
        }
    }

    /** Null ids marks every notification of the current user as read. */
    open suspend fun markRead(ids: List<String>?): Int = postgrest.rpc(
        "mark_notifications_read", buildJsonObject {
            ids?.let { putJsonArray("p_ids") { it.forEach { id -> add(JsonPrimitive(id)) } } }
        }
    ).decodeAs<Int>()

    open suspend fun open(id: String): NotificationTarget = postgrest.rpc(
        "open_my_notification", buildJsonObject { put("p_id", id) }
    ).decodeAs<NotificationTarget>()

    open suspend fun preferences(): NotificationPreferences =
        postgrest.rpc("get_my_notification_preferences").decodeAs<NotificationPreferences>()

    open suspend fun setPreference(category: NotificationCategory, enabled: Boolean): NotificationPreferences =
        postgrest.rpc("set_my_notification_preferences", buildJsonObject {
            put("p_category", category.wire)
            put("p_enabled", enabled)
        }).decodeAs<NotificationPreferences>()

    /**
     * Receive-only private channel `notifications:<userId>`. The broadcast collector is
     * registered before joining; every (re)join emits [NotificationSignal.Subscribed] so the
     * caller re-reads after the subscription gap. The channel is removed on cancellation.
     */
    open fun observe(userId: String): Flow<NotificationSignal> = channelFlow {
        val realtime = client.realtime
        val channel = realtime.channel("$TOPIC_PREFIX$userId") { isPrivate = true }
        try {
            launch(start = CoroutineStart.UNDISPATCHED) {
                channel.broadcastFlow<JsonObject>(EVENT).collect { send(NotificationSignal.Changed) }
            }
            launch(start = CoroutineStart.UNDISPATCHED) {
                var joined = false
                channel.status.collect { status ->
                    when (status) {
                        RealtimeChannel.Status.SUBSCRIBED -> if (!joined) {
                            joined = true
                            send(NotificationSignal.Subscribed)
                        }
                        RealtimeChannel.Status.UNSUBSCRIBED -> if (joined) {
                            joined = false
                            send(NotificationSignal.Disconnected)
                        }
                        else -> Unit
                    }
                }
            }
            yield() // let the broadcast callback register before the join request goes out
            channel.subscribe(blockUntilSubscribed = false)
            awaitCancellation()
        } finally {
            // Removal must survive cancellation so an old session never keeps a live channel.
            withContext(NonCancellable) { runCatching { realtime.removeChannel(channel) } }
        }
    }

    companion object {
        const val TOPIC_PREFIX = "notifications:"
        const val EVENT = "notifications_changed"
        private const val MISSING_FUNCTION = "PGRST202"
    }
}
