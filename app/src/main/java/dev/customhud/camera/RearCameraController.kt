package dev.customhud.camera

import dev.customhud.display.session.Cancellable
import dev.customhud.display.session.SessionScheduler
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** What the camera owner (ZeekrStreamCamera adapter) does. Every call returns immediately. */
interface RearCameraBackend {
    /** Opens zeekr:202 into the currently attached surface. */
    fun open()

    /** Closes; [onClosed] runs on the main thread once the SDK no longer renders into the surface. */
    fun close(onClosed: () -> Unit)

    /** Final teardown (native destroy, service unbind). */
    fun release()
}

enum class CameraPhase { CLOSED, OPENING, STREAMING, CLOSING, RETRY_WAIT, RELEASED }
enum class CameraDisplay { HIDDEN, PLACEHOLDER, STREAMING }

data class RearCameraStatus(
    val display: CameraDisplay = CameraDisplay.HIDDEN,
    val reason: String = "HUD 未运行",
    val phase: CameraPhase = CameraPhase.CLOSED,
    val mode: RearCameraMode = RearCameraMode.DEFAULT,
    val gear: GearReading = GearReading.unavailable("—", "未开始"),
    val hostActive: Boolean = false,
    val surfaceReady: Boolean = false,
    val generation: Long = 0,
    val openCount: Int = 0,
    val renderFps: Float? = null,
    val firstFrameMs: Long? = null,
    val lastError: String? = null,
    val retryAttempt: Int = 0,
    val nextRetryAtMonotonic: Long? = null,
    /** Diagnostic: a static opaque white box instead of the camera (camera closed). */
    val testPattern: Boolean = false,
)

/**
 * Decides when zeekr:202 is open. Open only while the hosting DEVICE session is attached, the TextureView
 * surface exists, the widget is enabled and the mode allows it (gear); everything else closes. Errors retry
 * with backoff (2 s, 5 s, 10 s, then every 30 s) and only affect this widget. Main thread only.
 */
