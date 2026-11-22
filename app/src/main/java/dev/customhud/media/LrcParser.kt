package dev.customhud.media

import org.json.JSONObject

data class LyricLine(
    val timeMs: Long,
    val text: String,
    val translatedText: String? = null,
)

/** Line-synchronised lyrics: LRC text or the `data.lrclist[]` JSON form. Pure, no Android types. */
object LrcParser {
    private val TIMESTAMP = Regex("""\[(\d+):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val ENHANCED_TAG = Regex("<[^>]*>")

    fun parse(raw: String?): List<LyricLine> {
        if (raw.isNullOrBlank()) return emptyList()
        val text = raw.removePrefix("\uFEFF").trim()
        if (text == "null") return emptyList()
        if (text.startsWith("{")) return parseJson(text)
        val result = ArrayList<LyricLine>()
        text.lineSequence().forEach { source ->
            val line = ENHANCED_TAG.replace(source, "").trim()
            // Only the run of timestamps at the start of the line counts; metadata like [offset:100] never matches.
            val times = ArrayList<Long>(2)
            var position = 0
            while (true) {
                val match = TIMESTAMP.find(line, position)?.takeIf { it.range.first == position } ?: break
                toMillis(match)?.let(times::add)
                position = match.range.last + 1
            }
            if (times.isEmpty()) return@forEach
            val lyric = line.substring(position).trim()
            if (lyric.isEmpty() || lyric == "//") return@forEach
            times.forEach { result += LyricLine(it, lyric) }
        }
        result.sortBy { it.timeMs } // stable: equal timestamps keep file order
        return result
    }

    /** Attaches translations with exactly the same timestamp; never by index. */
    fun mergeByTime(original: List<LyricLine>, translated: List<LyricLine>): List<LyricLine> {
        if (translated.isEmpty()) return original
        val byTime = HashMap<Long, String>(translated.size)
        translated.forEach { byTime.putIfAbsent(it.timeMs, it.text) }
        return original.map { line -> byTime[line.timeMs]?.let { line.copy(translatedText = it) } ?: line }
    }

    /** Index of the last line with time <= [positionMs], or -1 before the first line. */
    fun currentIndex(lines: List<LyricLine>, positionMs: Long): Int {
        var low = 0
        var high = lines.lastIndex
        var answer = -1
        while (low <= high) {
            val middle = (low + high).ushr(1)
            if (lines[middle].timeMs <= positionMs) {
                answer = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return answer
    }

    private fun toMillis(match: MatchResult): Long? {
        val minute = match.groupValues[1].toLongOrNull() ?: return null
        val second = match.groupValues[2].toLongOrNull() ?: return null
        if (second !in 0..59) return null
        val fraction = match.groupValues[3]
        val millis = when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLong() * 100L
            2 -> fraction.toLong() * 10L // centiseconds
            else -> fraction.take(3).toLong()
        }
        return (minute * 60L + second) * 1000L + millis
    }

    private fun parseJson(text: String): List<LyricLine> = try {
        val list = JSONObject(text).optJSONObject("data")?.optJSONArray("lrclist")
        val result = ArrayList<LyricLine>()
        if (list != null) {
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val seconds = item.optString("time").toDoubleOrNull() ?: continue
                val lyric = item.optString("lineLyric").trim()
                if (seconds < 0 || lyric.isEmpty() || lyric == "//") continue
                result += LyricLine((seconds * 1000).toLong(), lyric)
            }
        }
        result.sortBy { it.timeMs }
        result
    } catch (_: Exception) {
        emptyList()
    }
}
