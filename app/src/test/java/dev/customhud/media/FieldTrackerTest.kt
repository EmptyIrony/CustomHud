package dev.customhud.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldTrackerTest {
    private val lyric = FieldKey("com.tencent.qqmusiccar", "meta:TITLE")
    private val album = FieldKey("com.tencent.qqmusiccar", "meta:ALBUM")

    @Test
    fun repeatedValueIsNotAChange() {
        val tracker = FieldTracker()
        assertTrue(tracker.update(lyric, "第一句", 0))
        assertFalse(tracker.update(lyric, "第一句", 10))
        assertEquals(0, tracker.get(lyric)!!.changeCount)
    }

    @Test
    fun frequentlyChangingFieldRanksFirst() {
        val tracker = FieldTracker()
        tracker.update(album, "专辑", 0)
        listOf("一", "二", "三", "四").forEachIndexed { i, line -> tracker.update(lyric, line, 1_000L * (i + 1)) }
        assertEquals(lyric, tracker.all().first().key)
        assertEquals(listOf(lyric to 4), tracker.frequentSince(500, 3))
        assertTrue(tracker.frequentSince(3_500, 3).isEmpty())
    }

    @Test
    fun blankIsAValueButNullIsAbsent() {
        val tracker = FieldTracker()
        tracker.update(lyric, "一句", 0)
        assertTrue(tracker.update(lyric, "  ", 1))
        assertEquals("", tracker.get(lyric)!!.value)
        assertFalse(tracker.update(album, null, 2))
        assertNull(tracker.get(album))
    }

    @Test
    fun specRoundTripsAndRejectsGarbage() {
        assertEquals(lyric, FieldKey.parse(lyric.spec))
        assertEquals(FieldKey("p", "event:x/y|z"), FieldKey.parse("p|event:x/y|z"))
        assertNull(FieldKey.parse(""))
        assertNull(FieldKey.parse("|x"))
        assertNull(FieldKey.parse("p|"))
    }

    @Test
    fun longValuesAreTruncated() {
        val tracker = FieldTracker(maxValueLength = 5)
        tracker.update(lyric, "1234567", 0)
        assertEquals("12345…", tracker.get(lyric)!!.value)
    }
}
