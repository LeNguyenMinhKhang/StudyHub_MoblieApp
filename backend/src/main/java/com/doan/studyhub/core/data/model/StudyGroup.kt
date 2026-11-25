package com.doan.studyhub.core.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class GroupRole { @SerialName("owner") OWNER, @SerialName("member") MEMBER }

@Serializable
data class GroupSubject(val id: String, val name: String, @SerialName("is_active") val isActive: Boolean)

@Serializable
data class GroupSummary(
    val id: String,
    val name: String,
    val description: String? = null,
    val subject: GroupSubject? = null,
    val role: GroupRole,
    @SerialName("member_count") val memberCount: Int,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String
)

typealias GroupDetail = GroupSummary

@Serializable
data class GroupMember(
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val role: GroupRole,
    @SerialName("joined_at") val joinedAt: String
)

@Serializable
data class GroupPeer(val id: String, @SerialName("display_name") val displayName: String,
    @SerialName("avatar_url") val avatarUrl: String? = null)

@Serializable
data class GroupInvitation(
    val id: String, val status: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("group_id") val groupId: String,
    @SerialName("group_name") val groupName: String,
    val description: String? = null,
    val subject: GroupSubject? = null,
    val inviter: GroupPeer,
    val invitee: GroupPeer? = null
)

@Serializable
data class GroupCursor(val at: String, val id: String)

@Serializable
data class GroupPage<T>(val items: List<T>, @SerialName("next_cursor") val nextCursor: GroupCursor? = null)

@Serializable
data class GroupInvitationResult(val id: String, @SerialName("group_id") val groupId: String, val status: String)

@Serializable
data class GroupMutationResult(@SerialName("group_id") val groupId: String, val status: String)

/** [subjectId] is optional: a group may be created without a subject. */
data class CreateGroupRequest(val requestId: String, val subjectId: String?, val name: String, val description: String? = null)

enum class GroupResponse(val wire: String) { ACCEPT("accept"), DECLINE("decline") }
