package com.doan.studyhub.user.ui.group.document

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.doan.studyhub.core.data.document.DisplayAnnotation
import com.doan.studyhub.core.data.model.AnnotationShape

/** Draws annotations with a [PageTransform]; shared by the live viewer and the export renderer. */
class AnnotationPainter(private val density: Float) {
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val path = Path()
    private val rect = RectF()

    /** Pin radius in view pixels; export passes its own scale so pins stay legible on paper. */
    fun pinRadius(scale: Float = density) = 12f * scale

    fun draw(canvas: Canvas, t: PageTransform, shape: AnnotationShape, pending: Boolean = false, selected: Boolean = false,
             pinNumber: Int? = null, pinScale: Float = density) {
        when (shape) {
            is AnnotationShape.Ink -> {
                ink.color = Color.parseColor(shape.color)
                ink.alpha = if (pending) 170 else 255
                ink.strokeWidth = t.strokeWidth(shape.width).coerceAtLeast(1f)
                path.reset()
                shape.points.forEachIndexed { i, p ->
                    val v = t.toView(p)
                    if (i == 0) path.moveTo(v[0], v[1]) else path.lineTo(v[0], v[1])
                }
                if (shape.points.size == 1) {
                    val v = t.toView(shape.points[0]); path.lineTo(v[0] + 0.01f, v[1])
                }
                canvas.drawPath(path, ink)
            }
            is AnnotationShape.Highlight -> {
                val a = t.toView(shape.left.toFloat(), shape.top.toFloat())
                val b = t.toView(shape.right.toFloat(), shape.bottom.toFloat())
                rect.set(minOf(a[0], b[0]), minOf(a[1], b[1]), maxOf(a[0], b[0]), maxOf(a[1], b[1]))
                fill.color = Color.parseColor(shape.color)
                fill.alpha = if (pending) 70 else HIGHLIGHT_ALPHA
                canvas.drawRect(rect, fill)
            }
            is AnnotationShape.Note -> {
                val v = t.toView(shape.anchor)
                val r = pinRadius(pinScale)
                fill.color = Color.parseColor(shape.color); fill.alpha = if (pending) 170 else 255
                canvas.drawCircle(v[0], v[1], r, fill)
                outline.color = Color.DKGRAY; outline.strokeWidth = 1.5f * pinScale
                canvas.drawCircle(v[0], v[1], r, outline)
                label.textSize = r * 1.1f
                canvas.drawText(pinNumber?.toString() ?: "✎", v[0], v[1] + label.textSize / 3f, label)
            }
        }
        if (selected) {
            val bounds = bounds(t, shape)
            outline.color = Color.parseColor("#2563EB"); outline.strokeWidth = 2f * density
            canvas.drawRect(bounds, outline)
        }
    }

    fun drawAll(canvas: Canvas, t: PageTransform, items: List<DisplayAnnotation>, selectedId: String?) {
        // Highlights below ink, notes on top, matching export order.
        items.sortedBy { order(it.shape) }.forEach { draw(canvas, t, it.shape, it.pending || it.conflict, it.id == selectedId) }
    }

    /** View-space bounds used for selection outline and hit-testing. */
    fun bounds(t: PageTransform, shape: AnnotationShape): RectF {
        val points = when (shape) {
            is AnnotationShape.Ink -> shape.points.map { t.toView(it) }
            is AnnotationShape.Highlight -> listOf(t.toView(shape.left.toFloat(), shape.top.toFloat()),
                t.toView(shape.right.toFloat(), shape.bottom.toFloat()))
            is AnnotationShape.Note -> listOf(t.toView(shape.anchor))
        }
        val pad = when (shape) {
            is AnnotationShape.Note -> pinRadius()
            is AnnotationShape.Ink -> t.strokeWidth(shape.width) / 2f + 6f * density
            else -> 2f * density
        }
        return RectF(points.minOf { it[0] } - pad, points.minOf { it[1] } - pad, points.maxOf { it[0] } + pad, points.maxOf { it[1] } + pad)
    }

    /** Topmost annotation under a touch, or null. Ink is hit within its stroke, not its bounding box. */
    fun hit(t: PageTransform, items: List<DisplayAnnotation>, x: Float, y: Float, pageIndex: Int): DisplayAnnotation? =
        items.filter { it.pageIndex == pageIndex }.sortedByDescending { order(it.shape) }.firstOrNull { item ->
            val shape = item.shape
            if (shape is AnnotationShape.Ink) {
                val tolerance = t.strokeWidth(shape.width) / 2f + 10f * density
                shape.points.map { t.toView(it) }.zipWithNext().any { (a, b) -> segmentDistance(x, y, a, b) <= tolerance } ||
                    (shape.points.size == 1 && t.toView(shape.points[0]).let { kotlin.math.hypot(it[0] - x, it[1] - y) <= tolerance })
            } else bounds(t, shape).contains(x, y)
        }

    private fun segmentDistance(x: Float, y: Float, a: FloatArray, b: FloatArray): Float {
        val dx = b[0] - a[0]; val dy = b[1] - a[1]
        val len = dx * dx + dy * dy
        val u = if (len == 0f) 0f else (((x - a[0]) * dx + (y - a[1]) * dy) / len).coerceIn(0f, 1f)
        return kotlin.math.hypot(a[0] + u * dx - x, a[1] + u * dy - y)
    }

    private fun order(shape: AnnotationShape) = when (shape) {
        is AnnotationShape.Highlight -> 0
        is AnnotationShape.Ink -> 1
        is AnnotationShape.Note -> 2
    }

    companion object { const val HIGHLIGHT_ALPHA = 102 } // 40 % opacity
}
