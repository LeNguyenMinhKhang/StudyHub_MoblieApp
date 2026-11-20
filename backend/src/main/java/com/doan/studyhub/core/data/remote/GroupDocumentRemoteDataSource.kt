package com.doan.studyhub.core.data.remote

import com.doan.studyhub.core.data.BuildConfig
import com.doan.studyhub.core.data.SupabaseClient
import com.doan.studyhub.core.data.model.*
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.storage.storage
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.json.*

/** Error returned by the finalize Edge Function: a stable SQLSTATE/domain code, never SQL text. */
class DocumentFunctionException(val status: Int, val code: String, val reason: String?) : Exception(code)

/** Storage failure reduced to its HTTP status; provider text is dropped. */
class DocumentStorageException(val status: Int) : Exception("storage $status")

open class GroupDocumentRemoteDataSource {
    private val client get() = SupabaseClient.client
    private val postgrest get() = client.postgrest
    private val json = Json { ignoreUnknownKeys = true }

    private fun page(cursor: GroupCursor?, limit: Int) = buildJsonObject {
        put("p_limit", limit)
        cursor?.let { put("p_cursor", Json.encodeToJsonElement(it)) }
    }

    open suspend fun documents(groupId: String, cursor: GroupCursor?, limit: Int): GroupPage<GroupDocument> =
        postgrest.rpc("list_group_documents", buildJsonObject {
            put("p_group_id", groupId); page(cursor, limit).forEach { (k, v) -> put(k, v) }
        }).decodeAs()

    open suspend fun document(id: String): GroupDocument =
        postgrest.rpc("get_group_document", buildJsonObject { put("p_document_id", id) }).decodeAs()

    open suspend fun versions(documentId: String, cursor: GroupCursor?, limit: Int): GroupPage<DocumentVersionSummary> =
        postgrest.rpc("list_group_document_versions", buildJsonObject {
            put("p_document_id", documentId); page(cursor, limit).forEach { (k, v) -> put(k, v) }
        }).decodeAs()

    open suspend fun version(id: String): DocumentVersion =
        postgrest.rpc("get_group_document_version", buildJsonObject { put("p_version_id", id) }).decodeAs()

    open suspend fun archive(documentId: String): DocumentArchiveResult =
        postgrest.rpc("archive_group_document", buildJsonObject { put("p_document_id", documentId) }).decodeAs()

    open suspend fun reserve(requestId: String, groupId: String, title: String?, sizeBytes: Long, documentId: String?): DocumentUpload =
        postgrest.rpc("reserve_group_document_upload", buildJsonObject {
            put("p_request_id", requestId); put("p_group_id", groupId); put("p_title", title)
            put("p_size_bytes", sizeBytes); put("p_document_id", documentId)
        }).decodeAs()

    open suspend fun uploadStatus(uploadId: String): DocumentUpload =
        postgrest.rpc("get_group_document_upload", buildJsonObject { put("p_upload_id", uploadId) }).decodeAs()

    /**
     * Uploads into the reserved private key. Never upserts, so a ready object cannot be
     * overwritten. A duplicate means an earlier attempt already stored this reservation's bytes.
     */
    open suspend fun uploadObject(upload: DocumentUpload, bytes: ByteArray) {
        try {
            client.storage.from(upload.bucket).upload(upload.objectKey, bytes) {
                upsert = false
                contentType = ContentType.Application.Pdf
            }
        } catch (error: RestException) {
            if (error.statusCode == 409 || error.error.contains("Duplicate", ignoreCase = true)) return
            throw DocumentStorageException(error.statusCode)
        }
    }

    /** Authenticated download; Storage RLS checks current membership on every request. */
    open suspend fun download(version: DocumentVersion): ByteArray = try {
        client.storage.from(version.bucket).downloadAuthenticated(version.objectKey)
    } catch (error: RestException) {
        throw DocumentStorageException(error.statusCode)
    }

