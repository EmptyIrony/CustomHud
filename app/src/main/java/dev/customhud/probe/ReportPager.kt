package dev.customhud.probe

/** Wraps report lines for a monospace screen (CJK = 2 columns) and splits them into photo pages. */
object ReportPager {
    private const val CONTINUATION = "    "

    fun columnsOf(ch: Char): Int = if (ch.code >= 0x2E80) 2 else 1

    fun wrap(line: String, columns: Int): List<String> {
        if (columns <= CONTINUATION.length + 1) return listOf(line)
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var used = 0
        for (ch in line) {
            val w = columnsOf(ch)
            if (used + w > columns) {
                result += current.toString()
                current.clear()
                current.append(CONTINUATION)
                used = CONTINUATION.length
            }
            current.append(ch)
            used += w
        }
        result += current.toString()
        return result
    }

    fun paginate(lines: List<String>, columns: Int, rows: Int): List<List<String>> {
        val safeRows = rows.coerceAtLeast(4)
        val pages = mutableListOf<MutableList<String>>()
        var page = mutableListOf<String>()
        for (line in lines) {
            val wrapped = wrap(line, columns)
            // Start a section on a fresh page rather than leaving its title stranded at the bottom.
            val isSection = line.startsWith("== ")
            if (page.isNotEmpty() && (page.size + wrapped.size > safeRows || (isSection && safeRows - page.size < 4))) {
                pages += page
                page = mutableListOf()
            }
            if (page.isEmpty() && line.isBlank()) continue
            wrapped.forEach { part ->
                if (page.size >= safeRows) {
                    pages += page
                    page = mutableListOf()
                }
                page += part
            }
        }
        if (page.isNotEmpty()) pages += page
        return pages
    }
}
