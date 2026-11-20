package com.doan.studyhub.core.data.repository

import com.doan.studyhub.common.*
import com.doan.studyhub.core.data.SupabaseClient
import com.doan.studyhub.core.data.model.*
import com.doan.studyhub.core.data.remote.GroupRemoteDataSource
import io.github.jan.supabase.auth.auth
import io.ktor.util.decodeBase64String
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.*
import java.util.UUID

open class GroupRepository(
    private val remote: GroupRemoteDataSource = GroupRemoteDataSource(),
    private val api: SafeApiCall = SafeApiCall(),
    private val session: () -> String? = { SupabaseClient.client.auth.currentUserOrNull()?.id },
    private val sessionKey: () -> String? = { groupSessionId(SupabaseClient.client.auth.currentSessionOrNull()?.accessToken) }
) {
    open fun currentUserId(): String? = session()
    open fun currentSessionKey(): String? = sessionKey()
    open fun sessionChanges(): Flow<String?> = SupabaseClient.client.auth.sessionStatus
        .map { currentUserId() to currentSessionKey() }.distinctUntilChanged().map { it.first }

    open suspend fun groups(cursor: GroupCursor? = null, limit: Int = 20) = read {
        page(cursor, limit); remote.groups(cursor, limit)
    }
    open suspend fun detail(id: String) = read { remote.detail(uuid(id)) }
    open suspend fun members(id: String, cursor: GroupCursor? = null, limit: Int = 20) = read {
        page(cursor, limit); remote.members(uuid(id), cursor, limit)
    }
    open suspend fun incoming(cursor: GroupCursor? = null, limit: Int = 20) = read {
        page(cursor, limit); remote.incoming(cursor, limit)
    }
    open suspend fun invitations(id: String, cursor: GroupCursor? = null, limit: Int = 20) = read {
        page(cursor, limit); remote.invitations(uuid(id), cursor, limit)
    }
    open suspend fun create(request: CreateGroupRequest) = mutate {
        val name = request.name.trim()
        val description = request.description?.trim()?.ifEmpty { null }
        validateText(name, description)
        remote.create(request.copy(requestId = uuid(request.requestId), subjectId = uuid(request.subjectId), name = name, description = description))
    }
    open suspend fun update(id: String, name: String, description: String?) = mutate {
        val cleanName = name.trim()
        val cleanDescription = description?.trim()?.ifEmpty { null }
        validateText(cleanName, cleanDescription)
        remote.update(uuid(id), cleanName, cleanDescription)
    }
    open suspend fun invite(id: String, target: String) = mutate { remote.invite(uuid(id), uuid(target)) }
    open suspend fun respond(id: String, action: GroupResponse) = mutate { remote.respond(uuid(id), action) }
    open suspend fun revoke(id: String) = mutate { remote.revoke(uuid(id)) }
    open suspend fun leave(id: String) = mutate { remote.leave(uuid(id)) }
    open suspend fun remove(id: String, target: String) = mutate { remote.remove(uuid(id), uuid(target)) }
    open suspend fun transfer(id: String, target: String) = mutate { remote.transfer(uuid(id), uuid(target)) }
    open suspend fun archive(id: String) = mutate { remote.archive(uuid(id)) }

    private suspend fun <T> read(block: suspend () -> T) = call(Operation.GROUP_READ, block)
    private suspend fun <T> mutate(block: suspend () -> T) = call(Operation.GROUP_MUTATE, block)
    private suspend fun <T> call(operation: Operation, block: suspend () -> T): ApiResponse<T> = api.execute(operation) {
        val actor = currentUserId() ?: throw DomainException(ErrorType.UNAUTHENTICATED)
        val key = currentSessionKey()
        try {
            val result = block()
            if (currentUserId() != actor || currentSessionKey() != key) throw DomainException(ErrorType.UNAUTHENTICATED)
            result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (currentUserId() != actor || currentSessionKey() != key) throw DomainException(ErrorType.UNAUTHENTICATED)
            throw error
        }
    }

    private fun uuid(value: String): String {
        val parsed = try { UUID.fromString(value).toString() } catch (_: IllegalArgumentException) { invalid() }
        if (!parsed.equals(value, ignoreCase = true)) invalid()
        return parsed
    }
    private fun page(cursor: GroupCursor?, limit: Int) {
        if (limit !in 1..50) invalid()
        cursor?.let {
            uuid(it.id)
            if (it.at.length !in 20..64 || !it.at.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:\\d{2})"))) invalid()
        }
    }
    private fun validateText(name: String, description: String?) {
        if (name.codePointCount(0, name.length) !in 1..100 ||
            (description?.codePointCount(0, description.length) ?: 0) > 1000) invalid()
    }
    private fun invalid(): Nothing = throw DomainException(ErrorType.VALIDATION_FAILED)
}

// Used only to fence local UI results, never for authorization or JWT verification.
internal fun groupSessionId(token: String?): String? = try {
    token?.split('.')?.takeIf { it.size == 3 }?.get(1)?.let { encoded ->
        val base64 = encoded.replace('-', '+').replace('_', '/').padEnd((encoded.length + 3) / 4 * 4, '=')
        Json.parseToJsonElement(base64.decodeBase64String()).jsonObject["session_id"]?.jsonPrimitive?.contentOrNull
    }
} catch (_: Exception) { null }
