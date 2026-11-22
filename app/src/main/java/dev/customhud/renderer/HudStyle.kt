package dev.customhud.renderer

import android.content.Context
import android.content.SharedPreferences

/**
 * HUD text appearance shared by the device window and the layout preview.
 * [weight] 0 = 常规, 1 = 加粗, 2 = 特粗 (extra stroke around glyphs = more lit pixels).
 * [brightness] 40..100 % of pure white. [redrawOnlyOnChange] skips frames whose visible content
 * is identical, so a static HUD is not re-composited several times a second.
 * [textWidth] caps lyric / track lines at this % of the HUD width, for HUD optics that cut the edges.
 * [lyricRows] 1 = current line only (long lines wrap), 2 = current line plus a dimmer context row.
 * [lyricSecond] what the context row shows for media-center lyrics: 0 = next line, 1 = translation when there is one.
 * [lyricMotionStyle] index into [MotionPreset.ALL]; [MotionPreset.OFF] = hard cuts and no extra frames (flicker A/B).
 * [splitMode] / [splitChars] long-line splitting ([LyricSplitter]): pieces are shown in turn within the line's time.
 * [contextLevel] brightness of the context row in % of the current line.
 */
data class HudStyle(
    val weight: Int = 1,
    val brightness: Int = 100,
    val redrawOnlyOnChange: Boolean = true,
    val textWidth: Int = DEFAULT_TEXT_WIDTH,
    val lyricRows: Int = 2,
    val lyricSecond: Int = SECOND_NEXT,
    val lyricMotionStyle: Int = 0,
    val contextLevel: Int = DEFAULT_CONTEXT_LEVEL,
    val splitMode: Int = LyricSplitter.AUTO,
    val splitChars: Int = DEFAULT_SPLIT_CHARS,
) {
    val motionPreset: MotionPreset? get() = MotionPreset.byId(lyricMotionStyle)

    /** Stroke width as a fraction of text size for the FILL_AND_STROKE outline. */
    val strokeFraction: Float get() = STROKES.getOrElse(weight) { STROKES.last() }

    fun level(fraction: Float = 1f): Int = (255f * brightness.coerceIn(MIN_BRIGHTNESS, 100) / 100f * fraction).toInt().coerceIn(0, 255)

    companion object {
        const val MIN_BRIGHTNESS = 40
        const val DEFAULT_TEXT_WIDTH = 80
        const val MIN_TEXT_WIDTH = 30
        const val MAX_TEXT_WIDTH = 100
        const val TEXT_WIDTH_STEP = 5
        const val DEFAULT_SPLIT_CHARS = 12
        val SPLIT_CHAR_STEPS = listOf(6, 8, 10, 12, 14, 16, 18, 20, 24)
        const val SECOND_NEXT = 0
        const val SECOND_TRANSLATION = 1
        val SECOND_LABELS = listOf("下一句", "翻译优先")
        const val DEFAULT_CONTEXT_LEVEL = 72
        val CONTEXT_LEVEL_STEPS = listOf(85, 72, 60, 50)
        val WEIGHT_LABELS = listOf("常规", "加粗", "特粗")
        private val STROKES = listOf(0f, .035f, .07f)
    }
}

class HudStylePrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("hud-style", Context.MODE_PRIVATE)

    var style: HudStyle
        get() = HudStyle(
            weight = prefs.getInt("weight", 1).coerceIn(0, HudStyle.WEIGHT_LABELS.lastIndex),
            brightness = prefs.getInt("brightness", 100).coerceIn(HudStyle.MIN_BRIGHTNESS, 100),
            redrawOnlyOnChange = prefs.getBoolean("redrawOnlyOnChange", true),
            textWidth = prefs.getInt("textWidth", HudStyle.DEFAULT_TEXT_WIDTH)
                .coerceIn(HudStyle.MIN_TEXT_WIDTH, HudStyle.MAX_TEXT_WIDTH),
            lyricRows = prefs.getInt("lyricRows", 2).coerceIn(1, 2),
            lyricSecond = prefs.getInt("lyricSecond", HudStyle.SECOND_NEXT).coerceIn(0, HudStyle.SECOND_LABELS.lastIndex),
            // Older builds stored an on/off switch; off maps to 关闭.
            lyricMotionStyle = prefs.getInt("lyricMotionStyle", if (prefs.getBoolean("lyricMotion", true)) 0 else MotionPreset.OFF)
                .coerceIn(0, MotionPreset.OFF),
            contextLevel = prefs.getInt("contextLevel", HudStyle.DEFAULT_CONTEXT_LEVEL)
                .coerceIn(HudStyle.CONTEXT_LEVEL_STEPS.min(), HudStyle.CONTEXT_LEVEL_STEPS.max()),
            splitMode = prefs.getInt("splitMode", LyricSplitter.AUTO).coerceIn(0, LyricSplitter.MODE_LABELS.lastIndex),
            splitChars = prefs.getInt("splitChars", HudStyle.DEFAULT_SPLIT_CHARS)
                .coerceIn(HudStyle.SPLIT_CHAR_STEPS.min(), HudStyle.SPLIT_CHAR_STEPS.max()),
        )
        set(value) {
            prefs.edit()
                .putInt("weight", value.weight)
                .putInt("brightness", value.brightness)
                .putBoolean("redrawOnlyOnChange", value.redrawOnlyOnChange)
                .putInt("textWidth", value.textWidth)
                .putInt("lyricRows", value.lyricRows)
                .putInt("lyricSecond", value.lyricSecond)
                .putInt("lyricMotionStyle", value.lyricMotionStyle)
                .putInt("splitMode", value.splitMode)
                .putInt("splitChars", value.splitChars)
                .putInt("contextLevel", value.contextLevel)
                .apply()
        }

    /** Returns an unregister function. SharedPreferences keeps listeners weakly, so we hold it. */
    fun observe(listener: (HudStyle) -> Unit): () -> Unit {
        val callback = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> listener(style) }
        prefs.registerOnSharedPreferenceChangeListener(callback)
        held += callback
        return {
            prefs.unregisterOnSharedPreferenceChangeListener(callback)
            held -= callback
        }
    }

    private companion object {
        val held = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
    }
}
