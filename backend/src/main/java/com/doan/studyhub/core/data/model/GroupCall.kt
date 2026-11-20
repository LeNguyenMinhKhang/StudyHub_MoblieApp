package com.doan.studyhub.core.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Wire contract of supabase/migrations/20261118090000_group_calls.sql (projection) and the
// group-call Edge Function (connection). No provider room name, SID or secret is ever exposed.

@Serializable
enum class CallStatus {
    @SerialName("starting") STARTING, @SerialName("active") ACTIVE, @SerialName("ending") ENDING,
    @SerialName("ended") ENDED, @SerialName("failed") FAILED
}

@Serializable
enum class CallMode(val wire: String) { @SerialName("audio") AUDIO("audio"), @SerialName("video") VIDEO("video") }

@Serializable
data class CallPeer(val id: String, @SerialName("display_name") val displayName: String,
    @SerialName("avatar_url") val avatarUrl: String? = null)

@Serializable
data class GroupCall(
    val id: String,
    @SerialName("group_id") val groupId: String,
    val status: CallStatus,
    @SerialName("initial_mode") val initialMode: CallMode,
    @SerialName("started_by") val startedBy: CallPeer,
    @SerialName("created_at") val createdAt: String,
    @SerialName("activated_at") val activatedAt: String? = null,
    @SerialName("ended_at") val endedAt: String? = null,
    @SerialName("participant_count") val participantCount: Int,
    val capacity: Int,
    /** Current owner, or the initiator while still a member. */
    @SerialName("can_end") val canEnd: Boolean,
    /** Ending: new joins are refused until the provider confirmed cleanup. */
    @SerialName("cleanup_pending") val cleanupPending: Boolean,
    /** The caller's own live admission ("reserved"/"connected"), if any. */
    @SerialName("my_status") val myStatus: String? = null
) {
    val joinable get() = status == CallStatus.STARTING || status == CallStatus.ACTIVE
}

@Serializable
data class GroupCallState(val call: GroupCall? = null)

@Serializable
data class GroupCallResult(val call: GroupCall)

@Serializable
data class CallLeaveResult(@SerialName("call_id") val callId: String, val status: String)

/**
 * Short-lived media credentials for one room/identity. Kept in memory only; [toString] redacts
 * the token so it cannot reach logs or crash reports.
 */
@Serializable
data class CallConnection(
    val url: String,
    val token: String,
    val identity: String,
    @SerialName("expires_at") val expiresAt: String
) {
    override fun toString() = "CallConnection(url=$url, identity=$identity, expiresAt=$expiresAt, token=<redacted>)"
}

@Serializable
data class CallJoin(val call: GroupCall, val connection: CallConnection)
