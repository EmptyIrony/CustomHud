package dev.customhud.probe

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ComponentInfo
import android.content.pm.PackageManager
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import dev.customhud.BuildConfig
import dev.customhud.CustomHudApplication
import dev.customhud.adapters.VEHICLE_ROWS
import dev.customhud.adapters.VehiclePermissions
import dev.customhud.display.android.DeviceCapabilities
import dev.customhud.media.MediaMonitor
import dev.customhud.media.MediaNotificationListener
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * One-shot environment probe using public APIs only. Produces plain text lines meant to be
 * read on screen and photographed page by page.
 */
class ProbeRunner(private val context: Context) {
    private val app = context.applicationContext as CustomHudApplication
    private val main = Handler(Looper.getMainLooper())
    private val samples = mutableListOf<String>()
    private var background: BackgroundResult? = null
    private var cancelled = false
    private var startedAt = 0L

    private data class BackgroundResult(
        val packages: List<String>,
        val dumpsys: List<String>,
        val network: List<String>,
    )

    /** Runs for [durationMs]; [onProgress] gets (message, 0..1). Returns a cancel function. */
    fun run(
        durationMs: Long,
        onProgress: (String, Float) -> Unit,
        onDone: (List<String>) -> Unit,
    ): () -> Unit {
        startedAt = SystemClock.elapsedRealtime()
        val releaseMedia = app.media.acquire()
        // Keeps the shared ECARX poller running for the whole probe; the section is read at the end.
        val releaseVehicle = app.vehicle.subscribe { }
        val header = header()
        val initialSessions = sessionSection()
        val sessionPackages = app.media.controllers().map { it.packageName }.distinct()
        Thread({
            val result = BackgroundResult(
                packages = safeLines("应用组件") { packageSection(sessionPackages) },
                dumpsys = safeLines("dumpsys") { dumpsysSection() },
                network = safeLines("网络") { networkSection() },
            )
            main.post { background = result }
        }, "customhud-probe").start()

        val tick = object : Runnable {
            override fun run() {
                if (cancelled) return
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                if (elapsed % 5_000 < 1_000 || samples.isEmpty()) sample()
                if (elapsed >= durationMs && background != null) {
                    releaseMedia()
                    val vehicle = safeLines("A 车况") { vehicleSection() }
                    releaseVehicle()
                    val result = background!!
                    onDone(header + vehicle + initialSessions + changeSection() + sampleSection() + notificationSection() +
                        result.packages + result.dumpsys + result.network + listOf("", "== 报告结束 =="))
                    return
                }
                val waiting = if (elapsed >= durationMs) "（等待系统查询完成）" else ""
                onProgress("正在记录媒体字段变化，请保持歌曲播放… ${elapsed / 1000}/${durationMs / 1000} 秒$waiting", (elapsed.toFloat() / durationMs).coerceAtMost(1f))
                main.postDelayed(this, 1_000)
            }
        }
        main.post(tick)
        return {
            if (!cancelled) {
                cancelled = true
                releaseMedia()
                releaseVehicle()
            }
        }
    }

