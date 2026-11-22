package dev.customhud.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import java.util.concurrent.CopyOnWriteArrayList

data class NowPlaying(
    val packageName: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val lyric: String? = null,
    val playing: Boolean = false,
    val lyricSourceSet: Boolean = false,
    /** Media-center LRC only: index of [lyric] in the song (-1 = unknown), the next line and the translation. */
    val lyricIndex: Int = -1,
    val nextLyric: String? = null,
    val translatedLyric: String? = null,
    /** Media-center LRC: song time of this line and the next one, and elapsedRealtime when this line started. */
    val lineStartMs: Long? = null,
    val lineEndMs: Long? = null,
    val lineStartedAt: Long? = null,
)

enum class MediaAccess(val label: String) {
    NOT_STARTED("未启动"),
    DIRECT("可直接读取（系统权限）"),
    VIA_LISTENER("经通知使用权读取"),
    DENIED("被拒绝"),
}

/**
 * Reads media sessions and media notifications through public APIs only, and records every
 * text field so the user can pick which one carries the current lyric line.
 * All callbacks and mutations happen on the main thread.
 */
class MediaMonitor(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("media", Context.MODE_PRIVATE)
    private val manager: MediaSessionManager? = context.getSystemService(MediaSessionManager::class.java)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val tracked = LinkedHashMap<MediaSession.Token, Tracked>()
    private val lifecycle = ArrayDeque<Pair<Long, String>>()
    private var users = 0
    private var sessionListenerRegistered = false
    private var notifyPosted = false

    val fields = FieldTracker()

    /** Experimental vendor media-center lyric line, exposed as the field [ZeekrLyricClient.FIELD_LINE]. */
    val zeekr = ZeekrLyricClient(context) { state, lineChanged ->
        if (lineChanged) {
            val now = SystemClock.elapsedRealtime()
            // Empty string, not null: a cleared line is a real value (gap between lines / song change).
            fields.update(FieldKey(ZeekrLyricClient.FIELD_PACKAGE, ZeekrLyricClient.FIELD_LINE), state.line.orEmpty(), now)
            fields.update(FieldKey(ZeekrLyricClient.FIELD_PACKAGE, "title"), state.title, now)
        }
        notifyChanged()
    }

    var access = MediaAccess.NOT_STARTED
        private set
    var accessDetail = ""
        private set
    var listenerConnected = false
        private set

    var lyricSource: FieldKey? = FieldKey.parse(prefs.getString(KEY_LYRIC_SOURCE, null))
        set(value) {
            field = value
            prefs.edit().putString(KEY_LYRIC_SOURCE, value?.spec).apply()
            notifyChanged()
        }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        onSessions(list ?: emptyList())
    }

    /** Starts monitoring while at least one user holds it. The returned function releases once. */
    fun acquire(): () -> Unit {
        users += 1
        if (users == 1) start()
        var released = false
        return {
            if (!released) {
                released = true
                users -= 1
                if (users == 0) stop()
            }
        }
    }

    fun observe(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    fun controllers(): List<MediaController> = tracked.values.map { it.controller }

    fun lifecycleSince(sinceMs: Long): List<Pair<Long, String>> = lifecycle.filter { it.first >= sinceMs }

    fun nowPlaying(): NowPlaying {
        val active = tracked.values.firstOrNull { it.controller.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: tracked.values.firstOrNull()
        val metadata = active?.controller?.metadata
        val source = lyricSource
        // Line-synchronised extras exist only for media-center LRC (Bluetooth / generic fields have no next line).
        val z = zeekr.state
        val lrc = source?.packageName == ZeekrLyricClient.FIELD_PACKAGE && z.showsLyric && z.mode == LyricMode.LRC
        val lyric = source?.let { key ->
            if (key.packageName == ZeekrLyricClient.FIELD_PACKAGE) {
                // The media-center client knows its own playback status; use its live line directly.
                return@let z.line?.takeIf { z.showsLyric && it.isNotBlank() }
            }
            val sourceController = tracked.values.firstOrNull { it.controller.packageName == key.packageName }
            val sourcePlaying = sourceController == null ||
                sourceController.controller.playbackState?.state == PlaybackState.STATE_PLAYING
            if (sourcePlaying) fields.get(key)?.value?.takeIf { it.isNotBlank() } else null
        }
        return NowPlaying(
            packageName = active?.controller?.packageName,
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: metadata?.description?.title?.toString(),
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata?.description?.subtitle?.toString(),
            lyric = lyric,
            playing = active?.controller?.playbackState?.state == PlaybackState.STATE_PLAYING,
            lyricSourceSet = source != null,
            lyricIndex = if (lrc) z.lineIndex else -1,
            nextLyric = if (lrc) z.nextLine?.takeIf { it.isNotBlank() } else null,
            translatedLyric = if (lrc) z.translatedLine?.takeIf { it.isNotBlank() } else null,
            lineStartMs = if (lrc) z.lineStartMs else null,
            lineEndMs = if (lrc) z.lineEndMs else null,
            lineStartedAt = if (lrc) z.lineStartedAt else null,
        )
    }

    fun onListenerConnected(active: Array<StatusBarNotification>?) {
        listenerConnected = true
        note("notification listener connected")
        active?.forEach { onNotification(it) }
        // A connected listener may unlock getActiveSessions(component) when direct access was denied.
        if (users > 0 && access != MediaAccess.DIRECT) {
            unregisterSessionListener()
            connectSessions()
        }
        notifyChanged()
    }

    fun onListenerDisconnected() {
        listenerConnected = false
        note("notification listener disconnected")
        notifyChanged()
    }

    fun onNotification(sbn: StatusBarNotification) {
        val extras = sbn.notification?.extras
        val isMedia = extras?.containsKey("android.mediaSession") == true
        val knownPackage = tracked.values.any { it.controller.packageName == sbn.packageName }
        if (!isMedia && !knownPackage && !sbn.packageName.contains("music", ignoreCase = true)) return
        val now = SystemClock.elapsedRealtime()
        var changed = fields.update(FieldKey(sbn.packageName, "notif:ticker"), sbn.notification?.tickerText?.toString(), now)
        changed = recordBundle(sbn.packageName, "notif:", extras, now) || changed
        if (changed) notifyChanged()
    }

    private fun start() {
        note("monitor start")
        connectSessions()
        zeekr.start()
    }

    private fun stop() {
        note("monitor stop")
        zeekr.stop()
        unregisterSessionListener()
        tracked.values.forEach { it.release() }
        tracked.clear()
        access = MediaAccess.NOT_STARTED
        notifyChanged()
    }

    private fun connectSessions() {
        val sessionManager = manager ?: run {
            access = MediaAccess.DENIED
            accessDetail = "MediaSessionManager unavailable"
            return
        }
        val listenerComponent = ComponentName(context, MediaNotificationListener::class.java)
        val details = mutableListOf<String>()
        for ((mode, component) in listOf(MediaAccess.DIRECT to null, MediaAccess.VIA_LISTENER to listenerComponent)) {
            try {
                val sessions = sessionManager.getActiveSessions(component)
                if (!sessionListenerRegistered) {
                    sessionManager.addOnActiveSessionsChangedListener(sessionsListener, component, main)
                    sessionListenerRegistered = true
                }
                access = mode
                details += "$mode ok"
                accessDetail = details.joinToString("; ")
                note("sessions access=$mode count=${sessions.size}")
                onSessions(sessions)
                return
            } catch (error: SecurityException) {
                details += "$mode denied: ${error.message?.take(120)}"
            } catch (error: RuntimeException) {
                details += "$mode failed: ${error::class.java.simpleName} ${error.message?.take(120)}"
            }
        }
        access = MediaAccess.DENIED
        accessDetail = details.joinToString("; ")
        note("sessions denied: $accessDetail")
        notifyChanged()
    }

    private fun unregisterSessionListener() {
        if (!sessionListenerRegistered) return
        try {
            manager?.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (_: RuntimeException) {
        }
        sessionListenerRegistered = false
    }

    private fun onSessions(sessions: List<MediaController>) {
        val tokens = sessions.map { it.sessionToken }.toSet()
        tracked.keys.filter { it !in tokens }.forEach { token ->
            tracked.remove(token)?.let {
                note("session removed ${it.controller.packageName}")
                it.release()
            }
        }
        sessions.forEach { controller ->
            if (controller.sessionToken !in tracked) {
                val item = Tracked(controller)
                tracked[controller.sessionToken] = item
                note("session added ${controller.packageName}")
                item.begin()
            }
        }
        notifyChanged()
    }

    private fun note(message: String) {
        lifecycle.addLast(SystemClock.elapsedRealtime() to message)
        while (lifecycle.size > 200) lifecycle.removeFirst()
    }

    private fun notifyChanged() {
        if (notifyPosted) return
        notifyPosted = true
        main.post {
            notifyPosted = false
            listeners.forEach { it() }
        }
    }

    private fun recordBundle(packageName: String, prefix: String, bundle: Bundle?, now: Long): Boolean {
        if (bundle == null) return false
        var changed = false
        try {
            for (key in bundle.keySet()) {
                val value = try {
                    @Suppress("DEPRECATION")
                    describe(bundle.get(key))
                } catch (error: RuntimeException) {
                    "[unreadable ${error::class.java.simpleName}]"
                }
                changed = fields.update(FieldKey(packageName, prefix + key), value, now) || changed
            }
        } catch (error: RuntimeException) {
            // Custom Parcelables from another app can fail to unparcel; record that instead of crashing.
            changed = fields.update(FieldKey(packageName, prefix + "<bundle>"), "[unreadable ${error::class.java.simpleName}]", now) || changed
        }
        return changed
    }

    private inner class Tracked(val controller: MediaController) : MediaController.Callback() {
        private val pkg = controller.packageName

        fun begin() {
            controller.registerCallback(this, main)
            val now = SystemClock.elapsedRealtime()
            recordMetadata(controller.metadata, now)
            recordState(controller.playbackState, now)
            recordBundle(pkg, "extra:", safe { controller.extras }, now)
            fields.update(FieldKey(pkg, "queueTitle"), controller.queueTitle?.toString(), now)
        }

        fun release() {
            try {
                controller.unregisterCallback(this)
            } catch (_: RuntimeException) {
            }
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            recordMetadata(metadata, SystemClock.elapsedRealtime())
            notifyChanged()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            recordState(state, SystemClock.elapsedRealtime())
            notifyChanged()
        }

        override fun onExtrasChanged(extras: Bundle?) {
            recordBundle(pkg, "extra:", extras, SystemClock.elapsedRealtime())
            notifyChanged()
        }

        override fun onSessionEvent(event: String, extras: Bundle?) {
            val now = SystemClock.elapsedRealtime()
            fields.update(FieldKey(pkg, "event:$event"), describe(extras), now)
            recordBundle(pkg, "event:$event/", extras, now)
            notifyChanged()
        }

        override fun onQueueTitleChanged(title: CharSequence?) {
            fields.update(FieldKey(pkg, "queueTitle"), title?.toString(), SystemClock.elapsedRealtime())
            notifyChanged()
        }

        override fun onSessionDestroyed() {
            note("session destroyed $pkg")
            release()
            tracked.remove(controller.sessionToken)
            notifyChanged()
        }

        private fun recordMetadata(metadata: MediaMetadata?, now: Long) {
            metadata ?: return
            for (key in metadata.keySet()) {
                fields.update(FieldKey(pkg, "meta:" + shortKey(key)), describeMetadata(metadata, key), now)
            }
            val description = metadata.description
            fields.update(FieldKey(pkg, "desc:title"), description?.title?.toString(), now)
            fields.update(FieldKey(pkg, "desc:subtitle"), description?.subtitle?.toString(), now)
            fields.update(FieldKey(pkg, "desc:description"), description?.description?.toString(), now)
        }

        private fun recordState(state: PlaybackState?, now: Long) {
            state ?: return
            fields.update(FieldKey(pkg, "state"), stateName(state.state), now)
            recordBundle(pkg, "stateExtra:", state.extras, now)
            state.customActions?.forEach { action ->
                fields.update(FieldKey(pkg, "action:${action.action}"), action.name?.toString(), now)
            }
        }
    }

    companion object {
        private const val KEY_LYRIC_SOURCE = "lyricSource"
        private const val METADATA_PREFIX = "android.media.metadata."
        private val LONG_KEYS = setOf(
            MediaMetadata.METADATA_KEY_DURATION, MediaMetadata.METADATA_KEY_YEAR,
            MediaMetadata.METADATA_KEY_TRACK_NUMBER, MediaMetadata.METADATA_KEY_NUM_TRACKS,
            MediaMetadata.METADATA_KEY_DISC_NUMBER, "android.media.metadata.BT_FOLDER_TYPE",
        )
        private val BITMAP_KEYS = setOf(
            MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON,
        )
        private val RATING_KEYS = setOf(MediaMetadata.METADATA_KEY_RATING, MediaMetadata.METADATA_KEY_USER_RATING)

        fun shortKey(key: String): String = key.removePrefix(METADATA_PREFIX)

        fun describeMetadata(metadata: MediaMetadata, key: String): String? = try {
            when (key) {
                in BITMAP_KEYS -> metadata.getBitmap(key)?.let { "[bitmap ${it.width}x${it.height}]" }
                in LONG_KEYS -> metadata.getLong(key).toString()
                in RATING_KEYS -> metadata.getRating(key)?.let(::describeRating)
                else -> metadata.getText(key)?.toString() ?: "long:${metadata.getLong(key)}"
            }
        } catch (error: RuntimeException) {
            "[unreadable ${error::class.java.simpleName}]"
        }

        private fun describeRating(rating: Rating): String = "[rating style=${rating.ratingStyle} rated=${rating.isRated}]"

        fun describe(value: Any?): String = when (value) {
            null -> "null"
            is CharSequence -> value.toString()
            is Bitmap -> "[bitmap ${value.width}x${value.height}]"
            is Bundle -> try {
                "[bundle " + value.keySet().joinToString(",", limit = 12) + "]"
            } catch (error: RuntimeException) {
                "[bundle unreadable]"
            }
            is Array<*> -> value.joinToString(",", "[", "]", limit = 8) { describe(it) }
            is IntArray -> value.joinToString(",", "[", "]", limit = 8)
            is LongArray -> value.joinToString(",", "[", "]", limit = 8)
            is FloatArray -> value.joinToString(",", "[", "]", limit = 8)
            is ByteArray -> "[bytes ${value.size}]"
            is Collection<*> -> value.joinToString(",", "[", "]", limit = 8) { describe(it) }
            else -> value.toString()
        }

        fun stateName(state: Int): String = when (state) {
            PlaybackState.STATE_NONE -> "NONE"
            PlaybackState.STATE_STOPPED -> "STOPPED"
            PlaybackState.STATE_PAUSED -> "PAUSED"
            PlaybackState.STATE_PLAYING -> "PLAYING"
            PlaybackState.STATE_FAST_FORWARDING -> "FAST_FORWARDING"
            PlaybackState.STATE_REWINDING -> "REWINDING"
            PlaybackState.STATE_BUFFERING -> "BUFFERING"
            PlaybackState.STATE_ERROR -> "ERROR"
            PlaybackState.STATE_CONNECTING -> "CONNECTING"
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "SKIP_PREV"
            PlaybackState.STATE_SKIPPING_TO_NEXT -> "SKIP_NEXT"
            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "SKIP_QUEUE"
            else -> "STATE_$state"
        }

        internal inline fun <T> safe(block: () -> T): T? = try {
            block()
        } catch (_: RuntimeException) {
            null
        }
    }
}
