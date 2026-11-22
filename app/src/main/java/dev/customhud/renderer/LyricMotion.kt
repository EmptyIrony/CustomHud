package dev.customhud.renderer

import kotlin.math.abs
import kotlin.math.min

/**
 * One row of the lyric block. [slot] 0 is the top row. [primary] rows are the bright current line;
 * the others (next / previous / translation) are drawn smaller and dimmer.
 * [key] identifies a lyric line (or a piece of a split line) across frames, together with its text, so a
 * line that moves from the "next" slot to the "current" slot glides there instead of being redrawn.
 */
data class LyricRow(val key: Long, val text: String, val slot: Int, val primary: Boolean)

/** What the lyric widget shows at rest. [slots] = 1 (single row, may wrap) or 2 (two single-line rows). */
data class LyricFrame(val rows: List<LyricRow>, val slots: Int)

/**
 * Builds the lyric frame from what the media layer knows. Indexed sources (media-center LRC) give the
 * line index and the next line; un-indexed sources (Bluetooth, title-as-lyric, generic fields) only give
 * the current line, so two-row mode shows the previous line above it instead.
 *
 * Indexed two-row layout: current on top (slot 0), next / translation below (slot 1).
 * Un-indexed two-row layout: previous on top (slot 0), current below (slot 1).
 * Either way the current line stays in one place for the whole song; lines scroll upwards.
 *
 * A long line may be split into timed pieces ([segment] = piece shown now); [nextSameLine] says the "next"
 * text is the following piece of the same line rather than the next line's first piece.
 */
class LyricFrameBuilder {
    private var lastCurrent: String? = null
    private var lastShown: LyricRow? = null
    private var previousRow: LyricRow? = null
    private var sequence = 0L

    fun build(
        current: String?,
        index: Int,
        next: String?,
        translated: String?,
        twoRows: Boolean,
        preferTranslation: Boolean,
        segment: Int = 0,
        nextSameLine: Boolean = false,
        /** Un-indexed sources: the full line when [current] is one piece of it. */
        lineText: String? = current,
    ): LyricFrame {
        val slots = if (twoRows) 2 else 1
        val line = clean(current)
        val nextLine = clean(next)
        if (index >= 0 || nextLine != null) {
            lastCurrent = null
            lastShown = null
            previousRow = null
            val rows = ArrayList<LyricRow>(2)
            line?.let { rows += LyricRow(pieceKey(index, segment), it, 0, primary = true) }
            if (twoRows) {
                val translation = clean(translated)
                if (preferTranslation && translation != null && line != null) {
                    // One key per line: the translation stays put while the pieces of a split line change.
                    rows += LyricRow(pieceKey(index, 0) + 1, translation, 1, primary = false)
                } else if (nextLine != null) {
                    val key = if (nextSameLine) pieceKey(index, segment + 1) else pieceKey(index + 1, 0)
                    rows += LyricRow(key, nextLine, 1, primary = false)
                }
            }
            return LyricFrame(rows, slots)
        }
        // Un-indexed: a new full line bumps the sequence; pieces of it share the sequence.
        val whole = clean(lineText) ?: line
        if (whole != null && whole != lastCurrent) {
            lastCurrent = whole
            sequence += 1
        }
        if (line == null) return LyricFrame(emptyList(), slots)
        val current = LyricRow(UNINDEXED_BASE + (sequence * PIECE_SPAN + segment) * KEY_STRIDE, line, 1, primary = true)
        val shown = lastShown
        if (shown == null || shown.key != current.key || shown.text != current.text) {
            // What was current a moment ago (previous line, or previous piece of this one) moves up.
            if (shown != null) previousRow = shown
            lastShown = current
        }
        if (!twoRows) return LyricFrame(listOf(current.copy(slot = 0)), slots)
        val rows = ArrayList<LyricRow>(2)
        previousRow?.let { rows += it.copy(slot = 0, primary = false) }
        rows += current
        return LyricFrame(rows, slots)
    }

