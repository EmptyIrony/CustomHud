package dev.customhud.renderer

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.MaskFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import dev.customhud.camera.CameraDisplay
import dev.customhud.camera.RearCameraStatus
import dev.customhud.data.DataMode
import dev.customhud.data.Gear
import dev.customhud.data.VehicleSignal
import dev.customhud.data.VehicleSnapshot
import dev.customhud.layout.CameraGeometry
import dev.customhud.layout.LayoutSnapshot
import dev.customhud.layout.WidgetConfig
import dev.customhud.layout.WidgetType
import dev.customhud.media.NowPlaying
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class HudCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    data class FrameState(
        val layout: LayoutSnapshot,
        val vehicle: VehicleSnapshot,
        val clockText: String,
        val mode: DataMode,
        val frameGeneration: Long,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        strokeJoin = Paint.Join.ROUND
    }
    private val dimPaint = Paint(paint)
    private val lyricPaint = Paint(paint)
    private val trackPaint = Paint(paint)
    private val cameraFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val cameraStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.rgb(120, 120, 120)
    }
    private val cameraText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(200, 200, 200)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val stylePrefs = HudStylePrefs(context)
    private var stopStyleObserver: (() -> Unit)? = null
    private var vehicleKey: Any? = null

    /** Text weight / brightness / redraw policy; applied immediately. */
    var style: HudStyle = stylePrefs.style
        set(value) {
            if (field == value) return
            field = value
            applyStyle()
            lyricMotion.reset()
            frameGeneration += 1
            requestDraw()
        }
    private var media = NowPlaying()
    private val bounds = mutableMapOf<WidgetType, RectF>()
    private val failureCounts = mutableMapOf<WidgetType, Int>()
    private var layout: LayoutSnapshot? = null
    private var vehicle = VehicleSnapshot()
    private var frameGeneration = 0L
    private var invalidatePosted = false
    private var firstDrawAt: Long? = null
    private var clock = "10:08"
    private var camera: RearCameraStatus? = null
    private val lyricFrames = LyricFrameBuilder()
    private val lyricMotion = LyricMotion()
    /** Set after the animated path threw once; lyrics then fall back to hard cuts for this view. */
    private var motionBroken = false
    private var lyricDrawn = false
    private var lastReportedGeneration = -1L
    private var lyricWasAnimating = false

    /** Which widgets this view draws (e.g. the cluster window draws only REAR_CAMERA). */
    var widgetFilter: (WidgetType) -> Boolean = { true }
        set(value) {
            field = value
            frameGeneration += 1
            requestDraw()
        }

    var mode: DataMode = DataMode.PREVIEW
        set(value) {
            field = value
            clock = if (value == DataMode.PREVIEW) "10:08" else currentClock()
        }
    var onFirstDraw: ((Long) -> Unit)? = null
    var onDrawCompleted: ((Long, Long) -> Unit)? = null
    var onDrawError: ((WidgetType?, String) -> Unit)? = null

    /** Bounds of what each widget drew in the last frame (view pixels); used by the editor. */
    val widgetBounds: Map<WidgetType, RectF> get() = bounds

    private val invalidateTask = Runnable {
        invalidatePosted = false
        if (isAttachedToWindow) invalidate()
    }
    private val clockTask = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || mode != DataMode.DEVICE) return
            val next = currentClock()
            // Redrawing an unchanged frame every second made some HUDs pulse in brightness.
            if (next != clock || !style.redrawOnlyOnChange) {
                clock = next
                frameGeneration += 1
                requestDraw()
            }
            handler.postDelayed(this, 1000L)
        }
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isFocusable = false
        isClickable = false
        applyStyle()
    }

    fun submitFrame(layout: LayoutSnapshot, vehicle: VehicleSnapshot) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val key = renderKey(layout, vehicle)
        if (style.redrawOnlyOnChange && firstDrawAt != null && layout == this.layout && key == vehicleKey) return
        frameGeneration += 1
        this.layout = layout
        this.vehicle = vehicle
        vehicleKey = key
        requestDraw()
    }

    fun submitMedia(nowPlaying: NowPlaying) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (nowPlaying == media && style.redrawOnlyOnChange) return
        media = nowPlaying
        frameGeneration += 1
        requestDraw()
    }

    /** Rear camera state; the placeholder is drawn here, the picture itself by the TextureView below. */
    fun submitCamera(status: RearCameraStatus?) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val visible = status?.let { Triple(it.display, it.reason, it.testPattern) }
        if (visible == camera?.let { Triple(it.display, it.reason, it.testPattern) }) {
            camera = status
            return
        }
        camera = status
        frameGeneration += 1
        requestDraw()
    }

    private fun requestDraw() {
        if (!invalidatePosted) {
            invalidatePosted = true
            handler.post(invalidateTask)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        stopStyleObserver?.invoke()
        style = stylePrefs.style
        stopStyleObserver = stylePrefs.observe { next -> handler.post { style = next } }
        handler.removeCallbacks(clockTask)
        if (mode == DataMode.DEVICE) handler.post(clockTask)
        requestDraw()
    }

    override fun onDetachedFromWindow() {
        frameGeneration += 1
        lyricMotion.reset()
        pieceTimerAt = -1L
        unindexedLine = null
        stopStyleObserver?.invoke()
        stopStyleObserver = null
        handler.removeCallbacksAndMessages(null)
        invalidatePosted = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val currentLayout = layout ?: return
        if (width == 0 || height == 0) return
        val currentGeneration = frameGeneration
        bounds.clear()
        lyricDrawn = false
        try {
            currentLayout.document.orderedWidgets().forEach { widget ->
                if (!widget.enabled || !widgetFilter(widget.type) || failureCounts.getOrDefault(widget.type, 0) >= 3) return@forEach
                try {
                    drawWidget(canvas, widget)?.let { bounds[widget.type] = it }
                } catch (error: Exception) {
                    val failures = failureCounts.getOrDefault(widget.type, 0) + 1
                    failureCounts[widget.type] = failures
                    onDrawError?.invoke(widget.type, error::class.java.name)
                }
            }
            val now = SystemClock.elapsedRealtime()
            if (firstDrawAt == null) {
                firstDrawAt = now
                onFirstDraw?.invoke(now)
            }
            // Only while a lyric transition runs; at rest nothing is redrawn (anti-flicker).
            val animating = lyricDrawn && lyricMotion.isAnimating()
            val settled = lyricWasAnimating && !animating
            lyricWasAnimating = animating
            // Animation frames redraw the same generation: report each generation once, plus the frame a
            // transition settles on (the editor overlay reads the at-rest bounds from it).
            if (currentGeneration == frameGeneration && (currentGeneration != lastReportedGeneration || settled)) {
                lastReportedGeneration = currentGeneration
                onDrawCompleted?.invoke(currentLayout.revision, currentGeneration)
            }
            if (animating) postInvalidateOnAnimation()
        } catch (error: Exception) {
            onDrawError?.invoke(null, error::class.java.name)
        }
    }

    private fun drawWidget(canvas: Canvas, widget: WidgetConfig): RectF? {
        val geometry = widget.geometry(width, height)
        val unit = geometry.baseUnit.coerceAtLeast(1f)
        val x = geometry.anchorX
        val y = geometry.anchorY
        when (widget.type) {
            WidgetType.CLOCK -> {
                paint.size(unit * 1.6f)
                return canvas.drawCentered(clock, x, y, paint)
            }
            WidgetType.TURN -> {
                paint.size(unit * 1.2f)
                val hazard = booleanValue(VehicleSignal.HAZARD) == true
                val left = booleanValue(VehicleSignal.LEFT_INDICATOR) == true || hazard
                val right = booleanValue(VehicleSignal.RIGHT_INDICATOR) == true || hazard
                val a = canvas.drawCentered(if (left == true) "◀" else "△", x - geometry.pairSpreadPx / 2, y, paint)
                val b = canvas.drawCentered(if (right == true) "▶" else "△", x + geometry.pairSpreadPx / 2, y, paint)
                return RectF(a).apply { union(b) }
            }
            WidgetType.REAR_CAMERA -> return drawCamera(canvas, widget)
            WidgetType.LYRIC -> return drawLyric(canvas, x, y, unit * 1.25f)
            WidgetType.TRACK -> {
                val text = listOfNotNull(media.title, media.artist).filter { it.isNotBlank() }.joinToString(" · ")
                    .ifEmpty { if (mode == DataMode.PREVIEW) PREVIEW_TRACK else return null }
                return drawFitted(canvas, text, x, y, unit * .72f, trackPaint, allowTwoLines = false)
            }
            WidgetType.TIRES -> {
                val values = listOf(
                    VehicleSignal.TIRE_PRESSURE_FL, VehicleSignal.TIRE_PRESSURE_FR,
                    VehicleSignal.TIRE_PRESSURE_RL, VehicleSignal.TIRE_PRESSURE_RR,
                ).map { (vehicle.value(it)?.value as? Number)?.toDouble() }
                if (values.any { it != null }) {
                    paint.size(unit * .5f)
                    dimPaint.size(unit * .30f)
                    fun bar(v: Double?) = v?.let { formatNumber(it, 1) } ?: "—"
                    val front = canvas.drawCentered("${bar(values[0])}  ${bar(values[1])}", x, y - unit * .30f, paint)
                    val rear = canvas.drawCentered("${bar(values[2])}  ${bar(values[3])}", x, y + unit * .30f, paint)
                    val caption = canvas.drawCentered("胎压 bar", x, y + unit * .80f, dimPaint)
                    return RectF(front).apply { union(rear); union(caption) }
                }
            }
            else -> Unit
        }
        paint.size(unit * .72f)
        dimPaint.size(unit * .30f)
        val label = widget.type.label
        val text = when (widget.type) {
            WidgetType.BATTERY -> numberText(VehicleSignal.BATTERY_PERCENT, "%")
            WidgetType.RANGE -> numberText(VehicleSignal.RANGE, " km")
            WidgetType.TEMP -> numberText(VehicleSignal.BATTERY_TEMPERATURE, " °C")
            WidgetType.DRIVE_MODE -> enumText(VehicleSignal.DRIVE_MODE)
            WidgetType.SUSP_HEIGHT -> enumText(VehicleSignal.SUSPENSION_HEIGHT)
            WidgetType.SUSP_STIFF -> enumText(VehicleSignal.SUSPENSION_STIFFNESS)
            WidgetType.POWER_METER -> numberText(VehicleSignal.POWER, " kW")
            WidgetType.CHARGE -> booleanValue(VehicleSignal.CHARGING)?.let { if (it) "充电中" else "未充电" } ?: "—"
            WidgetType.TIRES -> if (mode == DataMode.PREVIEW) "示例" else "不可用"
            WidgetType.MOTORS, WidgetType.G_METER, WidgetType.PEDALS,
            WidgetType.SPEED_LIMIT, WidgetType.CAM_AHEAD, WidgetType.AVG_SECTION, WidgetType.AMBUSH_HEAT,
            WidgetType.POST_AHEAD, WidgetType.AMBUSH_OBJ, WidgetType.SIGN_AHEAD -> unavailableText()
            else -> "—"
        }
        val value = canvas.drawCentered(text, x, y, paint)
        val caption = canvas.drawCentered(label, x, y + unit * .55f, dimPaint)
        return RectF(value).apply { union(caption) }
    }

    /**
     * PREVIEW: placeholder only, the camera is never opened. DEVICE: nothing while hidden (P gear, mode off),
     * an opaque box with the reason while not streaming (covers any stale TextureView frame), and nothing over
     * the live picture.
     */
    private fun drawCamera(canvas: Canvas, widget: WidgetConfig): RectF? {
        val box = CameraGeometry.box(widget, width, height) ?: return null
        val rect = RectF(box.left, box.top, box.right, box.bottom)
        val reason = if (mode == DataMode.PREVIEW) {
            "后视摄像头 zeekr:202（预览占位，不打开相机）"
        } else {
            val status = camera ?: return null
            when (status.display) {
                CameraDisplay.HIDDEN -> return null
                CameraDisplay.STREAMING -> return rect
                CameraDisplay.PLACEHOLDER -> status.reason
            }
        }
        if (mode == DataMode.DEVICE && camera?.testPattern == true) {
            cameraFill.color = Color.WHITE
            canvas.drawRect(rect, cameraFill)
            return rect
        }
        val radius = rect.height() * .04f
        cameraFill.color = if (mode == DataMode.PREVIEW) Color.rgb(28, 32, 38) else Color.BLACK
        canvas.drawRoundRect(rect, radius, radius, cameraFill)
        cameraStroke.strokeWidth = (rect.height() * .012f).coerceAtLeast(2f)
        canvas.drawRoundRect(rect, radius, radius, cameraStroke)
        cameraText.textSize = rect.height() * .11f
        canvas.drawText("后视", rect.centerX(), rect.top + rect.height() * .30f, cameraText)
        cameraText.textSize = rect.height() * .075f
        val fit = TextFit.fit(reason, rect.width() * .88f, { cameraText.measureText(it) }, allowTwoLines = true)
        cameraText.textSize *= fit.scale
        val lineHeight = cameraText.textSize * LINE_SPACING
        val top = rect.centerY() + rect.height() * .08f - lineHeight * (fit.lines.size - 1) / 2
        fit.lines.forEachIndexed { index, line ->
            val baseline = top + index * lineHeight - (cameraText.descent() + cameraText.ascent()) / 2
            canvas.drawText(line, rect.centerX(), baseline, cameraText)
        }
        return rect
    }

    /** Pieces of a long line; measured once per text / width / size / settings. */
    private val pieceCache = object : LinkedHashMap<String, List<String>>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?): Boolean = size > 8
    }
    private var pieceTimerAt = -1L
    private var unindexedLine: String? = null
    private var unindexedLineAt = 0L
    private val pieceTask = Runnable {
        pieceTimerAt = -1L
        frameGeneration += 1
        requestDraw()
    }
    private val blurFilters = HashMap<Int, BlurMaskFilter>()

    private fun piecesOf(text: String, maxWidth: Float, size: Float): List<String> {
        val splitMode = style.splitMode
        if (splitMode == LyricSplitter.OFF) return listOf(text)
        val key = "$splitMode|${style.splitChars}|${maxWidth.roundToInt()}|${size.roundToInt()}|${style.weight}|$text"
        return pieceCache.getOrPut(key) {
            lyricPaint.size(size)
            LyricSplitter.split(text, splitMode, style.splitChars, maxWidth - lyricPaint.strokeWidth) { lyricPaint.measureText(it) }
        }
    }

    /**
     * Current line (or the piece of a long line due now), plus the context row. Pieces need the line's
     * timing (media-center LRC while playing); without it a long line is shrunk / wrapped as before.
     */
    private fun lyricFrame(x: Float, size: Float): LyricFrame {
        val twoRows = style.lyricRows >= 2
        val preferTranslation = style.lyricSecond == HudStyle.SECOND_TRANSLATION
        val sample = mode == DataMode.PREVIEW && media.lyric.isNullOrBlank() && media.nextLyric == null
        if (sample) return lyricFrames.build(PREVIEW_LYRIC, 0, PREVIEW_NEXT, PREVIEW_TRANSLATION, twoRows, preferTranslation)
        val maxWidth = lyricMaxWidth(x)
        val lyric = media.lyric
        val startedAt = media.lineStartedAt
        var current = lyric
        var segment = 0
        var nextSameLine = false
        var next = media.nextLyric?.let { piecesOf(it, maxWidth, size).firstOrNull() ?: it }
        if (lyric == null || media.lyricIndex >= 0) {
            unindexedLine = null
        } else if (lyric != unindexedLine) {
            unindexedLine = lyric
            unindexedLineAt = SystemClock.elapsedRealtime()
        }
        if (lyric != null && media.lyricIndex < 0 && media.nextLyric == null) {
            // Bluetooth / title / generic sources: no timing, so pieces are paced from when the line arrived
            // at an assumed singing speed; the next line replaces them whenever it comes.
            val pieces = piecesOf(lyric, maxWidth, size)
            if (pieces.size > 1) {
                val starts = LyricSplitter.pieceStarts(pieces, null)
                val piece = LyricSplitter.pieceAt(starts, SystemClock.elapsedRealtime() - unindexedLineAt)
                if (piece + 1 < pieces.size) schedulePiece(unindexedLineAt + starts[piece + 1])
                return lyricFrames.build(pieces[piece], -1, null, null, twoRows, preferTranslation, segment = piece, lineText = lyric)
            }
            return lyricFrames.build(lyric, -1, null, null, twoRows, preferTranslation)
        }
        if (lyric != null && media.lyricIndex >= 0 && startedAt != null) {
            val pieces = piecesOf(lyric, maxWidth, size)
            if (pieces.size > 1) {
                val duration = media.lineEndMs?.let { end -> media.lineStartMs?.let { end - it } }
                val starts = LyricSplitter.pieceStarts(pieces, duration)
                segment = LyricSplitter.pieceAt(starts, SystemClock.elapsedRealtime() - startedAt)
                current = pieces[segment]
                if (segment + 1 < pieces.size) {
                    next = pieces[segment + 1]
                    nextSameLine = true
                    schedulePiece(startedAt + starts[segment + 1])
                }
            }
        }
        return lyricFrames.build(current, media.lyricIndex, next, media.translatedLyric, twoRows, preferTranslation, segment, nextSameLine)
    }

    /** One redraw at the next piece boundary (elapsedRealtime); nothing runs in between. */
    private fun schedulePiece(atElapsed: Long) {
        if (atElapsed == pieceTimerAt) return
        handler.removeCallbacks(pieceTask)
        pieceTimerAt = atElapsed
        handler.postDelayed(pieceTask, (atElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0L) + PIECE_SLACK_MS)
    }

    /**
     * Current line bright and large, the context row (next / previous / translation) smaller and dimmer,
     * animated per the selected [MotionPreset]. Width / edge / vertical limits as in [drawFitted].
     */
    private fun drawLyric(canvas: Canvas, x: Float, y: Float, size: Float): RectF? {
        lyricDrawn = true
        val frame = lyricFrame(x, size)
        val preset = if (motionBroken) null else style.motionPreset
        if (preset != null) {
            val saveCount = canvas.save()
            try {
                return drawLyricRows(canvas, frame, x, y, size, preset).also { canvas.restoreToCount(saveCount) }
            } catch (error: Exception) {
                // Never let the animation take the lyric widget down (3 failures disable a widget):
                // fall back to hard cuts for the life of this view, with the canvas transform restored.
                canvas.restoreToCount(saveCount)
                motionBroken = true
                lyricMotion.reset()
                onDrawError?.invoke(WidgetType.LYRIC, "motion:" + error::class.java.name)
            }
        }
        return drawLyricRows(canvas, frame, x, y, size, null)
    }

    private fun drawLyricRows(canvas: Canvas, frame: LyricFrame, x: Float, y: Float, size: Float, preset: MotionPreset?): RectF? {
        val now = SystemClock.uptimeMillis()
        lyricMotion.setTarget(frame, now, preset)
        lyricMotion.advance(now)
        val rows = lyricMotion.rows
        if (rows.isEmpty()) return null
        val twoRows = frame.slots > 1
        val maxWidth = lyricMaxWidth(x)
        var primary = size
        var secondary = size * SECONDARY_SIZE
        if (twoRows) {
            // A long current line shrinks to fit; keep the dim row smaller than the bright one.
            frame.rows.firstOrNull { it.primary }?.let { current ->
                lyricPaint.size(primary)
                val needed = lyricPaint.measureText(current.text) + lyricPaint.strokeWidth
                val fitted = primary * (maxWidth / needed).coerceIn(TextFit.FLOOR_SCALE, 1f)
                secondary = minOf(secondary, fitted * MAX_CONTEXT_RATIO)
            }
        }
        var spacing = if (twoRows) (primary + secondary) / 2f * ROW_SPACING else primary * LINE_SPACING
        if (twoRows) {
            // Keep the whole two-row block inside the view vertically (rows are single-line here).
            val block = spacing + (primary + secondary) * ROW_HALF_HEIGHT + primary * style.strokeFraction
            val verticalRoom = 2f * minOf(y, height - y)
            if (block > verticalRoom && verticalRoom > 0f) {
                val scale = verticalRoom / block
                primary *= scale
                secondary *= scale
                spacing *= scale
            }
        }
        val middle = (frame.slots - 1) / 2f
        val contextFraction = style.contextLevel / 100f
        var result: RectF? = null
        try {
            rows.forEach { row ->
                val e = row.emphasis.value.coerceIn(0f, 1f)
                val rowSize = secondary + (primary - secondary) * e
                val cy = y + (row.slot.value - middle) * spacing
                val color = grey(style.level(contextFraction + (1f - contextFraction) * e))
                drawMotionRow(canvas, row, x, cy, y, rowSize, spacing, maxWidth, color, wrap = !twoRows)?.let { rect ->
                    if (!row.exiting) result = result?.apply { union(rect) } ?: RectF(rect)
                }
            }
        } finally {
            lyricPaint.maskFilter = null
            lyricPaint.color = grey(style.level())
        }
        return result
    }

    /**
     * Draws a row glyph by glyph (each with its own spring look). One row may wrap into two lines
     * (single-row mode), shrinks to fit [maxWidth], and is cut with "…" when even the smallest size is too wide.
     * Returns the resting bounds (animation offsets ignored) for the layout editor.
     */
    private fun drawMotionRow(
        canvas: Canvas, row: MotionRow, x: Float, cy: Float, restY: Float, size: Float, spacing: Float,
        maxWidth: Float, color: Int, wrap: Boolean,
    ): RectF? {
        val glyphs = row.glyphs
        if (glyphs.isEmpty()) return null
        val unit = row.measured ?: run {
            lyricPaint.textSize = MEASURE_SIZE
            FloatArray(glyphs.size) { lyricPaint.measureText(glyphs[it].text) / MEASURE_SIZE }.also { row.measured = it }
        }
        lyricPaint.size(size)
        val stroke = lyricPaint.strokeWidth
        fun width(range: IntRange, at: Float): Float {
            var sum = 0f
            for (i in range) sum += unit[i]
            return sum * at
        }
        val full = width(glyphs.indices, size) + stroke
        val breakAt = if (wrap && full * TextFit.MIN_SCALE > maxWidth && glyphs.size >= 2) wrapIndex(glyphs, unit) else -1
        val ranges = if (breakAt > 0) listOf(0 until breakAt, breakAt until glyphs.size) else listOf(glyphs.indices)
        val widest = ranges.maxOf { width(it, size) } + stroke
        var scale = (maxWidth / widest).coerceIn(TextFit.FLOOR_SCALE, 1f)
        if (wrap) {
            // Keep the block inside the view vertically, measured at the resting centre (not the moving one).
            val block = (lyricPaint.descent() - lyricPaint.ascent() + stroke) + size * LINE_SPACING * (ranges.size - 1)
            val room = 2f * minOf(restY, height - restY)
            if (block * scale > room && room > 0f) scale = room / block
        }
        val drawSize = size * scale
        lyricPaint.size(drawSize)
        val budget = maxWidth - lyricPaint.strokeWidth
        val lineHeight = drawSize * LINE_SPACING
        val ascent = lyricPaint.ascent()
        val descent = lyricPaint.descent()
        val baselineOffset = -(descent + ascent) / 2
        var bounds: RectF? = null
        ranges.forEachIndexed { lineIndex, range ->
            var visible = range
            var lineWidth = width(range, drawSize)
            var ellipsisWidth = 0f
            if (lineWidth > budget) {
                ellipsisWidth = lyricPaint.measureText(ELLIPSIS)
                var used = 0f
                var end = range.first
                while (end <= range.last && used + unit[end] * drawSize + ellipsisWidth <= budget) {
                    used += unit[end] * drawSize
                    end++
                }
                visible = range.first until end
                lineWidth = used + ellipsisWidth
            }
            val lineY = cy + (lineIndex - (ranges.size - 1) / 2f) * lineHeight
            val left = x - lineWidth / 2
            var gx = left
            for (i in visible) {
                val gw = unit[i] * drawSize
                drawGlyph(canvas, row, i, glyphs[i].text, gx, gw, x, lineY, baselineOffset, spacing, color)
                gx += gw
            }
            if (ellipsisWidth > 0f) {
                drawGlyph(canvas, row, if (visible.isEmpty()) range.first else visible.last, ELLIPSIS, gx, ellipsisWidth, x, lineY, baselineOffset, spacing, color)
            }
            val rect = RectF(left, lineY + baselineOffset + ascent, left + lineWidth, lineY + baselineOffset + descent)
            bounds = bounds?.apply { union(rect) } ?: rect
        }
        return bounds
    }

    private fun drawGlyph(
        canvas: Canvas, row: MotionRow, index: Int, text: String, gx: Float, gw: Float, centerX: Float,
        lineY: Float, baselineOffset: Float, spacing: Float, color: Int,
    ) {
        val look = row.look(index)
        if (look.alpha <= MIN_GLYPH_ALPHA) return
        val glyphCenter = gx + gw / 2
        val ox = if (row.wholeLine) centerX else glyphCenter
        lyricPaint.color = color
        lyricPaint.alpha = (look.alpha * 255f).roundToInt().coerceIn(0, 255)
        lyricPaint.maskFilter = blurFilter(look.blur)
        canvas.save()
        canvas.translate(ox, lineY + look.rise * spacing)
        if (look.scale != 1f) canvas.scale(look.scale, look.scale)
        // lyricPaint is centre-aligned.
        canvas.drawText(text, glyphCenter - ox, baselineOffset, lyricPaint)
        canvas.restore()
    }

    private fun blurFilter(radius: Float): MaskFilter? {
        if (radius < MIN_BLUR) return null
        val step = (radius * 2f).roundToInt().coerceAtLeast(1)
        return blurFilters.getOrPut(step) { BlurMaskFilter(step / 2f, BlurMaskFilter.Blur.NORMAL) }
    }

    /** Break index for a wrapped row: after punctuation near the middle when possible, balancing widths. */
    private fun wrapIndex(glyphs: List<MotionGlyph>, unit: FloatArray): Int {
        val n = glyphs.size
        val prefix = FloatArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + unit[i]
        val total = prefix[n]
        val middle = n / 2
        val preferred = (1 until n).filter { WRAP_BREAKS.contains(glyphs[it - 1].text) && abs(it - middle) <= n / 4 }
        return preferred.ifEmpty { (1 until n).toList() }.minBy { max(prefix[it], total - prefix[it]) }
    }

    /** Text is centred on the widget anchor, so the room is limited by the nearer screen edge,
     *  and by the user's width cap (HUD optics may show less than the whole display). */
    private fun lyricMaxWidth(x: Float): Float {
        val edgeRoom = 2f * minOf(x, width - x) - width * EDGE_MARGIN * 2f
        return minOf(width * style.textWidth / 100f, edgeRoom)
    }

    private fun drawFitted(canvas: Canvas, text: String, x: Float, y: Float, size: Float, textPaint: Paint, allowTwoLines: Boolean): RectF? {
        textPaint.size(size)
        val maxWidth = lyricMaxWidth(x)
        // The FILL_AND_STROKE outline adds strokeWidth beyond measureText (half on each end).
        val stroke = textPaint.strokeWidth
        val fit = TextFit.fit(text, maxWidth, { textPaint.measureText(it) + stroke }, allowTwoLines = allowTwoLines)
        if (fit.lines.isEmpty()) return null
        var scale = fit.scale
        // Keep the whole block inside the view vertically as well.
        val block = (textPaint.descent() - textPaint.ascent() + stroke) + textPaint.textSize * LINE_SPACING * (fit.lines.size - 1)
        val verticalRoom = 2f * minOf(y, height - y)
        if (block * scale > verticalRoom && verticalRoom > 0f) scale = verticalRoom / block
        textPaint.size(size * scale)
        val lineHeight = textPaint.textSize * LINE_SPACING
        val top = y - lineHeight * (fit.lines.size - 1) / 2
        var result: RectF? = null
        fit.lines.forEachIndexed { index, line ->
            val rect = canvas.drawCentered(line, x, top + index * lineHeight, textPaint)
            result = result?.apply { union(rect) } ?: rect
        }
        return result
    }

    private fun numberText(signal: VehicleSignal, suffix: String): String {
        val number = vehicle.value(signal)?.value as? Number ?: return "—"
        return formatNumber(number, RenderKey.DECIMALS[signal] ?: 1) + suffix
    }

    private fun enumText(signal: VehicleSignal): String = when (val value = vehicle.value(signal)?.value) {
        is Gear -> value.name
        is Enum<*> -> value.name
        is String -> value
        else -> "—"
    }

    private fun booleanValue(signal: VehicleSignal): Boolean? = vehicle.value(signal)?.value as? Boolean
    private fun unavailableText(): String = if (mode == DataMode.PREVIEW) "示例" else "不可用"

    private fun renderKey(layout: LayoutSnapshot, snapshot: VehicleSnapshot): Any = RenderKey.of(layout, snapshot)

    private fun formatNumber(number: Number, decimals: Int): String = RenderKey.formatNumber(number, decimals)

    private fun grey(level: Int) = Color.rgb(level, level, level)

    private fun applyStyle() {
        val fill = if (style.strokeFraction > 0f) Paint.Style.FILL_AND_STROKE else Paint.Style.FILL
        paint.color = grey(style.level())
        lyricPaint.color = grey(style.level())
        trackPaint.color = grey(style.level(.85f))
        dimPaint.color = grey(style.level(.72f))
        listOf(paint, lyricPaint, trackPaint, dimPaint).forEach { it.style = fill }
    }

    /** Text size plus the matching outline width for the current weight. */
    private fun Paint.size(textSize: Float) {
        this.textSize = textSize
        strokeWidth = textSize * this@HudCanvasView.style.strokeFraction
    }

    private fun currentClock(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    private fun Canvas.drawCentered(text: String, x: Float, y: Float, textPaint: Paint): RectF {
        val baseline = y - (textPaint.descent() + textPaint.ascent()) / 2
        drawText(text, x, baseline, textPaint)
        val half = textPaint.measureText(text) / 2
        return RectF(x - half, baseline + textPaint.ascent(), x + half, baseline + textPaint.descent())
    }

    private companion object {
        const val PREVIEW_LYRIC = "♪ 这里显示当前这一句歌词"
        const val PREVIEW_NEXT = "下一句会小一点、暗一点地等在下面"
        const val PREVIEW_TRANSLATION = "The current line, translated"
        const val PREVIEW_TRACK = "示例歌曲 · 歌手"
        /** Context row size relative to the current line. */
        const val SECONDARY_SIZE = .72f
        /** Centre-to-centre distance of the two rows, in mean row size. */
        const val ROW_SPACING = 1.32f
        /** Half of a row's ink height (CJK ascent + descent ≈ 1.2 × size), per row size. */
        const val ROW_HALF_HEIGHT = .6f
        /** The context row is at most this fraction of the fitted current line. */
        const val MAX_CONTEXT_RATIO = .85f
        const val MEASURE_SIZE = 100f
        const val MIN_GLYPH_ALPHA = .004f
        const val MIN_BLUR = .35f
        const val ELLIPSIS = "…"
        const val WRAP_BREAKS = " ，,。.、！!？?；;：:·-—/|"
        /** Redraw a few ms after a piece boundary so the new piece is already due. */
        const val PIECE_SLACK_MS = 15L
        const val LINE_SPACING = 1.18f
        const val EDGE_MARGIN = .02f

    }
}
