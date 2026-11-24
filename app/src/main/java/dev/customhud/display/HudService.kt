package dev.customhud.display

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import dev.customhud.CustomHudApplication
import dev.customhud.R
import dev.customhud.adapters.EcarxGear
import dev.customhud.adapters.EcarxGearSource
import dev.customhud.camera.AndroidRearCamera
import dev.customhud.camera.CameraPrefs
import dev.customhud.camera.CameraWindowLayer
import dev.customhud.camera.GearReading
import dev.customhud.camera.RearCameraController
import dev.customhud.camera.RearCameraMode
import dev.customhud.camera.RearCameraPolicy
import dev.customhud.display.android.AndroidDisplayPlatform
import dev.customhud.display.android.DeviceCapabilities
import dev.customhud.display.android.HudWindowRoot
import dev.customhud.display.android.MainThreadScheduler
import dev.customhud.display.session.DisplaySession
import dev.customhud.display.session.DisplayTarget
import dev.customhud.display.session.SessionConfig
import dev.customhud.display.session.SessionSnapshot
import dev.customhud.display.session.SessionState
import dev.customhud.display.session.StartRequest
import dev.customhud.display.session.StopRequest
import dev.customhud.display.session.WindowSpec
import dev.customhud.layout.LayoutSnapshot
import dev.customhud.layout.WidgetType
import dev.customhud.settings.MainActivity
import java.util.Locale
import java.util.UUID