    fun reset() {
        lastCurrent = null
        lastShown = null
        previousRow = null
    }

    private fun clean(text: String?): String? = text?.replace(WHITESPACE, " ")?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        const val KEY_STRIDE = 4L
        /** Pieces per line in the key space (a split line has far fewer). */
        const val PIECE_SPAN = 64L
        private const val UNINDEXED_BASE = 1L shl 40
        private val WHITESPACE = Regex("\\s+")

        /** Key of piece [segment] of line [index]; the next piece / next line's first piece follow from it. */
        fun pieceKey(index: Int, segment: Int): Long = (index * PIECE_SPAN + segment) * KEY_STRIDE
    }
}

/** Damped spring parameters: [omega] stiffness in rad/s, [zeta] damping ratio (< 1 overshoots). */
data class SpringSpec(val omega: Float, val zeta: Float)

/** How new text appears: per-glyph springs started [delayMs] + i × [staggerMs] after the change. */
data class EnterSpec(
    val delayMs: Float, val staggerMs: Float, val spring: SpringSpec,
    /** Starts this many row spacings below its place. */
    val rise: Float,
    val scaleFrom: Float, val blurFrom: Float,
)

/** How leaving text disappears: per-glyph springs i × [staggerMs] apart, floating [rise] row spacings up. */
data class ExitSpec(val staggerMs: Float, val spring: SpringSpec, val rise: Float, val scaleTo: Float, val blurTo: Float)

/**
 * A motion style. Rows that stay (next → current) use [glide]; [enter] / [exit] animate glyphs.
 * Same numbers as docs/lyric-motion-preview.html.
 */
data class MotionPreset(val id: Int, val label: String, val glide: SpringSpec, val enter: EnterSpec, val exit: ExitSpec) {
    /** Whole-line styles scale around the line centre; staggered ones around each glyph. */
    val wholeLine: Boolean get() = enter.staggerMs == 0f

    companion object {
        const val OFF = 4
        val ALL = listOf(
            MotionPreset(
                0, "灵动弹入", SpringSpec(13f, .74f),
                EnterSpec(90f, 26f, SpringSpec(15f, .52f), rise = .55f, scaleFrom = .55f, blurFrom = 6f),
                ExitSpec(14f, SpringSpec(17f, .92f), rise = .45f, scaleTo = .86f, blurTo = 5f),
            ),
            MotionPreset(
                1, "轻盈波浪", SpringSpec(12f, .8f),
                EnterSpec(60f, 34f, SpringSpec(13f, .7f), rise = .35f, scaleFrom = .92f, blurFrom = 3f),
                ExitSpec(18f, SpringSpec(15f, 1f), rise = .3f, scaleTo = .96f, blurTo = 3f),
            ),
            MotionPreset(
                2, "丝滑滑动", SpringSpec(12f, .78f),
                EnterSpec(70f, 0f, SpringSpec(12f, .78f), rise = .6f, scaleFrom = 1f, blurFrom = 0f),
                ExitSpec(0f, SpringSpec(14f, 1f), rise = .6f, scaleTo = 1f, blurTo = 0f),
            ),
            MotionPreset(
                3, "柔光放大", SpringSpec(11f, .8f),
                EnterSpec(60f, 0f, SpringSpec(10f, .55f), rise = .12f, scaleFrom = .82f, blurFrom = 10f),
                ExitSpec(0f, SpringSpec(12f, 1f), rise = .15f, scaleTo = 1.1f, blurTo = 10f),
            ),
        )
        val LABELS = ALL.map { it.label } + "关闭"

        /** null = 关闭 (hard cuts). */
        fun byId(id: Int): MotionPreset? = ALL.getOrNull(id)
    }
}

/** Velocity-keeping damped spring, stepped with small fixed substeps so it is frame-rate independent. */
class Spring(initial: Float, var spec: SpringSpec) {
    var value = initial
        private set
    var velocity = 0f
        private set
    var target = initial
        private set
    var waitMs = 0f

    fun retarget(next: Float, nextSpec: SpringSpec = spec) {
        target = next
        spec = nextSpec
    }

