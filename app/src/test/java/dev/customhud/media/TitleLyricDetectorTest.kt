package dev.customhud.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleLyricDetectorTest {
    private val song = "Just for the Night（Artist）"

    @Test
    fun latchesOnQuickTitleChangesWithSameArtist() {
        val d = TitleLyricDetector()
        assertFalse(d.update("6|bt", "Just for the Night", "Artist", false, 0))
        assertFalse(d.update("6|bt", "Whenever the night gets cold", song, false, 5_000)) // new song: anchor
        assertFalse(d.update("6|bt", "Line two", song, false, 9_000))
        assertFalse(d.update("6|bt", "Line three", song, false, 13_000))
        assertTrue(d.update("6|bt", "Line four", song, false, 17_000))
        // Sticky across songs and long instrumental gaps on the same source.
        assertTrue(d.update("6|bt", "Next song first line", "Other（Artist）", false, 200_000))
        assertTrue(d.update("6|bt", "Next song first line", "Other（Artist）", false, 260_000))
    }

    @Test
    fun normalAlbumPlaybackDoesNotLatch() {
        val d = TitleLyricDetector()
        var now = 0L
        for (i in 0 until 10) {
            assertFalse(d.update("6|qq", "Track $i", "Same Artist", false, now))
            now += 180_000
        }
    }

    @Test
    fun skippingAFewTracksQuicklyDoesNotLatch() {
        val d = TitleLyricDetector()
        d.update("6|qq", "Track 0", "Same Artist", false, 0)
        assertFalse(d.update("6|qq", "Track 1", "Same Artist", false, 200_000))
        assertFalse(d.update("6|qq", "Track 2", "Same Artist", false, 205_000))
        assertFalse(d.update("6|qq", "Track 3", "Same Artist", false, 210_000))
    }

    @Test
    fun titleChangesWithChangingArtistDoNotLatch() {
        val d = TitleLyricDetector()
        var now = 0L
        for (i in 0 until 8) {
            assertFalse(d.update("6|qq", "Track $i", "Artist $i", false, now))
            now += 3_000
        }
    }

    @Test
    fun realLyricsOrSourceChangeUnlatch() {
        val d = TitleLyricDetector()
        listOf("a", "b", "c", "d").forEachIndexed { i, t -> d.update("2|bt", t, song, false, i * 4_000L) }
        assertTrue(d.latched)
        assertFalse(d.update("6|qq", "Song", "Artist", false, 20_000))
        listOf("a", "b", "c", "d").forEachIndexed { i, t -> d.update("2|bt", t, song, false, 30_000 + i * 4_000L) }
        assertTrue(d.latched)
        assertFalse(d.update("2|bt", "e", song, true, 50_000))
    }

    @Test
    fun recognisesBluetoothPackages() {
        assertTrue(TitleLyricDetector.looksBluetooth("com.ecarx.bluetooth.music"))
        assertTrue(TitleLyricDetector.looksBluetooth("com.xxx.BtMusic"))
        assertFalse(TitleLyricDetector.looksBluetooth("com.tencent.qqmusiccar"))
        assertFalse(TitleLyricDetector.looksBluetooth(null))
    }
}
