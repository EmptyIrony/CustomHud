package dev.customhud.adapters

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import dev.customhud.data.CapabilityStatus
import dev.customhud.data.CloseStatus
import dev.customhud.data.ConnectionState
import dev.customhud.data.DataCloseResult
import dev.customhud.data.DataError
import dev.customhud.data.DataMode
import dev.customhud.data.DataValue
import dev.customhud.data.Gear
import dev.customhud.data.SignalCapability
import dev.customhud.data.TimestampBasis
import dev.customhud.data.Validity
import dev.customhud.data.VehicleDataSource
import dev.customhud.data.VehicleProfile
import dev.customhud.data.VehicleSignal
import dev.customhud.data.VehicleSnapshot
import dev.customhud.data.VehicleSnapshots
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DEVICE-mode vehicle data from standard android.car properties, polled every 500 ms on a
 * background thread. Each signal records which property it came from or why it is missing;
 * nothing is invented (a failed read never becomes 0 / P / false).
 */
class AndroidCarDataSource(private val context: Context) : VehicleDataSource {
    @Volatile
    override var connection: ConnectionState = ConnectionState.UNAVAILABLE
        private set
    @Volatile
    override var generation: Long = 0
        private set
    @Volatile
    private var current = VehicleSnapshot()
    @Volatile
    private var observer: (VehicleSnapshot) -> Unit = {}
    private val active = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "CustomHUD-android.car").apply { isDaemon = true }
    }
    private var readFuture: ScheduledFuture<*>? = null
    @Volatile
    private var access: CarPropertyAccess? = null
    private var profile = VehicleProfile("standard-android-car")
    private val capabilityMap = ConcurrentHashMap<VehicleSignal, SignalCapability>()

    /** Per standard property: config when readable, else the classified reason. Background-thread owned. */
    private val configs = mutableMapOf<Int, CarPropertyAccess.Config>()
    private val propertyReasons = ConcurrentHashMap<Int, String>()

    /** One-line human status for diagnostics screens. */
    @Volatile
    var statusLine: String = "未连接"
        private set

    override fun open(profile: VehicleProfile, mode: DataMode, observer: (VehicleSnapshot) -> Unit): Long {
        check(mode == DataMode.DEVICE) { "android.car adapter is for DEVICE mode" }
        if (active.get() && this.profile == profile) return generation
        if (active.get()) close()
        this.profile = profile
        this.observer = observer
        generation += 1
        active.set(true)
        connection = ConnectionState.CONNECTING
        statusLine = "正在连接 android.car…"
        current = VehicleSnapshot(connection = connection, generation = generation, profile = profile.name)
        publish(current)
        val openGeneration = generation
        executor.execute { connect(openGeneration) }
        return generation
    }

    private fun connect(openGeneration: Long) {
        if (!isCurrent(openGeneration)) return
        val started = SystemClock.elapsedRealtime()
        try {
            val carAccess = CarPropertyAccess.connect(context)
            if (!isCurrent(openGeneration)) {
                carAccess.disconnect()
                return
            }
            access = carAccess
            val all = carAccess.configs()
            classifyCandidates(carAccess, all)
            connection = ConnectionState.CONNECTED
            val readable = CarProps.CANDIDATES.keys.count { it in configs }
            statusLine = "已连接（${SystemClock.elapsedRealtime() - started} ms）· 系统列出 ${all.size} 个可访问属性 · 标准候选可读 $readable/${CarProps.CANDIDATES.size}"
            current = current.copy(connection = connection)
            publish(current)
            readFuture = executor.scheduleWithFixedDelay({ readAll(openGeneration) }, 0, 500, TimeUnit.MILLISECONDS)
        } catch (error: Throwable) {
            failConnection(openGeneration, error)
        }
    }

    /** getPropertyList only returns properties we may access; probe the rest once for a reason. */
    private fun classifyCandidates(carAccess: CarPropertyAccess, all: List<CarPropertyAccess.Config>) {
        val listed = all.associateBy { it.id }
        CarProps.CANDIDATES.keys.forEach { id ->
            val config = listed[id] ?: carAccess.config(id)
            when {
                config != null && config.readable -> {
                    configs[id] = config
                    propertyReasons[id] = "可读 areas=${config.areaIds.joinToString(",") { "0x" + it.toString(16) }}"
                }
                config != null -> propertyReasons[id] = "不可读 access=${config.access}"
                else -> {
                    val probe = carAccess.read(id, 0)
                    propertyReasons[id] = when (probe.code) {
                        "UNSUPPORTED" -> "车机未提供"
                        "PERMISSION_DENIED" -> "无权限（${probe.detail}）"
                        else -> "未列出：${probe.code} ${probe.detail}"
                    }
                }
            }
        }
        refreshCapabilities()
    }

    private fun refreshCapabilities() {
        val now = SystemClock.elapsedRealtime()
        SIGNAL_SOURCES.forEach { (signal, ids) ->
            val readable = ids.filter { it in configs }
            val status = if (readable.isNotEmpty()) CapabilityStatus.AVAILABLE else if (ids.isEmpty()) CapabilityStatus.UNSUPPORTED else {
                if (ids.any { propertyReasons[it]?.startsWith("无权限") == true }) CapabilityStatus.DENIED else CapabilityStatus.UNSUPPORTED
            }
            val reason = if (ids.isEmpty()) "没有对应的 Android 标准属性" else ids.joinToString("; ") { "${CarProps.name(it)}: ${propertyReasons[it] ?: "?"}" }
            capabilityMap[signal] = SignalCapability(status, reason, now, profile.name)
        }
    }

    /** Values read during one poll, keyed by (property, area). */
    private inner class Poll(private val carAccess: CarPropertyAccess, val now: Long, val openGeneration: Long) {
        private val cache = mutableMapOf<Long, CarPropertyAccess.Read>()

        fun read(id: Int, area: Int = 0): CarPropertyAccess.Read? {
            if (id !in configs) return null
            return cache.getOrPut((id.toLong() shl 32) or (area.toLong() and 0xffffffffL)) { carAccess.read(id, area) }
        }

        fun areas(id: Int): List<Int> = configs[id]?.areaIds.orEmpty()

        /** First successful read among [ids] (global area). */
        fun first(vararg ids: Int): Pair<Int, CarPropertyAccess.Read>? = ids.asList().firstNotNullOfOrNull { id ->
            read(id)?.takeIf { it.ok }?.let { id to it }
        }

        /** Why none of [ids] gave a value. */
        fun missing(signal: VehicleSignal, vararg ids: Int): DataValue<Any> {
            val reasons = ids.map { id ->
                val read = read(id)
                when {
                    read == null -> "${CarProps.name(id)}: ${propertyReasons[id] ?: "未就绪"}"
                    else -> "${CarProps.name(id)}: ${read.code} ${read.detail}"
                }
            }
            val code = when {
                ids.none { it in configs } -> if (ids.any { propertyReasons[it]?.startsWith("无权限") == true }) "PERMISSION_DENIED" else "NOT_SUPPORTED"
                else -> ids.asList().mapNotNull { read(it)?.code }.firstOrNull() ?: "NO_VALUE"
            }
            return unavailableValue<Any>(signal, now, openGeneration, code, reasons.joinToString("; "))
        }

        fun <T> known(signal: VehicleSignal, value: T, read: CarPropertyAccess.Read, source: String, budget: Long = 3000): DataValue<T> {
            val sampled = read.timestampNanos / 1_000_000
            val validSample = read.timestampNanos > 0 && sampled <= now + 1000
            return DataValue(
                value = value,
                validity = Validity.KNOWN,
                observedAtMonotonic = if (validSample) sampled else null,
                receivedAtMonotonic = now,
                timestampBasis = if (validSample) TimestampBasis.DEVICE_SAMPLE else TimestampBasis.READ_COMPLETION,
                unit = signal.unit,
                source = source,
                connectionGeneration = openGeneration,
                freshnessBudgetMs = profile.freshnessBudgetsMs[signal] ?: budget,
            )
        }
    }

    private fun readAll(openGeneration: Long) {
        if (!isCurrent(openGeneration)) return
        val carAccess = access ?: return
        val poll = Poll(carAccess, SystemClock.elapsedRealtime(), openGeneration)
        val values = mutableMapOf<VehicleSignal, DataValue<*>>()
        try {
            readSignals(poll, values)
        } catch (error: Throwable) {
            // A mapping bug must not stop polling; the affected signals keep their last value.
            statusLine = "读取异常 ${error.javaClass.simpleName}: ${error.message?.take(60)}"
        }
        NO_STANDARD_PROPERTY.forEach { signal ->
            values[signal] = unavailableValue<Any>(signal, poll.now, openGeneration, "NO_STANDARD_PROPERTY", "Android 标准车辆属性中没有此项")
        }
        val next = current.copy(values = values, connection = connection, generation = openGeneration, profile = profile.name)
        current = VehicleSnapshots.rejectOlder(current, next)
        publish(current)
    }

    private fun readSignals(poll: Poll, values: MutableMap<VehicleSignal, DataValue<*>>) {
        val p = CarProps

        values[VehicleSignal.SPEED] = poll.first(p.PERF_VEHICLE_SPEED, p.PERF_VEHICLE_SPEED_DISPLAY)?.let { (id, read) ->
            (read.value as? Number)?.let { poll.known(VehicleSignal.SPEED, CarMapping.speedKmh(it.toFloat()), read, "android.car:${p.name(id)}:m/s", 1500) }
        } ?: poll.missing(VehicleSignal.SPEED, p.PERF_VEHICLE_SPEED, p.PERF_VEHICLE_SPEED_DISPLAY)

        values[VehicleSignal.GEAR] = poll.first(p.GEAR_SELECTION, p.CURRENT_GEAR)?.let { (id, read) ->
            val raw = read.value as? Int ?: return@let null
            val gear = CarMapping.gear(raw)
            if (gear == null) {
                unavailableValue<Gear>(VehicleSignal.GEAR, poll.now, poll.openGeneration, "ENUM_UNMAPPED", "${p.name(id)}=$raw 未映射", Validity.UNKNOWN)
            } else {
                poll.known(VehicleSignal.GEAR, gear, read, "android.car:${p.name(id)}", 5000)
            }
        } ?: poll.missing(VehicleSignal.GEAR, p.GEAR_SELECTION, p.CURRENT_GEAR)

        values[VehicleSignal.POWER_STATE] = poll.first(p.IGNITION_STATE)?.let { (_, read) ->
            CarMapping.ignition(read.value as? Int ?: -1)?.let { poll.known(VehicleSignal.POWER_STATE, it, read, "android.car:IGNITION_STATE") }
        } ?: poll.missing(VehicleSignal.POWER_STATE, p.IGNITION_STATE)

        val level = poll.first(p.EV_BATTERY_LEVEL)
        val capacity = poll.first(p.EV_CURRENT_BATTERY_CAPACITY, p.INFO_EV_BATTERY_CAPACITY)
        values[VehicleSignal.BATTERY_PERCENT] = if (level != null && capacity != null) {
            val levelWh = (level.second.value as? Number)?.toFloat()
            val capacityWh = (capacity.second.value as? Number)?.toFloat()
            val percent = if (levelWh != null && capacityWh != null) CarMapping.batteryPercent(levelWh, capacityWh) else null
            percent?.let { poll.known(VehicleSignal.BATTERY_PERCENT, it, level.second, "android.car:EV_BATTERY_LEVEL/${p.name(capacity.first)}") }
                ?: unavailableValue<Double>(VehicleSignal.BATTERY_PERCENT, poll.now, poll.openGeneration, "OUT_OF_RANGE", "level=$levelWh Wh capacity=$capacityWh Wh", Validity.UNKNOWN)
        } else {
            poll.missing(VehicleSignal.BATTERY_PERCENT, p.EV_BATTERY_LEVEL, p.INFO_EV_BATTERY_CAPACITY)
        }

        values[VehicleSignal.RANGE] = poll.first(p.RANGE_REMAINING)?.let { (_, read) ->
            (read.value as? Number)?.let { poll.known(VehicleSignal.RANGE, CarMapping.rangeKm(it.toFloat()), read, "android.car:RANGE_REMAINING:m") }
        } ?: poll.missing(VehicleSignal.RANGE, p.RANGE_REMAINING)

        val rate = poll.first(p.EV_BATTERY_INSTANTANEOUS_CHARGE_RATE)
        val rateMw = (rate?.second?.value as? Number)?.toFloat()
        values[VehicleSignal.POWER] = if (rate != null && rateMw != null) {
            poll.known(VehicleSignal.POWER, CarMapping.powerKw(rateMw), rate.second, "android.car:EV_BATTERY_INSTANTANEOUS_CHARGE_RATE:mW(驾驶为正)", 1500)
        } else {
            poll.missing(VehicleSignal.POWER, p.EV_BATTERY_INSTANTANEOUS_CHARGE_RATE)
        }

        val chargeState = poll.first(p.EV_CHARGE_STATE)
        val port = poll.first(p.EV_CHARGE_PORT_CONNECTED)
        val charging = CarMapping.charging(chargeState?.second?.value as? Int, port?.second?.value as? Boolean, rateMw)
        val chargeRead = chargeState?.second ?: port?.second
        values[VehicleSignal.CHARGING] = if (charging != null && chargeRead != null) {
            poll.known(VehicleSignal.CHARGING, charging, chargeRead, "android.car:" + if (chargeState != null) "EV_CHARGE_STATE" else "EV_CHARGE_PORT_CONNECTED+CHARGE_RATE")
        } else {
            poll.missing(VehicleSignal.CHARGING, p.EV_CHARGE_STATE, p.EV_CHARGE_PORT_CONNECTED)
        }

        val turn = poll.first(p.TURN_SIGNAL_STATE)
        val hazard = poll.first(p.HAZARD_LIGHTS_STATE)
        val (left, right) = CarMapping.indicators(turn?.second?.value as? Int, (hazard?.second?.value as? Int)?.let { it == 1 })
        val lightRead = turn?.second ?: hazard?.second
        values[VehicleSignal.LEFT_INDICATOR] = if (left != null && lightRead != null) {
            poll.known(VehicleSignal.LEFT_INDICATOR, left, lightRead, "android.car:TURN_SIGNAL_STATE/HAZARD", 1500)
        } else {
            poll.missing(VehicleSignal.LEFT_INDICATOR, p.TURN_SIGNAL_STATE)
        }
        values[VehicleSignal.RIGHT_INDICATOR] = if (right != null && lightRead != null) {
            poll.known(VehicleSignal.RIGHT_INDICATOR, right, lightRead, "android.car:TURN_SIGNAL_STATE/HAZARD", 1500)
        } else {
            poll.missing(VehicleSignal.RIGHT_INDICATOR, p.TURN_SIGNAL_STATE)
        }
        values[VehicleSignal.HAZARD] = hazard?.let { (_, read) ->
            poll.known(VehicleSignal.HAZARD, (read.value as? Int) == 1, read, "android.car:HAZARD_LIGHTS_STATE", 1500)
        } ?: poll.missing(VehicleSignal.HAZARD, p.HAZARD_LIGHTS_STATE)

        val doorAreas = poll.areas(p.DOOR_LOCK)
        val doorReads = doorAreas.mapNotNull { poll.read(p.DOOR_LOCK, it)?.takeIf { read -> read.ok } }
        values[VehicleSignal.LOCKED] = if (doorAreas.isNotEmpty() && doorReads.size == doorAreas.size) {
            poll.known(VehicleSignal.LOCKED, doorReads.all { it.value == true }, doorReads.first(), "android.car:DOOR_LOCK×${doorAreas.size}")
        } else {
            poll.missing(VehicleSignal.LOCKED, p.DOOR_LOCK)
        }

        val tireAreas = poll.areas(p.TIRE_PRESSURE)
        listOf(
            VehicleSignal.TIRE_PRESSURE_FL to p.WHEEL_LEFT_FRONT,
            VehicleSignal.TIRE_PRESSURE_FR to p.WHEEL_RIGHT_FRONT,
            VehicleSignal.TIRE_PRESSURE_RL to p.WHEEL_LEFT_REAR,
            VehicleSignal.TIRE_PRESSURE_RR to p.WHEEL_RIGHT_REAR,
        ).forEach { (signal, wheel) ->
            val read = if (wheel in tireAreas) poll.read(p.TIRE_PRESSURE, wheel) else null
            val kpa = (read?.takeIf { it.ok }?.value as? Number)?.toFloat()
            values[signal] = if (read != null && kpa != null) {
                poll.known(signal, CarMapping.kpaToBar(kpa), read, "android.car:TIRE_PRESSURE:kPa→bar area=$wheel", 60_000)
            } else if (p.TIRE_PRESSURE in configs) {
                unavailableValue<Double>(signal, poll.now, poll.openGeneration, read?.code ?: "NO_AREA", "TIRE_PRESSURE area=$wheel ${read?.detail ?: "车机未提供该车轮"}")
            } else {
                poll.missing(signal, p.TIRE_PRESSURE)
            }
        }
    }

    private fun <T> unavailableValue(
        signal: VehicleSignal,
        now: Long,
        openGeneration: Long,
        code: String,
        reason: String,
        validity: Validity = Validity.UNAVAILABLE,
    ): DataValue<T> = DataValue(
        value = null,
        validity = validity,
        observedAtMonotonic = null,
        receivedAtMonotonic = now,
        timestampBasis = TimestampBasis.UNKNOWN,
        unit = signal.unit,
        source = "android.car",
        connectionGeneration = openGeneration,
        freshnessBudgetMs = profile.freshnessBudgetsMs[signal],
        error = DataError(code, reason),
    )

    private fun failConnection(openGeneration: Long, error: Throwable) {
        if (!isCurrent(openGeneration)) return
        connection = ConnectionState.ERROR
        val now = SystemClock.elapsedRealtime()
        statusLine = "连接失败 ${error.javaClass.name}: ${error.message?.take(80) ?: ""}"
        val values = VehicleSignal.entries.associateWith { signal ->
            DataValue<Any>(
                value = null,
                validity = Validity.UNAVAILABLE,
                observedAtMonotonic = null,
                receivedAtMonotonic = now,
                timestampBasis = TimestampBasis.UNKNOWN,
                unit = signal.unit,
                source = "android.car",
                connectionGeneration = openGeneration,
                freshnessBudgetMs = profile.freshnessBudgetsMs[signal],
                error = DataError("CONNECTION_FAILED", "android.car connection failed", error::class.java.name),
            )
        }
        current = VehicleSnapshot(values, connection, openGeneration, profile.name)
        publish(current)
    }

    private fun publish(snapshot: VehicleSnapshot) {
        if (snapshot.generation == generation && active.get()) observer(snapshot)
    }

    private fun isCurrent(openGeneration: Long) = active.get() && generation == openGeneration

    override fun capabilities(): Map<VehicleSignal, SignalCapability> = capabilityMap.toMap()
    override fun snapshot(): VehicleSnapshot = current

    /** Standard candidate property → readable / reason, plus manifest permission state. */
    fun propertyReport(): List<String> {
        val lines = mutableListOf<String>()
        CarProps.CANDIDATES.forEach { (id, name) -> lines += "$name: ${propertyReasons[id] ?: "未检查"}" }
        CarProps.PERMISSIONS.forEach { permission ->
            val granted = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            lines += "${if (granted) "✓" else "✗"} ${permission.substringAfterLast('.')}"
        }
        return lines
    }

    override fun close(): DataCloseResult {
        if (!active.getAndSet(false)) return DataCloseResult(CloseStatus.COMPLETE, 0)
        generation += 1
        readFuture?.cancel(true)
        connection = ConnectionState.DISCONNECTED
        val old = access
        access = null
        executor.execute { old?.disconnect() }
        executor.shutdown()
        return DataCloseResult(CloseStatus.PARTIAL, 1, DataError("SDK_CLOSE_PENDING", "Local polling cancelled; SDK disconnect queued"))
    }

    companion object {
        /** Signals with no standard Android property; shown as unavailable with this reason. */
        val NO_STANDARD_PROPERTY = listOf(
            VehicleSignal.BATTERY_TEMPERATURE, VehicleSignal.VOLTAGE, VehicleSignal.CURRENT, VehicleSignal.TIME_REMAINING,
            VehicleSignal.TIRE_TEMPERATURE_FL, VehicleSignal.TIRE_TEMPERATURE_FR, VehicleSignal.TIRE_TEMPERATURE_RL, VehicleSignal.TIRE_TEMPERATURE_RR,
            VehicleSignal.MOTOR_TEMPERATURE_FRONT, VehicleSignal.MOTOR_TEMPERATURE_REAR, VehicleSignal.MOTOR_ACTIVITY, VehicleSignal.MOTOR_FAULT,
            VehicleSignal.ACCELERATOR, VehicleSignal.BRAKE, VehicleSignal.ACCELERATION_LONGITUDINAL, VehicleSignal.ACCELERATION_LATERAL,
            VehicleSignal.DRIVE_MODE, VehicleSignal.SUSPENSION_HEIGHT, VehicleSignal.SUSPENSION_STIFFNESS,
        )

        private val SIGNAL_SOURCES: Map<VehicleSignal, List<Int>> = mapOf(
            VehicleSignal.SPEED to listOf(CarProps.PERF_VEHICLE_SPEED, CarProps.PERF_VEHICLE_SPEED_DISPLAY),
            VehicleSignal.GEAR to listOf(CarProps.GEAR_SELECTION, CarProps.CURRENT_GEAR),
            VehicleSignal.POWER_STATE to listOf(CarProps.IGNITION_STATE),
            VehicleSignal.BATTERY_PERCENT to listOf(CarProps.EV_BATTERY_LEVEL, CarProps.INFO_EV_BATTERY_CAPACITY),
            VehicleSignal.RANGE to listOf(CarProps.RANGE_REMAINING),
            VehicleSignal.POWER to listOf(CarProps.EV_BATTERY_INSTANTANEOUS_CHARGE_RATE),
            VehicleSignal.CHARGING to listOf(CarProps.EV_CHARGE_STATE, CarProps.EV_CHARGE_PORT_CONNECTED),
            VehicleSignal.LEFT_INDICATOR to listOf(CarProps.TURN_SIGNAL_STATE),
            VehicleSignal.RIGHT_INDICATOR to listOf(CarProps.TURN_SIGNAL_STATE),
            VehicleSignal.HAZARD to listOf(CarProps.HAZARD_LIGHTS_STATE),
            VehicleSignal.LOCKED to listOf(CarProps.DOOR_LOCK),
            VehicleSignal.TIRE_PRESSURE_FL to listOf(CarProps.TIRE_PRESSURE),
            VehicleSignal.TIRE_PRESSURE_FR to listOf(CarProps.TIRE_PRESSURE),
            VehicleSignal.TIRE_PRESSURE_RL to listOf(CarProps.TIRE_PRESSURE),
            VehicleSignal.TIRE_PRESSURE_RR to listOf(CarProps.TIRE_PRESSURE),
        ) + NO_STANDARD_PROPERTY.associateWith { emptyList() }
    }
}
