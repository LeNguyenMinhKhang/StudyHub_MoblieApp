package com.doan.studyhub.core.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.round

// Wire contract of supabase/migrations/20261117090000_group_documents.sql.

@Serializable
data class DocumentPeer(val id: String, @SerialName("display_name") val displayName: String,
    @SerialName("avatar_url") val avatarUrl: String? = null)

@Serializable
data class DocumentVersionSummary(
    val id: String,
    @SerialName("document_id") val documentId: String,
    @SerialName("version_number") val versionNumber: Int,
    @SerialName("page_count") val pageCount: Int,
    @SerialName("size_bytes") val sizeBytes: Long,
    @SerialName("uploaded_by") val uploadedBy: DocumentPeer,
    @SerialName("is_current") val isCurrent: Boolean,
    @SerialName("created_at") val createdAt: String
)

@Serializable
data class GroupDocument(
    val id: String,
    @SerialName("group_id") val groupId: String,
    val title: String,
    @SerialName("uploaded_by") val uploadedBy: DocumentPeer,
    @SerialName("can_manage") val canManage: Boolean,
    @SerialName("current_version") val currentVersion: DocumentVersionSummary,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String
)

/** Rendered page size in PDF points (crop box after /Rotate), as derived by the server. */
@Serializable
data class PageSize(val w: Double, val h: Double)

@Serializable
data class DocumentVersion(
    val id: String,
    @SerialName("document_id") val documentId: String,
    @SerialName("group_id") val groupId: String,
    val title: String,
    @SerialName("version_number") val versionNumber: Int,
    @SerialName("page_count") val pageCount: Int,
    @SerialName("size_bytes") val sizeBytes: Long,
    @SerialName("uploaded_by") val uploadedBy: DocumentPeer,
    @SerialName("is_current") val isCurrent: Boolean,
    @SerialName("created_at") val createdAt: String,
    val bucket: String,
    @SerialName("object_key") val objectKey: String,
    @SerialName("content_hash") val contentHash: String,
    val pages: List<PageSize>,
    val revision: Long,
    @SerialName("active_annotations") val activeAnnotations: Int,
    @SerialName("current_version_id") val currentVersionId: String,
    @SerialName("can_manage") val canManage: Boolean
)

@Serializable
enum class UploadStatus {
    @SerialName("pending") PENDING, @SerialName("ready") READY,
    @SerialName("failed") FAILED, @SerialName("expired") EXPIRED
}

@Serializable
data class DocumentUpload(
    val id: String,
    @SerialName("group_id") val groupId: String,
    @SerialName("document_id") val documentId: String? = null,
    @SerialName("version_id") val versionId: String? = null,
    val bucket: String,
    @SerialName("object_key") val objectKey: String,
    val status: UploadStatus,
    @SerialName("failure_reason") val failureReason: String? = null,
    @SerialName("expires_at") val expiresAt: String
)

@Serializable
enum class AnnotationKind(val wire: String) {
    @SerialName("ink") INK("ink"), @SerialName("highlight") HIGHLIGHT("highlight"), @SerialName("note") NOTE("note")
}

@Serializable
data class AnnotationAuthor(val id: String, @SerialName("display_name") val displayName: String)

@Serializable
data class DocumentAnnotation(
    val id: String,
    @SerialName("version_id") val versionId: String,
    @SerialName("page_index") val pageIndex: Int,
    val kind: AnnotationKind,
    /** Null for tombstones; never rendered or exported when [deleted]. */
    val data: JsonObject? = null,
    val author: AnnotationAuthor,
    @SerialName("row_version") val rowVersion: Int,
    val revision: Long,
    val deleted: Boolean,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String
) {
    val shape: AnnotationShape? get() = data?.let { AnnotationShape.parse(kind, it) }
}

