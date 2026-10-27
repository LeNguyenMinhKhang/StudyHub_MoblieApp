package com.doan.studyhub.core.data.remote

import com.doan.studyhub.core.data.SupabaseClient
import com.doan.studyhub.core.data.model.*
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

open class FriendshipRemoteDataSource {
    private val postgrest get() = SupabaseClient.client.postgrest

    open suspend fun state(targetId: String): FriendshipSnapshot = postgrest.rpc(
        "get_friendship_state", buildJsonObject { put("p_target_id", targetId) }
    ).decodeAs<FriendshipSnapshot>()

    open suspend fun list(kind: FriendshipListKind, cursor: FriendshipCursor?, limit: Int): FriendshipPage = postgrest.rpc(
        "list_my_friendships", buildJsonObject {
            put("p_kind", kind.wire)
            put("p_limit", limit.coerceIn(1, 50))
            cursor?.let { put("p_cursor_created_at", it.createdAt); put("p_cursor_id", it.id) }
        }
    ).decodeAs<FriendshipPage>()

    open suspend fun mutate(action: FriendshipAction, id: String): FriendshipSnapshot = postgrest.rpc(
        action.rpc, buildJsonObject { put(if (action == FriendshipAction.SEND) "p_target_id" else "p_friendship_id", id) }
    ).decodeAs<FriendshipSnapshot>()
}
