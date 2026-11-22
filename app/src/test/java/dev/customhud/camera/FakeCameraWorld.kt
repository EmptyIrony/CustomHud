package dev.customhud.camera

import dev.customhud.display.session.Cancellable
import dev.customhud.display.session.SessionScheduler

/** Deterministic main-thread scheduler for camera tests. */
class FakeCameraScheduler(start: Long = 1_000L) : SessionScheduler {
    private data class Task(val at: Long, val order: Long, val block: () -> Unit, var cancelled: Boolean = false)
    private var now = start
    private var order = 0L
    private val tasks = mutableListOf<Task>()
    override fun now(): Long = now
    override fun postDelayed(delayMs: Long, task: () -> Unit): Cancellable {
        val entry = Task(now + delayMs, order++, task)
        tasks.add(entry)
        return object : Cancellable {
            override fun cancel() {
                entry.cancelled = true
            }
        }
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

/** Records backend calls; close completes only when the test says so (like the SDK work-queue fence). */
class FakeCameraBackend(private val scheduler: FakeCameraScheduler) : RearCameraBackend {
    val calls = mutableListOf<String>()
    val openTimes = mutableListOf<Long>()
    private val pendingCloses = mutableListOf<() -> Unit>()
    var autoCompleteClose = false

    override fun open() {
        calls += "open"
        openTimes += scheduler.now()
    }

    override fun close(onClosed: () -> Unit) {
        calls += "close"
        if (autoCompleteClose) onClosed() else pendingCloses += onClosed
    }

    override fun release() {
        calls += "release"
    }

    val pendingCloseCount: Int get() = pendingCloses.size
    val opens: Int get() = calls.count { it == "open" }

    fun completeClose() {
        val callbacks = pendingCloses.toList()
        pendingCloses.clear()
        callbacks.forEach { it() }
    }
}

class CameraFixture(mode: RearCameraMode = RearCameraMode.ALWAYS) {
    val scheduler = FakeCameraScheduler()
    val backend = FakeCameraBackend(scheduler)
    val logs = mutableListOf<String>()
    val controller = RearCameraController(backend, scheduler, log = { message, _, code, generation ->
        logs += "gen=$generation code=${code ?: "-"} $message"
    })

    init {
        controller.setMode(mode)
        controller.setWidgetEnabled(true)
    }

    fun ready(generation: Long = 1) {
        controller.setHost(true, generation)
        controller.setSurfaceReady(true)
    }

    fun ecarx(gear: dev.customhud.data.Gear?) = controller.onEcarxGear(
        if (gear == null) GearReading.unavailable(RearCameraPolicy.ECARX, "已断开") else GearReading(gear, RearCameraPolicy.ECARX, "事件"),
    )
}
