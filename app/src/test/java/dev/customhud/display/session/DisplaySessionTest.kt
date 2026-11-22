package dev.customhud.display.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplaySessionTest {
    @Test
    fun earlyAttachCallbackWaitsForAddReturnAndDrawIsNotPhysicalConfirmation() {
        val fixture = Fixture()
        fixture.session.start(fixture.request())
        assertEquals(SessionState.ATTACHED, fixture.session.snapshot.state)
        assertEquals(1, fixture.platform.windows.size)
        assertEquals(1, fixture.platform.listenerRegistrations)
        assertEquals(1, fixture.platform.dataStarts)
        assertTrue(fixture.session.snapshot.addViewReturned)
        assertTrue(fixture.session.snapshot.viewAttached)
        fixture.platform.windows.single().draw(1)
        assertTrue(fixture.session.snapshot.firstDrawAtMonotonic != null)
        assertEquals(UserObservation.UNKNOWN, fixture.session.snapshot.userObservation)
    }

    @Test
    fun duplicateStartAndStopAreIdempotent() {
        val fixture = Fixture()
        val request = fixture.request("same")
        fixture.session.start(request)
        fixture.session.start(request)
        fixture.session.start(fixture.request("other"))
        assertEquals(1, fixture.platform.windows.size)
        assertEquals(1, fixture.platform.dataStarts)
        val stop = StopRequest("test", "stop")
        fixture.session.stop(stop)
        fixture.session.stop(stop)
        fixture.session.stop(StopRequest("test", "stop-2"))
        assertEquals(SessionState.STOPPED, fixture.session.snapshot.state)
        assertEquals(1, fixture.platform.windows.single().removeCalls)
        assertEquals(1, fixture.platform.listenerCloses)
        assertEquals(0, fixture.session.snapshot.resourceCount)
    }

    @Test
    fun addViewRejectionRollsBackWithoutAttachedState() {
        val fixture = Fixture(rejectAdd = true, retryDelays = emptyList())
        fixture.session.start(fixture.request())
        assertEquals(SessionState.FAILED, fixture.session.snapshot.state)
        assertFalse(fixture.session.snapshot.viewAttached)
        assertFalse(fixture.session.snapshot.addViewReturned)
        assertEquals("ATTACH_FAILED", fixture.session.snapshot.lastError?.code)
        assertEquals(1, fixture.platform.windows.size)
        assertEquals(1, fixture.platform.windows.single().removeCalls)
        assertEquals(1, fixture.session.snapshot.resourceCount)
        assertEquals(0, fixture.platform.dataStarts)
    }

    @Test
    fun attachTimeoutIsStructuredAndRolledBack() {
        val fixture = Fixture(callbackOnAdd = false, retryDelays = emptyList())
        fixture.session.start(fixture.request())
        assertEquals(SessionState.ATTACHING, fixture.session.snapshot.state)
        fixture.scheduler.advanceBy(2000)
        assertEquals(SessionState.FAILED, fixture.session.snapshot.state)
        assertEquals("ATTACH_TIMEOUT", fixture.session.snapshot.lastError?.code)
        assertEquals(0, fixture.platform.dataStarts)
        assertEquals(1, fixture.platform.listenerRegistrations)
    }

    @Test
    fun waitingDisplayStopCancelsFutureAppearance() {
        val fixture = Fixture(targetAvailable = false)
        fixture.session.start(fixture.request())
        assertEquals(SessionState.WAITING_DISPLAY, fixture.session.snapshot.state)
        fixture.session.stop(StopRequest("cancel", "stop"))
        fixture.platform.targetAvailable = true
        fixture.platform.emitDisplay()
        fixture.scheduler.advanceBy(1000)
        assertEquals(SessionState.STOPPED, fixture.session.snapshot.state)
        assertTrue(fixture.platform.windows.isEmpty())
        assertEquals(1, fixture.platform.listenerCloses)
    }

    @Test
    fun targetRemovalAndRecoveryUseNewGenerationAndOnlyConfirmedTarget() {
        val fixture = Fixture()
        fixture.session.start(fixture.request())
        val first = fixture.platform.windows.single()
        val oldGeneration = fixture.session.snapshot.generation
        fixture.platform.targetAvailable = false
        fixture.platform.emitDisplay()
        fixture.scheduler.advanceBy(100)
        assertEquals(SessionState.WAITING_DISPLAY, fixture.session.snapshot.state)
        assertEquals(1, first.removeCalls)
        fixture.platform.targetAvailable = true
        fixture.platform.name = "different display"
        fixture.platform.emitDisplay()
        fixture.scheduler.advanceBy(100)
        assertEquals(1, fixture.platform.windows.size)
        assertEquals(SessionState.WAITING_DISPLAY, fixture.session.snapshot.state)
        fixture.platform.name = "HUD"
        fixture.platform.emitDisplay()
        fixture.scheduler.advanceBy(100)
        assertEquals(SessionState.ATTACHED, fixture.session.snapshot.state)
        assertEquals(2, fixture.platform.windows.size)
        assertNotEquals(oldGeneration, fixture.session.snapshot.generation)
        first.draw(1)
        assertTrue(fixture.session.snapshot.staleCallbackCount > 0)
    }

    @Test
    fun removeFailureRetainsOwnerAndNextStopRetries() {
        val fixture = Fixture()
        fixture.session.start(fixture.request())
        val window = fixture.platform.windows.single()
        window.failRemove = true
        fixture.session.stop(StopRequest("test", "stop-1"))
        assertEquals(SessionState.FAILED, fixture.session.snapshot.state)
        assertEquals("CLEANUP_FAILED", fixture.session.snapshot.lastError?.code)
        assertEquals(1, fixture.session.snapshot.pendingCleanupCount)
        assertTrue(window.attached)
        fixture.session.stop(StopRequest("retry", "stop-2"))
        assertEquals(SessionState.STOPPED, fixture.session.snapshot.state)
        assertEquals(2, window.removeCalls)
        assertEquals(0, fixture.session.snapshot.resourceCount)
    }

    @Test
    fun suspendResumeKeepsOneListenerAndDoesNotStartWithoutIntent() {
        val fixture = Fixture()
        fixture.session.start(fixture.request())
        fixture.session.suspend("screen-off")
        assertEquals(SessionState.SUSPENDED, fixture.session.snapshot.state)
        fixture.session.resume("screen-on")
        assertEquals(SessionState.ATTACHED, fixture.session.snapshot.state)
        assertEquals(1, fixture.platform.listenerRegistrations)
        assertEquals(2, fixture.platform.windows.size)
        assertEquals(1, fixture.platform.windows[0].removeCalls)
        fixture.session.stop(StopRequest("test", "stop"))
        fixture.session.resume("late-screen-on")
        assertEquals(SessionState.STOPPED, fixture.session.snapshot.state)
    }

    @Test
    fun asyncDetachAfterScreenOffIsClearedSoScreenOnRebuilds() {
        val fixture = Fixture()
        fixture.session.start(fixture.request())
        val first = fixture.platform.windows.single().apply { asyncDetach = true }
        fixture.session.suspend("screen-off")
        assertEquals(SessionState.SUSPENDED, fixture.session.snapshot.state)
        first.finishDetach() // arrives after the generation was invalidated
        assertEquals(0, fixture.session.snapshot.pendingCleanupCount)
        fixture.session.resume("screen-on")
        assertEquals(SessionState.ATTACHED, fixture.session.snapshot.state)
        assertEquals(2, fixture.platform.windows.size)
        assertNull(fixture.session.snapshot.lastError)
        fixture.scheduler.advanceBy(5_000) // the old cleanup timeout must not judge the new window
        assertEquals(SessionState.ATTACHED, fixture.session.snapshot.state)
        assertNull(fixture.session.snapshot.lastError)
    }

    @Test
    fun resumeBeforeOldViewDetachesWaitsInsteadOfFailing() {
        val fixture = Fixture()
        fixture.session.start(fixture.request())
        val first = fixture.platform.windows.single().apply { asyncDetach = true }
        fixture.session.suspend("screen-off")
        fixture.session.resume("screen-on")
        assertEquals(SessionState.RECOVERING, fixture.session.snapshot.state)
        assertEquals(1, fixture.platform.windows.size) // never two windows at once
        first.finishDetach()
        assertEquals(SessionState.ATTACHED, fixture.session.snapshot.state)
        assertEquals(2, fixture.platform.windows.size)
    }

    @Test
    fun missingDetachCallbackIsResolvedByCleanupTimeout() {
        val fixture = Fixture()
        fixture.session.start(fixture.request())
        val first = fixture.platform.windows.single().apply { asyncDetach = true }
        fixture.session.suspend("screen-off")
        fixture.scheduler.advanceBy(2_000) // timeout re-requests removal, which completes immediately
        assertFalse(first.attached)
        assertEquals(2, first.removeCalls)
        fixture.session.resume("screen-on")
        assertEquals(SessionState.ATTACHED, fixture.session.snapshot.state)
    }

    @Test
    fun stopAndStartAfterAsyncDetachDoNotConflict() {
        val fixture = Fixture()
        fixture.session.start(fixture.request())
        fixture.platform.windows.single().apply { asyncDetach = true }
        fixture.session.suspend("screen-off")
        fixture.platform.windows.single().finishDetach()
        fixture.session.stop(StopRequest("user", "stop"))
        assertEquals(SessionState.STOPPED, fixture.session.snapshot.state)
        val result = fixture.session.start(fixture.request("start-2"))
        assertTrue(result.accepted)
        assertEquals(SessionState.ATTACHED, fixture.session.snapshot.state)
    }

    @Test
    fun startAfterFailedCleanupRetriesCleanupInsteadOfConflict() {
        val fixture = Fixture()
        fixture.session.start(fixture.request())
        val window = fixture.platform.windows.single()
        window.failRemove = true
        fixture.session.stop(StopRequest("test", "stop-1"))
        assertEquals(SessionState.FAILED, fixture.session.snapshot.state)
        val result = fixture.session.start(fixture.request("start-2"))
        assertTrue(result.accepted)
        assertEquals(SessionState.ATTACHED, fixture.session.snapshot.state)
        assertEquals(2, window.removeCalls)
        assertEquals(2, fixture.platform.windows.size)
        assertFalse(window.attached)
    }

    @Test
    fun explicitStartWakesSuspendedSessionWithSameConfig() {
        val fixture = Fixture()
        fixture.session.start(fixture.request())
        fixture.session.suspend("screen-off")
        val result = fixture.session.start(fixture.request("start-2"))
        assertTrue(result.accepted)
        assertEquals(SessionState.ATTACHED, fixture.session.snapshot.state)
    }

    @Test
    fun differentConfigWhileRunningStillConflicts() {
        val fixture = Fixture()
        fixture.session.start(fixture.request())
        val other = StartRequest(fixture.config.copy(target = DisplayTarget(3, "Other")), "start-2")
        val result = fixture.session.start(other)
        assertFalse(result.accepted)
        assertEquals("CONFIG_CONFLICT", result.error?.code)
        assertEquals(1, fixture.platform.windows.size)
    }

    @Test
    fun staleRevisionIsRejectedWithoutChangingLayout() {
        val fixture = Fixture()
        fixture.session.start(fixture.request())
        val result = fixture.session.reconfigure(fixture.config.copy(configRevision = 1, layoutRevision = 9), "old")
        assertFalse(result.accepted)
        assertEquals("STALE_REVISION", result.error?.code)
        assertEquals(1L, fixture.session.snapshot.layoutRevision)
    }

    private class Fixture(
        rejectAdd: Boolean = false,
        callbackOnAdd: Boolean = true,
        targetAvailable: Boolean = true,
        retryDelays: List<Long> = listOf(250, 1000, 3000),
    ) {
        val scheduler = FakeScheduler()
        val platform = FakePlatform(rejectAdd, callbackOnAdd, targetAvailable)
        val session = DisplaySession(platform, scheduler)
        val config = SessionConfig(
            target = DisplayTarget(2, "HUD"),
            configRevision = 1,
            layoutRevision = 1,
            recoveryPolicy = RecoveryPolicy(retryDelaysMs = retryDelays),
        )

        fun request(id: String = "start") = StartRequest(config, id)
    }

    private class FakeScheduler : SessionScheduler {
        private data class Task(var at: Long, val order: Long, val block: () -> Unit, var cancelled: Boolean = false)
        private var now = 1000L
        private var order = 0L
        private val tasks = mutableListOf<Task>()
        override fun now(): Long = now
        override fun postDelayed(delayMs: Long, task: () -> Unit): Cancellable {
            val entry = Task(now + delayMs, order++, task)
            tasks.add(entry)
            return object : Cancellable { override fun cancel() { entry.cancelled = true } }
        }
        fun advanceBy(duration: Long) {
            val end = now + duration
            while (true) {
                val next = tasks.filter { !it.cancelled && it.at <= end }
                    .sortedWith(compareBy<Task>({ it.at }, { it.order }))
                    .firstOrNull() ?: break
                tasks.remove(next)
                now = next.at
                next.block()
            }
            now = end
        }
    }

    private class FakePlatform(
        private val rejectAdd: Boolean,
        private val callbackOnAdd: Boolean,
        var targetAvailable: Boolean,
    ) : SessionPlatform {
        var name = "HUD"
        var listenerRegistrations = 0
        var listenerCloses = 0
        var dataStarts = 0
        val windows = mutableListOf<FakeWindow>()
        private var listener: () -> Unit = {}
        override fun displays(generation: Long): List<DisplayDescriptor> = if (!targetAvailable) emptyList() else listOf(
            DisplayDescriptor(2, name, 0, 1024, 576, 213, 0, true, 2, 1000, generation),
        )
        override fun overlayGranted(): Boolean = true
        override fun registerDisplayListener(listener: () -> Unit): DisplayListenerHandle {
            listenerRegistrations++
            this.listener = listener
            return object : DisplayListenerHandle { override fun close() { listenerCloses++ } }
        }
        override fun createWindow(target: DisplayTarget, spec: WindowSpec, callbacks: WindowCallbacks): SessionWindow =
            FakeWindow(rejectAdd, callbackOnAdd, callbacks).also { windows.add(it) }
        override fun startData(generation: Long): DataHandle {
            dataStarts++
            return object : DataHandle { override fun close() = Unit }
        }
        fun emitDisplay() = listener()
    }

    private class FakeWindow(
        private val rejectAdd: Boolean,
        private val callbackOnAdd: Boolean,
        private val callbacks: WindowCallbacks,
    ) : SessionWindow {
        var attached = false
        var removeCalls = 0
        var failRemove = false
        /** Like WindowManager.removeView: the view detaches later, on a following main-loop message. */
        var asyncDetach = false
        var detachPending = false
        override val isAttached: Boolean get() = attached
        override fun add() {
            if (rejectAdd) throw SecurityException("denied")
            attached = true
            if (callbackOnAdd) callbacks.onAttach()
        }
        override fun remove() {
            removeCalls++
            if (failRemove) {
                failRemove = false
                throw IllegalArgumentException("not attached")
            }
            if (attached) {
                if (asyncDetach && !detachPending) {
                    detachPending = true
                } else {
                    finishDetach() // a repeated request behaves like removeViewImmediate
                }
            }
        }
        fun finishDetach() {
            if (!attached) return
            detachPending = false
            attached = false
            callbacks.onDetach()
        }
        override fun refresh() = Unit
        fun draw(revision: Long) = callbacks.onDraw(revision, 1)
    }
}
