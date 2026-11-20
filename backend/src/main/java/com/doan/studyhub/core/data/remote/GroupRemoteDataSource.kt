package com.doan.studyhub.core.data.remote

import com.doan.studyhub.core.data.SupabaseClient
import com.doan.studyhub.core.data.model.*
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.*

open class GroupRemoteDataSource {
    private val postgrest get() = SupabaseClient.client.postgrest

    private fun page(cursor: GroupCursor?, limit: Int, groupId: String? = null) = buildJsonObject {
        put("p_limit", limit)
        cursor?.let { put("p_cursor", Json.encodeToJsonElement(it)) }
        groupId?.let { put("p_group_id", it) }
    }

    open suspend fun groups(cursor: GroupCursor?, limit: Int): GroupPage<GroupSummary> =
        postgrest.rpc("list_my_study_groups", page(cursor, limit)).decodeAs()
    open suspend fun detail(id: String): GroupDetail =
        postgrest.rpc("get_study_group", buildJsonObject { put("p_group_id", id) }).decodeAs()
    open suspend fun create(request: CreateGroupRequest): GroupDetail =
        postgrest.rpc("create_study_group", buildJsonObject {
            put("p_request_id", request.requestId); put("p_subject_id", request.subjectId)
            put("p_name", request.name); put("p_description", request.description)
        }).decodeAs()
    open suspend fun update(id: String, name: String, description: String?): GroupDetail =
        postgrest.rpc("update_study_group", buildJsonObject {
            put("p_group_id", id); put("p_name", name); put("p_description", description)
        }).decodeAs()
    open suspend fun members(id: String, cursor: GroupCursor?, limit: Int): GroupPage<GroupMember> =
        postgrest.rpc("list_study_group_members", page(cursor, limit, id)).decodeAs()
    open suspend fun incoming(cursor: GroupCursor?, limit: Int): GroupPage<GroupInvitation> =
        postgrest.rpc("list_my_study_group_invitations", page(cursor, limit)).decodeAs()
    open suspend fun invitations(id: String, cursor: GroupCursor?, limit: Int): GroupPage<GroupInvitation> =
        postgrest.rpc("list_study_group_invitations", page(cursor, limit, id)).decodeAs()
    open suspend fun invite(id: String, target: String): GroupInvitationResult =
        postgrest.rpc("invite_study_group_member", buildJsonObject {
            put("p_group_id", id); put("p_target_id", target)
        }).decodeAs()
    open suspend fun respond(id: String, action: GroupResponse): GroupInvitationResult =
        postgrest.rpc("respond_study_group_invitation", buildJsonObject {
            put("p_invitation_id", id); put("p_action", action.wire)
        }).decodeAs()
    open suspend fun revoke(id: String): GroupInvitationResult =
        postgrest.rpc("revoke_study_group_invitation", buildJsonObject { put("p_invitation_id", id) }).decodeAs()
    open suspend fun leave(id: String): GroupMutationResult =
        postgrest.rpc("leave_study_group", buildJsonObject { put("p_group_id", id) }).decodeAs()
    open suspend fun remove(id: String, target: String): GroupMutationResult =
        postgrest.rpc("remove_study_group_member", buildJsonObject {
            put("p_group_id", id); put("p_target_id", target)
        }).decodeAs()
    open suspend fun transfer(id: String, target: String): GroupDetail =
        postgrest.rpc("transfer_study_group_ownership", buildJsonObject {
            put("p_group_id", id); put("p_target_id", target)
        }).decodeAs()
    open suspend fun archive(id: String): GroupMutationResult =
        postgrest.rpc("archive_study_group", buildJsonObject { put("p_group_id", id) }).decodeAs()
}