    val busy: Boolean get() = waitMs > 0f || value != target || velocity != 0f

    fun step(dtMs: Float) {
        var dt = dtMs
        if (waitMs > 0f) {
            waitMs -= dt
            if (waitMs > 0f) return
            dt = -waitMs
            waitMs = 0f
        }
        if (value == target && velocity == 0f) return
        var remaining = dt / 1000f
        val w = spec.omega
        val z = spec.zeta
        while (remaining > 0f) {
            val h = min(SUBSTEP_S, remaining)
            val accel = -w * w * (value - target) - 2f * z * w * velocity
            velocity += accel * h
            value += velocity * h
            remaining -= h
        }
        if (abs(value - target) < SETTLE_X && abs(velocity) < SETTLE_V) {
            value = target
            velocity = 0f
        }
    }

    private companion object {
        const val SUBSTEP_S = 1f / 240f
        // Visible precision: 0.003 slot ≈ 0.2 px of a 65 px row step, 0.003 progress < 1/255 alpha.
        // Finer settling would only add identical frames (the HUD pulsed on redundant redraws).
        const val SETTLE_X = 3e-3f
        const val SETTLE_V = 3e-2f
    }
}

/** One glyph (code point) of a row with its enter (0 → 1) and exit (0 → 1) progress. */
class MotionGlyph(val text: String, val enter: Spring, var exit: Spring)

/** Look of one glyph at this instant; [rise] in row spacings (positive = lower), [blur] in px. */
data class GlyphLook(val alpha: Float, val rise: Float, val scale: Float, val blur: Float)

class MotionRow internal constructor(
    val key: Long,
    val text: String,
    var primary: Boolean,
    val slot: Spring,
    val emphasis: Spring,
    val glyphs: List<MotionGlyph>,
    val enterSpec: EnterSpec?,
) {
    var exitSpec: ExitSpec? = null
        internal set
    var exiting = false
        internal set
    /** Renderer cache (glyph widths at a reference size); not part of the motion state. */
    var measured: FloatArray? = null
    var wholeLine = true
        internal set

    val busy: Boolean get() = slot.busy || emphasis.busy || glyphs.any { it.enter.busy || it.exit.busy }

    fun look(index: Int): GlyphLook {
        val glyph = glyphs[index]
        val p = glyph.enter.value
        val q = glyph.exit.value
        val pa = p.coerceIn(0f, 1f)
        val qa = q.coerceIn(0f, 1f)
        val enter = enterSpec
        val exit = exitSpec
        val rise = (if (enter != null) (1f - p) * enter.rise else 0f) - (if (exit != null) q * exit.rise else 0f)
        val scale = (if (enter != null) lerp(enter.scaleFrom, 1f, p) else 1f) * (if (exit != null) lerp(1f, exit.scaleTo, q) else 1f)
        val blur = (if (enter != null) enter.blurFrom * (1f - pa) else 0f) + (if (exit != null) exit.blurTo * qa else 0f)
        return GlyphLook(alpha = pa * (1f - qa), rise = rise, scale = scale.coerceAtLeast(.01f), blur = blur)
    }

    internal fun step(dtMs: Float) {
        slot.step(dtMs)
        emphasis.step(dtMs)
        glyphs.forEach {
            it.enter.step(dtMs)
            it.exit.step(dtMs)
        }
    }

    internal val gone: Boolean get() = exiting && glyphs.all { it.exit.value >= 1f || !it.exit.busy && it.exit.value >= .999f }

    companion object {
        private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

        fun glyphsOf(text: String): List<String> {
            val result = ArrayList<String>(text.length)
            var i = 0
            while (i < text.length) {
                val count = Character.charCount(text.codePointAt(i))
                result += text.substring(i, i + count)
                i += count
            }
            return result
        }
    }
}