    private fun header(): List<String> {
        val listener = ComponentName(context, MediaNotificationListener::class.java)
        val listenerGranted = try {
            context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(listener)
        } catch (error: RuntimeException) {
            null
        }
        val lines = mutableListOf(
            "== 0 概况 ==",
            "CustomHUD ${BuildConfig.VERSION_NAME} buildId=${BuildConfig.BUILD_ID}",
            "时间 ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}",
            "Android ${Build.VERSION.RELEASE} SDK ${Build.VERSION.SDK_INT} · ${Build.MANUFACTURER} ${Build.MODEL}",
            "uid=${Process.myUid()} 包名=${context.packageName}",
            "悬浮窗=${Settings.canDrawOverlays(context)} 通知使用权=${listenerGranted ?: "未知"} 监听已连接=${app.media.listenerConnected}",
            "媒体读取=${app.media.access.name}（${app.media.access.label}）",
        )
        if (app.media.accessDetail.isNotBlank()) lines += "  ${app.media.accessDetail}"
        app.media.zeekr.state.let { z ->
            lines += "极氪媒体中心=${z.stage.label} 启用=${z.enabled} 模式=${z.mode.label}"
            if (z.detail.isNotBlank()) lines += "  ${z.detail}"
            if (z.bindInfo.isNotBlank()) lines += "  ${z.bindInfo}"
            z.descriptorCheck?.let { lines += "  $it" }
            lines += "  推送 歌曲=${z.infoPushes} 进度=${z.progressPushes} 其他=${z.otherCallbacks} 行=${z.lineIndex + 1}/${z.lineCount} 音源=${z.sourceType} 状态=${z.playbackStatus}"
            lines += "  标题当歌词=${z.titleMode.label} → ${z.titleDecision} 应用=${z.packageName}"
            lines += "  歌词长度 原文=${z.lyricLength} 翻译=${z.translatedLength} 应用=${z.packageName}"
            z.line?.let { lines += "  当前句「$it」" }
            z.lastError?.let { lines += "  错误 $it" }
        }
        lines += "权限:"
        PERMISSIONS.forEach { permission ->
            val granted = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            lines += "  ${if (granted) "✓" else "✗"} ${permission.substringAfterLast('.')}"
        }
        lines += "屏幕:"
        DeviceCapabilities(context).displays().forEach {
            lines += "  id=${it.id} ${it.name} ${it.width}x${it.height} dpi=${it.densityDpi} flags=0x${it.flags.toString(16)} valid=${it.valid}"
        }
        return lines + ""
    }

    private fun sessionSection(): List<String> {
        val controllers = app.media.controllers()
        val lines = mutableListOf("== 1 媒体会话（${controllers.size} 个） ==")
        if (controllers.isEmpty()) {
            lines += "  没有读到活动会话。请先在车机 QQ 音乐播放一首歌再探测。"
        }
        controllers.forEachIndexed { index, controller ->
            lines += "[${index + 1}] ${controller.packageName}"
            lines += describeController(controller).map { "  $it" }
        }
        return lines + ""
    }

    private fun describeController(controller: MediaController): List<String> {
        val lines = mutableListOf<String>()
        safe { lines += "tag=${if (Build.VERSION.SDK_INT >= 30) controller.tag else "-"} flags=0x${controller.flags.toString(16)} ratingType=${controller.ratingType}" }
        safe {
            val info = controller.playbackInfo
            lines += "播放类型=${info?.playbackType} 音量=${info?.currentVolume}/${info?.maxVolume}"
        }
        safe {
            val state = controller.playbackState
            if (state == null) {
                lines += "状态: 无"
            } else {
                lines += "状态 ${MediaMonitor.stateName(state.state)} 位置=${seconds(state.position)} 速度=${state.playbackSpeed} " +
                    "更新于 ${seconds(SystemClock.elapsedRealtime() - state.lastPositionUpdateTime)} 前"
                lines += "actions=0x${state.actions.toString(16)} 缓冲=${seconds(state.bufferedPosition)} 队列项=${state.activeQueueItemId}"
                state.errorMessage?.let { lines += "错误=$it" }
                state.customActions.forEach { lines += "自定义按钮: ${it.name} (${it.action})" }
                bundleLines(state.extras).forEach { lines += "状态附加 $it" }
            }
        }
        safe {
            val metadata = controller.metadata
            if (metadata == null) {
                lines += "metadata: 无"
            } else {
                val keys = metadata.keySet().sorted()
                lines += "metadata（${keys.size} 项）:"
                keys.forEach { key -> lines += "  ${MediaMonitor.shortKey(key)} = ${MediaMonitor.describeMetadata(metadata, key)}" }
                val d = metadata.description
                lines += "  description: title=${d?.title} subtitle=${d?.subtitle} desc=${d?.description} mediaId=${d?.mediaId}"
            }
        }
        safe { bundleLines(controller.extras).forEach { lines += "会话附加 $it" } }
        safe {
            val queue = controller.queue
            lines += "队列=${queue?.size ?: "无"} 队列标题=${controller.queueTitle}"
            queue?.take(3)?.forEach { lines += "  · ${it.description?.title} / ${it.description?.subtitle}" }
        }
        safe { bundleLines(controller.sessionInfo).forEach { lines += "会话信息 $it" } }
        return lines
    }

