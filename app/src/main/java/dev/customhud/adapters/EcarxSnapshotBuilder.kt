package dev.customhud.adapters

import dev.customhud.data.DataError
import dev.customhud.data.DataValue
import dev.customhud.data.Gear
import dev.customhud.data.TimestampBasis
import dev.customhud.data.Validity
import dev.customhud.data.VehicleSignal
import java.util.Locale

/**
 * Turns raw ECARX reads into [DataValue]s, one per [VehicleSignal]. Only the readings CarLight's
 * owner confirmed on the car (docs/vehicle-interfaces.md §27) become KNOWN. Unconfirmed candidates
 * (battery / motor temperature, charging, power, ignition) are read too, but published as UNKNOWN
 * with the raw value in the error text, so the probe page can show them without the HUD claiming a
 * unit. A failed read is UNAVAILABLE, never 0 / off / P.
 *
 * Not thread-safe: one instance per poller thread (it keeps the turn-signal hold state).
 */
class EcarxSnapshotBuilder(private val turnHoldMs: Long = TURN_HOLD_MS) {
    private val lastOn = HashMap<VehicleSignal, Long>()

    /** Fast group (every poll): speed, gear, indicators, pedals, acceleration, motor activity. */
    fun fast(r: EcarxReads, now: Long, generation: Long): Map<VehicleSignal, DataValue<*>> {
        val out = LinkedHashMap<VehicleSignal, DataValue<*>>()
        val ctx = Ctx(now, generation, FAST_BUDGET_MS)
        out[VehicleSignal.SPEED] = ctx.sensor(VehicleSignal.SPEED, r, EcarxIds.SENSOR_SPEED, "m/s×3.6", EcarxDecode::speedKmh)

        val gearRaw = r.sensorEvent(EcarxIds.SENSOR_GEAR)
        val gear = EcarxDecode.gear(gearRaw)
        out[VehicleSignal.GEAR] = when {
            gear == null -> ctx.missing(VehicleSignal.GEAR, EcarxIds.SENSOR_GEAR, if (gearRaw == null) "NO_VALUE" else "INVALID", rawText(gearRaw))
            gear == Gear.UNKNOWN -> ctx.missing(VehicleSignal.GEAR, EcarxIds.SENSOR_GEAR, "ENUM_UNMAPPED", "原值 ${hexOrNull(gearRaw)} 未映射", Validity.UNKNOWN)
            else -> ctx.known(VehicleSignal.GEAR, gear, "ECARX ${EcarxIds.hex(EcarxIds.SENSOR_GEAR)} 原值 ${hexOrNull(gearRaw)}")
        }

        out[VehicleSignal.LEFT_INDICATOR] = indicator(ctx, VehicleSignal.LEFT_INDICATOR, r, EcarxIds.FUNC_LEFT_TURN)
        out[VehicleSignal.RIGHT_INDICATOR] = indicator(ctx, VehicleSignal.RIGHT_INDICATOR, r, EcarxIds.FUNC_RIGHT_TURN)
        out[VehicleSignal.HAZARD] = indicator(ctx, VehicleSignal.HAZARD, r, EcarxIds.FUNC_HAZARD)

        out[VehicleSignal.ACCELERATOR] = ctx.sensor(VehicleSignal.ACCELERATOR, r, EcarxIds.SENSOR_ACCELERATOR, "0～100 %", EcarxDecode::pedal)
        out[VehicleSignal.BRAKE] = ctx.sensor(VehicleSignal.BRAKE, r, EcarxIds.SENSOR_BRAKE, "原值，实车 0～30", EcarxDecode::pedal)
        out[VehicleSignal.ACCELERATION_LONGITUDINAL] = ctx.sensor(VehicleSignal.ACCELERATION_LONGITUDINAL, r, EcarxIds.SENSOR_ACCEL_LON, "m/s²÷9.8 = g", EcarxDecode::accelG)
        out[VehicleSignal.ACCELERATION_LATERAL] = ctx.sensor(VehicleSignal.ACCELERATION_LATERAL, r, EcarxIds.SENSOR_ACCEL_LAT, "m/s²÷9.8 = g", EcarxDecode::accelG)

        val flowRaw = r.functionIntAny(EcarxIds.FUNC_POWER_FLOW)
        val motors = EcarxDecode.motorActivity(flowRaw)
        out[VehicleSignal.MOTOR_ACTIVITY] = when {
            motors != null -> ctx.known(VehicleSignal.MOTOR_ACTIVITY, motors, "ECARX ${EcarxIds.hex(EcarxIds.FUNC_POWER_FLOW)} 原值 ${hexOrNull(flowRaw)}")
            EcarxDecode.validInt(flowRaw) != null -> ctx.missing(VehicleSignal.MOTOR_ACTIVITY, EcarxIds.FUNC_POWER_FLOW, "ENUM_UNMAPPED", "原值 ${hexOrNull(flowRaw)} 没有前/后电机解读", Validity.UNKNOWN)
            else -> ctx.missing(VehicleSignal.MOTOR_ACTIVITY, EcarxIds.FUNC_POWER_FLOW, if (flowRaw == null) "NO_VALUE" else "INVALID", rawText(flowRaw))
        }
        return out
    }

