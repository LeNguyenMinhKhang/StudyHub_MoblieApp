package com.doan.studyhub.user.ui.group.document

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.doan.studyhub.core.data.document.DisplayAnnotation
import com.doan.studyhub.core.data.model.AnnotationLimits
import com.doan.studyhub.core.data.model.AnnotationShape
import com.doan.studyhub.core.data.model.PagePoint

enum class EditorTool { VIEW, INK, HIGHLIGHT, NOTE, SELECT }

/**
 * One PDF page with its annotation overlay. Drawing, hit-testing and touch input all use the
 * same [PageTransform], so marks stay on the same page content at every zoom/rotation.
 */
class PdfPageView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    interface Listener {
        fun onStroke(points: List<PagePoint>)
        fun onHighlight(left: Double, top: Double, right: Double, bottom: Double)
        fun onNoteAt(point: PagePoint)
        fun onSelect(annotation: DisplayAnnotation?)
        fun onRenderSize(widthPx: Int, heightPx: Int)
        fun onTouchUser() {}
    }

    var listener: Listener? = null
    var tool = EditorTool.VIEW
        set(value) { field = value; cancelGesture() }
    var inkColor = AnnotationLimits.INK_COLORS[1]
    var inkWidth = 0.004
    var highlightColor = AnnotationLimits.HIGHLIGHT_COLORS[0]
    var annotations: List<DisplayAnnotation> = emptyList()
        set(value) { field = value; invalidate() }
    var selectedId: String? = null
        set(value) { field = value; invalidate() }

    private var pageIndex = 0
    private var pageWidth = 612f
    private var pageHeight = 792f
    private var bitmap: Bitmap? = null
    private var viewRotation = 0
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f

    private val painter = AnnotationPainter(resources.displayMetrics.density)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val paperPaint = Paint().apply { color = Color.WHITE }
    private val matrix = Matrix()
    private val stroke = ArrayList<PagePoint>()
    private var dragStart: PagePoint? = null
    private var dragEnd: PagePoint? = null
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var multiTouch = false

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { cancelGesture(); multiTouch = true; return true }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val before = transform()
            val focus = before.toPage(detector.focusX, detector.focusY, clamp = true)
            zoom = (zoom * detector.scaleFactor).coerceIn(1f, 6f)
            if (focus != null) { // keep the content under the fingers in place
                val after = transform().toView(focus)
                panX += detector.focusX - after[0]; panY += detector.focusY - after[1]
            }
            clampPan(); invalidate()
            return true
        }
        override fun onScaleEnd(detector: ScaleGestureDetector) { requestRender() }
    })

    fun showPage(index: Int, widthPt: Float, heightPt: Float, resetView: Boolean) {
        pageIndex = index; pageWidth = widthPt; pageHeight = heightPt
        if (resetView) { zoom = 1f; panX = 0f; panY = 0f }
        cancelGesture(); invalidate()
    }

    fun setBitmap(rendered: Bitmap?) { bitmap = rendered; invalidate() }

    fun rotateClockwise() { viewRotation = (viewRotation + 90) % 360; panX = 0f; panY = 0f; invalidate(); requestRender() }
    fun resetZoom() { zoom = 1f; panX = 0f; panY = 0f; invalidate(); requestRender() }
    val currentRotation get() = viewRotation

    fun transform(): PageTransform = PageTransform.fit(pageWidth, pageHeight, width.coerceAtLeast(1).toFloat(),
        height.coerceAtLeast(1).toFloat(), viewRotation, zoom, panX, panY)

    private fun requestRender() {
        val t = transform()
        // Pixels needed for the unrotated page at the current on-screen scale.
        listener?.onRenderSize((pageWidth * t.scale).toInt(), (pageHeight * t.scale).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); clampPan(); requestRender() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = transform()
        val origin = t.toView(0f, 0f); val corner = t.toView(1f, 1f)
        canvas.drawRect(minOf(origin[0], corner[0]), minOf(origin[1], corner[1]), maxOf(origin[0], corner[0]),
            maxOf(origin[1], corner[1]), paperPaint)
        bitmap?.takeIf { !it.isRecycled }?.let { b ->
            matrix.reset()
            matrix.postScale(pageWidth / b.width, pageHeight / b.height)
            matrix.postRotate(viewRotation.toFloat())
            when (viewRotation) {
                90 -> matrix.postTranslate(pageHeight, 0f)
                180 -> matrix.postTranslate(pageWidth, pageHeight)
                270 -> matrix.postTranslate(0f, pageWidth)
            }
            matrix.postScale(t.scale, t.scale)
            matrix.postTranslate(t.offsetX, t.offsetY)
            canvas.drawBitmap(b, matrix, bitmapPaint)
        }
        painter.drawAll(canvas, t, annotations.filter { it.pageIndex == pageIndex }, selectedId)
        // In-progress gesture previews (not yet an annotation, never saved until completed).
        if (stroke.isNotEmpty()) painter.draw(canvas, t, AnnotationShape.Ink(stroke, inkColor, inkWidth), pending = true)
        val start = dragStart; val end = dragEnd
        if (start != null && end != null) painter.draw(canvas, t, AnnotationShape.Highlight(minOf(start.x, end.x),
            minOf(start.y, end.y), maxOf(start.x, end.x), maxOf(start.y, end.y), highlightColor), pending = true)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        listener?.onTouchUser()
        scaler.onTouchEvent(event)
        if (event.pointerCount > 1) { multiTouch = true; cancelGesture(); return true }
        val t = transform()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                multiTouch = false
                downX = event.x; downY = event.y; lastX = event.x; lastY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
                when (tool) {
                    EditorTool.INK -> t.toPage(event.x, event.y)?.let { stroke.add(it) }
                    EditorTool.HIGHLIGHT -> { dragStart = t.toPage(event.x, event.y); dragEnd = dragStart }
                    else -> Unit
                }
            }
            MotionEvent.ACTION_MOVE -> if (!multiTouch) {
                when (tool) {
                    EditorTool.INK -> if (stroke.isNotEmpty()) {
                        for (i in 0 until event.historySize) stroke.add(t.toPage(event.getHistoricalX(i), event.getHistoricalY(i), clamp = true)!!)
                        stroke.add(t.toPage(event.x, event.y, clamp = true)!!)
                    }
                    EditorTool.HIGHLIGHT -> if (dragStart != null) dragEnd = t.toPage(event.x, event.y, clamp = true)
                    else -> if (zoom > 1f) { panX += event.x - lastX; panY += event.y - lastY; clampPan() }
                }
                lastX = event.x; lastY = event.y
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (!multiTouch) finishGesture(t, event)
                cancelGesture()
                if (tool == EditorTool.VIEW && zoom > 1f) requestRender()
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> cancelGesture()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun finishGesture(t: PageTransform, event: MotionEvent) {
        val tap = kotlin.math.hypot(event.x - downX, event.y - downY) < 12f * resources.displayMetrics.density
        when (tool) {
            EditorTool.INK -> if (stroke.isNotEmpty()) listener?.onStroke(stroke.toList()) // sync on pen-up
            EditorTool.HIGHLIGHT -> {
                val s = dragStart; val e = dragEnd
                if (s != null && e != null && !tap) listener?.onHighlight(minOf(s.x, e.x), minOf(s.y, e.y), maxOf(s.x, e.x), maxOf(s.y, e.y))
            }
            EditorTool.NOTE -> if (tap) t.toPage(event.x, event.y)?.let { listener?.onNoteAt(it) }
            EditorTool.SELECT -> if (tap) listener?.onSelect(painter.hit(t, annotations, event.x, event.y, pageIndex))
            EditorTool.VIEW -> if (tap) painter.hit(t, annotations, event.x, event.y, pageIndex)
                ?.takeIf { it.shape is AnnotationShape.Note }?.let { listener?.onSelect(it) }
        }
    }

    private fun cancelGesture() { stroke.clear(); dragStart = null; dragEnd = null; invalidate() }

    private fun clampPan() {
        if (width == 0 || height == 0) return
        val t = PageTransform.fit(pageWidth, pageHeight, width.toFloat(), height.toFloat(), viewRotation, zoom)
        val maxX = maxOf(0f, (t.displayedWidth - width) / 2f)
        val maxY = maxOf(0f, (t.displayedHeight - height) / 2f)
        panX = panX.coerceIn(-maxX, maxX); panY = panY.coerceIn(-maxY, maxY)
    }
}
