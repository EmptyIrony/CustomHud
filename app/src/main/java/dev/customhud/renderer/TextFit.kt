package dev.customhud.renderer

/**
 * Fits one text into at most two centred lines within [maxWidth]: first by shrinking,
 * then by breaking near the middle (preferring spaces / punctuation). No scrolling.
 * The result never exceeds [maxWidth]: if even [floorScale] is too wide, lines are cut with "…".
 */
object TextFit {
    data class Fit(val lines: List<String>, val scale: Float)

    private const val BREAK_CHARS = " ，,。.、！!？?；;：:·-—/|"
    private const val ELLIPSIS = "…"
    const val FLOOR_SCALE = .3f
    const val MIN_SCALE = .62f
    private val WHITESPACE = Regex("\\s+")

    fun fit(
        text: String,
        maxWidth: Float,
        measure: (String) -> Float,
        minScale: Float = MIN_SCALE,
        allowTwoLines: Boolean = true,
        floorScale: Float = FLOOR_SCALE,
    ): Fit {
        // Vendor lyric text may carry line breaks / tabs; they would draw as boxes or overlap.
        val clean = text.replace(WHITESPACE, " ").trim()
        if (clean.isEmpty() || maxWidth <= 0f) return Fit(emptyList(), 1f)
        val full = measure(clean)
        if (full <= maxWidth) return Fit(listOf(clean), 1f)
        val lines = if (full * minScale <= maxWidth || !allowTwoLines || clean.length < 2) {
            listOf(clean)
        } else {
            split(clean, measure).toList()
        }
        val widest = lines.maxOf(measure)
        val scale = (maxWidth / widest).coerceIn(floorScale, 1f)
        val budget = maxWidth / scale
        return Fit(lines.map { if (measure(it) <= budget) it else ellipsize(it, budget, measure) }, scale)
    }

    /** Longest prefix of [line] plus "…" that fits in [budget] (unscaled units). */
    internal fun ellipsize(line: String, budget: Float, measure: (String) -> Float): String {
        var low = 0
        var high = line.length
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (measure(line.substring(0, mid).trimEnd() + ELLIPSIS) <= budget) low = mid else high = mid - 1
        }
        return line.substring(0, low).trimEnd() + ELLIPSIS
    }

    internal fun split(text: String, measure: (String) -> Float): Pair<String, String> {
        val preferred = (1 until text.length).filter { BREAK_CHARS.indexOf(text[it - 1]) >= 0 }
        val middle = text.length / 2
        // Only use punctuation breaks reasonably close to the middle; otherwise split anywhere.
        val near = preferred.filter { kotlin.math.abs(it - middle) <= text.length / 4 }
        val candidates = near.ifEmpty { (1 until text.length).toList() }
        val best = candidates.minBy { index ->
            maxOf(measure(text.substring(0, index).trim()), measure(text.substring(index).trim()))
        }
        return text.substring(0, best).trim() to text.substring(best).trim()
    }
}