    /** Slow group (about every 2 s): energy, tyres, locks, drive mode, suspension, unverified raw values. */
    fun slow(r: EcarxReads, now: Long, generation: Long): Map<VehicleSignal, DataValue<*>> {
        val out = LinkedHashMap<VehicleSignal, DataValue<*>>()
        val ctx = Ctx(now, generation, SLOW_BUDGET_MS)

        val socRaw = r.sensorFloat(EcarxIds.SENSOR_SOC)
        val soc = EcarxDecode.percent(socRaw)
        out[VehicleSignal.BATTERY_PERCENT] = if (soc != null) {
            ctx.known(VehicleSignal.BATTERY_PERCENT, soc, "ECARX ${EcarxIds.hex(EcarxIds.SENSOR_SOC)} %")
        } else {
            ctx.sensor(VehicleSignal.BATTERY_PERCENT, r, EcarxIds.SENSOR_SOC_LEGACY, "% (${EcarxIds.hex(EcarxIds.SENSOR_SOC)} ${rawText(socRaw)})", EcarxDecode::percent)
        }
        out[VehicleSignal.RANGE] = ctx.sensor(VehicleSignal.RANGE, r, EcarxIds.SENSOR_RANGE, "km", EcarxDecode::rangeKm)

        val pressures = listOf(VehicleSignal.TIRE_PRESSURE_FL, VehicleSignal.TIRE_PRESSURE_FR, VehicleSignal.TIRE_PRESSURE_RL, VehicleSignal.TIRE_PRESSURE_RR)
        val temps = listOf(VehicleSignal.TIRE_TEMPERATURE_FL, VehicleSignal.TIRE_TEMPERATURE_FR, VehicleSignal.TIRE_TEMPERATURE_RL, VehicleSignal.TIRE_TEMPERATURE_RR)
        for (k in 0 until 4) {
            out[pressures[k]] = ctx.sensor(pressures[k], r, EcarxIds.SENSOR_TIRE_PRESSURE[k], "原值÷100 = bar", EcarxDecode::tirePressureBar)
            out[temps[k]] = ctx.sensor(temps[k], r, EcarxIds.SENSOR_TIRE_TEMP[k], "°C", EcarxDecode::tireTempC)
        }

        val lockRaw = r.functionIntAny(EcarxIds.FUNC_CENTRAL_LOCK)
        out[VehicleSignal.LOCKED] = EcarxDecode.onOff(lockRaw)?.let {
            ctx.known(VehicleSignal.LOCKED, it, "ECARX ${EcarxIds.hex(EcarxIds.FUNC_CENTRAL_LOCK)} 中控锁 原值 $lockRaw")
        } ?: ctx.missing(VehicleSignal.LOCKED, EcarxIds.FUNC_CENTRAL_LOCK, if (lockRaw == null) "NO_VALUE" else "INVALID", rawText(lockRaw))

        val modeRaw = EcarxDecode.validInt(r.functionIntAny(EcarxIds.FUNC_DRIVE_MODE))
        out[VehicleSignal.DRIVE_MODE] = modeRaw?.let {
            ctx.known(VehicleSignal.DRIVE_MODE, EcarxDecode.driveModeLabel(it), "ECARX ${EcarxIds.hex(EcarxIds.FUNC_DRIVE_MODE)} 原值 ${EcarxIds.hex(it)}")
        } ?: ctx.missing(VehicleSignal.DRIVE_MODE, EcarxIds.FUNC_DRIVE_MODE, "NO_VALUE", "读不到或无效值")

        out[VehicleSignal.SUSPENSION_HEIGHT] = firstValid(ctx, VehicleSignal.SUSPENSION_HEIGHT, r, EcarxIds.FUNC_SUSPENSION_HEIGHT, EcarxDecode::suspensionHeightLabel)
        out[VehicleSignal.SUSPENSION_STIFFNESS] = firstValid(ctx, VehicleSignal.SUSPENSION_STIFFNESS, r, EcarxIds.FUNC_SUSPENSION_DAMPING, EcarxDecode::suspensionDampingLabel)

        // Not verified on the car: raw values for the probe page only.
        out[VehicleSignal.BATTERY_TEMPERATURE] = ctx.unverified(VehicleSignal.BATTERY_TEMPERATURE, EcarxIds.SENSOR_BATTERY_TEMP, rawText(r.sensorFloat(EcarxIds.SENSOR_BATTERY_TEMP)))
        out[VehicleSignal.MOTOR_TEMPERATURE_FRONT] = ctx.unverified(VehicleSignal.MOTOR_TEMPERATURE_FRONT, EcarxIds.SENSOR_MOTOR_TEMP_FRONT, rawText(r.sensorFloat(EcarxIds.SENSOR_MOTOR_TEMP_FRONT)))
        out[VehicleSignal.MOTOR_TEMPERATURE_REAR] = ctx.unverified(VehicleSignal.MOTOR_TEMPERATURE_REAR, EcarxIds.SENSOR_MOTOR_TEMP_REAR, rawText(r.sensorFloat(EcarxIds.SENSOR_MOTOR_TEMP_REAR)))
        out[VehicleSignal.POWER_STATE] = ctx.unverified(VehicleSignal.POWER_STATE, EcarxIds.SENSOR_IGNITION, hexOrNull(r.sensorEvent(EcarxIds.SENSOR_IGNITION)))
        val plug = r.functionIntAny(EcarxIds.FUNC_CHARGE_PLUG_STATE)
        val battery = r.sensorEvent(EcarxIds.SENSOR_EV_BATTERY_STATE)
        out[VehicleSignal.CHARGING] = ctx.unverified(VehicleSignal.CHARGING, EcarxIds.FUNC_CHARGE_PLUG_STATE,
            "插口 ${hexOrNull(plug)} · 电池状态 ${EcarxIds.hex(EcarxIds.SENSOR_EV_BATTERY_STATE)}=${hexOrNull(battery)}")
        out[VehicleSignal.VOLTAGE] = ctx.unverified(VehicleSignal.VOLTAGE, EcarxIds.FUNC_CHARGE_VOLTAGE, rawText(r.functionFloat(EcarxIds.FUNC_CHARGE_VOLTAGE)))
        out[VehicleSignal.CURRENT] = ctx.unverified(VehicleSignal.CURRENT, EcarxIds.FUNC_CHARGE_CURRENT, rawText(r.functionFloat(EcarxIds.FUNC_CHARGE_CURRENT)))
        out[VehicleSignal.TIME_REMAINING] = ctx.unverified(VehicleSignal.TIME_REMAINING, EcarxIds.FUNC_CHARGE_ETA, rawText(r.functionFloat(EcarxIds.FUNC_CHARGE_ETA)))
        out[VehicleSignal.POWER] = ctx.unverified(VehicleSignal.POWER, EcarxIds.SENSOR_DISCHARGE_POWER, rawText(r.sensorFloat(EcarxIds.SENSOR_DISCHARGE_POWER)))
        out[VehicleSignal.MOTOR_FAULT] = ctx.missing(VehicleSignal.MOTOR_FAULT, null, "NO_SOURCE", "ECARX 没有已确认的电机故障读数")
        return out
    }