    private fun sample() {
        val now = SystemClock.elapsedRealtime()
        app.media.controllers().forEach { controller ->
            val state = controller.playbackState ?: return@forEach
            val estimated = if (state.state == PlaybackState.STATE_PLAYING) {
                state.position + ((now - state.lastPositionUpdateTime) * state.playbackSpeed).toLong()
            } else {
                state.position
            }
            samples += "+${seconds(now - startedAt)} ${controller.packageName.takeLast(24)} ${MediaMonitor.stateName(state.state)} " +
                "报告=${seconds(state.position)} 推算=${seconds(estimated)} 上次更新=${seconds(now - state.lastPositionUpdateTime)}前"
        }
    }

    private fun changeSection(): List<String> {
        val lines = mutableListOf("== 2 字段变化（探测期间） ==")
        val frequent = app.media.fields.frequentSince(startedAt, 3)
            .filterNot { it.first.id == "state" }
        if (frequent.isEmpty()) {
            lines += "  没有反复变化的文字字段（≥3 次）。歌词大概率不在媒体信息/通知里。"
        } else {
            lines += "  疑似歌词字段（变化次数）:"
            frequent.take(10).forEach { (key, count) -> lines += "  ★ ${key.packageName} ${key.id} ×$count" }
        }
        val history = app.media.fields.historySince(startedAt)
        lines += "  变化记录（${history.size} 条，最多显示 80 条）:"
        history.takeLast(80).forEach {
            lines += "  +${seconds(it.atMs - startedAt)} ${it.key.id} = ${it.value}"
        }
        app.media.lifecycleSince(startedAt).forEach { (at, message) -> lines += "  +${seconds(at - startedAt)} [会话] $message" }
        return lines + ""
    }

    private fun sampleSection(): List<String> = listOf("== 3 播放进度采样 ==") +
        samples.ifEmpty { listOf("  无可采样的播放状态") }.map { "  $it" } + ""

    private fun notificationSection(): List<String> {
        val lines = mutableListOf("== 4 通知字段 ==")
        if (!app.media.listenerConnected) {
            lines += "  通知监听未连接（未开启通知使用权，或系统没有该设置页）。"
        }
        val notif = app.media.fields.all().filter { it.key.id.startsWith("notif:") }
        if (notif.isEmpty()) lines += "  没有记录到媒体通知字段。"
        notif.take(40).forEach { lines += "  ${it.key.packageName.takeLast(24)} ${it.key.id} ×${it.changeCount} = ${it.value}" }
        return lines + ""
    }

