package dev.customhud.display.android

import java.util.concurrent.TimeUnit

/**
 * Lists the windows on one display from `dumpsys window windows` (top-most first, as WindowManager
 * prints them), so a photo shows whether the cluster's map / telltales are Android windows above ours.
 * Needs the DUMP permission (system uid); failures are reported as text.
 */
object WindowStack {
    data class Entry(val index: Int, val title: String, val type: String, val pkg: String, val visible: String, val format: String)

    fun query(displayId: Int): List<String> {
        val text = try {
            val process = ProcessBuilder("dumpsys", "window", "windows").redirectErrorStream(true).start()
            val out = StringBuilder()
            val reader = Thread {
                try {
                    process.inputStream.bufferedReader().useLines { lines -> lines.take(40_000).forEach { out.append(it).append('\n') } }
                } catch (_: Exception) {
                }
            }
            reader.start()
            if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroy()
            reader.join(2_000)
            out.toString()
        } catch (error: Exception) {
            return listOf("dumpsys 失败：${error::class.java.simpleName}: ${error.message}")
        }
        if (text.lineSequence().take(5).any { it.contains("Permission Denial", true) }) return listOf("dumpsys 被拒绝（需要 DUMP 权限 / system uid）")
        val entries = parse(text, displayId)
        if (entries.isEmpty()) return listOf("屏幕 $displayId 上没有解析到窗口；dumpsys 前几行：") + text.lineSequence().take(8).toList()
        return entries.map { e ->
            "#${e.index} ${e.title} · ${e.type} · ${e.pkg} · ${e.format} · ${e.visible}"
        }
    }

    /** Pure parser (unit-tested). Keeps the dumpsys order: index 0 is the top-most window. */
    fun parse(text: String, displayId: Int): List<Entry> {
        val result = mutableListOf<Entry>()
        var index = -1
        var title = ""
        var display: Int? = null
        var type = "?"
        var pkg = "?"
        var visible = ""
        var format = "?"
        fun flush() {
            if (index >= 0 && display == displayId) result += Entry(index, title, type, pkg, visible.ifEmpty { "可见性?" }, format)
        }
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            HEADER.find(line)?.let { m ->
                flush()
                index = m.groupValues[1].toInt()
                title = m.groupValues[2].substringAfter(' ').substringAfter(' ').ifEmpty { m.groupValues[2] }
                display = null
                type = "?"
                pkg = "?"
                visible = ""
                format = "?"
                return@let
            } ?: run {
                if (index < 0) return@run
                DISPLAY.find(line)?.let { display = it.groupValues[1].toInt() }
                PACKAGE.find(line)?.let { pkg = it.groupValues[1] }
                TYPE.find(line)?.let { type = it.groupValues[1] }
                FORMAT.find(line)?.let { format = it.groupValues[1] }
                VISIBLE.find(line)?.let { visible = "可见=${it.groupValues[1]}" }
            }
        }
        flush()
        return result
    }

    private val HEADER = Regex("""^Window #(\d+) Window\{([^}]*)\}""")
    private val DISPLAY = Regex("""mDisplayId=(\d+)""")
    private val PACKAGE = Regex("""\bpackage=([\w.]+)""")
    private val TYPE = Regex("""\bty=([A-Z_0-9]+)""")
    private val FORMAT = Regex("""\bfmt=([A-Z_0-9-]+)""")
    private val VISIBLE = Regex("""\bisVisible=(true|false)""")
}