@Serializable
enum class AnnotationAction(val wire: String) {
    @SerialName("create") CREATE("create"), @SerialName("update") UPDATE("update"),
    @SerialName("delete") DELETE("delete"), @SerialName("restore") RESTORE("restore")
}

/** One idempotent mutation; persisted verbatim in the pending journal and replayed with the same IDs. */
@Serializable
data class AnnotationMutation(
    @SerialName("op_id") val opId: String,
    @SerialName("version_id") val versionId: String,
    @SerialName("annotation_id") val annotationId: String,
    val action: AnnotationAction,
    @SerialName("expected_row_version") val expectedRowVersion: Int? = null,
    @SerialName("page_index") val pageIndex: Int? = null,
    val kind: AnnotationKind? = null,
    val data: JsonObject? = null
)

@Serializable
data class AnnotationMutationResult(
    @SerialName("op_id") val opId: String,
    val action: AnnotationAction,
    val revision: Long,
    val annotation: DocumentAnnotation
)

@Serializable
data class DocumentSnapshot(
    val id: String,
    @SerialName("version_id") val versionId: String,
    val revision: Long,
    @SerialName("content_hash") val contentHash: String,
    val purpose: String,
    @SerialName("expires_at") val expiresAt: String
)

@Serializable
data class DocumentSnapshotPage(
    @SerialName("snapshot_id") val snapshotId: String,
    @SerialName("version_id") val versionId: String,
    val revision: Long,
    val items: List<DocumentAnnotation>,
    @SerialName("next_after") val nextAfter: String? = null
)

@Serializable
data class DocumentChangesPage(
    @SerialName("version_id") val versionId: String,
    val revision: Long,
    val items: List<DocumentAnnotation>,
    @SerialName("next_since") val nextSince: Long? = null,
    @SerialName("reset_required") val resetRequired: Boolean
)

@Serializable
data class DocumentRevision(
    @SerialName("version_id") val versionId: String,
    val revision: Long,
    @SerialName("current_version_id") val currentVersionId: String
)

@Serializable
data class DocumentChannel(@SerialName("group_id") val groupId: String, val topic: String)

@Serializable
data class DocumentArchiveResult(@SerialName("document_id") val documentId: String, val status: String)

/**
 * Wire contract of supabase/migrations/20261119090000_group_document_guidance.sql.
 * Navigation only: it never grants or removes the right to annotate. [generation] fences the
 * guide's own updates; followers order reads by [sequence].
 */
@Serializable
data class DocumentGuidance(
    @SerialName("call_id") val callId: String,
    val guide: DocumentPeer,
    @SerialName("document_id") val documentId: String,
    @SerialName("version_id") val versionId: String,
    @SerialName("page_index") val pageIndex: Int,
    val generation: Long,
    val sequence: Long,
    @SerialName("lease_expires_at") val leaseExpiresAt: String,
    @SerialName("is_me") val isMe: Boolean
)

/** `guidance` is null when nobody guides (never claimed, released, lease expired or call closed). */
@Serializable
data class GuidanceState(val guidance: DocumentGuidance? = null)

@Serializable
data class GuidanceRelease(val released: Boolean)

object GuidanceLimits {
    /** Server lease. The guide renews it by heartbeat well inside this window. */
    const val LEASE_SECONDS = 30
    const val HEARTBEAT_SECONDS = 10
}

enum class SnapshotPurpose(val wire: String) { VIEW("view"), EXPORT("export") }

/** Invalidation-only realtime signal; content is always fetched through authorized RPCs. */
sealed interface DocumentSignal {
    data object Subscribed : DocumentSignal
    data object Disconnected : DocumentSignal
    data object LibraryChanged : DocumentSignal
    data class AnnotationsChanged(val versionId: String, val revision: Long) : DocumentSignal
    /** The guide of [callId] moved, claimed or released; re-read through get guidance. */
    data class GuidanceChanged(val callId: String) : DocumentSignal
}

