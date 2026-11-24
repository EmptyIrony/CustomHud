package dev.customhud.camera

import dev.customhud.data.DataValue
import dev.customhud.data.Gear
import dev.customhud.data.Validity
import dev.customhud.data.VehicleSignal
import dev.customhud.data.VehicleSnapshot

/** When the rear camera widget subscribes to zeekr:202. */
enum class RearCameraMode(val label: String, val needsGear: Boolean) {
    ALWAYS("常显", false),
    NOT_PARK("非 P 挡", true),
    REVERSE_ONLY("仅 R 挡", true),
    OFF("关", false),
    ;

    companion object {
        /** P 挡不订阅摄像头，非 P 挡才打开（用户 2026-11-06 要求）。 */
        val DEFAULT = NOT_PARK

        fun parse(raw: String?): RearCameraMode = entries.firstOrNull { it.name == raw } ?: DEFAULT
    }
}

/** One gear observation; [gear] is null when the source cannot say (never guessed as P). */
data class GearReading(val gear: Gear?, val source: String, val detail: String) {
    val available: Boolean get() = gear != null && gear != Gear.UNKNOWN

    fun label(): String = if (available) "${gear!!.short()}（$source）" else "不可用（$detail）"

    companion object {
        fun unavailable(source: String, detail: String) = GearReading(null, source, detail)
    }
}

fun Gear.short(): String = when (this) {
    Gear.PARK -> "P"
    Gear.REVERSE -> "R"
    Gear.NEUTRAL -> "N"
    Gear.DRIVE -> "D"
    Gear.LOW -> "L"
    Gear.UNKNOWN -> "?"
}

sealed class ModeDecision {
    object Show : ModeDecision() {
        override fun toString() = "Show"
    }

    /** Not shown, nothing drawn (the cluster's own content stays visible). */
    data class Hide(val reason: String) : ModeDecision()

    /** Cannot decide (gear data missing): placeholder with the reason, camera stays closed. */
    data class Unavailable(val reason: String) : ModeDecision()
}

object RearCameraPolicy {
    /** Retry after onError / DISCONNECTED: 2 s, 5 s, 10 s, then every 30 s. */
    val RETRY_DELAYS_MS = listOf(2_000L, 5_000L, 10_000L)
    const val RETRY_STEADY_MS = 30_000L

    fun retryDelay(attempt: Int): Long = RETRY_DELAYS_MS.getOrElse(attempt) { RETRY_STEADY_MS }

    fun decide(mode: RearCameraMode, widgetEnabled: Boolean, gear: GearReading): ModeDecision {
        if (!widgetEnabled) return ModeDecision.Hide("布局中未启用")
        return when (mode) {
            RearCameraMode.OFF -> ModeDecision.Hide("模式：关")
            RearCameraMode.ALWAYS -> ModeDecision.Show
            RearCameraMode.NOT_PARK -> when {
                !gear.available -> ModeDecision.Unavailable("挡位不可用（${gear.detail}），“非 P 挡”无法判断")
                gear.gear == Gear.PARK -> ModeDecision.Hide("P 挡")
                else -> ModeDecision.Show
            }
            RearCameraMode.REVERSE_ONLY -> when {
                !gear.available -> ModeDecision.Unavailable("挡位不可用（${gear.detail}），“仅 R 挡”无法判断")
                gear.gear == Gear.REVERSE -> ModeDecision.Show
                else -> ModeDecision.Hide("${gear.gear!!.short()} 挡，非 R")
            }
        }
    }

    /** Gear from the polled ECARX vehicle snapshot; only KNOWN and fresh values count. */
    fun gearFromSnapshot(snapshot: VehicleSnapshot, now: Long): GearReading {
        val value: DataValue<*> = snapshot.value(VehicleSignal.GEAR)
            ?: return GearReading.unavailable(POLLED, "无挡位数据")
        val gear = value.value as? Gear
        return when {
            value.validity != Validity.KNOWN || gear == null ->
                GearReading.unavailable(POLLED, value.error?.let { "${it.code} ${it.message}" } ?: value.validity.name)
            gear == Gear.UNKNOWN -> GearReading.unavailable(POLLED, "挡位值未映射")
            !value.isFresh(now) -> GearReading.unavailable(POLLED, "数据过期")
            else -> GearReading(gear, POLLED, value.source)
        }
    }

    const val POLLED = "ECARX 轮询"
    const val ECARX = "ECARX 0x200200"
}

/**
 * Picks the gear the camera mode uses. ECARX 0x200200 (event-driven, the same subscription CarLight's
 * gear sounds use on this car) wins; the polled vehicle snapshot (getSensorEvent 0x200200, 200 ms) is the
 * fallback while it is fresh.
 * Main thread only.
 */
class GearTracker(private val polledStaleMs: Long = POLLED_STALE_MS) {
    var ecarx: GearReading = GearReading.unavailable(RearCameraPolicy.ECARX, "未连接")
        private set
    private var polled: GearReading = GearReading.unavailable(RearCameraPolicy.POLLED, "无数据")
    private var polledAt: Long? = null

    fun updateEcarx(reading: GearReading) {
        ecarx = reading
    }

    fun updatePolled(reading: GearReading, now: Long) {
        polled = reading
        polledAt = now
    }

    fun effective(now: Long): GearReading {
        if (ecarx.available) return ecarx
        val at = polledAt
        val fresh = at != null && now - at <= polledStaleMs
        if (polled.available && fresh) return polled
        val carDetail = if (polled.available && !fresh) "数据过期" else polled.detail
        return GearReading.unavailable("—", "ECARX：${ecarx.detail}；ECARX 轮询：$carDetail")
    }

    companion object {
        const val POLLED_STALE_MS = 5_000L
    }
}