    /** Every signal UNAVAILABLE with one reason (connecting, disconnected, poll stalled). */
    fun unavailableAll(now: Long, generation: Long, code: String, message: String): Map<VehicleSignal, DataValue<*>> {
        val ctx = Ctx(now, generation, FAST_BUDGET_MS)
        return VehicleSignal.entries.associateWith { ctx.missing(it, null, code, message) }
    }

    /**
     * Turn / hazard: 1 on, 0 off. Whether the value follows the lamp blink or the stalk is not
     * documented, so an "off" within [turnHoldMs] of the last "on" still counts as on (steady arrow).
     */
    private fun indicator(ctx: Ctx, signal: VehicleSignal, r: EcarxReads, id: Int): DataValue<*> {
        val raw = r.functionIntAny(id)
        val on = EcarxDecode.onOff(raw)
            ?: return ctx.missing(signal, id, if (raw == null) "NO_VALUE" else "INVALID", rawText(raw))
        if (on) lastOn[signal] = ctx.now
        val held = !on && lastOn[signal]?.let { ctx.now - it <= turnHoldMs } == true
        return ctx.known(signal, on || held, "ECARX ${EcarxIds.hex(id)} 原值 $raw" + if (held) "（熄灭 ${turnHoldMs} ms 内保持）" else "")
    }