/** Canonical point: normalized to the rendered page, origin top-left, both axes 0..1. */
@Serializable
data class PagePoint(val x: Double, val y: Double)

object AnnotationLimits {
    const val MAX_POINTS = 4096
    const val MAX_PAYLOAD_BYTES = 65536
    const val MAX_NOTE_CHARS = 2000
    const val MAX_ACTIVE = 10000
    const val MIN_WIDTH = 0.001
    const val MAX_WIDTH = 0.05
    val INK_COLORS = listOf("#111827", "#DC2626", "#2563EB", "#16A34A", "#F59E0B", "#9333EA")
    val HIGHLIGHT_COLORS = listOf("#FDE047", "#86EFAC", "#F9A8D4", "#93C5FD")
    val NOTE_COLORS = HIGHLIGHT_COLORS
}

sealed interface AnnotationShape {
    val kind: AnnotationKind
    fun toJson(): JsonObject

    data class Ink(val points: List<PagePoint>, val color: String, val width: Double) : AnnotationShape {
        override val kind get() = AnnotationKind.INK
        override fun toJson() = buildJsonObject {
            put("points", buildJsonArray { points.forEach { add(point(it)) } })
            put("color", color)
            put("width", round4(width))
        }
    }

    /** Axis-aligned region [left, top, right, bottom] in canonical coordinates. */
    data class Highlight(val left: Double, val top: Double, val right: Double, val bottom: Double, val color: String) : AnnotationShape {
        override val kind get() = AnnotationKind.HIGHLIGHT
        override fun toJson() = buildJsonObject {
            put("rect", buildJsonArray { listOf(left, top, right, bottom).forEach { add(JsonPrimitive(round4(it))) } })
            put("color", color)
        }
    }

    data class Note(val anchor: PagePoint, val text: String, val color: String) : AnnotationShape {
        override val kind get() = AnnotationKind.NOTE
        override fun toJson() = buildJsonObject {
            put("anchor", point(anchor))
            put("text", text)
            put("color", color)
        }
    }

    companion object {
        fun round4(value: Double): Double = round(value.coerceIn(0.0, 1.0) * 10000.0) / 10000.0
        private fun point(p: PagePoint) = buildJsonArray { add(JsonPrimitive(round4(p.x))); add(JsonPrimitive(round4(p.y))) }

        private fun JsonElement.pt(): PagePoint? = (this as? JsonArray)?.takeIf { it.size == 2 }?.let {
            val x = it[0].jsonPrimitive.doubleOrNull ?: return null
            val y = it[1].jsonPrimitive.doubleOrNull ?: return null
            PagePoint(x, y)
        }

        /** Lenient decode of server data; null when the payload cannot be rendered. */
        fun parse(kind: AnnotationKind, data: JsonObject): AnnotationShape? = runCatching {
            val color = data["color"]?.jsonPrimitive?.content ?: return null
            when (kind) {
                AnnotationKind.INK -> Ink(data["points"]!!.jsonArray.map { it.pt()!! }, color,
                    data["width"]!!.jsonPrimitive.doubleOrNull!!)
                AnnotationKind.HIGHLIGHT -> data["rect"]!!.jsonArray.map { it.jsonPrimitive.doubleOrNull!! }
                    .let { Highlight(it[0], it[1], it[2], it[3], color) }
                AnnotationKind.NOTE -> Note(data["anchor"]!!.pt()!!, data["text"]!!.jsonPrimitive.content, color)
            }
        }.getOrNull()

        /** Mirrors the server whitelist; returns false instead of letting the server reject. */
        fun isValid(shape: AnnotationShape): Boolean {
            fun inPage(v: Double) = !v.isNaN() && v in 0.0..1.0
            val structural = when (shape) {
                is Ink -> shape.points.size in 1..AnnotationLimits.MAX_POINTS && shape.color in AnnotationLimits.INK_COLORS &&
                    shape.width in AnnotationLimits.MIN_WIDTH..AnnotationLimits.MAX_WIDTH &&
                    shape.points.all { inPage(it.x) && inPage(it.y) }
                is Highlight -> shape.color in AnnotationLimits.HIGHLIGHT_COLORS &&
                    listOf(shape.left, shape.top, shape.right, shape.bottom).all(::inPage) &&
                    round4(shape.left) < round4(shape.right) && round4(shape.top) < round4(shape.bottom)
                is Note -> shape.color in AnnotationLimits.NOTE_COLORS && inPage(shape.anchor.x) && inPage(shape.anchor.y) &&
                    shape.text == shape.text.trim() &&
                    shape.text.codePointCount(0, shape.text.length) in 1..AnnotationLimits.MAX_NOTE_CHARS
            }
            return structural && jsonbTextBytes(shape.toJson()) <= AnnotationLimits.MAX_PAYLOAD_BYTES
        }

        /** UTF-8 size of PostgreSQL's jsonb text form (`{"k": v, ...}`, `[a, b]`), used for the 64 KiB limit. */
        fun jsonbTextBytes(element: JsonElement): Int = when (element) {
            is JsonObject -> 2 + element.entries.sumOf { (k, v) ->
                JsonPrimitive(k).toString().toByteArray(Charsets.UTF_8).size + 2 + jsonbTextBytes(v)
            } + 2 * (element.size - 1).coerceAtLeast(0)
            is JsonArray -> 2 + element.sumOf { jsonbTextBytes(it) } + 2 * (element.size - 1).coerceAtLeast(0)
            JsonNull -> 4
            is JsonPrimitive -> element.toString().toByteArray(Charsets.UTF_8).size
        }
    }
}

