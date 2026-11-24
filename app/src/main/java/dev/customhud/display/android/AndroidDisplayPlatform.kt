package dev.customhud.display.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import dev.customhud.CustomHudApplication
import dev.customhud.adapters.EcarxVehicleDataSource
import dev.customhud.adapters.EcarxVehicleFeed
import dev.customhud.camera.CameraWindowLayer
import dev.customhud.camera.RearCameraPolicy
import dev.customhud.data.DataMode
import dev.customhud.data.VehicleProfile
import dev.customhud.data.VehicleSignal
import dev.customhud.data.VehicleSnapshot
import dev.customhud.display.session.DataHandle
import dev.customhud.display.session.DisplayDescriptor
import dev.customhud.display.session.DisplayListenerHandle
import dev.customhud.display.session.DisplayTarget
import dev.customhud.display.session.SessionPlatform
import dev.customhud.display.session.SessionWindow
import dev.customhud.display.session.WindowCallbacks
import dev.customhud.display.session.WindowSpec
import dev.customhud.layout.WidgetType
import dev.customhud.renderer.HudCanvasView

/**
 * One platform per DisplaySession. [role] MAIN draws the HUD widgets (and REAR_CAMERA when [camera] is set,
 * i.e. the camera display is the HUD target); CAMERA_ONLY is the cluster window that draws only REAR_CAMERA.
 */