    private fun packageSection(sessionPackages: List<String>): List<String> {
        val pm = context.packageManager
        val lines = mutableListOf("== 5 媒体应用公开声明的组件 ==")
        @Suppress("DEPRECATION")
        val installed = pm.getInstalledPackages(0).map { it.packageName }
        val candidates = (sessionPackages + installed.filter { name -> MUSIC_HINTS.any { name.contains(it, ignoreCase = true) } })
            .distinct().take(8)
        if (candidates.isEmpty()) lines += "  没有找到媒体应用（已安装 ${installed.size} 个应用）。"
        candidates.forEach { packageName ->
            try {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(
                    packageName,
                    PackageManager.GET_PROVIDERS or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS,
                )
                val version = info.longVersionCode
                lines += "[$packageName] v${info.versionName} ($version)"
                val exported = mutableListOf<Pair<String, String>>()
                info.providers?.filter { it.exported }?.forEach { exported += "provider" to "${short(packageName, it)} auth=${it.authority}" }
                info.services?.filter { it.exported }?.forEach { exported += "service" to "${short(packageName, it)} perm=${it.permission ?: "-"}" }
                info.receivers?.filter { it.exported }?.forEach { exported += "receiver" to "${short(packageName, it)} perm=${it.permission ?: "-"}" }
                val (hits, others) = exported.partition { (_, text) -> LYRIC_HINTS.containsMatchIn(text) }
                lines += "  对外组件 ${exported.size} 个，名称含歌词/仪表相关词 ${hits.size} 个"
                hits.forEach { (kind, text) -> lines += "  ★ $kind $text" }
                others.take(25).forEach { (kind, text) -> lines += "  · $kind $text" }
                if (others.size > 25) lines += "  …另有 ${others.size - 25} 个"
            } catch (error: Exception) {
                lines += "[$packageName] 读取失败 ${error::class.java.simpleName}"
            }
        }
        return lines + ""
    }

    private fun dumpsysSection(): List<String> {
        val lines = mutableListOf("== 6 系统查询（dumpsys） ==")
        val media = exec("dumpsys", "media_session")
        lines += "media_session: ${media.summary}"
        media.lines.filter { MEDIA_DUMP_KEEP.containsMatchIn(it) }.take(50).forEach { lines += "  ${it.trim()}" }

        val window = exec("dumpsys", "window", "windows")
        lines += "window: ${window.summary}"
        windowsOffDefault(window.lines).take(30).forEach { lines += "  $it" }

        val surface = exec("dumpsys", "SurfaceFlinger", "--list")
        lines += "SurfaceFlinger --list: ${surface.summary}"
        surface.lines.filter { it.isNotBlank() }.take(60).forEach { lines += "  ${it.trim()}" }
        return lines + ""
    }

    /** Windows whose displayId is not 0, as "display=N type=… name". */
    private fun windowsOffDefault(dump: List<String>): List<String> {
        val result = mutableListOf<String>()
        var name: String? = null
        var displayId: String? = null
        var type: String? = null
        fun flush() {
            if (name != null && displayId != null && displayId != "0") result += "display=$displayId ty=${type ?: "?"} $name"
        }
        for (line in dump) {
            val header = WINDOW_HEADER.find(line)
            if (header != null) {
                flush()
                name = header.groupValues[1]
                displayId = null
                type = null
                continue
            }
            if (displayId == null) DISPLAY_ID.find(line)?.let { displayId = it.groupValues[1] }
            if (type == null) WINDOW_TYPE.find(line)?.let { type = it.groupValues[1] }
        }
        flush()
        if (result.isEmpty() && dump.isNotEmpty()) result += "（未解析到非主屏窗口）"
        return result
    }