/**
 * Lyric transitions in the style of Apple Music: rows that stay glide to their new slot and grow / dim;
 * leaving glyphs float up and fade, new glyphs pop in from below, per [MotionPreset]. Springs keep their
 * velocity when a transition is interrupted, so nothing jumps. Pure: time is passed in. With no preset
 * every change is a hard cut and [isAnimating] is false (no extra frames).
 */
class LyricMotion {
    private val list = ArrayList<MotionRow>()
    val rows: List<MotionRow> get() = list
    var target: LyricFrame? = null
        private set
    private var lastMs = -1L

    fun setTarget(frame: LyricFrame, nowMs: Long, preset: MotionPreset?) {
        if (frame == target) return
        advance(nowMs)
        val old = target
        target = frame
        if (preset == null || old == null || old.slots != frame.slots) {
            list.clear()
            frame.rows.mapTo(list) { create(it, null) }
            return
        }
        val live = list.filter { !it.exiting }
        val used = HashSet<MotionRow>()
        fun match(row: LyricRow) = live.firstOrNull { it !in used && it.key == row.key && it.text == row.text }
        // Rows that stay tell which way the block scrolls; leaving rows keep moving that way.
        var shift = 0f
        for (row in frame.rows) {
            val m = match(row) ?: continue
            if (shift == 0f) shift = row.slot - m.slot.target
            used += m
        }
        used.clear()
        val next = ArrayList<MotionRow>(frame.rows.size)
        for (row in frame.rows) {
            val m = match(row)
            if (m != null) {
                used += m
                m.slot.retarget(row.slot.toFloat(), preset.glide)
                m.emphasis.retarget(if (row.primary) 1f else 0f, preset.glide)
                m.primary = row.primary
                next += m
            } else {
                next += create(row, preset)
            }
        }
        val leaving = ArrayList<MotionRow>()
        for (row in live) {
            if (row in used) continue
            row.exiting = true
            row.exitSpec = preset.exit
            row.wholeLine = row.wholeLine && preset.wholeLine
            row.slot.retarget(row.slot.target + shift, preset.glide)
            row.emphasis.retarget(0f, preset.glide)
            row.glyphs.forEachIndexed { i, glyph ->
                glyph.exit = Spring(glyph.exit.value, preset.exit.spring).apply {
                    retarget(1f)
                    waitMs = i * preset.exit.staggerMs
                }
            }
            leaving += row
        }
        val stillLeaving = list.filter { it.exiting && it !in leaving }
        list.clear()
        list += stillLeaving
        list += leaving
        list += next
    }

    /** Steps every spring to [nowMs] (a late frame is stepped at most [MAX_STEP_MS]) and drops finished rows. */
    fun advance(nowMs: Long) {
        val last = lastMs
        lastMs = nowMs
        if (last < 0) return
        val dt = (nowMs - last).coerceIn(0L, MAX_STEP_MS).toFloat()
        if (dt <= 0f) return
        list.forEach { it.step(dt) }
        list.removeAll { it.gone }
    }

    fun isAnimating(): Boolean = list.any { it.busy }

    fun reset() {
        list.clear()
        target = null
        lastMs = -1L
    }

    private fun create(row: LyricRow, preset: MotionPreset?): MotionRow {
        val enter = preset?.enter
        val glyphs = MotionRow.glyphsOf(row.text).mapIndexed { i, text ->
            val enterSpring = Spring(if (enter != null) 0f else 1f, enter?.spring ?: STATIC)
            if (enter != null) {
                enterSpring.retarget(1f)
                enterSpring.waitMs = enter.delayMs + i * enter.staggerMs
            }
            MotionGlyph(text, enterSpring, Spring(0f, preset?.exit?.spring ?: STATIC))
        }
        val glide = preset?.glide ?: STATIC
        return MotionRow(
            row.key, row.text, row.primary,
            Spring(row.slot.toFloat(), glide), Spring(if (row.primary) 1f else 0f, glide),
            glyphs, enter,
        ).also { it.wholeLine = preset?.wholeLine ?: true }
    }

    companion object {
        const val MAX_STEP_MS = 64L
        private val STATIC = SpringSpec(1f, 1f)
    }
}
