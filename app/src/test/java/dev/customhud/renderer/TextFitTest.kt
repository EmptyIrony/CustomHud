package dev.customhud.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextFitTest {
    private val measure: (String) -> Float = { it.length * 10f }

    @Test
    fun shortTextKeepsFullSize() {
        val fit = TextFit.fit("你好", 100f, measure)
        assertEquals(listOf("你好"), fit.lines)
        assertEquals(1f, fit.scale)
    }

    @Test
    fun slightlyLongTextShrinksOnOneLine() {
        val fit = TextFit.fit("一二三四五六七八九十十一", 100f, measure) // 120 wide, 0.83 ≥ minScale
        assertEquals(1, fit.lines.size)
        assertTrue(fit.scale < 1f && fit.scale > .8f)
    }

    @Test
    fun veryLongTextBreaksAtPunctuationNearMiddle() {
        val fit = TextFit.fit("我曾经跨过山和大海，也穿过人山人海", 100f, measure)
        assertEquals(listOf("我曾经跨过山和大海，", "也穿过人山人海"), fit.lines)
        assertTrue(fit.scale * 100 <= 100f)
    }

    @Test
    fun singleLineModeNeverBreaks() {
        val fit = TextFit.fit("我曾经跨过山和大海也穿过人山人海", 100f, measure, allowTwoLines = false)
        assertEquals(1, fit.lines.size)
        assertEquals(.625f, fit.scale)
        assertEquals(.3f, TextFit.fit("一".repeat(40), 100f, measure, allowTwoLines = false).scale) // floor
    }

    @Test
    fun blankTextDrawsNothing() {
        assertTrue(TextFit.fit("   ", 100f, measure).lines.isEmpty())
    }

    @Test
    fun neverWiderThanMaxWidthEvenAtFloorScale() {
        val oneLine = TextFit.fit("一".repeat(40), 100f, measure, allowTwoLines = false)
        assertEquals(.3f, oneLine.scale)
        assertTrue(oneLine.lines.single().endsWith("…"))
        assertTrue(measure(oneLine.lines.single()) * oneLine.scale <= 100f)

        val twoLines = TextFit.fit("二".repeat(100), 100f, measure)
        assertEquals(2, twoLines.lines.size)
        twoLines.lines.forEach { assertTrue(measure(it) * twoLines.scale <= 100f) }
    }

    @Test
    fun lineBreaksAndTabsBecomeSingleSpaces() {
        assertEquals(listOf("你好 世界"), TextFit.fit(" 你好\n\t世界 ", 1000f, measure).lines)
    }
}
