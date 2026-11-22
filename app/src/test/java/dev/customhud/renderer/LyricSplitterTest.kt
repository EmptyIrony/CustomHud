package dev.customhud.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricSplitterTest {
    /** CJK 10 px, ASCII 5 px. */
    private val measure: (String) -> Float = { s -> MotionRow.glyphsOf(s).sumOf { if (it[0].code < 0x80) 5.0 else 10.0 }.toFloat() }

    @Test
    fun offNeverSplits() {
        assertEquals(1, LyricSplitter.split("一".repeat(40), LyricSplitter.OFF, 8, 100f, measure).size)
    }

    @Test
    fun autoKeepsLinesThatFitOrShrinkALittle() {
        assertEquals(listOf("一二三四五六七八九十"), LyricSplitter.split("一二三四五六七八九十", LyricSplitter.AUTO, 0, 100f, measure))
        // 110 px at 100 px: 0.91 scale is fine.
        assertEquals(1, LyricSplitter.split("一二三四五六七八九十一", LyricSplitter.AUTO, 0, 100f, measure).size)
    }

    @Test
    fun autoSplitsAtPunctuationNearTheMiddle() {
        val pieces = LyricSplitter.split("就算整座城市都睡着了，我们的车灯也还亮着", LyricSplitter.AUTO, 0, 120f, measure)
        assertEquals(listOf("就算整座城市都睡着了", "我们的车灯也还亮着"), pieces)
        assertTrue(pieces.all { measure(it) <= 120f })
    }

    @Test
    fun autoUsesMorePiecesForVeryLongLines() {
        val pieces = LyricSplitter.split("一".repeat(30), LyricSplitter.AUTO, 0, 100f, measure)
        assertEquals(3, pieces.size)
        assertEquals(listOf(10, 10, 10), pieces.map { it.length })
    }

    @Test
    fun charsModeUsesTheLimit() {
        assertEquals(1, LyricSplitter.split("一二三四五六七八", LyricSplitter.CHARS, 8, 1000f, measure).size)
        val pieces = LyricSplitter.split("一二三四五六七八九十", LyricSplitter.CHARS, 8, 1000f, measure)
        assertEquals(listOf("一二三四五", "六七八九十"), pieces)
    }

    @Test
    fun latinWordsAreNotCut() {
        val pieces = LyricSplitter.split("we drive slowly along the coastline tonight", LyricSplitter.CHARS, 12, 1000f, measure)
        assertTrue(pieces.size >= 2)
        val words = "we drive slowly along the coastline tonight".split(" ").toSet()
        pieces.forEach { piece -> piece.split(" ").forEach { assertTrue("cut word in '$piece'", it in words) } }
    }

    @Test
    fun piecesArePacedBySingingTimeNotInstrumentalGaps() {
        val pieces = listOf("一二三四五", "六七八九十")
        // 4 s line: proportional halves.
        assertEquals(listOf(0L, 2000L), LyricSplitter.pieceStarts(pieces, 4000).toList())
        // 30 s until the next line (long instrumental): capped at 420 ms per character.
        assertEquals(listOf(0L, 2100L), LyricSplitter.pieceStarts(pieces, 30_000).toList())
        // Unknown end (last line): 300 ms per character.
        assertEquals(listOf(0L, 1500L), LyricSplitter.pieceStarts(pieces, null).toList())
        val starts = LyricSplitter.pieceStarts(pieces, 4000)
        assertEquals(0, LyricSplitter.pieceAt(starts, 0))
        assertEquals(0, LyricSplitter.pieceAt(starts, 1999))
        assertEquals(1, LyricSplitter.pieceAt(starts, 2000))
    }
}