    open suspend fun finalize(uploadId: String): DocumentUpload {
        val token = client.auth.currentSessionOrNull()?.accessToken
            ?: throw DocumentFunctionException(401, "G0002", null)
        val response = http.post("${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/group-document-finalize") {
            header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("upload_id", uploadId) }.toString())
        }
        val body = response.bodyAsText()
        if (response.status.isSuccess()) return json.decodeFromString(body)
        val error = runCatching { json.parseToJsonElement(body).jsonObject["error"]?.jsonObject }.getOrNull()
        throw DocumentFunctionException(response.status.value,
            error?.get("code")?.jsonPrimitive?.contentOrNull ?: "server_error",
            error?.get("reason")?.jsonPrimitive?.contentOrNull)
    }

    open suspend fun mutate(mutation: AnnotationMutation): AnnotationMutationResult =
        postgrest.rpc("mutate_group_document_annotation", buildJsonObject {
            put("p_op_id", mutation.opId); put("p_version_id", mutation.versionId)
            put("p_annotation_id", mutation.annotationId); put("p_action", mutation.action.wire)
            put("p_expected_row_version", mutation.expectedRowVersion); put("p_page_index", mutation.pageIndex)
            put("p_kind", mutation.kind?.wire); put("p_data", mutation.data ?: JsonNull)
        }).decodeAs()

    open suspend fun snapshot(versionId: String, purpose: SnapshotPurpose): DocumentSnapshot =
        postgrest.rpc("create_group_document_snapshot", buildJsonObject {
            put("p_version_id", versionId); put("p_purpose", purpose.wire)
        }).decodeAs()

    open suspend fun snapshotPage(snapshotId: String, after: String?, limit: Int): DocumentSnapshotPage =
        postgrest.rpc("list_group_document_snapshot_annotations", buildJsonObject {
            put("p_snapshot_id", snapshotId); put("p_after", after); put("p_limit", limit)
        }).decodeAs()

    open suspend fun changes(versionId: String, since: Long, limit: Int): DocumentChangesPage =
        postgrest.rpc("list_group_document_changes", buildJsonObject {
            put("p_version_id", versionId); put("p_since", since); put("p_limit", limit)
        }).decodeAs()

    open suspend fun revision(versionId: String): DocumentRevision =
        postgrest.rpc("get_group_document_revision", buildJsonObject { put("p_version_id", versionId) }).decodeAs()

    open suspend fun guidance(callId: String): GuidanceState =
        postgrest.rpc("get_group_document_guidance", buildJsonObject { put("p_call_id", callId) }).decodeAs()

    open suspend fun claimGuidance(callId: String, versionId: String, pageIndex: Int): GuidanceState =
        postgrest.rpc("claim_group_document_guidance", buildJsonObject {
            put("p_call_id", callId); put("p_version_id", versionId); put("p_page_index", pageIndex)
        }).decodeAs()

    open suspend fun updateGuidance(callId: String, generation: Long, versionId: String, pageIndex: Int): GuidanceState =
        postgrest.rpc("update_group_document_guidance", buildJsonObject {
            put("p_call_id", callId); put("p_generation", generation)
            put("p_version_id", versionId); put("p_page_index", pageIndex)
        }).decodeAs()

    open suspend fun releaseGuidance(callId: String, generation: Long?): GuidanceRelease =
        postgrest.rpc("release_group_document_guidance", buildJsonObject {
            put("p_call_id", callId); put("p_generation", generation)
        }).decodeAs()

    open suspend fun channel(groupId: String): DocumentChannel =
        postgrest.rpc("get_group_document_channel", buildJsonObject { put("p_group_id", groupId) }).decodeAs()

    /**
     * Receive-only private channel. Every (re)join emits [DocumentSignal.Subscribed] so the
     * caller catches up through RPCs; payloads carry only scope/version/revision.
     */
    open fun observe(topic: String): Flow<DocumentSignal> = channelFlow {
        val realtime = client.realtime
        val channel = realtime.channel(topic) { isPrivate = true }
        try {
            launch(start = CoroutineStart.UNDISPATCHED) {
                channel.broadcastFlow<JsonObject>(EVENT).collect { payload ->
                    val scope = payload["scope"]?.jsonPrimitive?.contentOrNull
                    val version = payload["version_id"]?.jsonPrimitive?.contentOrNull
                    val revision = payload["revision"]?.jsonPrimitive?.longOrNull
                    val call = payload["call_id"]?.jsonPrimitive?.contentOrNull
                    send(when {
                        scope == "annotations" && version != null && revision != null ->
                            DocumentSignal.AnnotationsChanged(version, revision)
                        scope == "guidance" && call != null -> DocumentSignal.GuidanceChanged(call)
                        else -> DocumentSignal.LibraryChanged
                    })
                }
            }
            launch(start = CoroutineStart.UNDISPATCHED) {
                var joined = false
                channel.status.collect { status ->
                    when (status) {
                        RealtimeChannel.Status.SUBSCRIBED -> if (!joined) { joined = true; send(DocumentSignal.Subscribed) }
                        RealtimeChannel.Status.UNSUBSCRIBED -> if (joined) { joined = false; send(DocumentSignal.Disconnected) }
                        else -> Unit
                    }
                }
            }
            yield()
            channel.subscribe(blockUntilSubscribed = false)
            awaitCancellation()
        } finally {
            withContext(NonCancellable) { runCatching { realtime.removeChannel(channel) } }
        }
    }

    companion object {
        const val EVENT = "documents_changed"
        private val http by lazy {
            HttpClient(OkHttp) { install(HttpTimeout) { requestTimeoutMillis = 120_000; connectTimeoutMillis = 15_000 } }
        }
    }
}
