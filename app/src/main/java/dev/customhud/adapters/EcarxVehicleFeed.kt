package dev.customhud.adapters

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.customhud.data.CapabilityStatus
import dev.customhud.data.CloseStatus
import dev.customhud.data.ConnectionState
import dev.customhud.data.DataCloseResult
import dev.customhud.data.DataMode
import dev.customhud.data.DataValue
import dev.customhud.data.SignalCapability
import dev.customhud.data.Validity
import dev.customhud.data.VehicleDataSource
import dev.customhud.data.VehicleProfile
import dev.customhud.data.VehicleSignal
import dev.customhud.data.VehicleSnapshot
import dev.customhud.logging.HudLog
import java.util.Locale

/**
 * Process-wide ECARX vehicle data: one poller on the shared [EcarxCarHub] connection, fanned out
 * to every observer (HUD window, cluster camera window, probe page). Runs while at least one
 * observer is subscribed. Fast signals are read every [FAST_MS], the rest every [SLOW_EVERY] polls.
 * Replaces the android.car properties, which are static placeholders on this head unit.
 *
 * Main thread API; observers are called on the main thread.
 */
class EcarxVehicleFeed(private val hub: EcarxCarHub, private val log: HudLog) {
    private val main = Handler(Looper.getMainLooper())
    private val observers = mutableListOf<(VehicleSnapshot) -> Unit>()
    private val idleBuilder = EcarxSnapshotBuilder()
    private var handle: EcarxCarHub.Handle? = null
    private var poller: Poller? = null
    private var stalled = false

    @Volatile
    var snapshot = VehicleSnapshot(profile = PROFILE)
        private set
    @Volatile
    var connection = ConnectionState.UNAVAILABLE
        private set
    @Volatile
    var generation = 0L
        private set
    @Volatile
    var status = "未启动"
        private set
    /** Poll cost and binder call counts, for diagnostics. */
    @Volatile
    var pollStats = ""
        private set
    @Volatile
    private var lastTickAt = 0L

    private val watchdog = object : Runnable {
        override fun run() {
            checkStall()
            main.postDelayed(this, 1_000)
        }
    }

    /** Main thread. The observer gets the current snapshot at once; the returned function unsubscribes. */
    fun subscribe(observer: (VehicleSnapshot) -> Unit): () -> Unit {
        requireMain()
        observers += observer
        if (observers.size == 1) start() else observer(snapshot)
        var subscribed = true
        return {
            runOnMain {
                if (subscribed) {
                    subscribed = false
                    observers.remove(observer)
                    if (observers.isEmpty()) stop()
                }
            }
        }
    }

