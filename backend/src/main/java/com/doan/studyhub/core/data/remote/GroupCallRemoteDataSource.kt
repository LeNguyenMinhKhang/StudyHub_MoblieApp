package com.doan.studyhub.core.data.remote

import com.doan.studyhub.core.data.BuildConfig
import com.doan.studyhub.core.data.SupabaseClient
import com.doan.studyhub.core.data.model.*
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.*

/** Error from the group-call Edge Function: a stable domain/provider code, never SQL or provider text. */
class CallFunctionException(val status: Int, val code: String) : Exception(code)

open class GroupCallRemoteDataSource {
    private val json = Json { ignoreUnknownKeys = true }

    open suspend fun current(groupId: String): GroupCallState =
        SupabaseClient.client.postgrest.rpc("get_study_group_call", buildJsonObject { put("p_group_id", groupId) })
            .decodeAs()

    open suspend fun startOrJoin(groupId: String, requestId: String, clientSessionId: String, mode: CallMode): CallJoin =
        invoke(buildJsonObject {
            put("action", "start_or_join"); put("group_id", groupId); put("request_id", requestId)
            put("client_session_id", clientSessionId); put("mode", mode.wire)
        })

    open suspend fun leave(callId: String): CallLeaveResult =
        invoke(buildJsonObject { put("action", "leave"); put("call_id", callId) })

    open suspend fun end(callId: String): GroupCallResult =
        invoke(buildJsonObject { put("action", "end"); put("call_id", callId) })

    private suspend inline fun <reified T> invoke(body: JsonObject): T {
        val token = SupabaseClient.client.auth.currentSessionOrNull()?.accessToken
            ?: throw CallFunctionException(401, "G0002")
        val response = http.post("${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/group-call") {
            header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        val text = response.bodyAsText()
        if (response.status.isSuccess()) return json.decodeFromString(text)
        val code = runCatching { json.parseToJsonElement(text).jsonObject["error"]?.jsonObject?.get("code")?.jsonPrimitive?.contentOrNull }
            .getOrNull()
        throw CallFunctionException(response.status.value, code ?: "server_error")
    }

    private companion object {
        val http by lazy {
            HttpClient(OkHttp) { install(HttpTimeout) { requestTimeoutMillis = 30_000; connectTimeoutMillis = 15_000 } }
        }
    }
}
