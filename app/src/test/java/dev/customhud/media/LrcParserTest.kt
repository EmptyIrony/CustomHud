package dev.customhud.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {
    @Test
    fun parsesCentisecondsMillisAndRepeatedTags() {
        val lines = LrcParser.parse("\uFEFF[ti:歌名]\n[offset:100]\n[00:12.50]第一句\n[00:18.00][00:25.123]副歌\n[01:02]无小数\n[00:30.5]一位小数")
        assertEquals(listOf(12_500L, 18_000L, 25_123L, 30_500L, 62_000L), lines.map { it.timeMs })
        assertEquals(listOf("第一句", "副歌", "副歌", "一位小数", "无小数"), lines.map { it.text })
    }

    @Test
    fun dropsEmptySlashAndEnhancedTagsAndKeepsBracketsInsideText() {
        val lines = LrcParser.parse("[00:01.00]\n[00:02.00]//\n[00:03.00]<00:03.00>逐<00:03.50>字\n[00:04.00]歌词[live]版")
        assertEquals(listOf("逐字", "歌词[live]版"), lines.map { it.text })
    }

    @Test
    fun invalidInputsAreEmpty() {
        assertTrue(LrcParser.parse(null).isEmpty())
        assertTrue(LrcParser.parse("  ").isEmpty())
        assertTrue(LrcParser.parse("null").isEmpty())
        assertTrue(LrcParser.parse("纯文本歌词，没有时间").isEmpty())
        assertTrue(LrcParser.parse("[00:75.00]秒数非法").isEmpty())
        assertTrue(LrcParser.parse("{ broken json").isEmpty())
    }

    @Test
    fun parsesJsonLrcList() {
        val lines = LrcParser.parse("""{"data":{"lrclist":[{"time":"18.00","lineLyric":"第二句"},{"time":"12.5","lineLyric":"第一句"},{"time":"x","lineLyric":"坏"}]}}""")
        assertEquals(listOf(12_500L to "第一句", 18_000L to "第二句"), lines.map { it.timeMs to it.text })
    }

    @Test
    fun mergesTranslationByExactTimestampOnly() {
        val original = LrcParser.parse("[00:01.00]one\n[00:02.00]two\n[00:03.00]three")
        val translated = LrcParser.parse("[00:01.00]一\n[00:03.00]三\n[00:04.00]多余")
        val merged = LrcParser.mergeByTime(original, translated)
        assertEquals(listOf("一", null, "三"), merged.map { it.translatedText })
    }

    @Test
    fun currentIndexFindsLastLineAtOrBeforePosition() {
        val lines = LrcParser.parse("[00:01.00]a\n[00:02.00]b\n[00:03.00]c")
        assertEquals(-1, LrcParser.currentIndex(lines, 999))
        assertEquals(0, LrcParser.currentIndex(lines, 1_000))
        assertEquals(1, LrcParser.currentIndex(lines, 2_999))
        assertEquals(2, LrcParser.currentIndex(lines, 600_000))
        assertEquals(-1, LrcParser.currentIndex(emptyList(), 5_000))
        assertNull(lines.getOrNull(LrcParser.currentIndex(lines, 0)))
    }
}
