package dev.customhud.camera

import dev.customhud.data.Gear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RearCameraControllerTest {
    @Test
    fun opensOnlyWithAttachedHostSurfaceAndEnabledWidget() {
        val f = CameraFixture()
        f.controller.setHost(true, 1)
        assertEquals(0, f.backend.opens)
        assertEquals(CameraDisplay.PLACEHOLDER, f.controller.status.display) // waiting for the TextureView
        f.controller.setSurfaceReady(true)
        assertEquals(1, f.backend.opens)
        assertEquals(CameraPhase.OPENING, f.controller.status.phase)
        f.controller.onFirstFrame(420)
        assertEquals(CameraDisplay.STREAMING, f.controller.status.display)
        assertTrue(f.logs.any { it.contains("first frame 420 ms") && it.startsWith("gen=1") })
    }

    @Test
    fun disabledWidgetNeverOpensAndDisablingCloses() {
        val f = CameraFixture()
        f.controller.setWidgetEnabled(false)
        f.ready()
        assertEquals(0, f.backend.opens)
        assertEquals(CameraDisplay.HIDDEN, f.controller.status.display)
        f.controller.setWidgetEnabled(true)
        assertEquals(1, f.backend.opens)
        f.controller.setWidgetEnabled(false)
        assertEquals(listOf("open", "close"), f.backend.calls)
    }

    @Test
    fun notParkModeSubscribesOnlyOutsidePark() {
        val f = CameraFixture(RearCameraMode.NOT_PARK)
        f.ready()
        // No gear yet: placeholder with reason, camera closed, nothing invented.
        assertEquals(0, f.backend.opens)
        assertEquals(CameraDisplay.PLACEHOLDER, f.controller.status.display)
        assertTrue(f.controller.status.reason.contains("挡位不可用"))
        assertTrue(f.logs.any { it.contains("GEAR_UNAVAILABLE") })
        f.ecarx(Gear.PARK)
        assertEquals(0, f.backend.opens)
        assertEquals(CameraDisplay.HIDDEN, f.controller.status.display)
        f.ecarx(Gear.DRIVE)
        assertEquals(1, f.backend.opens)
        f.ecarx(Gear.REVERSE) // still not P: stays open, no reopen
        assertEquals(1, f.backend.opens)
        f.ecarx(Gear.PARK)
        assertEquals(listOf("open", "close"), f.backend.calls)
        f.backend.completeClose()
        assertEquals(CameraPhase.CLOSED, f.controller.status.phase)
        // Losing the gear source while driving closes too (cannot confirm "not P").
        f.ecarx(Gear.DRIVE)
        f.ecarx(null)
        assertEquals(listOf("open", "close", "open", "close"), f.backend.calls)
    }

    @Test
    fun reverseOnlyWithoutGearDataShowsUnavailable() {
        val f = CameraFixture(RearCameraMode.REVERSE_ONLY)
        f.ready()
        assertEquals(0, f.backend.opens)
        assertEquals(CameraDisplay.PLACEHOLDER, f.controller.status.display)
        assertTrue(f.controller.status.reason.contains("仅 R 挡"))
        f.ecarx(Gear.REVERSE)
        assertEquals(1, f.backend.opens)
    }

    @Test
    fun polledFallbackExpiresWithoutNewData() {
        val f = CameraFixture(RearCameraMode.NOT_PARK)
        f.ready()
        f.controller.onPolledGear(GearReading(Gear.DRIVE, RearCameraPolicy.POLLED, "ECARX 0x200200"))
        assertEquals(1, f.backend.opens)
        f.scheduler.advanceBy(GearTracker.POLLED_STALE_MS + 200)
        assertEquals(listOf("open", "close"), f.backend.calls)
        assertTrue(f.controller.status.reason.contains("挡位不可用"))
    }

    @Test
    fun removalWaitsForCloseCallbackAndNothingReopensMeanwhile() {
        val f = CameraFixture()
        f.ready()
        f.controller.onFirstFrame(300)
        val order = mutableListOf<String>()
        f.controller.closeForRemoval { order += "removeView" }
        assertEquals(listOf("open", "close"), f.backend.calls)
        assertTrue(order.isEmpty()) // removeView only after the SDK let go of the surface
        f.controller.setHost(true, 2) // a new generation attaching while the close is pending
        assertEquals(1, f.backend.opens)
        f.backend.completeClose()
        assertEquals(listOf("removeView"), order)
        assertEquals(2, f.backend.opens) // reopen for the new generation only after the close
    }

    @Test
    fun removalWithNothingOpenRunsImmediately() {
        val f = CameraFixture()
        var removed = false
        f.controller.closeForRemoval { removed = true }
        assertTrue(removed)
        assertTrue(f.backend.calls.isEmpty())
    }

    @Test
    fun suspendClosesAndResumeReopens() {
        val f = CameraFixture()
        f.backend.autoCompleteClose = true
        f.ready(generation = 3)
        f.controller.onFirstFrame(100)
        f.controller.closeForRemoval { } // suspend → window removed
        f.controller.setHost(false, 4)
        f.controller.setSurfaceReady(false) // TextureView destroyed with the window
        assertEquals(listOf("open", "close"), f.backend.calls)
        f.controller.setHost(true, 5) // resume: new window
        assertEquals(1, f.backend.opens)
        f.controller.setSurfaceReady(true)
        assertEquals(listOf("open", "close", "open"), f.backend.calls)
        assertEquals(5, f.controller.status.generation)
    }

    @Test
    fun surfaceDestroyedReleasesTextureOnlyAfterClose() {
        val f = CameraFixture()
        f.ready()
        f.controller.onFirstFrame(100)
        var textureReleased = false
        f.controller.setSurfaceReady(false)
        f.controller.whenClosed { textureReleased = true }
        assertFalse(textureReleased)
        f.backend.completeClose()
        assertTrue(textureReleased)
    }

    @Test
    fun errorsBackOff2s5s10sThen30s() {
        val f = CameraFixture()
        f.backend.autoCompleteClose = true
        f.ready()
        val expected = listOf(2_000L, 5_000L, 10_000L, 30_000L, 30_000L)
        expected.forEachIndexed { index, delay ->
            val opensBefore = f.backend.opens
            f.controller.onCameraError(1, "CAMERA_IN_USE")
            assertEquals(CameraPhase.RETRY_WAIT, f.controller.status.phase)
            assertEquals(CameraDisplay.PLACEHOLDER, f.controller.status.display)
            assertTrue(f.controller.status.reason.contains("CAMERA_IN_USE"))
            f.scheduler.advanceBy(delay - 1)
            assertEquals("retry ${index + 1} too early", opensBefore, f.backend.opens)
            f.scheduler.advanceBy(1)
            assertEquals("retry ${index + 1} at $delay ms", opensBefore + 1, f.backend.opens)
        }
        assertTrue(f.logs.any { it.contains("CAMERA_ERROR_1") })
    }

    @Test
    fun disconnectDuringCloseDoesNotScheduleSecondRetry() {
        val f = CameraFixture()
        f.ready()
        f.controller.onBackendFailure("相机服务断开", "CAMERA_DISCONNECTED")
        f.controller.onCameraError(5, "CAMERA_SERVICE") // late duplicate while closing
        f.backend.completeClose()
        assertEquals(1, f.controller.status.retryAttempt)
        f.scheduler.advanceBy(2_000)
        assertEquals(2, f.backend.opens)
    }

    @Test
    fun stableStreamingResetsBackoff() {
        val f = CameraFixture()
        f.backend.autoCompleteClose = true
        f.ready()
        f.controller.onCameraError(6, "CAMERA_STREAM")
        f.scheduler.advanceBy(2_000)
        f.controller.onCameraError(6, "CAMERA_STREAM")
        f.scheduler.advanceBy(5_000)
        assertEquals(2, f.controller.status.retryAttempt)
        f.controller.onFirstFrame(500)
        f.scheduler.advanceBy(RearCameraController.STABLE_MS)
        assertEquals(0, f.controller.status.retryAttempt)
        f.controller.onCameraError(6, "CAMERA_STREAM")
        val opens = f.backend.opens
        f.scheduler.advanceBy(2_000)
        assertEquals(opens + 1, f.backend.opens)
    }

    @Test
    fun leavingTheWantedStateCancelsPendingRetry() {
        val f = CameraFixture(RearCameraMode.NOT_PARK)
        f.backend.autoCompleteClose = true
        f.ready()
        f.ecarx(Gear.DRIVE)
        f.controller.onCameraError(1, "CAMERA_IN_USE")
        f.ecarx(Gear.PARK)
        f.scheduler.advanceBy(60_000)
        assertEquals(1, f.backend.opens)
        f.ecarx(Gear.DRIVE) // a fresh attempt, not the 2nd backoff step
        assertEquals(2, f.backend.opens)
        assertEquals(0, f.controller.status.retryAttempt)
    }

    @Test
    fun noFirstFrameWithin15sIsAFailure() {
        val f = CameraFixture()
        f.backend.autoCompleteClose = true
        f.ready()
        f.scheduler.advanceBy(RearCameraController.FIRST_FRAME_TIMEOUT_MS)
        assertEquals(CameraPhase.RETRY_WAIT, f.controller.status.phase)
        assertTrue(f.logs.any { it.contains("FIRST_FRAME_TIMEOUT") })
    }

    @Test
    fun fpsIsLoggedEveryTenSeconds() {
        val f = CameraFixture()
        f.ready()
        f.controller.onFirstFrame(200)
        for (i in 1..40) {
            f.scheduler.advanceBy(500)
            f.controller.onStats(i * 15L, 30f)
        }
        val fpsLines = f.logs.filter { it.contains("render fps=") }
        assertEquals(2, fpsLines.size)
        assertTrue(fpsLines.first().contains("fps=30.0"))
    }

    @Test
    fun releaseWaitsForCloseThenReleasesOnce() {
        val f = CameraFixture()
        f.ready()
        f.controller.onFirstFrame(200)
        f.controller.release()
        assertEquals(listOf("open", "close"), f.backend.calls)
        f.backend.completeClose()
        assertEquals(listOf("open", "close", "release"), f.backend.calls)
        f.scheduler.advanceBy(RearCameraController.RELEASE_FALLBACK_MS)
        assertEquals(1, f.backend.calls.count { it == "release" })
        f.controller.setHost(true, 9)
        assertEquals(1, f.backend.opens)
    }

    @Test
    fun releaseFallsBackWhenCloseCallbackNeverArrives() {
        val f = CameraFixture()
        f.ready()
        f.controller.release()
        f.scheduler.advanceBy(RearCameraController.RELEASE_FALLBACK_MS)
        assertEquals(listOf("open", "close", "release"), f.backend.calls)
        f.backend.completeClose() // late callback is harmless
        assertEquals(1, f.backend.calls.count { it == "release" })
    }

    @Test
    fun testPatternClosesCameraAndShowsStaticBox() {
        val f = CameraFixture()
        f.backend.autoCompleteClose = true
        f.ready()
        f.controller.onFirstFrame(100)
        f.controller.setTestPattern(true)
        assertEquals(listOf("open", "close"), f.backend.calls)
        assertTrue(f.controller.status.testPattern)
        assertEquals(CameraDisplay.PLACEHOLDER, f.controller.status.display)
        f.controller.setTestPattern(false)
        assertEquals(listOf("open", "close", "open"), f.backend.calls)
        assertFalse(f.controller.status.testPattern)
    }
}
