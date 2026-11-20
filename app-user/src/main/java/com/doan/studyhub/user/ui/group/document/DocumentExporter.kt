package com.doan.studyhub.user.ui.group.document

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.provider.DocumentsContract
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.StyleSpan
import com.doan.studyhub.core.data.model.AnnotationShape
import com.doan.studyhub.core.data.model.DocumentAnnotation
import com.doan.studyhub.core.data.model.DocumentVersion
import com.doan.studyhub.user.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.DateFormat
import java.util.Date
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

/** A note with its export number, ordered by page then position, shared by pins and the appendix. */
data class NumberedNote(val number: Int, val pageIndex: Int, val author: String, val text: String)

internal fun numberNotes(annotations: List<DocumentAnnotation>): Pair<Map<String, Int>, List<NumberedNote>> {
    val notes = annotations.mapNotNull { a -> (a.shape as? AnnotationShape.Note)?.let { a to it } }
        .sortedWith(compareBy({ it.first.pageIndex }, { it.second.anchor.y }, { it.second.anchor.x }, { it.first.id }))
    val numbers = notes.mapIndexed { i, (a, _) -> a.id to i + 1 }.toMap()
    return numbers to notes.mapIndexed { i, (a, n) -> NumberedNote(i + 1, a.pageIndex, a.author.displayName, n.text) }
}

/**
 * Flattened export: each page is rendered once at ~150 dpi (capped by [PdfPageRenderer.MAX_PIXELS]),
 * the committed overlay is drawn with the same [PageTransform] as the viewer, and the JPEG page is
 * streamed into [FlatPdfWriter]. Only one page bitmap is alive at a time. Note text is written in
 * full to appendix pages, paginated on line boundaries so text is never cut.
 */
class AndroidDocumentExporter(context: Context) : DocumentExportEngine {
    private val app = context.applicationContext

    override suspend fun render(source: File, version: DocumentVersion, annotations: List<DocumentAnnotation>, revision: Long,
                                target: File, progress: (done: Int, total: Int) -> Unit) = withContext(Dispatchers.Default) {
        val (numbers, notes) = numberNotes(annotations)
        val appendix = appendixLayout(version, revision, notes)
        val appendixPages = appendix?.let { paginate(it) } ?: emptyList()
        val total = version.pageCount + appendixPages.size
        val stream = FileOutputStream(target)
        var complete = false
        try {
            val writer = FlatPdfWriter(stream, mapOf(
                "Title" to version.title,
                "Subject" to app.getString(R.string.doc_export_subject, version.versionNumber, revision),
                "Keywords" to "studyhub version=${version.id} revision=$revision hash=${version.contentHash}",
                "Creator" to "StudyHub"))
            PdfPageRenderer(source).use { renderer ->
                val byPage = annotations.groupBy { it.pageIndex }
                for (index in 0 until version.pageCount) {
                    coroutineContext.ensureActive()
                    val size = version.pages.getOrNull(index)
                    val (wPt, hPt) = if (size != null) size.w to size.h
                        else renderer.pageSize(index).let { it.first.toDouble() to it.second.toDouble() }
                    val pxPerPt = minOf(DPI / 72.0, sqrt(PdfPageRenderer.MAX_PIXELS / (wPt * hPt)))
                    val bitmap = renderer.render(index, (wPt * pxPerPt).toInt().coerceAtLeast(1), (hPt * pxPerPt).toInt().coerceAtLeast(1),
                        forPrint = true) ?: throw kotlinx.coroutines.CancellationException("renderer closed")
                    try {
                        val scale = bitmap.width / wPt.toFloat()
                        val painter = AnnotationPainter(scale)
                        val canvas = Canvas(bitmap)
                        val t = PageTransform(bitmap.width.toFloat(), bitmap.height.toFloat())
                        byPage[index].orEmpty().mapNotNull { a -> a.shape?.let { a to it } }
                            .sortedBy { (_, s) -> when (s) { is AnnotationShape.Highlight -> 0; is AnnotationShape.Ink -> 1; is AnnotationShape.Note -> 2 } }
                            .forEach { (a, shape) -> painter.draw(canvas, t, shape, pinNumber = numbers[a.id], pinScale = scale * 0.75f) }
                        writer.addPage(wPt, hPt, jpeg(bitmap))
                    } finally {
                        bitmap.recycle()
                    }
                    progress(index + 1, total)
                }
            }
            appendixPages.forEachIndexed { i, lines ->
                coroutineContext.ensureActive()
                writer.addPage(A4_W, A4_H, appendixPage(requireNotNull(appendix), lines, i + 1, appendixPages.size))
                progress(version.pageCount + i + 1, total)
            }
            writer.close()
            complete = true
        } finally {
            if (!complete) { runCatching { stream.close() }; target.delete() }
        }
    }

