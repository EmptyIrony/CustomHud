package dev.customhud.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.DeadObjectException
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

enum class ZeekrStage(val label: String) {
    OFF("未连接"),
    BINDING("正在绑定媒体中心服务"),
    CONNECTED("已连接，正在注册回调"),
    REGISTERED("已注册，等待推送"),
    RECEIVING("正在接收推送"),
    DISCONNECTED("服务断开，等待重连"),
    FAILED("失败"),
}

enum class LyricMode(val label: String) {
    NONE("无歌词"),
    LRC("整首 LRC + 播放进度"),
    LRC_NO_PROGRESS("整首 LRC，等待进度"),
    BLUETOOTH("蓝牙：内容即当前句"),
    BLUETOOTH_TITLE("标题即当前句（手机蓝牙歌词）"),
    SENTENCE("播放源当前句快照"),
}

data class ZeekrLyricState(
    val enabled: Boolean = true,
    val stage: ZeekrStage = ZeekrStage.OFF,
    val detail: String = "",
    val bindInfo: String = "",
    val descriptorCheck: String? = null,
    val mode: LyricMode = LyricMode.NONE,
    val line: String? = null,
    val translatedLine: String? = null,
    val nextLine: String? = null,
    val lineIndex: Int = -1,
    /** LRC: song time of the current line and of the next line (its end); null when unknown. */
    val lineStartMs: Long? = null,
    val lineEndMs: Long? = null,
    /** LRC while playing: elapsedRealtime when the current line started (stable; moves only on seek / resume). */
    val lineStartedAt: Long? = null,
    val lineCount: Int = 0,
    val sentence: String? = null,
    val uuid: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val packageName: String? = null,
    val sourceType: Int? = null,
    val playbackStatus: Int? = null,
    val lyricVisible: Boolean? = null,
    val lyricLength: Int = 0,
    val translatedLength: Int = 0,
    val progressMs: Long? = null,
    val infoPushes: Int = 0,
    val progressPushes: Int = 0,
    val otherCallbacks: Int = 0,
    val lastError: String? = null,
    /** Wall-clock time of [lastError], so an old error is recognisable on a photo. */
    val lastErrorAt: Long? = null,
    val history: List<Pair<Long, String>> = emptyList(),
    val titleMode: TitleLyricMode = TitleLyricMode.AUTO,
    /** Why the title is / is not used as the line (photo diagnostics). */
    val titleDecision: String = "—",
) {
    /** False only for states where a lyric line should not be shown (paused, interrupted, none, error). */
    val showsLyric: Boolean get() = playbackStatus == null || playbackStatus !in HIDDEN_STATUSES

    companion object {
        private val HIDDEN_STATUSES = setOf(0, 2, 3, 7)
    }
}

/**
 * Current lyric line from the head unit's media center (com.zeekr.mediacenter), via its Widget Binder.
 *
 * Talks plain Binder/Parcel with the documented transaction codes, so no vendor SDK classes are
 * needed and no other app's code is loaded. Every remote call is guarded; a refused bind, a version
 * mismatch or an unexpected reply becomes a readable [ZeekrLyricState.detail]/[ZeekrLyricState.lastError].
 *
 * Threads: binding, state and line computation on the main thread; every outgoing IPC (register,
 * snapshot, IMusicPlaybackInfo getters) and LRC parsing on a private worker thread. Incoming
 * callbacks arrive on binder threads and are handed off immediately. Song-info pushes are coalesced
 * (only the newest is read). Between progress pushes the position is interpolated while playing,
 * and the next line change is scheduled exactly instead of polling.
 */
