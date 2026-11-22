package dev.customhud.camera

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import dev.customhud.logging.HudLog

/**
 * [RearCameraBackend] on top of [ZeekrStreamCamera] (unchanged CarLight wrapper: its own control thread,
 * close(Runnable) fenced behind the SDK work queue). Holds the surface of the TextureView that currently
 * hosts the camera widget. Main thread only.
 */
class AndroidRearCamera(context: Context, private val log: HudLog) : RearCameraBackend, ZeekrStreamCamera.Listener {
    private val prefs = CameraPrefs(context)
    lateinit var controller: RearCameraController

    private val main = Handler(Looper.getMainLooper())
    private val camera = ZeekrStreamCamera(context, this)
    private var surface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var opened = false

    /** Generation of the session that hosts the camera; tags every log line. */
    var generation: Long = 0

    fun attachSurface(surface: Surface, width: Int, height: Int) {
        this.surface = surface
        surfaceWidth = width
        surfaceHeight = height
        log.add(CATEGORY, "surface attached ${width}x$height", generation = generation)
        controller.setSurfaceReady(true)
    }

    fun resizeSurface(surface: Surface, width: Int, height: Int) {
        if (surface !== this.surface) return
        surfaceWidth = width
        surfaceHeight = height
        if (opened) camera.updateSurface(surface, width, height)
    }

    /** The TextureView gave its SurfaceTexture back; the controller closes before anything is released. */
    fun detachSurface(surface: Surface) {
        if (surface !== this.surface) return
        controller.setSurfaceReady(false)
        this.surface = null
    }

    override fun open() {
        val target = surface
        if (target == null || !target.isValid) {
            main.post { controller.onBackendFailure("Surface 无效") }
            return
        }
        opened = true
        val config = config(prefs.fps)
        log.add(CATEGORY, "open request ${configText(config.fps)} opaque=${prefs.opaque} surface=${surfaceWidth}x$surfaceHeight", generation = generation)
        camera.open(target, surfaceWidth, surfaceHeight, config)
    }

    override fun close(onClosed: () -> Unit) {
        opened = false
        camera.close { onClosed() }
    }

    override fun release() {
        opened = false
        camera.release()
        surface = null
    }

    // ---------------------------------------------------------------- ZeekrStreamCamera.Listener (main thread)

    override fun onState(state: ZeekrStreamCamera.State, detail: String?) {
        val text = detail.orEmpty()
        log.add(CATEGORY, "state $state $text".trim(), level = if (state == ZeekrStreamCamera.State.ERROR) "WARN" else "INFO", generation = generation)
        when (state) {
            ZeekrStreamCamera.State.STARTED -> controller.onBackendStarted()
            ZeekrStreamCamera.State.ERROR -> controller.onBackendFailure(text.ifEmpty { "ERROR" })
            ZeekrStreamCamera.State.DISCONNECTED -> controller.onBackendFailure("相机服务断开 $text".trim(), "CAMERA_DISCONNECTED")
            ZeekrStreamCamera.State.STOPPED -> controller.onBackendStopped(text)
            else -> Unit
        }
    }

    override fun onEvent(line: String) {
        // Load timing, open parameters and raw SDK callbacks come through here.
        log.add(CATEGORY, line, generation = generation)
    }

    override fun onFirstFrame(cameraIndex: Int, sinceOpenMs: Long) {
        controller.onFirstFrame(sinceOpenMs)
    }

    override fun onCameraError(cameraIndex: Int, code: Int, name: String) {
        log.add(CATEGORY, "onError index=$cameraIndex code=$code name=$name", level = "WARN", errorCode = "CAMERA_ERROR_$code", generation = generation)
        controller.onCameraError(code, name)
    }

    override fun onStats(stats: ZeekrStreamCamera.Stats) {
        controller.onStats(stats.renderedFrames, stats.renderFps)
    }

    companion object {
        const val CATEGORY = "CAMERA"
        fun config(fps: Int) = ZeekrStreamCamera.Config(
            RearCameraController.URL,
            RearCameraController.WIDTH,
            RearCameraController.HEIGHT,
            fps,
            RearCameraController.MIRROR,
            RearCameraController.USE_GRAPHIC_BUFFER,
            ZeekrStreamCamera.CALLBACK_OFF,
        )

        fun configText(fps: Int) = RearCameraController.CONFIG_TEXT.replace("@${RearCameraController.FPS}", "@$fps")
    }
}