class RearCameraController(
    private val backend: RearCameraBackend,
    private val scheduler: SessionScheduler,
    private val log: (message: String, level: String, code: String?, generation: Long) -> Unit = { _, _, _, _ -> },
    private val gearTracker: GearTracker = GearTracker(),
    private val configText: () -> String = { CONFIG_TEXT },
) {
    private val observers = CopyOnWriteArrayList<(RearCameraStatus) -> Unit>()
    private var phase = CameraPhase.CLOSED
    private var hostActive = false
    private var generation = 0L
    private var surfaceReady = false
    private var widgetEnabled = false
    private var mode = RearCameraMode.DEFAULT
    private var testPattern = false
    private var lastDecision: ModeDecision? = null
    private var lastGear: GearReading? = null
    private var releasing = false
    private var backendReleased = false

    private var openSeq = 0
    private var openedAt = 0L
    private var firstFrameMs: Long? = null
    private var renderFps: Float? = null
    private var fpsLogAt = 0L
    private var fpsLogFrames = 0L
    private var lastError: String? = null
    private var retryAttempt = 0
    private var retryAfterClose: Long? = null
    private var nextRetryAt: Long? = null
    private var retryTask: Cancellable? = null
    private var watchdog: Cancellable? = null
    private var stableTask: Cancellable? = null
    private var gearStaleTask: Cancellable? = null
    private val closeWaiters = mutableListOf<() -> Unit>()

    var status = RearCameraStatus()
        private set

    fun observe(observer: (RearCameraStatus) -> Unit): () -> Unit {
        observers.add(observer)
        observer(status)
        return { observers.remove(observer) }
    }

    // ------------------------------------------------------------------ inputs

    /** The DEVICE session hosting the camera widget is attached ([active]) for [generation]. */
    fun setHost(active: Boolean, generation: Long) {
        if (active == hostActive && generation == this.generation) return
        if (active != hostActive) log("host ${if (active) "attached" else "inactive"}", "INFO", null, generation)
        hostActive = active
        this.generation = generation
        reconcile()
    }

    fun setSurfaceReady(ready: Boolean) {
        if (ready == surfaceReady) return
        surfaceReady = ready
        log("surface ${if (ready) "ready" else "gone"}", "INFO", null, generation)
        reconcile()
    }

    fun setWidgetEnabled(enabled: Boolean) {
        if (enabled == widgetEnabled) return
        widgetEnabled = enabled
        reconcile()
    }

    fun setMode(mode: RearCameraMode) {
        if (mode == this.mode) return
        log("mode ${this.mode} -> $mode", "INFO", null, generation)
        this.mode = mode
        reconcile()
    }

    /** Diagnostic: replace the camera by a static white box (no frame updates at all). */
    fun setTestPattern(on: Boolean) {
        if (on == testPattern) return
        log("test pattern ${if (on) "on" else "off"}", "INFO", null, generation)
        testPattern = on
        reconcile()
    }

    fun onEcarxGear(reading: GearReading) {
        gearTracker.updateEcarx(reading)
        reconcile()
    }

    fun onAndroidCarGear(reading: GearReading) {
        gearTracker.updateAndroidCar(reading, scheduler.now())
        gearStaleTask?.cancel()
        // Re-evaluate once this reading would be stale if no newer one arrives.
        gearStaleTask = scheduler.postDelayed(GearTracker.ANDROID_CAR_STALE_MS + 100) { reconcile() }
        reconcile()
    }

    /**
     * The hosting window is about to be removed: close the camera and run [done] (the actual removeView)
     * once the SDK has let go of the surface. Runs [done] immediately when nothing is open.
     */
    fun closeForRemoval(done: () -> Unit) {
        hostActive = false
        whenClosed(done)
        reconcile()
    }

    /** Runs [action] once the camera is not rendering (now, or after the pending close). */
    fun whenClosed(action: () -> Unit) {
        if (phase == CameraPhase.OPENING || phase == CameraPhase.STREAMING || phase == CameraPhase.CLOSING) {
            closeWaiters += action
            if (phase != CameraPhase.CLOSING) close("释放画面区域")
        } else {
            action()
        }
    }

    /** Service destroyed: close if needed, then release the SDK. */
    fun release() {
        if (releasing) return
        releasing = true
        cancelTasks()
        log("release requested phase=$phase", "INFO", null, generation)
        when (phase) {
            CameraPhase.OPENING, CameraPhase.STREAMING -> {
                closeWaiters += ::releaseBackend
                close("服务销毁")
            }
            CameraPhase.CLOSING -> closeWaiters += ::releaseBackend
            else -> releaseBackend()
        }
        // The close callback normally arrives within a few hundred ms; never keep the SDK forever.
        scheduler.postDelayed(RELEASE_FALLBACK_MS) { releaseBackend() }
        publish()
    }

    // ------------------------------------------------------------------ backend events (main thread)

    fun onBackendStarted() {
        if (phase == CameraPhase.OPENING || phase == CameraPhase.STREAMING) log("camera started", "INFO", null, generation)
    }

    fun onFirstFrame(sinceOpenMs: Long) {
        if (phase != CameraPhase.OPENING) return
        watchdog?.cancel()
        phase = CameraPhase.STREAMING
        firstFrameMs = sinceOpenMs
        fpsLogAt = scheduler.now()
        fpsLogFrames = 0
        log("first frame ${sinceOpenMs} ms after open (open #$openSeq)", "INFO", null, generation)
        val seq = openSeq
        stableTask?.cancel()
        stableTask = scheduler.postDelayed(STABLE_MS) {
            if (seq == openSeq && phase == CameraPhase.STREAMING && retryAttempt != 0) {
                retryAttempt = 0
                log("streaming stable; retry backoff reset", "INFO", null, generation)
                publish()
            }
        }
        publish()
    }

    /** SDK error state, DISCONNECTED, load failure, invalid surface …: close and retry with backoff. */
    fun onBackendFailure(detail: String, code: String? = null) {
        if (phase != CameraPhase.OPENING && phase != CameraPhase.STREAMING) {
            // Late report for an open that is already closing / closed: keep the detail for the UI only.
            if (phase == CameraPhase.CLOSING || phase == CameraPhase.RETRY_WAIT) lastError = detail
            publish()
            return
        }
        lastError = detail
        val delay = RearCameraPolicy.retryDelay(retryAttempt)
        retryAttempt += 1
        retryAfterClose = delay
        log("failure: $detail; retry #$retryAttempt in ${delay / 1000} s", "WARN", code ?: "CAMERA_FAILED", generation)
        close("错误")
    }

    /** Explicit SDK error code (logged by the caller as well); refines the visible reason. */
    fun onCameraError(code: Int, name: String) {
        val detail = "错误 $code $name".trim()
        if (phase == CameraPhase.OPENING || phase == CameraPhase.STREAMING) {
            onBackendFailure(detail, "CAMERA_ERROR_$code")
        } else {
            lastError = detail
            publish()
        }
    }

    /** The SDK stopped a channel we did not close (only meaningful while streaming). */
    fun onBackendStopped(detail: String) {
        if (phase == CameraPhase.STREAMING) onBackendFailure("相机被停止 $detail".trim())
    }

    /** 2 Hz statistics from the SDK; logs the render fps every 10 s while streaming. */
    fun onStats(renderedFrames: Long, fps: Float) {
        if (phase != CameraPhase.STREAMING) return
        renderFps = fps
        val now = scheduler.now()
        if (now - fpsLogAt >= FPS_LOG_MS) {
            val avg = (renderedFrames - fpsLogFrames) * 1000f / (now - fpsLogAt).coerceAtLeast(1)
            log(String.format(Locale.ROOT, "render fps=%.1f frames=%d", avg, renderedFrames), "INFO", null, generation)
            fpsLogAt = now
            fpsLogFrames = renderedFrames
        }
        publish()
    }

    // ------------------------------------------------------------------ state machine

    private fun decision(): ModeDecision {
        val gear = gearTracker.effective(scheduler.now())
        if (gear != lastGear) {
            val previous = lastGear
            lastGear = gear
            if (previous == null || previous.gear != gear.gear || previous.available != gear.available || previous.source != gear.source) {
                log("gear ${gear.label()}", if (gear.available) "INFO" else "WARN", if (gear.available) null else "GEAR_UNAVAILABLE", generation)
            }
        }
        val decision = RearCameraPolicy.decide(mode, widgetEnabled, gear)
        if (decision != lastDecision) {
            lastDecision = decision
            log("decision $decision mode=$mode", if (decision is ModeDecision.Unavailable) "WARN" else "INFO",
                if (decision is ModeDecision.Unavailable) "GEAR_UNAVAILABLE" else null, generation)
        }
        return decision
    }

    private fun reconcile() {
        if (phase == CameraPhase.RELEASED) return
        val decision = decision()
        val want = !releasing && !testPattern && hostActive && surfaceReady && decision == ModeDecision.Show
        when {
            want && phase == CameraPhase.CLOSED -> open()
            !want && (phase == CameraPhase.OPENING || phase == CameraPhase.STREAMING) -> close(reasonFor(decision))
            !want && phase == CameraPhase.RETRY_WAIT -> {
                cancelRetry(resetAttempts = true)
                phase = CameraPhase.CLOSED
            }
            // Not wanted any more (P gear, suspend, mode …): the next open is a fresh attempt.
            !want && phase == CameraPhase.CLOSED -> retryAttempt = 0
        }
        publish()
    }

    private fun reasonFor(decision: ModeDecision): String = when {
        releasing -> "服务销毁"
        !hostActive -> "HUD 会话未运行"
        testPattern -> "测试画面"
        !surfaceReady -> "画面区域已销毁"
        decision is ModeDecision.Hide -> decision.reason
        decision is ModeDecision.Unavailable -> "挡位不可用"
        else -> "—"
    }

    private fun open() {
        cancelRetry(resetAttempts = false)
        phase = CameraPhase.OPENING
        openSeq += 1
        openedAt = scheduler.now()
        firstFrameMs = null
        renderFps = null
        log("open #$openSeq ${configText()} gear=${lastGear?.label()} mode=$mode", "INFO", null, generation)
        val seq = openSeq
        watchdog?.cancel()
        watchdog = scheduler.postDelayed(FIRST_FRAME_TIMEOUT_MS) {
            if (seq == openSeq && phase == CameraPhase.OPENING) {
                onBackendFailure("${FIRST_FRAME_TIMEOUT_MS / 1000} 秒未出图", "FIRST_FRAME_TIMEOUT")
            }
        }
        backend.open()
    }

    private fun close(reason: String) {
        watchdog?.cancel()
        stableTask?.cancel()
        phase = CameraPhase.CLOSING
        log("close #$openSeq reason=$reason", "INFO", null, generation)
        val seq = openSeq
        publish()
        backend.close { onClosed(seq) }
    }

    private fun onClosed(seq: Int) {
        if (phase != CameraPhase.CLOSING || seq != openSeq) return
        log("closed #$seq", "INFO", null, generation)
        phase = CameraPhase.CLOSED
        renderFps = null
        val waiters = closeWaiters.toList()
        closeWaiters.clear()
        waiters.forEach { it() }
        if (phase == CameraPhase.RELEASED) return
        val retry = retryAfterClose
        retryAfterClose = null
        if (retry != null && !releasing) {
            val wanted = hostActive && surfaceReady && lastDecision == ModeDecision.Show
            if (wanted) {
                phase = CameraPhase.RETRY_WAIT
                nextRetryAt = scheduler.now() + retry
                retryTask = scheduler.postDelayed(retry) {
                    retryTask = null
                    nextRetryAt = null
                    if (phase == CameraPhase.RETRY_WAIT) phase = CameraPhase.CLOSED
                    reconcile()
                }
                publish()
                return
            }
        }
        reconcile()
    }

    private fun releaseBackend() {
        if (backendReleased) return
        backendReleased = true
        closeWaiters.toList().also { closeWaiters.clear() }.forEach { it() }
        phase = CameraPhase.RELEASED
        backend.release()
        log("released", "INFO", null, generation)
        publish()
    }

    private fun cancelRetry(resetAttempts: Boolean) {
        retryTask?.cancel()
        retryTask = null
        nextRetryAt = null
        retryAfterClose = null
        if (resetAttempts) retryAttempt = 0
    }

    private fun cancelTasks() {
        retryTask?.cancel()
        retryTask = null
        nextRetryAt = null
        retryAfterClose = null
        watchdog?.cancel()
        stableTask?.cancel()
        gearStaleTask?.cancel()
    }

    private fun publish() {
        val decision = lastDecision ?: RearCameraPolicy.decide(mode, widgetEnabled, gearTracker.effective(scheduler.now()))
        val (display, reason) = when {
            phase == CameraPhase.RELEASED || releasing -> CameraDisplay.HIDDEN to "已释放"
            !hostActive -> CameraDisplay.HIDDEN to "HUD 会话未运行"
            testPattern && widgetEnabled -> CameraDisplay.PLACEHOLDER to "测试画面：静止白块（相机已关闭）"
            decision is ModeDecision.Hide -> CameraDisplay.HIDDEN to decision.reason
            decision is ModeDecision.Unavailable -> CameraDisplay.PLACEHOLDER to decision.reason
            phase == CameraPhase.STREAMING -> CameraDisplay.STREAMING to "出图中"
            phase == CameraPhase.RETRY_WAIT -> CameraDisplay.PLACEHOLDER to
                "相机失败：${lastError ?: "未知"} · 将在 ${((nextRetryAt ?: scheduler.now()) - scheduler.now() + 999) / 1000} 秒后第 $retryAttempt 次重试"
            !surfaceReady -> CameraDisplay.PLACEHOLDER to "等待画面区域"
            else -> CameraDisplay.PLACEHOLDER to "正在打开后视摄像头…"
        }
        val next = RearCameraStatus(
            display = display,
            reason = reason,
            phase = phase,
            mode = mode,
            gear = lastGear ?: gearTracker.effective(scheduler.now()),
            hostActive = hostActive,
            surfaceReady = surfaceReady,
            generation = generation,
            openCount = openSeq,
            renderFps = renderFps,
            firstFrameMs = firstFrameMs,
            lastError = lastError,
            retryAttempt = retryAttempt,
            nextRetryAtMonotonic = nextRetryAt,
            testPattern = testPattern && widgetEnabled,
        )
        if (next == status) return
        status = next
        observers.forEach { it(next) }
    }

    companion object {
        const val URL = "zeekr:202"
        const val WIDTH = 1600
        const val HEIGHT = 900
        const val FPS = 30
        const val MIRROR = true
        const val USE_GRAPHIC_BUFFER = false
        const val CONFIG_TEXT = "url=$URL ${WIDTH}x$HEIGHT@$FPS mirror=$MIRROR useGraphicBuffer=$USE_GRAPHIC_BUFFER rawCallback=off"
        const val FIRST_FRAME_TIMEOUT_MS = 15_000L
        const val STABLE_MS = 30_000L
        const val FPS_LOG_MS = 10_000L
        const val RELEASE_FALLBACK_MS = 3_000L
    }
}
