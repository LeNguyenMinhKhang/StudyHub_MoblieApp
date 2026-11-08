package com.doan.studyhub.core.data.remote

import com.doan.studyhub.core.data.SupabaseClient
import com.doan.studyhub.core.data.model.BlockCursor
import com.doan.studyhub.core.data.model.BlockSnapshot
import com.doan.studyhub.core.data.model.BlockedUsersPage
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

open class BlockRemoteDataSource {

    private val postgrest
        get() = SupabaseClient.client.postgrest

    /**
     * Kiểm tra trạng thái Block giữa người dùng hiện tại
     * và người dùng được truyền vào.
     */
    open suspend fun state(targetId: String): BlockSnapshot =
        postgrest.rpc(
            "get_block_state",
            buildJsonObject {
                put("p_target_id", targetId)
            }
        ).decodeAs<BlockSnapshot>()

    /**
     * Chặn một người dùng.
     * RPC sẽ tự động xóa quan hệ Friendship nếu tồn tại.
     */
    open suspend fun block(targetId: String): BlockSnapshot =
        postgrest.rpc(
            "block_user",
            buildJsonObject {
                put("p_target_id", targetId)
            }
        ).decodeAs<BlockSnapshot>()

    /**
     * Bỏ chặn một người dùng.
     */
    open suspend fun unblock(targetId: String): BlockSnapshot =
        postgrest.rpc(
            "unblock_user",
            buildJsonObject {
                put("p_target_id", targetId)
            }
        ).decodeAs<BlockSnapshot>()

    /**
     * Lấy danh sách người mà tài khoản hiện tại đã chặn.
     */
    open suspend fun list(
        cursor: BlockCursor? = null,
        limit: Int = 20
    ): BlockedUsersPage =
        postgrest.rpc(
            "list_blocked_users",
            buildJsonObject {
                put("p_limit", limit.coerceIn(1, 50))

                cursor?.let {
                    put("p_cursor_created_at", it.createdAt)
                    put("p_cursor_id", it.id)
                }
            }
        ).decodeAs<BlockedUsersPage>()
}