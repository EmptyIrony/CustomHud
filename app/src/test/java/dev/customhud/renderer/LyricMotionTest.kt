package dev.customhud.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricMotionTest {
    private fun lrc(b: LyricFrameBuilder, index: Int, cur: String?, next: String?, two: Boolean = true, tr: String? = null, preferTr: Boolean = false) =
        b.build(cur, index, next, tr, two, preferTr)

    private val bounce = MotionPreset.byId(0)!!

    /** Steps at 60 fps from [from] to [to]; returns the time reached. */
    private fun run(m: LyricMotion, from: Long, to: Long): Long {
        var t = from
        while (t < to) {
            t += 16
            m.advance(t)
        }
        return t
    }

    // ---- frame builder ----

    @Test
    fun indexedTwoRowsShowCurrentOnTopAndNextBelow() {
        val f = lrc(LyricFrameBuilder(), 3, "当前", "下一句")
        assertEquals(listOf("当前" to 0, "下一句" to 1), f.rows.map { it.text to it.slot })
        assertTrue(f.rows[0].primary)
        assertFalse(f.rows[1].primary)
    }

    @Test
    fun nextRowBecomesCurrentWithTheSameKey() {
        val b = LyricFrameBuilder()
        assertEquals(lrc(b, 3, "A", "B").rows[1].key, lrc(b, 4, "B", "C").rows[0].key)
    }

    @Test
    fun repeatedTextOnTheNextIndexIsANewFrame() {
        val b = LyricFrameBuilder()
        val a = lrc(b, 3, "啦啦", "啦啦")
        val c = lrc(b, 4, "啦啦", "完")
        assertNotEquals(a, c)
        assertEquals(a.rows[1].key, c.rows[0].key)
    }

    @Test
    fun piecesOfASplitLineChainTheirKeys() {
        val b = LyricFrameBuilder()
        val p0 = b.build("前半句", 5, "后半句", null, true, false, segment = 0, nextSameLine = true)
        val p1 = b.build("后半句", 5, "下一句", null, true, false, segment = 1, nextSameLine = false)
        val l6 = b.build("下一句", 6, null, null, true, false)
        assertEquals(p0.rows[1].key, p1.rows[0].key)
        assertEquals(p1.rows[1].key, l6.rows[0].key)
    }

    @Test
    fun translationStaysWhilePiecesChange() {
        val b = LyricFrameBuilder()
        val p0 = b.build("前半句", 5, "后半句", "Translation", true, true, segment = 0, nextSameLine = true)
        val p1 = b.build("后半句", 5, "下一句", "Translation", true, true, segment = 1)
        assertEquals(p0.rows[1], p1.rows[1])
    }

    @Test
    fun beforeTheFirstLineOnlyTheNextRowIsShown() {
        assertEquals(listOf("第一句" to 1), lrc(LyricFrameBuilder(), -1, null, "第一句").rows.map { it.text to it.slot })
    }

    @Test
    fun unindexedTwoRowsShowPreviousAboveCurrent() {
        val b = LyricFrameBuilder()
        val first = b.build("一", -1, null, null, true, false)
        assertEquals(listOf("一" to 1), first.rows.map { it.text to it.slot })
        val second = b.build("二", -1, null, null, true, false)
        assertEquals(listOf("一" to 0, "二" to 1), second.rows.map { it.text to it.slot })
        assertEquals(first.rows[0].key, second.rows[0].key)
        assertEquals(second, b.build(" 二 ", -1, null, null, true, false))
    }

    @Test
    fun unindexedPiecesScrollThroughPreviousRow() {
        val b = LyricFrameBuilder()
        val p0 = b.build("前半句", -1, null, null, true, false, segment = 0, lineText = "前半句后半句")
        val p1 = b.build("后半句", -1, null, null, true, false, segment = 1, lineText = "前半句后半句")
        assertEquals(listOf("前半句" to 0, "后半句" to 1), p1.rows.map { it.text to it.slot })
        assertEquals(p0.rows.last().key, p1.rows[0].key) // the piece glides up
        assertEquals(p1, b.build("后半句", -1, null, null, true, false, segment = 1, lineText = "前半句后半句"))
        val next = b.build("下一句", -1, null, null, true, false)
        assertEquals(listOf("后半句" to 0, "下一句" to 1), next.rows.map { it.text to it.slot })
        assertEquals(p1.rows.last().key, next.rows[0].key)
    }

    @Test
    fun oneRowModeIsCurrentOnly() {
        val f = lrc(LyricFrameBuilder(), 2, "当前", "下一句", two = false)
        assertEquals(listOf("当前"), f.rows.map { it.text })
        assertEquals(1, f.slots)
    }

    // ---- motion ----

    @Test
    fun everyPresetGlidesAndThenStopsRedrawing() {
        for (preset in MotionPreset.ALL) {
            val b = LyricFrameBuilder()
            val m = LyricMotion()
            m.setTarget(lrc(b, 3, "晚风把路灯点亮", "我们慢慢地开"), 0, preset)
            assertFalse(preset.label, m.isAnimating()) // first frame after reset: hard cut
            m.setTarget(lrc(b, 4, "我们慢慢地开", "收音机里的老歌"), 100, preset)
            assertTrue(preset.label, m.isAnimating())
            val end = run(m, 100, 3000)
            assertFalse(preset.label, m.isAnimating())
            assertEquals(preset.label, listOf(Triple("我们慢慢地开", 0f, 1f), Triple("收音机里的老歌", 1f, 0f)),
                m.rows.map { Triple(it.text, it.slot.value, it.emphasis.value) })
            assertTrue(preset.label, m.rows.all { row -> row.glyphs.indices.all { row.look(it) == GlyphLook(1f, 0f, 1f, 0f) } })
            m.advance(end + 16)
            assertFalse(m.isAnimating())
        }
    }

    @Test
    fun bounceRaisesNextLineLeavesOldAndPopsNewGlyphsInOrder() {
        val b = LyricFrameBuilder()
        val m = LyricMotion()
        m.setTarget(lrc(b, 3, "AAAA", "BBBB"), 0, bounce)
        m.setTarget(lrc(b, 4, "BBBB", "CCCCCCCC"), 0, bounce)
        run(m, 0, 160)
        val gliding = m.rows.single { it.text == "BBBB" }
        assertTrue(gliding.slot.value < 1f && gliding.emphasis.value > 0f)
        val leaving = m.rows.single { it.text == "AAAA" }
        assertTrue(leaving.exiting && leaving.look(0).alpha < 1f && leaving.look(0).rise < 0f)
        val entering = m.rows.single { it.text == "CCCCCCCC" }
        assertTrue(entering.look(0).alpha > entering.look(7).alpha) // staggered left to right
        assertTrue(entering.look(7).rise > 0f) // still below its place
    }

    @Test
    fun bounceOvershootsTheScale() {
        val m = LyricMotion()
        m.setTarget(LyricFrame(emptyList(), 2), 0, bounce)
        m.setTarget(LyricFrame(listOf(LyricRow(0, "弹", 0, true)), 2), 0, bounce)
        var peak = 0f
        var t = 0L
        while (t < 1500) {
            t += 8
            m.advance(t)
            peak = maxOf(peak, m.rows.single().look(0).scale)
        }
        assertTrue("peak=$peak", peak > 1.02f && peak < 1.2f)
    }

    @Test
    fun offIsAHardCutWithNoFrames() {
        val b = LyricFrameBuilder()
        val m = LyricMotion()
        m.setTarget(lrc(b, 3, "A", "B"), 0, null)
        m.setTarget(lrc(b, 4, "B", "C"), 10, null)
        assertFalse(m.isAnimating())
        assertEquals(listOf("B", "C"), m.rows.map { it.text })
    }

    @Test
    fun interruptionKeepsPositionContinuous() {
        val b = LyricFrameBuilder()
        val m = LyricMotion()
        m.setTarget(lrc(b, 3, "A", "B"), 0, bounce)
        m.setTarget(lrc(b, 4, "B", "C"), 0, bounce)
        val t = run(m, 0, 120)
        val before = m.rows.single { it.text == "C" }.slot.value
        m.setTarget(lrc(b, 5, "C", "D"), t, bounce)
        assertEquals(before, m.rows.single { it.text == "C" }.slot.value, 1e-4f)
        run(m, t, t + 3000)
        assertFalse(m.isAnimating())
        assertEquals(listOf("C", "D"), m.rows.map { it.text })
    }

    @Test
    fun fadingToNothingRemovesRows() {
        val b = LyricFrameBuilder()
        val m = LyricMotion()
        m.setTarget(lrc(b, 3, "A", "B"), 0, bounce)
        m.setTarget(LyricFrame(emptyList(), 2), 0, bounce)
        run(m, 0, 3000)
        assertTrue(m.rows.isEmpty())
        assertFalse(m.isAnimating())
    }

    @Test
    fun rowModeChangeIsAHardCut() {
        val m = LyricMotion()
        m.setTarget(lrc(LyricFrameBuilder(), 3, "A", "B"), 0, bounce)
        m.setTarget(lrc(LyricFrameBuilder(), 3, "A", "B", two = false), 10, bounce)
        assertFalse(m.isAnimating())
    }

    @Test
    fun lateFramesAreSteppedAtMostMaxStep() {
        val m = LyricMotion()
        m.setTarget(LyricFrame(emptyList(), 1), 0, bounce)
        m.setTarget(LyricFrame(listOf(LyricRow(0, "字", 0, true)), 1), 0, bounce)
        m.advance(10_000) // one huge gap: only MAX_STEP_MS is simulated, no jump to the end
        assertTrue(m.isAnimating())
    }

    @Test
    fun surrogatePairsAreOneGlyph() {
        assertEquals(listOf("a", "😀", "b"), MotionRow.glyphsOf("a😀b"))
    }
}
