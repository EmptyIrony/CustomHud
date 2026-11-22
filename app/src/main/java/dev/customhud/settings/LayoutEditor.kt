package dev.customhud.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import dev.customhud.layout.LayoutDocument
import dev.customhud.layout.WidgetType
import dev.customhud.renderer.HudCanvasView
import kotlin.math.abs

/** Keeps its children at a fixed width:height ratio, as large as the available space allows. */
class AspectBox(context: Context) : ViewGroup(context) {
    var ratio: Float = 16f / 9f
        set(value) {
            if (value > 0f && value != field) {
                field = value
                requestLayout()
            }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec)
        val maxH = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else MeasureSpec.getSize(heightMeasureSpec)
        var w = maxW
        var h = (w / ratio).toInt()
        if (h > maxH) {
            h = maxH
            w = (h * ratio).toInt()
        }
        val ws = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY)
        val hs = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(ws, hs)
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) getChildAt(i).layout(0, 0, r - l, b - t)
    }
}

/**
 * Transparent layer over the preview: tap to select a widget, drag to move it,
 * snapping to the centre lines. Positions are normalised 0..1 like the layout schema.
 */
@SuppressLint("ViewConstructor")
class EditorOverlay(
    context: Context,
    private val canvasView: HudCanvasView,
    private val document: () -> LayoutDocument,
    private val onSelect: (WidgetType?) -> Unit,
    private val onMove: (WidgetType, Float, Float, Boolean) -> Unit,
) : View(context) {
    var selected: WidgetType? = null
        set(value) {
            field = value
            invalidate()
        }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val selectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dpf(3f)
        color = Ui.ACCENT
        pathEffect = DashPathEffect(floatArrayOf(context.dpf(10f), context.dpf(6f)), 0f)
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = context.dpf(2f)
        color = Ui.WARN
    }
    private val gridPaint = Paint().apply {
        strokeWidth = context.dpf(1f)
        color = 0x22FFFFFF
    }
    private val borderPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dpf(2f)
        color = Ui.STROKE
    }
    private var dragging: WidgetType? = null
    private var downX = 0f
    private var downY = 0f
    private var startCx = 0f
    private var startCy = 0f
    private var moved = false
    private var snapX = false
    private var snapY = false
    private val selectionRect = RectF()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val hit = hitTest(event.x, event.y)
                selected = hit
                onSelect(hit)
                val widget = hit?.let { type -> document().widgets.firstOrNull { it.type == type } }
                dragging = widget?.type
                if (widget != null) {
                    downX = event.x
                    downY = event.y
                    startCx = widget.cx
                    startCy = widget.cy
                    moved = false
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val type = dragging ?: return true
                if (!moved && abs(event.x - downX) < touchSlop && abs(event.y - downY) < touchSlop) return true
                moved = true
                var cx = (startCx + (event.x - downX) / width).coerceIn(0f, 1f)
                var cy = (startCy + (event.y - downY) / height).coerceIn(0f, 1f)
                snapX = abs(cx - .5f) < SNAP
                snapY = abs(cy - .5f) < SNAP
                if (snapX) cx = .5f
                if (snapY) cy = .5f
                onMove(type, cx, cy, false)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val type = dragging
                if (type != null && moved) {
                    val widget = document().widgets.firstOrNull { it.type == type }
                    if (widget != null) onMove(type, widget.cx, widget.cy, true)
                }
                dragging = null
                moved = false
                snapX = false
                snapY = false
                invalidate()
            }
        }
        return true
    }

    private fun hitTest(x: Float, y: Float): WidgetType? {
        val pad = context.dpf(24f)
        return canvasView.widgetBounds
            .filter { (_, rect) -> RectF(rect).apply { inset(-pad, -pad) }.contains(x, y) }
            .minByOrNull { (_, rect) -> rect.width() * rect.height() }
            ?.key
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawLine(w / 2, 0f, w / 2, h, gridPaint)
        canvas.drawLine(0f, h / 2, w, h / 2, gridPaint)
        canvas.drawRect(1f, 1f, w - 1f, h - 1f, borderPaint)
        if (snapX) canvas.drawLine(w / 2, 0f, w / 2, h, guidePaint)
        if (snapY) canvas.drawLine(0f, h / 2, w, h / 2, guidePaint)
        val rect = selected?.let { canvasView.widgetBounds[it] } ?: return
        val pad = context.dpf(10f)
        selectionRect.set(rect)
        selectionRect.inset(-pad, -pad)
        canvas.drawRoundRect(selectionRect, pad, pad, selectPaint)
    }

    private companion object {
        const val SNAP = .012f
    }
}