class HudService : Service() {
    private lateinit var app: CustomHudApplication
    private lateinit var platform: AndroidDisplayPlatform
    private lateinit var cameraPlatform: AndroidDisplayPlatform
    private lateinit var cameraPrefs: CameraPrefs
    private lateinit var cameraBinding: HudWindowRoot.CameraBinding
    private var gearSource: EcarxGearSource? = null
    /** Camera display for the current run when it differs from the HUD target (null = embedded / none). */
    private var cameraTarget: DisplayTarget? = null
    /** The session whose window hosts the camera widget in the current run. */
    private var cameraHost: DisplaySession? = null
    private var windowSpec = WindowSpec()
    private var cameraWindowLayer: CameraWindowLayer? = null
    private var stopLayoutObserver: (() -> Unit)? = null
    private var stopSessionObserver: (() -> Unit)? = null
    private var stopCameraSessionObserver: (() -> Unit)? = null
    private var stopCameraPrefsObserver: (() -> Unit)? = null
    private var receiverRegistered = false
    private var backgroundAllowed = true
    private var uiVisible = true
    private var lastStartId = 0
    private var destroying = false
    private var lastNotificationKey: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stopIfIdleTask = Runnable { stopIfIdle() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> sessions().forEach { it.suspend("screen-off") }
                Intent.ACTION_SCREEN_ON -> sessions().forEach { it.resume("screen-on") }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        app = application as CustomHudApplication
        cameraPrefs = CameraPrefs(this)
        val backend = AndroidRearCamera(this, app.log)
        val controller = RearCameraController(backend, MainThreadScheduler(), log = { message, level, code, generation ->
            app.log.add(AndroidRearCamera.CATEGORY, message, level, code, generation)
        }, configText = { AndroidRearCamera.configText(cameraPrefs.fps) })
        backend.controller = controller
        controller.setMode(cameraPrefs.mode)
        controller.setTestPattern(cameraPrefs.testPattern)
        cameraBinding = HudWindowRoot.CameraBinding(backend, controller, app.log, cameraPrefs)
        activeCamera = controller
        platform = AndroidDisplayPlatform(this)
        cameraPlatform = AndroidDisplayPlatform(this, AndroidDisplayPlatform.Role.CAMERA_ONLY, cameraBinding)
        val cameraSessionInstance = DisplaySession(
            platform = cameraPlatform,
            scheduler = MainThreadScheduler(),
            mainThread = { Looper.myLooper() == Looper.getMainLooper() },
            event = { category, message, code, generation ->
                cameraPlatform.updateSessionGeneration(generation)
                app.log.add("CLUSTER_$category", message, errorCode = code, generation = generation)
            },
        )
        cameraSession = cameraSessionInstance
        stopCameraSessionObserver = cameraSessionInstance.observe { snapshot ->
            cameraPlatform.updateSessionGeneration(snapshot.generation)
            onCameraHostSnapshot(cameraSessionInstance, snapshot)
            if (receiverRegistered && isIdle(snapshot)) scheduleStopIfIdle()
        }
        val session = DisplaySession(
            platform = platform,
            scheduler = MainThreadScheduler(),
            mainThread = { Looper.myLooper() == Looper.getMainLooper() },
            event = { category, message, code, generation ->
                platform.updateSessionGeneration(generation)
                app.log.add(category, message, errorCode = code, generation = generation)
            },
        )
        activeSession = session
        stopSessionObserver = session.observe(::onSession)
        stopLayoutObserver = app.layoutStore.observe { layout ->
            if (session.snapshot.desiredRunning) session.refresh(layout.revision, "layout-store")
            if (cameraSessionInstance.snapshot.desiredRunning) cameraSessionInstance.refresh(layout.revision, "layout-store")
            controller.setWidgetEnabled(cameraWidgetEnabled(layout))
            reconcileCameraSession()
        }
        stopCameraPrefsObserver = cameraPrefs.observe {
            mainHandler.post {
                controller.setMode(cameraPrefs.mode)
                controller.setTestPattern(cameraPrefs.testPattern)
                restartCameraWindowIfLayerChanged()
                reconcileCameraSession()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenReceiver, filter)
        receiverRegistered = true
        app.log.add("SERVICE", "created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification(activeSession?.snapshot))
        app.log.add("SERVICE", "foregroundEntered action=${intent?.action}")
        when (intent?.action) {
            ACTION_START -> {
                val target = DisplayTarget(intent.getIntExtra(EXTRA_DISPLAY_ID, -1), intent.getStringExtra(EXTRA_DISPLAY_NAME) ?: "")
                backgroundAllowed = intent.getBooleanExtra(EXTRA_BACKGROUND_ALLOWED, true)
                val keepScreenOn = intent.getBooleanExtra(EXTRA_KEEP_SCREEN_ON, true)
                windowSpec = WindowSpec(flags = if (keepScreenOn) 664 else 536)
                prepareCamera(target)
                val config = SessionConfig(
                    target = target,
                    windowSpec = windowSpec,
                    configRevision = app.layoutStore.current.revision,
                    layoutRevision = app.layoutStore.current.revision,
                    backgroundAllowed = backgroundAllowed,
                )
                val session = activeSession
                val result = session?.start(StartRequest(config, UUID.randomUUID().toString()))
                if (session != null && result?.error?.code == "CONFIG_CONFLICT") {
                    // The user explicitly started with a different target/option: restart rather than refuse.
                    // stop() keeps the new intent pending until the old window is fully cleaned up.
                    app.log.add("SERVICE", "start conflicts with active session (${session.snapshot.state}); restarting with new config")
                    session.stop(StopRequest("restart-new-config", UUID.randomUUID().toString()))
                    session.start(StartRequest(config, UUID.randomUUID().toString()))
                }
                reconcileCameraSession()
            }
            ACTION_STOP -> sessions().forEach { it.stop(StopRequest("user-visible-stop", UUID.randomUUID().toString())) }
            ACTION_UI_VISIBLE -> {
                uiVisible = intent.getBooleanExtra(EXTRA_UI_VISIBLE, true)
                if (!uiVisible && !backgroundAllowed) sessions().forEach { it.suspend("ui-hidden") }
                if (uiVisible) {
                    sessions().forEach {
                        it.resume("ui-visible")
                        it.onDisplayEvent()
                    }
                }
            }
        }
        scheduleStopIfIdle()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        destroying = true
        mainHandler.removeCallbacks(stopIfIdleTask)
        sessions().forEach { it.stop(StopRequest("service-destroy", UUID.randomUUID().toString())) }
        // Windows are removed from the camera close callback; release() waits for that close as well.
        cameraBinding.controller.release()
        gearSource?.close()
        gearSource = null
        stopLayoutObserver?.invoke()
        stopSessionObserver?.invoke()
        stopCameraSessionObserver?.invoke()
        stopCameraPrefsObserver?.invoke()
        cameraSession = null
        activeCamera = null
        if (receiverRegistered) unregisterReceiver(screenReceiver)
        activeSession = null
        // A notify() after stopForeground() re-posts a plain notification that outlives the service.
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        app.log.add("SERVICE", "destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun onSession(snapshot: SessionSnapshot) {
        platform.updateSessionGeneration(snapshot.generation)
        activeSession?.let { onCameraHostSnapshot(it, snapshot) }
        if (snapshot.state == SessionState.STOPPED || (!snapshot.desiredRunning && snapshot.state == SessionState.FAILED)) {
            // The HUD stopped: the cluster camera window follows it.
            if (cameraSession?.snapshot?.desiredRunning == true) mainHandler.post { reconcileCameraSession() }
        }
        // Only post when the visible text changes: the session publishes many snapshots per
        // operation and Android drops bursts of notification updates, leaving stale text.
        val key = "${snapshot.state}|${snapshot.target?.displayId}|${snapshot.desiredRunning}"
        if (receiverRegistered && !destroying && key != lastNotificationKey) {
            lastNotificationKey = key
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(snapshot))
        }
        // start() publishes intermediate snapshots that are still STOPPED/FAILED (e.g. the
        // generation bump before PREPARING). Stopping synchronously here destroyed the service
        // right after addView, so the HUD only flashed. Re-check once the current call returns.
        if (receiverRegistered && isIdle(snapshot)) scheduleStopIfIdle()
    }

    private fun sessions(): List<DisplaySession> = listOfNotNull(activeSession, cameraSession)

    private fun cameraWidgetEnabled(layout: LayoutSnapshot): Boolean =
        layout.document.widgets.any { it.type == WidgetType.REAR_CAMERA && it.enabled }

    /**
     * Decides where the camera lives for this run: inside the HUD window when the camera display is the HUD
     * target, otherwise in a second transparent window on the camera display (default display 1 = cluster),
     * started automatically together with the HUD.
     */
    private fun prepareCamera(mainTarget: DisplayTarget) {
        val saved = cameraPrefs.target
        val display = DeviceCapabilities(this).displays().firstOrNull { it.id == saved.id && it.valid }
        if (display != null && (display.name != saved.name || display.width != saved.width || display.height != saved.height)) {
            cameraPrefs.target = CameraPrefs.Target(display.id, display.name, display.width, display.height)
        }
        val name = display?.name ?: saved.name.ifEmpty { DEFAULT_CAMERA_DISPLAY_NAME }
        val target = DisplayTarget(saved.id, name)
        val embedded = target.displayId == mainTarget.displayId
        platform.camera = if (embedded) cameraBinding else null
        val previousTarget = cameraTarget
        cameraTarget = if (embedded) null else target
        cameraHost = if (embedded) activeSession else cameraSession
        if (previousTarget != null && previousTarget != cameraTarget) {
            cameraSession?.stop(StopRequest("camera-target-changed", UUID.randomUUID().toString()))
        }
        app.log.add(
            AndroidRearCamera.CATEGORY,
            "camera display=${target.displayId} name=$name present=${display != null} " +
                (if (embedded) "embedded in HUD window" else "own window") + " mode=${cameraPrefs.mode}",
        )
        startGearSource()
        cameraBinding.controller.setWidgetEnabled(cameraWidgetEnabled(app.layoutStore.current))
    }

    /** Starts / stops the cluster camera window so it runs exactly while it can show something. */
    private fun reconcileCameraSession() {
        val session = cameraSession ?: return
        val target = cameraTarget
        val mainRunning = activeSession?.snapshot?.desiredRunning == true
        val desired = mainRunning && target != null && cameraWidgetEnabled(app.layoutStore.current) &&
            cameraPrefs.mode != RearCameraMode.OFF
        val running = session.snapshot.desiredRunning
        if (desired && !running && target != null) {
            val layout = app.layoutStore.current
            val layer = cameraPrefs.windowLayer
            cameraWindowLayer = layer
            val config = SessionConfig(
                target = target,
                windowSpec = windowSpec.copy(type = layer.type),
                configRevision = layout.revision,
                layoutRevision = layout.revision,
                backgroundAllowed = backgroundAllowed,
            )
            app.log.add("CLUSTER_SESSION", "start camera window display=${target.displayId} type=${layer.type} ${layer.name} layer≈${layer.androidLayer}")
            session.start(StartRequest(config, UUID.randomUUID().toString()))
        } else if (!desired && running) {
            app.log.add("CLUSTER_SESSION", "stop camera window (hud=$mainRunning widget=${cameraWidgetEnabled(app.layoutStore.current)} mode=${cameraPrefs.mode})")
            session.stop(StopRequest("camera-not-needed", UUID.randomUUID().toString()))
        }
    }

    /** The window type cannot change in place: stop the camera window; reconcile starts it with the new type. */
    private fun restartCameraWindowIfLayerChanged() {
        val session = cameraSession ?: return
        val used = cameraWindowLayer ?: return
        if (used == cameraPrefs.windowLayer || !session.snapshot.desiredRunning) return
        app.log.add("CLUSTER_SESSION", "window layer ${used.name} -> ${cameraPrefs.windowLayer.name}; rebuilding camera window")
        cameraWindowLayer = null
        session.stop(StopRequest("camera-layer-changed", UUID.randomUUID().toString()))
    }

    /** DisplaySession → camera: open only while the hosting session is ATTACHED. */
    private fun onCameraHostSnapshot(session: DisplaySession, snapshot: SessionSnapshot) {
        if (session !== cameraHost) return
        cameraBinding.backend.generation = snapshot.generation
        cameraBinding.controller.setHost(snapshot.state == SessionState.ATTACHED && snapshot.desiredRunning, snapshot.generation)
    }

    private fun startGearSource() {
        if (gearSource != null) return
        val controller = cameraBinding.controller
        fun reading(raw: Int?, what: String): GearReading {
            val gear = EcarxGear.gearOf(raw)
            return when {
                gear != null -> GearReading(gear, RearCameraPolicy.ECARX, what)
                raw == null -> GearReading.unavailable(RearCameraPolicy.ECARX, "$what：无值/不支持")
                else -> GearReading.unavailable(RearCameraPolicy.ECARX, String.format(Locale.ROOT, "%s：未知值 0x%x", what, raw))
            }
        }
        val source = EcarxGearSource(app.ecarx, object : EcarxGearSource.Log {
            override fun info(category: String, message: String) = app.log.add("GEAR", message)
            override fun warn(category: String, message: String) = app.log.add("GEAR", message, "WARN", "ECARX_GEAR")
        }, object : EcarxGearSource.Listener {
            override fun onBaseline(rawGear: Int?) {
                app.log.add("GEAR", "ECARX baseline raw=${rawGear?.let { String.format(Locale.ROOT, "0x%x", it) }}")
                controller.onEcarxGear(reading(rawGear, "基准"))
            }

            override fun onGear(rawGear: Int?) {
                app.log.add("GEAR", "ECARX event raw=${rawGear?.let { String.format(Locale.ROOT, "0x%x", it) }}")
                controller.onEcarxGear(reading(rawGear, "事件"))
            }

            override fun onDisconnected() {
                controller.onEcarxGear(GearReading.unavailable(RearCameraPolicy.ECARX, "已断开，重连中"))
            }
        })
        gearSource = source
        try {
            source.start()
        } catch (error: Exception) {
            app.log.add("GEAR", "ECARX gear start failed type=${error::class.java.name}", "WARN", "ECARX_GEAR")
        }
    }

    private fun isIdle(snapshot: SessionSnapshot): Boolean =
        snapshot.state == SessionState.STOPPED ||
            (snapshot.state == SessionState.FAILED && !snapshot.desiredRunning && snapshot.resourceCount == 0)

    private fun scheduleStopIfIdle() {
        mainHandler.removeCallbacks(stopIfIdleTask)
        mainHandler.post(stopIfIdleTask)
    }

    private fun stopIfIdle() {
        val snapshot = activeSession?.snapshot ?: return
        if (!isIdle(snapshot)) return
        cameraSession?.snapshot?.let { if (!isIdle(it)) return }
        app.log.add("SERVICE", "idle state=${snapshot.state}; stopping service")
        // Only stop if no newer start command has been delivered since.
        if (stopSelfResult(lastStartId)) stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun notification(snapshot: SessionSnapshot?): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, HudService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val state = snapshot?.state?.name ?: "PREPARING"
        val target = snapshot?.target?.let { "display ${it.displayId}" } ?: "selected display"
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_hud)
            .setContentTitle("CustomHUD · $state")
            .setContentText("$target · software state only")
            .setContentIntent(openIntent)
            .setOngoing(snapshot?.desiredRunning == true)
            .addAction(Notification.Action.Builder(null, "停止", stopIntent).build())
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "HUD 会话", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "hud-session"
        private const val NOTIFICATION_ID = 41
        private const val ACTION_START = "dev.customhud.action.START"
        private const val ACTION_STOP = "dev.customhud.action.STOP"
        private const val ACTION_UI_VISIBLE = "dev.customhud.action.UI_VISIBLE"
        private const val EXTRA_DISPLAY_ID = "displayId"
        private const val EXTRA_DISPLAY_NAME = "displayName"
        private const val EXTRA_KEEP_SCREEN_ON = "keepScreenOn"
        private const val EXTRA_BACKGROUND_ALLOWED = "backgroundAllowed"
        private const val EXTRA_UI_VISIBLE = "uiVisible"
        /** Name both HDMI displays reported in the field (feedback/device-feedback.md). */
        private const val DEFAULT_CAMERA_DISPLAY_NAME = "HDMI 屏幕"

        @Volatile
        var activeSession: DisplaySession? = null
            private set

        /** Session of the separate camera window (camera display ≠ HUD display). */
        @Volatile
        var cameraSession: DisplaySession? = null
            private set

        @Volatile
        var activeCamera: RearCameraController? = null
            private set

        fun start(context: Context, target: DisplayTarget, keepScreenOn: Boolean, backgroundAllowed: Boolean) {
            val intent = Intent(context, HudService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_DISPLAY_ID, target.displayId)
                .putExtra(EXTRA_DISPLAY_NAME, target.name)
                .putExtra(EXTRA_KEEP_SCREEN_ON, keepScreenOn)
                .putExtra(EXTRA_BACKGROUND_ALLOWED, backgroundAllowed)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, HudService::class.java).setAction(ACTION_STOP))
        }

        fun uiVisibility(context: Context, visible: Boolean) {
            if (activeSession?.snapshot?.desiredRunning != true) return
            context.startService(Intent(context, HudService::class.java).setAction(ACTION_UI_VISIBLE).putExtra(EXTRA_UI_VISIBLE, visible))
        }
    }
}
