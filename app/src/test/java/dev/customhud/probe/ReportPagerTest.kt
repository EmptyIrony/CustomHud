package dev.customhud.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportPagerTest {
    @Test
    fun cjkCountsAsTwoColumns() {
        val lines = ReportPager.wrap("歌词歌词歌词歌词", 8)
        assertEquals("歌词歌词", lines[0])
        assertEquals("    歌词", lines[1])
        assertEquals("    歌词", lines[2])
    }

    @Test
    fun pagesNeverExceedRowsAndKeepAllText() {
        val input = (1..50).map { "line $it" }
        val pages = ReportPager.paginate(input, 40, 9)
        assertTrue(pages.all { it.size <= 9 })
        assertEquals(input, pages.flatten())
    }

    @Test
    fun sectionTitleIsNotStrandedAtPageBottom() {
        val input = (1..7).map { "row $it" } + "== 2 next ==" + "detail"
        val pages = ReportPager.paginate(input, 40, 9)
        assertEquals("== 2 next ==", pages[1].first())
    }
}
