package dev.customhud.camera

import dev.customhud.display.session.DataHandle
import dev.customhud.display.session.DisplayDescriptor
import dev.customhud.display.session.DisplayListenerHandle
import dev.customhud.display.session.DisplaySession
import dev.customhud.display.session.DisplayTarget
import dev.customhud.display.session.SessionConfig
import dev.customhud.display.session.SessionPlatform
import dev.customhud.display.session.SessionState
import dev.customhud.display.session.SessionWindow
import dev.customhud.display.session.StartRequest
import dev.customhud.display.session.StopRequest
import dev.customhud.display.session.WindowCallbacks
import dev.customhud.display.session.WindowSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Camera + DisplaySession together, wired the way HudService / AndroidDisplayPlatform do it: the host is
 * active only while the session is ATTACHED, and the window's remove() closes the camera before removeView.
 */
class CameraSessionLifecycleTest {
    @Test
    fun suspendClosesCameraBeforeRemoveViewAndResumeReopens() {
        val w = World()
        w.start()
        assertEquals(SessionState.ATTACHED, w.session.snapshot.state)
        assertEquals(listOf("open"), w.backend.calls)
        w.controller.onFirstFrame(250)

        w.session.suspend("screen-off")
        assertEquals(SessionState.SUSPENDED, w.session.snapshot.state)
        assertEquals(listOf("open", "close"), w.backend.calls)
        assertTrue("removeView must wait for the camera close", w.events.none { it == "removeView#1" })
        w.backend.completeClose()
        assertEquals(listOf("removeView#1", "detached#1", "textureReleased#1"), w.events.filter { !it.startsWith("added") })
        assertEquals(1, w.session.snapshot.resourceCount) // only the display listener is left

        w.session.resume("screen-on")
        assertEquals(SessionState.ATTACHED, w.session.snapshot.state)
        assertEquals(listOf("open", "close", "open"), w.backend.calls)
        assertEquals(w.session.snapshot.generation, w.controller.status.generation)
    }

    @Test
    fun stopFinishesOnlyAfterCameraClosed() {
        val w = World()
        w.start()
        w.session.stop(StopRequest("user", "stop"))
        assertEquals(SessionState.STOPPING, w.session.snapshot.state)
        w.backend.completeClose()
        assertEquals(SessionState.STOPPED, w.session.snapshot.state)
        assertEquals(0, w.session.snapshot.resourceCount)
        assertFalse(w.controller.status.hostActive)
    }

    @Test
    fun targetLossClosesAndReturnRebuilds() {
        val w = World()
        w.start()
        w.platform.targetAvailable = false
        w.platform.emitDisplay()
        w.scheduler.advanceBy(100)
        assertEquals(listOf("open", "close"), w.backend.calls)
        w.backend.completeClose()
        assertEquals(SessionState.WAITING_DISPLAY, w.session.snapshot.state)
        w.platform.targetAvailable = true
        w.platform.emitDisplay()
        w.scheduler.advanceBy(100)
        assertEquals(SessionState.ATTACHED, w.session.snapshot.state)
        assertEquals(listOf("open", "close", "open"), w.backend.calls)
    }

    @Test
    fun stuckCloseStillRemovesWindowButKeepsTextureUntilClose() {
        val w = World()
        w.start()
        w.session.stop(StopRequest("user", "stop"))
        w.scheduler.advanceBy(2_000) // cleanup timeout: second remove() → removeViewImmediate
        assertTrue(w.events.contains("removeViewImmediate#1"))
        assertEquals(SessionState.STOPPED, w.session.snapshot.state)
        assertFalse(w.events.contains("textureReleased#1"))
        w.backend.completeClose()
        assertTrue(w.events.contains("textureReleased#1"))
    }

    @Test
    fun cameraFailureDoesNotTouchTheSession() {
        val w = World()
        w.backend.autoCompleteClose = true
        w.start()
        w.controller.onCameraError(1, "CAMERA_IN_USE")
        assertEquals(SessionState.ATTACHED, w.session.snapshot.state)
        assertEquals(1, w.platform.windows.size)
        assertEquals(CameraPhase.RETRY_WAIT, w.controller.status.phase)
    }

    private class World {
        val scheduler = FakeCameraScheduler()
        val backend = FakeCameraBackend(scheduler)
        val events = mutableListOf<String>()
        val controller = RearCameraController(backend, scheduler)
        val platform = Platform(this)
        val session = DisplaySession(platform, scheduler)

        init {
            controller.setMode(RearCameraMode.ALWAYS)
            controller.setWidgetEnabled(true)
            session.observe { controller.setHost(it.state == SessionState.ATTACHED && it.desiredRunning, it.generation) }
        }

        fun start() {
            session.start(StartRequest(SessionConfig(DisplayTarget(1, "HDMI 屏幕"), WindowSpec(), layoutRevision = 1), "start"))
        }
    }

    private class Platform(private val world: World) : SessionPlatform {
        var targetAvailable = true
        val windows = mutableListOf<Window>()
        private var listener: () -> Unit = {}
        override fun displays(generation: Long) = if (!targetAvailable) emptyList() else listOf(
            DisplayDescriptor(1, "HDMI 屏幕", 0, 2560, 536, 213, 0, true, 2, 1000, generation),
        )
        override fun overlayGranted() = true
        override fun registerDisplayListener(listener: () -> Unit): DisplayListenerHandle {
            this.listener = listener
            return object : DisplayListenerHandle { override fun close() = Unit }
        }
        override fun createWindow(target: DisplayTarget, spec: WindowSpec, callbacks: WindowCallbacks): SessionWindow =
            Window(world, windows.size + 1, callbacks).also { windows += it }
        override fun startData(generation: Long): DataHandle = object : DataHandle { override fun close() = Unit }
        fun emitDisplay() = listener()
    }

    /** Mirrors AndroidWindow + HudWindowRoot: TextureView surface on attach, camera close before removeView. */
    private class Window(private val world: World, private val id: Int, private val callbacks: WindowCallbacks) : SessionWindow {
        private var attached = false
        private var closeRequested = false
        private var removed = false
        override val isAttached get() = attached

        override fun add() {
            attached = true
            world.events += "added#$id"
            callbacks.onAttach()
            world.controller.setSurfaceReady(true) // first draw creates the SurfaceTexture
        }

        override fun remove() {
            if (!closeRequested) {
                closeRequested = true
                world.controller.closeForRemoval { removeView(immediate = false) }
                return
            }
            removeView(immediate = true)
        }

        private fun removeView(immediate: Boolean) {
            if (removed) return
            removed = true
            world.events += if (immediate) "removeViewImmediate#$id" else "removeView#$id"
            // Detach → onSurfaceTextureDestroyed (returns false) → texture released after the close.
            attached = false
            world.events += "detached#$id"
            world.controller.setSurfaceReady(false)
            world.controller.whenClosed { world.events += "textureReleased#$id" }
            callbacks.onDetach()
        }

        override fun refresh() = Unit
    }
}
