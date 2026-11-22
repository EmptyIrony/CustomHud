package dev.customhud.media

/** Whether the track title should be shown as the current lyric line when the source has no lyrics. */
enum class TitleLyricMode(val label: String) {
    AUTO("自动"),
    ON("总是"),
    OFF("关闭"),
}

/**
 * Recognises phones that send the current lyric line as the Bluetooth (AVRCP) title ("car Bluetooth
 * lyrics"), without trusting the media center's source type.
 *
 * Signature: the title keeps changing every few seconds while the artist stays the same (the artist
 * then holds the song/artist). Normal playback changes the title only every few minutes, usually
 * together with the artist. After [needed] quick same-artist title changes (each within [windowMs]
 * of the previous change) the detector latches for that source (source type + package) until the
 * source changes or the source starts sending real lyrics.
 *
 * Pure logic, no Android types; call from one thread.
 */
class TitleLyricDetector(
    private val windowMs: Long = 20_000L,
    private val needed: Int = 3,
) {
    var latched = false
        private set
    var quickChanges = 0
        private set

    private var sourceKey: String? = null
    private var lastTitle: String? = null
    private var lastArtist: String? = null
    private var lastChangeAtMs: Long? = null

    /** Feeds one song-info snapshot; returns [latched]. */
    fun update(sourceKey: String, title: String?, artist: String?, hasOwnLyric: Boolean, nowMs: Long): Boolean {
        if (sourceKey != this.sourceKey) {
            reset()
            this.sourceKey = sourceKey
        }
        val t = title?.trim().orEmpty()
        val a = artist?.trim().orEmpty()
        if (hasOwnLyric) {
            // The source provides real lyrics: the title is a song title again.
            latched = false
            quickChanges = 0
            lastTitle = t
            lastArtist = a
            lastChangeAtMs = null
            return false
        }
        val previousTitle = lastTitle
        if (previousTitle == null) {
            lastTitle = t
            lastArtist = a
            lastChangeAtMs = nowMs
            return latched
        }
        if (t == previousTitle && a == lastArtist) return latched
        val quick = lastChangeAtMs?.let { nowMs - it in 0..windowMs } == true
        quickChanges = if (a == lastArtist && t.isNotEmpty() && quick) quickChanges + 1 else 0
        if (quickChanges >= needed) latched = true
        lastTitle = t
        lastArtist = a
        lastChangeAtMs = nowMs
        return latched
    }

    fun reset() {
        latched = false
        quickChanges = 0
        sourceKey = null
        lastTitle = null
        lastArtist = null
        lastChangeAtMs = null
    }

    companion object {
        /** Package names that are clearly a Bluetooth music player, whatever source type is reported. */
        fun looksBluetooth(packageName: String?): Boolean {
            val p = packageName?.lowercase() ?: return false
            return "bluetooth" in p || "btmusic" in p || "btaudio" in p || "a2dp" in p || "avrcp" in p
        }
    }
}