class AndroidDisplayPlatform(
    private val context: Context,
    private val role: Role = Role.MAIN,
    camera: HudWindowRoot.CameraBinding? = null,
) : SessionPlatform {
    enum class Role { MAIN, CAMERA_ONLY }

    /** Camera pieces for windows created from now on (MAIN hosts the camera only when it is on the HUD display). */
    var camera: HudWindowRoot.CameraBinding? = camera

    private val tag = if (role == Role.MAIN) "" else " role=camera"
    private val app = context.applicationContext as CustomHudApplication
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val capabilities = DeviceCapabilities(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeWindow: AndroidWindow? = null

    override fun displays(generation: Long): List<DisplayDescriptor> = capabilities.displays(generation)
    override fun overlayGranted(): Boolean = Settings.canDrawOverlays(context)

    override fun registerDisplayListener(listener: () -> Unit): DisplayListenerHandle {
        val displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = listener()
            override fun onDisplayRemoved(displayId: Int) = listener()
            override fun onDisplayChanged(displayId: Int) = listener()
        }
        displayManager.registerDisplayListener(displayListener, mainHandler)
        return DisplayListenerHandle {
            displayManager.unregisterDisplayListener(displayListener)
        }
    }

    // Hidden system types (DISPLAY_OVERLAY 2026, SECURE_SYSTEM_OVERLAY 2015) are valid for a system-uid window.
    @SuppressLint("WrongConstant")
    override fun createWindow(target: DisplayTarget, spec: WindowSpec, callbacks: WindowCallbacks): SessionWindow {
        val display = displayManager.getDisplay(target.displayId)
            ?: throw IllegalArgumentException("Target display missing")
        if (display.name != target.name || !display.isValid) throw IllegalArgumentException("Target identity changed")
        // MAIN keeps the documented overlay type; the camera window may use a higher system layer (user request).
        val allowed = if (role == Role.CAMERA_ONLY) CameraWindowLayer.TYPES else setOf(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        require(spec.type in allowed) { "Unsupported window type ${spec.type}" }
        val displayContext = context.createDisplayContext(display)
        val windowContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            displayContext.createWindowContext(spec.type, null)
        } else {
            displayContext
        }
        val windowManager = windowContext.getSystemService(WindowManager::class.java)
        val binding = camera
        val hostsCamera = binding != null
        val canvas = HudCanvasView(windowContext).apply {
            mode = DataMode.DEVICE
            widgetFilter = when (role) {
                Role.CAMERA_ONLY -> { type -> type == WidgetType.REAR_CAMERA }
                Role.MAIN -> if (hostsCamera) { _ -> true } else { type -> type != WidgetType.REAR_CAMERA }
            }
            submitFrame(app.layoutStore.current, VehicleSnapshot())
        }
        val root = HudWindowRoot(windowContext, canvas, binding)
        root.submitLayout(app.layoutStore.current)
        app.log.add("WINDOW", "targetContextCreated display=${target.displayId} api=${Build.VERSION.SDK_INT} type=${spec.type}$tag camera=$hostsCamera")
        return AndroidWindow(windowManager, root, spec, callbacks, binding).also { activeWindow = it }
    }

    override fun startData(generation: Long): DataHandle {
        val gearSink = camera?.controller
        val source = EcarxVehicleDataSource(app.vehicle)
        val profile = VehicleProfile(
            name = EcarxVehicleFeed.PROFILE,
            freshnessBudgetsMs = mapOf(VehicleSignal.SPEED to 1500L, VehicleSignal.GEAR to 5000L),
        )
        val handle = object : DataHandle {
            @Volatile
            var open = true
            override fun close() {
                if (!open) return
                open = false
                val result = source.close()
                app.log.add("DATA", "close status=${result.status} pending=${result.pendingResources}$tag", generation = generation)
            }
        }
        source.open(profile, DataMode.DEVICE) { snapshot ->
            mainHandler.post {
                if (handle.open && generation == appGeneration()) {
                    activeWindow?.updateVehicle(snapshot)
                    gearSink?.onPolledGear(RearCameraPolicy.gearFromSnapshot(snapshot, SystemClock.elapsedRealtime()))
                }
            }
        }
        return handle
    }

    private fun appGeneration(): Long = activeSessionGeneration

    @Volatile
    var activeSessionGeneration: Long = 0
        private set

    fun updateSessionGeneration(generation: Long) {
        activeSessionGeneration = generation
    }

    private inner class AndroidWindow(
        private val windowManager: WindowManager,
        private val view: HudWindowRoot,
        private val spec: WindowSpec,
        private val callbacks: WindowCallbacks,
        private val camera: HudWindowRoot.CameraBinding?,
    ) : SessionWindow {
        private val canvas = view.canvas
        private var addReturned = false
        private var removeRequested = false
        /** remove() asked the camera to close first; removeView follows from its close callback. */
        private var cameraCloseRequested = false
        private var vehicle = VehicleSnapshot()
        private val releaseMedia = if (role == Role.MAIN) app.media.acquire() else ({ })
        private val stopMediaObserver = if (role == Role.MAIN) app.media.observe { canvas.submitMedia(app.media.nowPlaying()) } else ({ })
        private val stopCameraObserver = camera?.controller?.observe { view.submitCamera(it) } ?: { }
        private val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = callbacks.onAttach()
            override fun onViewDetachedFromWindow(v: View) {
                callbacks.onDetach()
                if (removeRequested) releaseView()
            }
        }

        init {
            view.addOnAttachStateChangeListener(attachListener)
            canvas.onDrawCompleted = { revision, frame -> callbacks.onDraw(revision, frame) }
            canvas.submitMedia(app.media.nowPlaying())
            canvas.onDrawError = { type, cause ->
                app.log.add("RENDER", "widget=$type cause=$cause", "ERROR", "RENDER_FAILED", activeSessionGeneration)
            }
        }

        override val isAttached: Boolean get() = view.isAttachedToWindow

        override fun add() {
            val params = WindowManager.LayoutParams(
                spec.width,
                spec.height,
                spec.type,
                spec.flags,
                PixelFormat.TRANSLUCENT,
            )
            windowManager.addView(view, params)
            addReturned = true
        }

        override fun remove() {
            // Both are idempotent; release media even if the view detaches later.
            stopMediaObserver()
            releaseMedia()
            val binding = camera
            if (binding != null && !cameraCloseRequested && !removeRequested) {
                // The camera must be closed before removeView; the close is asynchronous, so the removal
                // continues from its callback (DisplaySession waits for the detach, and a repeated remove()
                // from its cleanup timeout removes immediately below).
                cameraCloseRequested = true
                binding.log.add("CAMERA", "window remove: closing camera first", generation = binding.backend.generation)
                binding.controller.closeForRemoval {
                    try {
                        removeView(immediate = false)
                    } catch (error: Exception) {
                        app.log.add("WINDOW", "deferred removeView failed type=${error::class.java.name}", "ERROR", "CLEANUP_FAILED", binding.backend.generation)
                    }
                }
                return
            }
            // A repeated request while the camera close is still pending (cleanup timeout) removes immediately;
            // the SurfaceTexture itself is still only released after the close callback.
            removeView(immediate = cameraCloseRequested)
        }

        private fun removeView(immediate: Boolean) {
            // removeView only queues the removal; a repeated request (cleanup timeout, retried stop)
            // escalates to removeViewImmediate instead of removing an already-removed view.
            if (view.isAttachedToWindow || (addReturned && !removeRequested)) {
                try {
                    if (removeRequested || immediate) windowManager.removeViewImmediate(view) else windowManager.removeView(view)
                } catch (error: IllegalArgumentException) {
                    // "View not attached to window manager": nothing left to remove unless it is still attached.
                    if (view.isAttachedToWindow) throw error
                }
                removeRequested = true
            }
            if (!view.isAttachedToWindow) releaseView()
        }

        override fun refresh() {
            val layout = app.layoutStore.current
            canvas.submitFrame(layout, vehicle)
            view.submitLayout(layout)
        }

        fun updateVehicle(snapshot: VehicleSnapshot) {
            vehicle = snapshot
            refresh()
        }

        private fun releaseView() {
            stopCameraObserver()
            canvas.onDrawCompleted = null
            canvas.onDrawError = null
            view.removeOnAttachStateChangeListener(attachListener)
            if (activeWindow === this) activeWindow = null
        }
    }
}

private fun DisplayListenerHandle(block: () -> Unit): DisplayListenerHandle = object : DisplayListenerHandle {
    override fun close() = block()
}