    private fun networkSection(): List<String> {
        val lines = mutableListOf("== 7 网络 ==")
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            lines += if (caps == null) {
                "  当前没有活动网络"
            } else {
                "  活动网络: INTERNET=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)} " +
                    "VALIDATED=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)} " +
                    "wifi=${caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)} cellular=${caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)}"
            }
        } catch (error: Exception) {
            lines += "  网络状态读取失败 ${error::class.java.simpleName}"
        }
        val started = SystemClock.elapsedRealtime()
        lines += try {
            val connection = URL("https://lrclib.net/").openConnection() as HttpURLConnection
            connection.connectTimeout = 6_000
            connection.readTimeout = 6_000
            connection.setRequestProperty("User-Agent", "CustomHUD-probe")
            val code = connection.responseCode
            connection.disconnect()
            "  lrclib.net: HTTP $code，${SystemClock.elapsedRealtime() - started} ms"
        } catch (error: Exception) {
            "  lrclib.net: 失败 ${error::class.java.simpleName} ${error.message?.take(80)}"
        }
        return lines + ""
    }

    private data class ExecResult(val summary: String, val lines: List<String>)

    private fun exec(vararg command: String): ExecResult = try {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = mutableListOf<String>()
        val reader = Thread {
            try {
                process.inputStream.bufferedReader().useLines { seq -> seq.take(20_000).forEach { synchronized(output) { output += it } } }
            } catch (_: Exception) {
            }
        }
        reader.start()
        val finished = process.waitFor(10, TimeUnit.SECONDS)
        if (!finished) process.destroy()
        reader.join(2_000)
        val copy = synchronized(output) { output.toList() }
        val denied = copy.take(5).any { it.contains("Permission Denial", true) || it.contains("not allowed", true) }
        val status = when {
            !finished -> "超时"
            denied -> "被拒绝 exit=${process.exitValue()}"
            else -> "exit=${process.exitValue()}"
        }
        ExecResult("$status，${copy.size} 行" + if (denied) "：${copy.first().take(100)}" else "", if (denied) emptyList() else copy)
    } catch (error: Exception) {
        ExecResult("无法执行 ${error::class.java.simpleName} ${error.message?.take(80)}", emptyList())
    }

    private fun bundleLines(bundle: android.os.Bundle?): List<String> {
        bundle ?: return emptyList()
        return try {
            bundle.keySet().sorted().map { key ->
                val value = try {
                    @Suppress("DEPRECATION")
                    MediaMonitor.describe(bundle.get(key))
                } catch (error: RuntimeException) {
                    "[unreadable ${error::class.java.simpleName}]"
                }
                "$key = ${value.take(160)}"
            }
        } catch (error: RuntimeException) {
            listOf("[bundle unreadable ${error::class.java.simpleName}]")
        }
    }

    private fun short(packageName: String, info: ComponentInfo): String = info.name.removePrefix(packageName)

    private inline fun safe(block: () -> Unit) {
        try {
            block()
        } catch (_: RuntimeException) {
        }
    }

    /** ECARX vehicle data as the HUD sees it (same shared poller); ? lines are raw values still to be confirmed. */
    private fun vehicleSection(): List<String> {
        val feed = app.vehicle
        return listOf("== A 车况（ECARX，与 HUD 相同的读取） ==") + feed.statusLines().map { "  $it" } +
            feed.signalLines(VEHICLE_ROWS).map { "  $it" } +
            listOf("  （✓ 已实车确认 · ? 原值待确认，HUD 不显示 · ✗ 读不到）", "")
    }

    private inline fun safeLines(title: String, block: () -> List<String>): List<String> = try {
        block()
    } catch (error: Exception) {
        listOf("== $title ==", "  失败 ${error::class.java.simpleName} ${error.message?.take(100)}", "")
    }

    companion object {
        private val PERMISSIONS = listOf(
            "android.permission.MEDIA_CONTENT_CONTROL",
            "android.permission.DUMP",
            "android.permission.QUERY_ALL_PACKAGES",
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.POST_NOTIFICATIONS",
        ) + VehiclePermissions.PERMISSIONS
        private val MUSIC_HINTS = listOf("music", "kugou", "kuwo", "netease", "ximalaya", "media")
        private val LYRIC_HINTS = Regex("(?i)lyric|lrc|geci|cluster|hud|meter|dashboard|instrument|ipc|widget")
        private val MEDIA_DUMP_KEEP = Regex("(?i)package=|metadata|description=|lyric|lrc|state=PlaybackState|active=|sessions? stack|ownerUid|tag=")
        private val WINDOW_HEADER = Regex("Window #\\d+ Window\\{[0-9a-f]+ \\S+ ([^}]+)\\}")
        private val DISPLAY_ID = Regex("mDisplayId=(\\d+)")
        private val WINDOW_TYPE = Regex("\\bty=([A-Z_0-9]+)")

        fun seconds(ms: Long): String = String.format(Locale.US, "%.1fs", ms / 1000.0)
    }
}
