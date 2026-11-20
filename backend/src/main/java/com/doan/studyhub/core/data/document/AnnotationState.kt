package com.doan.studyhub.core.data.document

import com.doan.studyhub.core.data.model.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class PendingStatus {
    /** Waiting for its turn or for connectivity; shown as Unsynced/Saving. */
    @SerialName("queued") QUEUED,
    /** Server reported a CAS conflict; the draft is kept until the user decides. */
    @SerialName("conflict") CONFLICT
}

/** A local, not-yet-acknowledged mutation. Persisted per account/version and replayed with the same IDs. */
@Serializable
data class PendingAnnotationOp(
    val mutation: AnnotationMutation,
    val status: PendingStatus = PendingStatus.QUEUED,
    /** Shape before this op, used for display of restores and for undo after acknowledgement. */
    @SerialName("previous_data") val previousData: kotlinx.serialization.json.JsonObject? = null,
    @SerialName("created_at") val createdAt: Long,
    /** Undo ops are dropped on conflict instead of keeping a draft: they never overwrite newer work. */
    val undo: Boolean = false
)

data class DisplayAnnotation(
    val id: String,
    val pageIndex: Int,
    val shape: AnnotationShape,
    val authorId: String,
    val authorName: String,
    /** Committed row version, or null while a create is not acknowledged. */
    val rowVersion: Int?,
    val pending: Boolean,
    val conflict: Boolean
)

/**
 * Committed annotation state of one document version, merged from a snapshot, ordered
 * deltas and mutation ACKs. Every server row carries its revision; a row only replaces an
 * older one, so duplicate, late or reordered delivery never loses or duplicates marks.
 */
class AnnotationState(val versionId: String) {
    private val rows = LinkedHashMap<String, DocumentAnnotation>()
    private val lastShapes = HashMap<String, AnnotationShape>()

    /** Highest revision known to be completely applied (no gaps below it). */
    var revision: Long = 0L
        private set

    fun reset(items: List<DocumentAnnotation>, atRevision: Long) {
        rows.clear()
        items.forEach { apply(it) }
        revision = atRevision
    }

    /** Applies a server row if it is newer; returns true when the visible state changed. */
    fun apply(annotation: DocumentAnnotation): Boolean {
        if (annotation.versionId != versionId) return false
        val current = rows[annotation.id]
        if (current != null && current.revision >= annotation.revision) return false
        rows[annotation.id] = annotation
        annotation.shape?.let { lastShapes[annotation.id] = it }
        return true
    }

    /** Marks a completed catch-up: every revision up to [atRevision] has been applied. */
    fun advanceTo(atRevision: Long) { if (atRevision > revision) revision = atRevision }

    fun committed(id: String): DocumentAnnotation? = rows[id]
    fun lastShape(id: String): AnnotationShape? = lastShapes[id]
    val activeCount: Int get() = rows.values.count { !it.deleted }

    fun visible(pending: List<PendingAnnotationOp>, me: AnnotationAuthor): List<DisplayAnnotation> {
        val result = LinkedHashMap<String, DisplayAnnotation>()
        rows.values.filter { !it.deleted }.forEach { a ->
            a.shape?.let { result[a.id] = DisplayAnnotation(a.id, a.pageIndex, it, a.author.id, a.author.displayName, a.rowVersion, false, false) }
        }
        for (op in pending) {
            val m = op.mutation
            if (m.versionId != versionId) continue
            val conflict = op.status == PendingStatus.CONFLICT
            val base = rows[m.annotationId]
            when (m.action) {
                AnnotationAction.CREATE -> {
                    val shape = m.kind?.let { k -> m.data?.let { AnnotationShape.parse(k, it) } } ?: continue
                    result[m.annotationId] = DisplayAnnotation(m.annotationId, m.pageIndex ?: 0, shape, me.id, me.displayName,
                        null, true, conflict)
                }
                AnnotationAction.UPDATE -> {
                    val kind = m.kind ?: base?.kind ?: continue
                    val shape = m.data?.let { AnnotationShape.parse(kind, it) } ?: continue
                    val existing = result[m.annotationId]
                    result[m.annotationId] = DisplayAnnotation(m.annotationId, existing?.pageIndex ?: base?.pageIndex ?: continue,
                        shape, existing?.authorId ?: base?.author?.id ?: me.id,
                        existing?.authorName ?: base?.author?.displayName ?: me.displayName, base?.rowVersion, true, conflict)
                }
                AnnotationAction.DELETE -> if (!conflict) result.remove(m.annotationId)
                AnnotationAction.RESTORE -> {
                    val shape = lastShapes[m.annotationId]
                        ?: op.previousData?.let { d -> base?.kind?.let { AnnotationShape.parse(it, d) } } ?: continue
                    base ?: continue
                    result[m.annotationId] = DisplayAnnotation(m.annotationId, base.pageIndex, shape, base.author.id,
                        base.author.displayName, base.rowVersion, true, conflict)
                }
            }
        }
        return result.values.toList()
    }
}