    private fun start() {
        generation += 1
        connection = ConnectionState.CONNECTING
        status = "正在连接 ECARX…"
        publish(VehicleSnapshot(idleBuilder.unavailableAll(SystemClock.elapsedRealtime(), generation, "CONNECTING", "正在连接 ECARX"),
            connection, generation, PROFILE))
        handle = hub.acquire(object : EcarxCarHub.Client {
            override fun onConnected(car: Any) {
                poller?.cancelled = true
                connection = ConnectionState.CONNECTED
                status = "已连接，轮询中"
                stalled = false
                lastTickAt = SystemClock.elapsedRealtime()
                poller = Poller(car, generation).also { it.start() }
                log.add("VEHICLE", "ECARX vehicle poll started generation=$generation")
            }

            override fun onDisconnected(reason: String) {
                poller?.cancelled = true
                poller = null
                connection = ConnectionState.DISCONNECTED
                status = "ECARX 断开：$reason；等待重连"
                publish(VehicleSnapshot(idleBuilder.unavailableAll(SystemClock.elapsedRealtime(), generation, "DISCONNECTED", "ECARX 已断开，重连中"),
                    connection, generation, PROFILE))
            }
        })
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, 1_000)
    }

    private fun stop() {
        main.removeCallbacks(watchdog)
        poller?.cancelled = true
        poller = null
        handle?.release()
        handle = null
        connection = ConnectionState.DISCONNECTED
        status = "已停止（没有使用者）"
        snapshot = VehicleSnapshot(connection = connection, generation = generation, profile = PROFILE)
        log.add("VEHICLE", "ECARX vehicle poll stopped generation=$generation")
    }

    /** A blocked binder call must not leave the HUD showing frozen values. */
    private fun checkStall() {
        val p = poller ?: return
        val age = SystemClock.elapsedRealtime() - lastTickAt
        if (age > STALL_MS && !stalled) {
            stalled = true
            status = "轮询 ${age / 1000} 秒没有返回"
            publish(VehicleSnapshot(idleBuilder.unavailableAll(SystemClock.elapsedRealtime(), generation, "POLL_STALLED", "ECARX 读取卡住"),
                connection, generation, PROFILE))
            log.add("VEHICLE", "poll stalled ${age} ms", "WARN", "POLL_STALLED")
        }
        if (age > STALL_RECONNECT_MS) {
            p.cancelled = true
            poller = null
            handle?.reportFailure(p.car, "轮询卡住 ${age / 1000} 秒")
        }
    }

    private fun publish(next: VehicleSnapshot) {
        snapshot = next
        observers.toList().forEach { it(next) }
    }

    fun capabilities(): Map<VehicleSignal, SignalCapability> {
        val now = SystemClock.elapsedRealtime()
        val current = snapshot
        return VehicleSignal.entries.associateWith { signal ->
            val value = current.value(signal)
            val status = when {
                value == null -> CapabilityStatus.NOT_READY
                value.validity == Validity.KNOWN -> CapabilityStatus.AVAILABLE
                value.error?.code == EcarxSnapshotBuilder.UNVERIFIED -> CapabilityStatus.UNKNOWN
                value.error?.code == "NO_SOURCE" -> CapabilityStatus.UNSUPPORTED
                else -> CapabilityStatus.NOT_READY
            }
            SignalCapability(status, value?.let { describe(it) } ?: this.status, now, PROFILE)
        }
    }

    /** Connection summary lines for the probe page / report. */
    fun statusLines(): List<String> = listOfNotNull(
        "ECARX 连接：${hub.state()}",
        hub.lastError()?.let { "上次连接错误：$it" },
        "车况轮询：$status",
        pollStats.takeIf { it.isNotEmpty() },
    )

    /** One line per signal: value + source, or the reason it is missing. */
    fun signalLines(rows: List<Pair<VehicleSignal, String>>, now: Long = SystemClock.elapsedRealtime()): List<String> =
        rows.map { (signal, name) -> line(snapshot.value(signal), name, now) }

    private fun line(value: DataValue<*>?, name: String, now: Long): String {
        val shown = value?.value
        val label = name.padEnd(7, '　')
        return when {
            value == null -> "… $label 等待数据"
            shown != null -> "✓ $label ${format(shown)}  ← ${value.source}" + if (!value.isFresh(now)) "（过期）" else ""
            value.error?.code == EcarxSnapshotBuilder.UNVERIFIED -> "? $label ${value.error.message}"
            else -> "✗ $label ${value.error?.code ?: value.validity.name}：${value.error?.message?.take(80) ?: ""}  ← ${value.source}"
        }
    }

    private fun describe(value: DataValue<*>): String = value.value?.let { "${format(it)} ← ${value.source}" }
        ?: "${value.error?.code}: ${value.error?.message} ← ${value.source}"

    private inner class Poller(val car: Any, private val pollGeneration: Long) : Thread("customhud-ecarx-poll") {
        @Volatile
        var cancelled = false

        init {
            isDaemon = true
        }

        override fun run() {
            try {
                loop()
            } catch (error: Throwable) {
                // Last resort: a poller bug must not take the process (HUD + camera) down.
                if (error is VirtualMachineError) throw error
                note("轮询线程异常退出 ${error.javaClass.simpleName}: ${error.message?.take(80)}")
                fail("轮询线程异常 ${error.javaClass.simpleName}")
            }
        }

        private fun loop() {
            val reader = EcarxReader(car)
            if (!reader.sensorReady()) {
                fail("取不到 ISensor：${reader.lastError}")
                return
            }
            val builder = EcarxSnapshotBuilder()
            var slow: Map<VehicleSignal, DataValue<*>> = emptyMap()
            var tick = 0L
            var badTicks = 0
            var costAvg = 0.0
            var next = SystemClock.uptimeMillis()
            var crashes = 0
            while (!cancelled) {
                val t0 = SystemClock.elapsedRealtime()
                reader.resetCounters()
                val fast = try {
                    val f = builder.fast(reader, t0, pollGeneration)
                    if (tick % SLOW_EVERY == 0L) slow = builder.slow(reader, t0, pollGeneration)
                    crashes = 0
                    f
                } catch (error: Exception) {
                    // A decoding bug is a failed tick, not a dead connection; keep the last values.
                    if (crashes++ == 0) note("轮询异常 ${error.javaClass.simpleName}: ${error.message?.take(80)}")
                    null
                }
                tick++
                if (fast == null) {
                    lastTickAt = SystemClock.elapsedRealtime() // alive, just failing: not a stall
                    if (crashes == 5) {
                        main.post {
                            if (!cancelled && poller === this) {
                                publish(VehicleSnapshot(idleBuilder.unavailableAll(SystemClock.elapsedRealtime(), pollGeneration, "POLL_ERROR", "车况解码异常，见日志"),
                                    ConnectionState.CONNECTED, pollGeneration, PROFILE))
                            }
                        }
                    }
                    next += FAST_MS
                    val pause = next - SystemClock.uptimeMillis()
                    if (pause > 0) SystemClock.sleep(pause) else next = SystemClock.uptimeMillis()
                    continue
                }
                val cost = SystemClock.elapsedRealtime() - t0
                costAvg += (cost - costAvg) * 0.1
                badTicks = if (reader.calls > 0 && reader.failures == reader.calls) badTicks + 1 else 0
                if (reader.deadObject) {
                    fail("DeadObjectException（${reader.lastError}）")
                    return
                }
                if (badTicks >= FAIL_TICKS) {
                    fail("连续 ${FAIL_TICKS * FAST_MS / 1000} 秒所有读取都失败（${reader.lastError}）")
                    return
                }
                lastTickAt = SystemClock.elapsedRealtime()
                pollStats = String.format(Locale.ROOT, "第 %d 次 · 本次 %d 个调用 / %d 个失败 · 平均 %.0f ms · 每 %d ms 一次%s%s",
                    tick, reader.calls, reader.failures, costAvg, FAST_MS,
                    if (reader.functionReady) "" else " · ICarFunction 不可用（转向灯/锁/驾驶模式等读不到）",
                    reader.lastError?.let { " · 最近错误 $it" } ?: "")
                val values = slow + fast
                main.post {
                    if (!cancelled && poller === this) {
                        stalled = false
                        if (status != "已连接，轮询中") status = "已连接，轮询中"
                        publish(VehicleSnapshot(values, ConnectionState.CONNECTED, pollGeneration, PROFILE))
                    }
                }
                next += FAST_MS
                val sleep = next - SystemClock.uptimeMillis()
                if (sleep > 0) SystemClock.sleep(sleep) else if (sleep < -1_000) next = SystemClock.uptimeMillis()
            }
        }

        private fun note(message: String) {
            main.post { log.add("VEHICLE", message, "WARN", "ECARX_POLL") }
        }

        private fun fail(reason: String) {
            main.post {
                if (!cancelled && poller === this) {
                    log.add("VEHICLE", "poll failed: $reason", "WARN", "ECARX_POLL")
                    poller = null
                    handle?.reportFailure(car, "车况轮询：$reason")
                }
            }
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    companion object {
        const val PROFILE = "ecarx-adaptapi"
        const val FAST_MS = 200L
        const val SLOW_EVERY = 10L
        const val FAIL_TICKS = 25
        const val STALL_MS = 3_000L
        const val STALL_RECONNECT_MS = 10_000L

        fun format(value: Any): String = when (value) {
            is Double -> String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
            is Boolean -> if (value) "是" else "否"
            else -> value.toString()
        }

        private fun requireMain() = check(Looper.myLooper() == Looper.getMainLooper()) { "EcarxVehicleFeed must be used on the main thread" }
    }
}

/** [VehicleDataSource] view of the shared [EcarxVehicleFeed] for one display session. */
class EcarxVehicleDataSource(private val feed: EcarxVehicleFeed) : VehicleDataSource {
    private var unsubscribe: (() -> Unit)? = null

    override val connection: ConnectionState get() = feed.connection
    override val generation: Long get() = feed.generation

    override fun open(profile: VehicleProfile, mode: DataMode, observer: (VehicleSnapshot) -> Unit): Long {
        check(mode == DataMode.DEVICE) { "ECARX adapter is for DEVICE mode" }
        unsubscribe?.invoke()
        unsubscribe = feed.subscribe(observer)
        return feed.generation
    }

    override fun capabilities(): Map<VehicleSignal, SignalCapability> = feed.capabilities()
    override fun snapshot(): VehicleSnapshot = feed.snapshot

    override fun close(): DataCloseResult {
        unsubscribe?.invoke()
        unsubscribe = null
        return DataCloseResult(CloseStatus.COMPLETE, 0)
    }
}