/**
 * Keeps a freehand stroke within 4,096 points and the 64 KiB payload by Ramer–Douglas–Peucker
 * simplification with a growing tolerance. End points are always preserved.
 */
object StrokeSimplifier {
    fun fit(points: List<PagePoint>, color: String, width: Double): AnnotationShape.Ink? {
        if (points.isEmpty()) return null
        val rounded = points.map { PagePoint(AnnotationShape.round4(it.x), AnnotationShape.round4(it.y)) }
            .fold(mutableListOf<PagePoint>()) { acc, p -> if (acc.lastOrNull() != p) acc.add(p); acc }
        var tolerance = 0.0
        var current: List<PagePoint> = rounded
        repeat(24) {
            val ink = AnnotationShape.Ink(current, color, width)
            if (current.size <= AnnotationLimits.MAX_POINTS &&
                AnnotationShape.jsonbTextBytes(ink.toJson()) <= AnnotationLimits.MAX_PAYLOAD_BYTES) return ink
            tolerance = if (tolerance == 0.0) 0.0002 else tolerance * 1.6
            current = simplify(rounded, tolerance)
        }
        return null
    }

    fun simplify(points: List<PagePoint>, tolerance: Double): List<PagePoint> {
        if (points.size < 3) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.lastIndex] = true
        val stack = ArrayDeque<Pair<Int, Int>>().apply { add(0 to points.lastIndex) }
        while (stack.isNotEmpty()) {
            val (start, end) = stack.removeLast()
            var maxDistance = -1.0
            var index = -1
            for (i in start + 1 until end) {
                val d = distance(points[i], points[start], points[end])
                if (d > maxDistance) { maxDistance = d; index = i }
            }
            if (index >= 0 && maxDistance > tolerance) {
                keep[index] = true
                stack.add(start to index)
                stack.add(index to end)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    private fun distance(p: PagePoint, a: PagePoint, b: PagePoint): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val length = dx * dx + dy * dy
        if (length == 0.0) return kotlin.math.hypot(p.x - a.x, p.y - a.y)
        return abs(dy * p.x - dx * p.y + b.x * a.y - b.y * a.x) / kotlin.math.sqrt(length)
    }
}