    override suspend fun publish(file: File, destination: String) = withContext(Dispatchers.IO) {
        val output = app.contentResolver.openOutputStream(Uri.parse(destination), "wt") ?: error("destination unavailable")
        output.use { out -> file.inputStream().use { it.copyTo(out, 64 * 1024) } }
        Unit
    }

    override fun discard(destination: String) {
        runCatching { DocumentsContract.deleteDocument(app.contentResolver, Uri.parse(destination)) }
    }

    private fun jpeg(bitmap: Bitmap): FlatPdfWriter.Image {
        val bytes = ByteArrayOutputStream(bitmap.width * bitmap.height / 4).also { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
        return FlatPdfWriter.Image(bitmap.width, bitmap.height, FlatPdfWriter.Filter.JPEG, bytes)
    }

    // ---- Note appendix ------------------------------------------------------------------

    private val pxPerPt = (DPI / 72.0).toFloat()
    private val pageW = (A4_W * pxPerPt).toInt()
    private val pageH = (A4_H * pxPerPt).toInt()
    private val margin = (MARGIN_PT * pxPerPt).toInt()
    private val footer = (18 * pxPerPt).toInt()
    private val contentW = pageW - 2 * margin
    private val contentH = pageH - 2 * margin - footer

    private fun appendixLayout(version: DocumentVersion, revision: Long, notes: List<NumberedNote>): StaticLayout? {
        if (notes.isEmpty()) return null
        val text = SpannableStringBuilder()
        fun bold(s: String) { val start = text.length; text.append(s); text.setSpan(StyleSpan(Typeface.BOLD), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        bold(app.getString(R.string.doc_export_appendix_title)); text.append("\n")
        text.append(app.getString(R.string.doc_export_appendix_meta, version.title, version.versionNumber, revision,
            DateFormat.getDateTimeInstance().format(Date()))).append("\n\n")
        notes.forEach { n ->
            bold(app.getString(R.string.doc_export_note_heading, n.number, n.pageIndex + 1, n.author)); text.append("\n")
            text.append(n.text).append("\n\n")
        }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 11f * pxPerPt }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, contentW)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.15f).setIncludePad(false).build()
    }

    /** Line ranges per page; a page always holds at least one line, never part of a line. */
    private fun paginate(layout: StaticLayout): List<IntRange> {
        val pages = mutableListOf<IntRange>()
        var first = 0
        while (first < layout.lineCount) {
            val top = layout.getLineTop(first)
            var last = first
            while (last + 1 < layout.lineCount && layout.getLineBottom(last + 1) - top <= contentH) last++
            pages += first..last
            first = last + 1
        }
        return pages
    }

    private fun appendixPage(layout: StaticLayout, lines: IntRange, number: Int, count: Int): FlatPdfWriter.Image {
        val bitmap = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            val canvas = Canvas(bitmap)
            val top = layout.getLineTop(lines.first)
            canvas.save()
            canvas.translate(margin.toFloat(), (margin - top).toFloat())
            canvas.clipRect(0, top, contentW, layout.getLineBottom(lines.last))
            layout.draw(canvas)
            canvas.restore()
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 9f * pxPerPt; textAlign = Paint.Align.CENTER }
            canvas.drawText(app.getString(R.string.doc_export_appendix_page, number, count), pageW / 2f, (pageH - margin).toFloat(), paint)
            return jpeg(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    companion object {
        const val DPI = 150.0
        const val A4_W = 595.0
        const val A4_H = 842.0
        const val MARGIN_PT = 40
    }
}
