package com.doan.studyhub.user.ui.group.document

import com.doan.studyhub.core.data.model.PagePoint

/**
 * Single mapping between canonical page coordinates (0..1 of the rendered page, origin
 * top-left, as stored on the server) and view pixels. The same instance is used for drawing,
 * hit-testing and export, so marks stay aligned across zoom, device size and view rotation.
 *
 * [pageWidth]/[pageHeight] are the unrotated rendered page size in pixels at scale 1;
 * [rotation] is a view-only quarter turn (0, 90, 180, 270); [scale] and [offsetX]/[offsetY]
 * place the rotated page in the view.
 */
data class PageTransform(
    val pageWidth: Float,
    val pageHeight: Float,
    val rotation: Int = 0,
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f
) {
    init {
        require(rotation in setOf(0, 90, 180, 270)) { "rotation must be a quarter turn" }
        require(pageWidth > 0f && pageHeight > 0f && scale > 0f)
    }

    /** Size of the page on screen after rotation and scale. */
    val displayedWidth get() = (if (rotation % 180 == 0) pageWidth else pageHeight) * scale
    val displayedHeight get() = (if (rotation % 180 == 0) pageHeight else pageWidth) * scale

    fun toView(point: PagePoint): FloatArray = toView(point.x.toFloat(), point.y.toFloat())

    fun toView(x: Float, y: Float): FloatArray {
        val px = x * pageWidth
        val py = y * pageHeight
        val (rx, ry) = when (rotation) {
            90 -> pageHeight - py to px
            180 -> pageWidth - px to pageHeight - py
            270 -> py to pageWidth - px
            else -> px to py
        }
        return floatArrayOf(rx * scale + offsetX, ry * scale + offsetY)
    }

    /** Inverse mapping; returns null outside the page so touches beyond its edge are ignored. */
    fun toPage(viewX: Float, viewY: Float, clamp: Boolean = false): PagePoint? {
        val rx = (viewX - offsetX) / scale
        val ry = (viewY - offsetY) / scale
        val (px, py) = when (rotation) {
            90 -> ry to pageHeight - rx
            180 -> pageWidth - rx to pageHeight - ry
            270 -> pageWidth - ry to rx
            else -> rx to ry
        }
        var x = px / pageWidth
        var y = py / pageHeight
        if (clamp) { x = x.coerceIn(0f, 1f); y = y.coerceIn(0f, 1f) }
        else if (x !in 0f..1f || y !in 0f..1f) return null
        return PagePoint(x.toDouble(), y.toDouble())
    }

    /** Stroke width in view pixels for a width stored relative to the page's short edge. */
    fun strokeWidth(relative: Double): Float = (relative * minOf(pageWidth, pageHeight) * scale).toFloat()

    companion object {
        /** Fits a page into a view, centered, at [zoom] (1 = fit) with an extra pan. */
        fun fit(pageWidth: Float, pageHeight: Float, viewWidth: Float, viewHeight: Float, rotation: Int = 0,
                zoom: Float = 1f, panX: Float = 0f, panY: Float = 0f): PageTransform {
            val rotatedW = if (rotation % 180 == 0) pageWidth else pageHeight
            val rotatedH = if (rotation % 180 == 0) pageHeight else pageWidth
            val base = minOf(viewWidth / rotatedW, viewHeight / rotatedH)
            val scale = base * zoom
            return PageTransform(pageWidth, pageHeight, rotation, scale,
                (viewWidth - rotatedW * scale) / 2f + panX, (viewHeight - rotatedH * scale) / 2f + panY)
        }
    }
}