    private fun firstValid(ctx: Ctx, signal: VehicleSignal, r: EcarxReads, ids: IntArray, label: (Int) -> String): DataValue<*> {
        for (id in ids) {
            val v = EcarxDecode.validInt(r.functionIntAny(id)) ?: continue
            return ctx.known(signal, label(v), "ECARX ${EcarxIds.hex(id)} 原值 ${EcarxIds.hex(v)}")
        }
        return ctx.missing(signal, ids.first(), "NO_VALUE", "${ids.joinToString("/") { EcarxIds.hex(it) }} 都读不到或无效")
    }

    private class Ctx(val now: Long, val generation: Long, val budget: Long) {
        fun <T> known(signal: VehicleSignal, value: T, source: String): DataValue<T> = DataValue(
            value = value,
            validity = Validity.KNOWN,
            observedAtMonotonic = null,
            receivedAtMonotonic = now,
            timestampBasis = TimestampBasis.READ_COMPLETION,
            unit = signal.unit,
            source = source,
            connectionGeneration = generation,
            freshnessBudgetMs = budget,
        )

        fun missing(signal: VehicleSignal, id: Int?, code: String, message: String, validity: Validity = Validity.UNAVAILABLE): DataValue<Any> = DataValue(
            value = null,
            validity = validity,
            observedAtMonotonic = null,
            receivedAtMonotonic = now,
            timestampBasis = TimestampBasis.UNKNOWN,
            unit = signal.unit,
            source = if (id == null) "ECARX" else "ECARX ${EcarxIds.hex(id)}",
            connectionGeneration = generation,
            freshnessBudgetMs = null,
            error = DataError(code, message),
        )

        fun unverified(signal: VehicleSignal, id: Int, raw: String): DataValue<Any> =
            missing(signal, id, UNVERIFIED, "原值 $raw（${EcarxIds.hex(id)}，未实车确认，HUD 不显示）", Validity.UNKNOWN)

        fun sensor(signal: VehicleSignal, r: EcarxReads, id: Int, how: String, decode: (Float?) -> Double?): DataValue<*> {
            val raw = r.sensorFloat(id)
            val value = decode(raw)
            return when {
                value != null -> known(signal, value, "ECARX ${EcarxIds.hex(id)} $how 原值 ${rawText(raw)}")
                raw == null -> missing(signal, id, "NO_VALUE", "读取失败或无值")
                else -> missing(signal, id, "INVALID", "原值 ${rawText(raw)} 无效或超范围")
            }
        }
    }

    companion object {
        const val UNVERIFIED = "UNVERIFIED"
        const val TURN_HOLD_MS = 800L
        const val FAST_BUDGET_MS = 1_500L
        const val SLOW_BUDGET_MS = 6_000L

        fun rawText(v: Float?): String = v?.let { String.format(Locale.ROOT, "%.3f", it).trimEnd('0').trimEnd('.') } ?: "—"
        fun rawText(v: Int?): String = v?.let { "$it (${EcarxIds.hex(it)})" } ?: "读取失败"
        fun hexOrNull(v: Int?): String = v?.let { EcarxIds.hex(it) } ?: "—"
    }
}
