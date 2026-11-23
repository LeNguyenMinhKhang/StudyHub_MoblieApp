
package com.doan.studyhub.core.data.remote

import com.doan.studyhub.core.data.SupabaseClient
import com.doan.studyhub.core.data.model.AdminUser
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

open class AdminUserRemoteDataSource {

    private val postgrest
        get() = SupabaseClient.client.postgrest

    /**
     * Lấy danh sách người dùng từ RPC admin_list_users.
     */
    open suspend fun listUsers(
        search: String? = null,
        role: String? = null,
        limit: Int = 50,
        offset: Int = 0
    ): List<AdminUser> =
        postgrest.rpc(
            "admin_list_users",
            buildJsonObject {
                put("p_search", search)
                put("p_role", role)
                put("p_limit", limit.coerceIn(1, 100))
                put("p_offset", offset.coerceAtLeast(0))
            }
        ).decodeList<AdminUser>()

    /**
     * Ban một người dùng role user.
     */
    open suspend fun banUser(userId: String) {
        postgrest.rpc(
            "admin_ban_user",
            buildJsonObject {
                put("p_user_id", userId)
            }
        )
    }

    /**
     * Gỡ ban một người dùng role user.
     */
    open suspend fun unbanUser(userId: String) {
        postgrest.rpc(
            "admin_unban_user",
            buildJsonObject {
                put("p_user_id", userId)
            }
        )
    }
}