class ZeekrLyricClient(
    context: Context,
    private val onChanged: (ZeekrLyricState, lineChanged: Boolean) -> Unit,
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("zeekr_lyric", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val worker: Handler by lazy { Handler(HandlerThread("zeekr-lyric").apply { start() }.looper) }

    @Volatile
    var state = ZeekrLyricState(
        enabled = prefs.getBoolean(KEY_ENABLED, true),
        titleMode = prefs.getString(KEY_TITLE_MODE, null)
            ?.let { saved -> TitleLyricMode.entries.firstOrNull { it.name == saved } } ?: TitleLyricMode.AUTO,
    )
        private set
    private val titleDetector = TitleLyricDetector()

    // Main-thread state.
    private var wanted = false
    private var generation = 0
    private var connection: Connection? = null
    private var widget: IBinder? = null
    private var callback: WidgetCallback? = null
    private var track: Track? = null
    private var progressMs = 0L
    private var progressAtMs = 0L // elapsedRealtime of the anchor; 0 = no progress yet
    private var lastProgressPushMs = 0L
    private var retryDelayMs = RETRY_MIN_MS
    private var lastNotifyMs = 0L
    private val tick = Runnable { recompute() }
    private val retry = Runnable { if (wanted && state.enabled) bind() }

    // Cross-thread.
    private val pendingInfo = AtomicReference<Pair<Int, IBinder>?>(null)
    private val drainScheduled = AtomicBoolean(false)
    private val infoPushes = AtomicInteger(0)
    private val progressPushes = AtomicInteger(0)
    private val others = AtomicInteger(0)

    // Worker-only parse cache.
    private var parsedKey: Pair<String, String>? = null
    private var parsedLines: List<LyricLine> = emptyList()

    fun start() = main.post {
        wanted = true
        if (state.enabled) bind()
    }

    fun stop() = main.post {
        wanted = false
        unbind("监听已暂停（无页面或 HUD 使用媒体数据）")
    }

    fun enable() = main.post {
        prefs.edit().putBoolean(KEY_ENABLED, true).apply()
        state = state.copy(enabled = true)
        wanted = true
        retryDelayMs = RETRY_MIN_MS
        bind()
    }

    fun setTitleMode(mode: TitleLyricMode) = main.post {
        prefs.edit().putString(KEY_TITLE_MODE, mode.name).apply()
        state = state.copy(titleMode = mode)
        recompute()
        onChanged(state, true)
    }

    fun disable() = main.post {
        prefs.edit().putBoolean(KEY_ENABLED, false).apply()
        state = state.copy(enabled = false)
        unbind("已手动断开")
    }

    // ---------------------------------------------------------------- binding (main thread)

    private fun bind() {
        main.removeCallbacks(retry)
        if (connection != null || !wanted || !state.enabled) return
        val myGeneration = ++generation
        val conn = Connection(myGeneration)
        val intent = Intent(ACTION_WIDGET).setClassName(SERVICE_PACKAGE, SERVICE_CLASS)
        val (ok, info) = bindService(intent, conn)
        if (ok) {
            connection = conn
            publish(stage = ZeekrStage.BINDING, detail = "bindService 已接受，等待连接", bindInfo = info)
        } else {
            publish(stage = ZeekrStage.FAILED, detail = "bindService 被拒绝：$info", bindInfo = info)
            scheduleRetry()
        }
    }

    /** Binds as the foreground user when this process runs in a different one; falls back to a plain bind. */
    private fun bindService(intent: Intent, conn: Connection): Pair<Boolean, String> {
        val myUser = Process.myUid() / PER_USER_RANGE
        val currentUser = try {
            Class.forName("android.app.ActivityManager").getMethod("getCurrentUser").invoke(null) as Int
        } catch (_: Throwable) {
            null
        }
        val users = "本进程用户 $myUser · 前台用户 ${currentUser ?: "未知"}"
        var asUserNote = ""
        if (currentUser != null && currentUser != myUser) {
            try {
                val handle = UserHandle::class.java.getMethod("of", Int::class.javaPrimitiveType).invoke(null, currentUser)
                val method = Context::class.java.getMethod(
                    "bindServiceAsUser", Intent::class.java, ServiceConnection::class.java,
                    Int::class.javaPrimitiveType, UserHandle::class.java,
                )
                if (method.invoke(appContext, intent, conn, Context.BIND_AUTO_CREATE, handle) == true) {
                    return true to "$users · 按前台用户绑定"
                }
                asUserNote = " · 按前台用户绑定返回 false"
            } catch (error: Throwable) {
                asUserNote = " · 按前台用户绑定失败 ${describe(error)}"
            }
        }
        return try {
            appContext.bindService(intent, conn, Context.BIND_AUTO_CREATE) to "$users · 普通绑定$asUserNote"
        } catch (error: RuntimeException) {
            false to "$users · ${describe(error)}$asUserNote"
        }
    }

    private fun unbind(reason: String) {
        main.removeCallbacks(retry)
        main.removeCallbacks(tick)
        generation++
        val conn = connection
        val binder = widget
        val cb = callback
        connection = null
        widget = null
        callback = null
        clearTrack()
        if (conn == null) {
            publish(stage = ZeekrStage.OFF, detail = reason)
            return
        }
        publish(stage = ZeekrStage.OFF, detail = reason)
        // Unregister with the same callback instance, then unbind.
        worker.post {
            if (binder != null && cb != null) {
                try {
                    call(binder, WIDGET_DESCRIPTOR, TX_SET_UN_WIDGET_API_SVC, { it.writeStrongBinder(cb) }) { }
                } catch (_: Throwable) {
                }
            }
            main.post {
                try {
                    appContext.unbindService(conn)
                } catch (_: RuntimeException) {
                }
            }
        }
    }

    private fun scheduleRetry() {
        if (!wanted || !state.enabled) return
        main.removeCallbacks(retry)
        main.postDelayed(retry, retryDelayMs)
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(RETRY_MAX_MS)
    }

    private inner class Connection(val gen: Int) : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (gen != generation || binder == null) return
            widget = binder
            retryDelayMs = RETRY_MIN_MS
            val cb = WidgetCallback(gen)
            callback = cb // strong reference for the whole connection
            publish(stage = ZeekrStage.CONNECTED, detail = "已连接 ${name?.shortClassName ?: ""}")
            worker.post { register(gen, binder, cb) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            if (gen != generation) return
            // The system rebinds by itself; drop the dead binder and stale song state.
            widget = null
            callback = null
            clearTrack()
            publish(stage = ZeekrStage.DISCONNECTED, detail = "onServiceDisconnected，等待系统自动重连")
        }

        override fun onBindingDied(name: ComponentName?) {
            if (gen != generation) return
            restart("onBindingDied，稍后重新绑定")
        }

        override fun onNullBinding(name: ComponentName?) {
            if (gen != generation) return
            restart("服务返回空 Binder（onNullBinding），稍后重试")
        }

        private fun restart(reason: String) {
            val conn = connection
            connection = null
            widget = null
            callback = null
            generation++
            clearTrack()
            try {
                conn?.let(appContext::unbindService)
            } catch (_: RuntimeException) {
            }
            publish(stage = ZeekrStage.FAILED, detail = reason)
            scheduleRetry()
        }
    }

    // ---------------------------------------------------------------- outgoing IPC (worker thread)

    private fun register(gen: Int, binder: IBinder, cb: WidgetCallback) {
        val descriptor = try {
            binder.interfaceDescriptor
        } catch (error: Throwable) {
            "读取失败 ${describe(error)}"
        }
        val check = if (descriptor == WIDGET_DESCRIPTOR) "接口描述符一致" else "接口描述符不同：$descriptor"
        try {
            call(binder, WIDGET_DESCRIPTOR, TX_SET_WIDGET_API_SVC, { it.writeStrongBinder(cb) }) { }
            main.post {
                if (gen == generation) publish(stage = ZeekrStage.REGISTERED, detail = "setWidgetApiSvc 成功", descriptorCheck = check)
            }
        } catch (error: Throwable) {
            main.post {
                if (gen != generation) return@post
                publish(stage = ZeekrStage.FAILED, detail = "setWidgetApiSvc 失败：${describe(error)}", descriptorCheck = check)
                scheduleRetry()
            }
            return
        }
        // Registration replays the current state; one explicit snapshot covers services that do not.
        try {
            val info = call(binder, WIDGET_DESCRIPTOR, TX_GET_MUSIC_PLAYBACK_INFO) { it.readStrongBinder() }
            if (info != null) onInfoBinder(gen, info)
        } catch (error: Throwable) {
            postError(gen, "getMusicPlaybackInfo 失败：${describe(error)}")
        }
        try {
            val progress = call(binder, WIDGET_DESCRIPTOR, TX_GET_PROGRESS) { it.readLong() }
            main.post { onProgress(gen, progress, fromPush = false) }
        } catch (error: Throwable) {
            postError(gen, "getProgress 失败：${describe(error)}")
        }
    }

    private fun onInfoBinder(gen: Int, binder: IBinder) {
        pendingInfo.set(gen to binder)
        if (drainScheduled.compareAndSet(false, true)) worker.post(::drainInfo)
    }

    private fun drainInfo() {
        drainScheduled.set(false)
        val (gen, binder) = pendingInfo.getAndSet(null) ?: return
        val next = readTrack(binder)
        if (next == null) {
            // The pushed info object died before it could be read (e.g. the phone/music app went away).
            // Keep the current song and lyrics; the next push replaces them.
            postError(gen, "歌曲信息对象已失效（DeadObjectException），已忽略这次推送，保留当前歌词")
            return
        }
        main.post { onTrack(gen, next) }
    }

    /**
     * Reads the fields the HUD needs: one IPC per getter, on the worker thread.
     * Returns null when the info object is dead, so a failed read never wipes the current song.
     */
    private fun readTrack(binder: IBinder): Track? {
        val errors = ArrayList<String>(1)
        var dead = false
        fun <T> field(name: String, read: () -> T): T? = if (dead) null else try {
            read()
        } catch (error: DeadObjectException) {
            dead = true
            null
        } catch (error: Throwable) {
            if (errors.isEmpty()) errors += "$name 失败：${describe(error)}"
            null
        }
        fun text(code: Int, name: String) = field(name) { call(binder, INFO_DESCRIPTOR, code) { it.readString() } }
        fun number(code: Int, name: String) = field(name) { call(binder, INFO_DESCRIPTOR, code) { it.readInt() } }
        fun long(code: Int, name: String) = field(name) { call(binder, INFO_DESCRIPTOR, code) { it.readLong() } }

        val readAt = SystemClock.elapsedRealtime()
        val uuid = text(TX_INFO_UUID, "getUuid")
        val sourceType = number(TX_INFO_SOURCE_TYPE, "getSourceType")
        val status = number(TX_INFO_PLAYBACK_STATUS, "getPlaybackStatus")
        val lyric = text(TX_INFO_LYRIC_CONTENT, "getLyricContent").orEmpty()
        val translated = text(TX_INFO_TRANSLATED_LYRICS, "getTranslatedLyrics").orEmpty()
        val sentence = text(TX_INFO_CURRENT_SENTENCE, "getCurrentLyricSentence")
        val position = long(TX_INFO_PLAY_POSITION, "getPlayPosition")
        val visibility = number(TX_INFO_EXTRA_VISIBILITY, "getExtraPlayInfoVisibility")
        val title = text(TX_INFO_TITLE, "getTitle")
        val artist = text(TX_INFO_ARTIST, "getArtist")
        val pkg = text(TX_INFO_PACKAGE_NAME, "getPackageName")
        if (dead) return null

        val bluetooth = sourceType == SOURCE_BLUETOOTH
        // Parse only when the lyric text actually changed (also covers lyrics filled in later for the same uuid).
        val key = lyric to translated
        if (!bluetooth && key != parsedKey) {
            parsedLines = LrcParser.mergeByTime(LrcParser.parse(lyric), LrcParser.parse(translated))
            parsedKey = key
        }
        // Bluetooth: the media center has no LRC; use its lyric/sentence fields. Title-as-lyric (phones with
        // "car Bluetooth lyrics") is decided on the main thread in recompute(), independent of sourceType.
        val btLyric = lyric.trim().ifEmpty { null } ?: sentence?.trim()?.ifEmpty { null }
        return Track(
            uuid = uuid, title = title, artist = artist, packageName = pkg,
            sourceType = sourceType, playbackStatus = status,
            lyricVisible = visibility?.let { it and 0x01 == 0x01 },
            sentence = sentence?.trim(),
            bluetoothLine = if (bluetooth) btLyric else null,
            hasOwnLyric = lyric.isNotBlank() || translated.isNotBlank() || !sentence.isNullOrBlank(),
            lines = if (bluetooth) emptyList() else parsedLines,
            lyricLength = lyric.length, translatedLength = translated.length,
            playPositionMs = position?.takeIf { it >= 0 }, readAtMs = readAt,
            error = errors.firstOrNull(),
        )
    }

    private fun postError(gen: Int, message: String) = main.post {
        if (gen == generation) publish(lastError = message)
    }

    // ---------------------------------------------------------------- incoming callbacks (binder threads)

    private inner class WidgetCallback(private val gen: Int) : Binder() {
        init {
            attachInterface(null, CALLBACK_DESCRIPTOR)
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code !in IBinder.FIRST_CALL_TRANSACTION..IBinder.LAST_CALL_TRANSACTION) {
                return super.onTransact(code, data, reply, flags)
            }
            try {
                data.enforceInterface(CALLBACK_DESCRIPTOR)
                when (code) {
                    TX_CB_UPDATE_MUSIC_PLAY_INFO -> {
                        infoPushes.incrementAndGet()
                        data.readStrongBinder()?.let { onInfoBinder(gen, it) }
                    }
                    TX_CB_UPDATE_PROGRESS -> {
                        progressPushes.incrementAndGet()
                        val progress = data.readLong()
                        main.post { onProgress(gen, progress, fromPush = true) }
                    }
                    else -> others.incrementAndGet() // lists, recommendations, collect results: not needed
                }
            } catch (error: Throwable) {
                postError(gen, "回调解析失败(code=$code)：${describe(error)}")
            }
            // Always acknowledge; never throw back into the media center.
            try {
                reply?.writeNoException()
            } catch (_: Throwable) {
            }
            return true
        }
    }

    // ---------------------------------------------------------------- state machine (main thread)

    private fun onTrack(gen: Int, next: Track) {
        if (gen != generation) return
        val old = track
        val now = SystemClock.elapsedRealtime()
        val songChanged = old == null || next.uuid != old.uuid
        val wasPlaying = old?.playbackStatus == STATUS_PLAYING
        val isPlaying = next.playbackStatus == STATUS_PLAYING
        if (wasPlaying && !isPlaying && progressAtMs > 0) {
            progressMs = estimate(now, playing = true) // freeze where playback stopped
            progressAtMs = now
        } else if (!wasPlaying && isPlaying && progressAtMs > 0) {
            progressAtMs = now // resume from the frozen position
        }
        // Use the snapshot position when a song starts or when progress pushes are not arriving.
        val position = next.playPositionMs
        if (position != null && (songChanged || now - lastProgressPushMs > PUSH_FRESH_MS)) {
            progressMs = position
            progressAtMs = next.readAtMs
        }
        titleDetector.update(
            "${next.sourceType}|${next.packageName}", next.title, next.artist, next.hasOwnLyric, now,
        )
        track = next
        if (songChanged) publish(history = emptyList())
        next.error?.let { publish(lastError = it) }
        recompute()
    }

    private fun onProgress(gen: Int, positionMs: Long, fromPush: Boolean) {
        if (gen != generation) return
        val now = SystemClock.elapsedRealtime()
        progressMs = positionMs.coerceAtLeast(0L)
        progressAtMs = now
        if (fromPush) lastProgressPushMs = now
        recompute()
    }

    private fun estimate(now: Long, playing: Boolean): Long =
        if (playing && progressAtMs > 0) progressMs + (now - progressAtMs) else progressMs

    private fun recompute() {
        main.removeCallbacks(tick)
        val t = track
        val now = SystemClock.elapsedRealtime()
        val playing = t?.playbackStatus == STATUS_PLAYING
        val position = estimate(now, playing)
        val hasProgress = progressAtMs > 0
        var mode = LyricMode.NONE
        var index = -1
        var line: String? = null
        var translated: String? = null
        var nextLine: String? = null
        var lineStartMs: Long? = null
        var lineEndMs: Long? = null
        var lineStartedAt: Long? = null
        val titleMode = state.titleMode
        val bluetooth = t?.sourceType == SOURCE_BLUETOOTH
        val btPackage = TitleLyricDetector.looksBluetooth(t?.packageName)
        val titleIsLyric = when (titleMode) {
            TitleLyricMode.ON -> true
            TitleLyricMode.OFF -> false
            TitleLyricMode.AUTO -> bluetooth || btPackage || titleDetector.latched
        }
        val titleDecision = when {
            t == null -> "—"
            t.hasOwnLyric -> "否：播放源自带歌词"
            titleMode == TitleLyricMode.ON -> "是：手动设为总是"
            titleMode == TitleLyricMode.OFF -> "否：手动关闭"
            bluetooth -> "是：音源=蓝牙"
            btPackage -> "是：应用包名像蓝牙音乐"
            titleDetector.latched -> "是：检测到标题快速变化（蓝牙歌词）"
            else -> "否：未识别（标题快速变化 ${titleDetector.quickChanges}/3）"
        }
        if (t != null) {
            when {
                t.bluetoothLine != null -> {
                    mode = LyricMode.BLUETOOTH
                    line = t.bluetoothLine
                }
                !t.hasOwnLyric && titleIsLyric && !t.title.isNullOrBlank() -> {
                    mode = LyricMode.BLUETOOTH_TITLE
                    line = t.title.trim()
                }
                t.lines.isNotEmpty() && hasProgress -> {
                    mode = LyricMode.LRC
                    index = LrcParser.currentIndex(t.lines, position)
                    line = t.lines.getOrNull(index)?.text.orEmpty()
                    translated = t.lines.getOrNull(index)?.translatedText
                    nextLine = t.lines.getOrNull(index + 1)?.text
                    val next = t.lines.getOrNull(index + 1)
                    val current = t.lines.getOrNull(index)
                    if (current != null) {
                        lineStartMs = current.timeMs
                        lineEndMs = next?.timeMs
                        if (playing) {
                            val startedAt = now - (position - current.timeMs)
                            val previous = state.lineStartedAt
                            // Progress pushes jitter by a few ms; keep the anchor unless it really moved.
                            lineStartedAt = if (previous != null && state.lineIndex == index && state.uuid == t.uuid &&
                                abs(previous - startedAt) < START_JITTER_MS) previous else startedAt
                        }
                    }
                    if (playing && next != null) {
                        main.postDelayed(tick, (next.timeMs - position).coerceIn(MIN_TICK_MS, MAX_TICK_MS))
                    }
                }
                t.lines.isNotEmpty() -> {
                    mode = LyricMode.LRC_NO_PROGRESS
                    line = t.sentence.orEmpty()
                }
                !t.sentence.isNullOrBlank() -> {
                    mode = LyricMode.SENTENCE
                    line = t.sentence
                }
            }
        }
        val old = state
        // Index / next / translation too: a repeated lyric line keeps its text but must still advance at once.
        val lineChanged = line != old.line || t?.uuid != old.uuid || index != old.lineIndex ||
            nextLine != old.nextLine || translated != old.translatedLine || lineStartedAt != old.lineStartedAt
        val history = if (lineChanged && !line.isNullOrBlank() && line != old.line) {
            (old.history + (System.currentTimeMillis() to line)).takeLast(HISTORY)
        } else {
            old.history
        }
        val counters = infoPushes.get()
        val progressCount = progressPushes.get()
        state = old.copy(
            stage = if (callback != null && (counters > 0 || progressCount > 0)) ZeekrStage.RECEIVING else old.stage,
            mode = mode, line = line, translatedLine = translated, nextLine = nextLine,
            lineStartMs = lineStartMs, lineEndMs = lineEndMs, lineStartedAt = lineStartedAt,
            lineIndex = index, lineCount = t?.lines?.size ?: 0, sentence = t?.sentence,
            uuid = t?.uuid, title = t?.title, artist = t?.artist, packageName = t?.packageName,
            sourceType = t?.sourceType, playbackStatus = t?.playbackStatus, lyricVisible = t?.lyricVisible,
            lyricLength = t?.lyricLength ?: 0, translatedLength = t?.translatedLength ?: 0,
            progressMs = if (hasProgress) position else null,
            infoPushes = counters, progressPushes = progressCount, otherCallbacks = others.get(),
            history = history, titleDecision = titleDecision,
        )
        // Line changes go out at once; counter/progress-only changes at most once a second.
        if (lineChanged || now - lastNotifyMs >= NOTIFY_INTERVAL_MS) {
            lastNotifyMs = now
            onChanged(state, lineChanged)
        }
    }

    private fun clearTrack() {
        main.removeCallbacks(tick)
        track = null
        progressMs = 0
        progressAtMs = 0
        lastProgressPushMs = 0
        pendingInfo.set(null)
        recompute()
    }

    private fun publish(
        stage: ZeekrStage = state.stage,
        detail: String = state.detail,
        bindInfo: String = state.bindInfo,
        descriptorCheck: String? = state.descriptorCheck,
        lastError: String? = state.lastError,
        history: List<Pair<Long, String>> = state.history,
    ) {
        val errorAt = if (lastError != null && lastError !== state.lastError) System.currentTimeMillis() else state.lastErrorAt
        state = state.copy(
            stage = stage, detail = detail, bindInfo = bindInfo, descriptorCheck = descriptorCheck,
            lastError = lastError, lastErrorAt = errorAt, history = history,
        )
        onChanged(state, false)
    }

    private data class Track(
        val uuid: String?,
        val title: String?,
        val artist: String?,
        val packageName: String?,
        val sourceType: Int?,
        val playbackStatus: Int?,
        val lyricVisible: Boolean?,
        val sentence: String?,
        val bluetoothLine: String?,
        val hasOwnLyric: Boolean,
        val lines: List<LyricLine>,
        val lyricLength: Int,
        val translatedLength: Int,
        val playPositionMs: Long?,
        val readAtMs: Long,
        val error: String?,
    )

    companion object {
        /** Synthetic package for the media field tracker / lyric source key. */
        const val FIELD_PACKAGE = "zeekr.mediacenter"
        const val FIELD_LINE = "currentLyricSentence"

        const val SERVICE_PACKAGE = "com.zeekr.mediacenter"
        private const val SERVICE_CLASS = "ecarx.xsf.mediacenter.ZeekrMediaCenterService"
        private const val ACTION_WIDGET = "ecarx.xsf.ZEEKR_ACTION_MEDIA_CENTER_WIDGET_API_SERVICE"
        private const val WIDGET_DESCRIPTOR = "com.zeekr.sdk.mediacenter.IMediaCenterWidgetApiSvc"
        private const val CALLBACK_DESCRIPTOR = "com.zeekr.sdk.mediacenter.IZeekrWidgetApiSvc"
        private const val INFO_DESCRIPTOR = "com.zeekr.sdk.mediacenter.IMusicPlaybackInfo"

        // Transaction codes for media center 3.3.49 (FIRST_CALL_TRANSACTION == 1).
        private const val TX_SET_WIDGET_API_SVC = 1
        private const val TX_SET_UN_WIDGET_API_SVC = 2
        private const val TX_GET_MUSIC_PLAYBACK_INFO = 3
        private const val TX_GET_PROGRESS = 4
        private const val TX_CB_UPDATE_MUSIC_PLAY_INFO = 1
        private const val TX_CB_UPDATE_PROGRESS = 2
        private const val TX_INFO_TITLE = 2
        private const val TX_INFO_ARTIST = 3
        private const val TX_INFO_SOURCE_TYPE = 9
        private const val TX_INFO_PLAYBACK_STATUS = 11
        private const val TX_INFO_LYRIC_CONTENT = 12
        private const val TX_INFO_CURRENT_SENTENCE = 14
        private const val TX_INFO_UUID = 24
        private const val TX_INFO_PACKAGE_NAME = 27
        private const val TX_INFO_PLAY_POSITION = 42
        private const val TX_INFO_EXTRA_VISIBILITY = 50
        private const val TX_INFO_TRANSLATED_LYRICS = 54

        const val SOURCE_BLUETOOTH = 2
        const val STATUS_PLAYING = 1

        private const val PER_USER_RANGE = 100_000
        private const val KEY_ENABLED = "enabled"
        private const val KEY_TITLE_MODE = "title_mode"
        private const val HISTORY = 8
        private const val RETRY_MIN_MS = 2_000L
        private const val RETRY_MAX_MS = 60_000L
        private const val PUSH_FRESH_MS = 3_000L
        private const val MIN_TICK_MS = 30L
        private const val MAX_TICK_MS = 15_000L
        private const val NOTIFY_INTERVAL_MS = 1_000L
        private const val START_JITTER_MS = 400L

        val SOURCE_TYPES = mapOf(
            0 to "本地", 1 to "USB", 2 to "蓝牙", 3 to "FM", 4 to "AM", 5 to "AUX", 6 to "在线媒体", 7 to "USB2",
            8 to "电台", 9 to "网络新闻", 10 to "网络视频", 11 to "播客", 12 to "图库", 13 to "DLNA",
        )
        val PLAYBACK_STATUSES = mapOf(
            0 to "暂停", 1 to "播放中", 2 to "被打断", 3 to "无", 4 to "快进", 5 to "快退", 6 to "缓冲",
            7 to "错误", 8 to "切上一首", 9 to "切下一首",
        )

        /** One synchronous AIDL-style call. Throws on a failed transact or a remote exception. */
        private inline fun <T> call(binder: IBinder, descriptor: String, code: Int, write: (Parcel) -> Unit = {}, read: (Parcel) -> T): T {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(descriptor)
                write(data)
                check(binder.transact(code, data, reply, 0)) { "transact($code) 返回 false" }
                reply.readException()
                return read(reply)
            } finally {
                reply.recycle()
                data.recycle()
            }
        }

        fun describe(error: Throwable): String {
            val root = (error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
            return "${root::class.java.simpleName}: ${root.message?.take(140).orEmpty()}"
        }
    }
}
