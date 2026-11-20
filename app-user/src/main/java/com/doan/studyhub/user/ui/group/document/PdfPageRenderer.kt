package com.doan.studyhub.user.ui.group.document

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/**
 * Thread-safe wrapper around [PdfRenderer] (which allows one open page at a time). Pages are
 * rendered off the main thread into bounded bitmaps; [close] releases the renderer and file
 * descriptor even while a render is waiting.
 */
class PdfPageRenderer(file: File) : Closeable {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try { PdfRenderer(descriptor) } catch (e: Exception) { descriptor.close(); throw e }
    private val mutex = Mutex()
    @Volatile private var closed = false

    val pageCount: Int get() = renderer.pageCount

    /** Rendered page size in points (PDFium applies crop box and /Rotate). */
    suspend fun pageSize(index: Int): Pair<Int, Int> = mutex.withLock {
        check(!closed)
        withContext(Dispatchers.IO) { renderer.openPage(index).use { it.width to it.height } }
    }

    /**
     * Renders [index] scaled to fit [maxWidth]x[maxHeight] pixels, capped at [maxPixels].
     * Returns null when the renderer was closed meanwhile.
     */
    suspend fun render(index: Int, maxWidth: Int, maxHeight: Int, maxPixels: Int = MAX_PIXELS, forPrint: Boolean = false): Bitmap? =
        mutex.withLock {
            if (closed) return@withLock null
            withContext(Dispatchers.Default) {
                renderer.openPage(index).use { page ->
                    val (w, h) = targetSize(page.width, page.height, maxWidth, maxHeight, maxPixels)
                    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE) // transparent PDF backgrounds render on paper white
                    page.render(bitmap, null, null,
                        if (forPrint) PdfRenderer.Page.RENDER_MODE_FOR_PRINT else PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }
        }

    override fun close() {
        if (closed) return
        closed = true
        // Wait for an in-flight render so the native page is never closed underneath it.
        kotlinx.coroutines.runBlocking { mutex.withLock { renderer.close(); descriptor.close() } }
    }

    companion object {
        /** 4 MP ≈ 16 MiB ARGB per bitmap: the viewer/export memory budget per page. */
        const val MAX_PIXELS = 4_000_000

        fun targetSize(pageW: Int, pageH: Int, maxW: Int, maxH: Int, maxPixels: Int): Pair<Int, Int> {
            var scale = minOf(maxW.toDouble() / pageW, maxH.toDouble() / pageH)
            if (pageW * scale * pageH * scale > maxPixels) scale = kotlin.math.sqrt(maxPixels.toDouble() / (pageW.toDouble() * pageH))
            return (pageW * scale).toInt().coerceAtLeast(1) to (pageH * scale).toInt().coerceAtLeast(1)
        }
    }
}
