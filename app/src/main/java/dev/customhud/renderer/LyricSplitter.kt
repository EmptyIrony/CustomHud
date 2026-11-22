package dev.customhud.renderer

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * Splits a long lyric line into pieces shown one after another within the line's time, so a long line
 * reads as two or more short ones instead of being shrunk. Pure (measure is injected).
 *
 * Modes: [OFF]; [AUTO] = split when the line would have to shrink below [AUTO_MIN_SCALE] to fit;
 * [CHARS] = split when the line is longer than maxChars (CJK = 1, Latin letter / digit = ½, space and
 * punctuation = ¼). Pieces are balanced and break after punctuation / spaces when one is near, never
 * inside a Latin word.
 */
object LyricSplitter {
    const val OFF = 0
    const val AUTO = 1
    const val CHARS = 2
    val MODE_LABELS = listOf("关", "自动（超宽时）", "按字数")

    const val AUTO_MIN_SCALE = .85f
    const val PIECE_MIN_SCALE = .95f
    /** Singing rarely takes longer than this per CJK character; longer gaps are instrumental. */
    const val MAX_MS_PER_UNIT = 420L
    /** Used when the line's end is unknown (last line). */
    const val DEFAULT_MS_PER_UNIT = 300L
    const val MAX_PIECES = 4

    private val SOFT_END = charArrayOf('，', ',', '、', '；', ';')
    private const val BREAK_AFTER = " ，,。.、！!？?；;：:·-—/|）)」』】"

    fun split(text: String, mode: Int, maxChars: Int, maxWidth: Float, measure: (String) -> Float): List<String> {
        val clean = text.trim()
        if (clean.isEmpty()) return emptyList()
        val glyphs = MotionRow.glyphsOf(clean)
        if (glyphs.size < 4) return listOf(clean)
        val pieces = when (mode) {
            AUTO -> {
                if (maxWidth <= 0f) return listOf(clean)
                val full = measure(clean)
                if (full * AUTO_MIN_SCALE <= maxWidth) return listOf(clean)
                // Fewest pieces that each fit at ≥ 95 % size.
                ceil(full * PIECE_MIN_SCALE / maxWidth).toInt()
            }
            CHARS -> {
                val units = units(clean)
                if (maxChars <= 0 || units <= maxChars + .01f) return listOf(clean)
                ceil(units / maxChars).toInt()
            }
            else -> return listOf(clean)
        }.coerceIn(2, MAX_PIECES).coerceAtMost(glyphs.size / 2)
        if (pieces < 2) return listOf(clean)
        val weights = if (mode == AUTO) glyphs.map(measure) else glyphs.map(::unit)
        return cut(glyphs, weights, pieces)
    }

    /** Start of each piece, in ms after the line starts, proportional to its share of the singing time. */
    fun pieceStarts(pieces: List<String>, lineDurationMs: Long?): LongArray {
        val units = pieces.map { max(units(it), .5f) }
        val total = units.sum()
        val singing = (total * MAX_MS_PER_UNIT).toLong()
        val duration = when {
            lineDurationMs == null || lineDurationMs <= 0 -> (total * DEFAULT_MS_PER_UNIT).toLong()
            else -> minOf(lineDurationMs, singing)
        }
        val starts = LongArray(pieces.size)
        var acc = 0f
        for (i in pieces.indices) {
            starts[i] = (duration * acc / total).toLong()
            acc += units[i]
        }
        return starts
    }

    /** Index of the piece shown [elapsedMs] after the line started. */
    fun pieceAt(starts: LongArray, elapsedMs: Long): Int {
        var index = 0
        for (i in starts.indices) if (starts[i] <= elapsedMs) index = i
        return index
    }

    fun units(text: String): Float = MotionRow.glyphsOf(text).sumOf { unit(it).toDouble() }.toFloat()

    private fun unit(glyph: String): Float {
        val c = glyph.codePointAt(0)
        return when {
            Character.isWhitespace(c) -> .25f
            c < 0x80 && Character.isLetterOrDigit(c) -> .5f
            c < 0x80 -> .25f
            BREAK_AFTER.contains(glyph) -> .25f
            else -> 1f
        }
    }

    private fun isWordChar(glyph: String): Boolean {
        val c = glyph.codePointAt(0)
        return c < 0x250 && Character.isLetterOrDigit(c) || glyph == "'"
    }

    private fun cut(glyphs: List<String>, weights: List<Float>, pieces: Int): List<String> {
        val prefix = FloatArray(glyphs.size + 1)
        for (i in glyphs.indices) prefix[i + 1] = prefix[i] + weights[i]
        val total = prefix.last()
        val pieceSize = total / pieces
        val cuts = ArrayList<Int>()
        var previous = 0
        for (k in 1 until pieces) {
            val ideal = total * k / pieces
            var best = -1
            var bestCost = Float.MAX_VALUE
            // Leave at least one glyph per remaining piece.
            for (j in previous + 1..glyphs.size - (pieces - k)) {
                var cost = abs(prefix[j] - ideal) / pieceSize
                if (!BREAK_AFTER.contains(glyphs[j - 1]) && glyphs[j] != " ") cost += .35f
                if (isWordChar(glyphs[j - 1]) && isWordChar(glyphs[j])) cost += 2f
                if (cost < bestCost) {
                    bestCost = cost
                    best = j
                }
            }
            if (best <= previous) break
            cuts += best
            previous = best
        }
        val result = ArrayList<String>(pieces)
        var start = 0
        for (end in cuts + glyphs.size) {
            // A piece reads as its own line: no dangling comma at its end.
            val piece = glyphs.subList(start, end).joinToString("").trim().trimEnd(*SOFT_END).trim()
            if (piece.isNotEmpty()) result += piece
            start = end
        }
        return result.ifEmpty { listOf(glyphs.joinToString("").trim()) }
    }
}
