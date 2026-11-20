package com.doan.studyhub.user.ui.group.document

import java.io.Closeable
import java.io.FilterOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Streaming writer for a flattened PDF made of one full-page raster image per page.
 *
 * Android's PdfDocument keeps every page as an in-memory recording until writeTo(), so its
 * memory grows with page count (measured in the 9.1 spike). This writer emits each page as
 * soon as it is added and keeps only object offsets, bounding memory to one encoded page.
 * Output is plain PDF 1.4 objects readable by any viewer; no fonts, scripts or forms.
 */
class FlatPdfWriter(output: OutputStream, private val info: Map<String, String>) : Closeable {
    /** JPEG bytes (DCTDecode) or zlib-deflated 8-bit RGB rows (FlateDecode). */
    class Image(val widthPx: Int, val heightPx: Int, val filter: Filter, val bytes: ByteArray) {
        init { require(widthPx > 0 && heightPx > 0 && bytes.isNotEmpty()) }
    }
    enum class Filter(val pdfName: String) { JPEG("DCTDecode"), FLATE("FlateDecode") }

    private class Counting(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
    }

    private val out = Counting(output)
    private val offsets = ArrayList<Long>() // index = object number - 1
    private val pageIds = ArrayList<Int>()
    private var closed = false
    val pageCount get() = pageIds.size

    init {
        ascii("%PDF-1.4\n")
        out.write(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))
        reserve(); reserve(); reserve() // 1 catalog, 2 page tree, 3 info: written at close()
    }

    private fun reserve(): Int { offsets.add(-1L); return offsets.size }
    private fun ascii(text: String) = out.write(text.toByteArray(Charsets.US_ASCII))
    private fun begin(id: Int) { offsets[id - 1] = out.count; ascii("$id 0 obj\n") }
    private fun end() = ascii("\nendobj\n")
    private fun number(value: Double) = String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')

    /** Adds a page of [widthPt]x[heightPt] points covered by [image]. */
    fun addPage(widthPt: Double, heightPt: Double, image: Image) {
        check(!closed) { "writer closed" }
        require(widthPt > 0 && heightPt > 0)
        val imageId = reserve(); val contentId = reserve(); val pageId = reserve()
        begin(imageId)
        ascii("<< /Type /XObject /Subtype /Image /Width ${image.widthPx} /Height ${image.heightPx} " +
            "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /${image.filter.pdfName} /Length ${image.bytes.size} >>\nstream\n")
        out.write(image.bytes)
        ascii("\nendstream"); end()
        val content = "q ${number(widthPt)} 0 0 ${number(heightPt)} 0 0 cm /Im0 Do Q"
        begin(contentId)
        ascii("<< /Length ${content.length} >>\nstream\n$content\nendstream"); end()
        begin(pageId)
        ascii("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${number(widthPt)} ${number(heightPt)}] " +
            "/Resources << /XObject << /Im0 $imageId 0 R >> >> /Contents $contentId 0 R >>"); end()
        pageIds.add(pageId)
        out.flush()
    }

    override fun close() {
        if (closed) return
        closed = true
        check(pageIds.isNotEmpty()) { "a PDF needs at least one page" }
        begin(1); ascii("<< /Type /Catalog /Pages 2 0 R >>"); end()
        begin(2); ascii("<< /Type /Pages /Count ${pageIds.size} /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] >>"); end()
        begin(3)
        val date = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val entries = (info + mapOf("Producer" to "StudyHub")).entries.joinToString(" ") { (key, value) ->
            "/${key.filter { it.isLetterOrDigit() }} ${textString(value)}"
        }
        ascii("<< $entries /CreationDate (D:${date}Z) >>"); end()
        val xref = out.count
        ascii("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { ascii(String.format(Locale.US, "%010d 00000 n \n", it)) }
        ascii("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R /Info 3 0 R >>\nstartxref\n$xref\n%%EOF\n")
        out.flush()
        out.close()
    }

    companion object {
        /** PDF text string as UTF-16BE with BOM (hex), so Vietnamese/emoji metadata survives. */
        fun textString(value: String): String {
            val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + value.toByteArray(Charsets.UTF_16BE)
            return bytes.joinToString("", "<", ">") { "%02X".format(it) }
        }
    }
}
