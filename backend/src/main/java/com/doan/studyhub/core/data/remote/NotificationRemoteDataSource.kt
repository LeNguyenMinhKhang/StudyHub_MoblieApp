package com.doan.studyhub.core.data.remote

import com.doan.studyhub.core.data.SupabaseClient
import com.doan.studyhub.core.data.model.NotificationCursor
import com.doan.studyhub.core.data.model.NotificationPage
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

open class NotificationRemoteDataSource {
    private val postgrest get() = SupabaseClient.client.postgrest

    open suspend fun list(cursor: NotificationCursor?, limit: Int): NotificationPage = postgrest.rpc(
        "list_my_notifications", buildJsonObject {
            put("p_limit", limit.coerceIn(1, 50))
            cursor?.let { put("p_cursor_created_at", it.createdAt); put("p_cursor_id", it.id) }
        }
    ).decodeAs<NotificationPage>()

    /** Null ids marks every notification of the current user as read. */
    open suspend fun markRead(ids: List<String>?): Int = postgrest.rpc(
        "mark_notifications_read", buildJsonObject {
            ids?.let { putJsonArray("p_ids") { it.forEach { id -> add(JsonPrimitive(id)) } } }
        }
    ).decodeAs<Int>()
}
