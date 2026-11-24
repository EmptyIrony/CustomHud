package dev.customhud.adapters

import dev.customhud.data.Gear

enum class MotorState(val label: String) { IDLE("待机"), DRIVE("驱动"), REGEN("回收") }

/** Front / rear motor activity decoded from the power flow function value. */
data class MotorActivity(val front: MotorState, val rear: MotorState) {
    override fun toString(): String = "前 ${front.label} · 后 ${rear.label}"
}

/**
 * Pure decoding of ECARX raw values, same rules as CarLight's `EcarxVehicle`: a null result means
 * unreadable or an invalid marker (NaN, -1, 253/254/255, out of range), never 0 / off / P.
 */
object EcarxDecode {
    const val G = 9.8

    fun finite(v: Float?): Float? = v?.takeIf { !it.isNaN() && !it.isInfinite() }

    private fun range(v: Float?, min: Float, max: Float): Double? =
        finite(v)?.takeIf { it in min..max }?.toDouble()

    /** 0x100100 m/s → km/h. */
    fun speedKmh(mps: Float?): Double? = finite(mps)?.takeIf { kotlin.math.abs(it) <= 150f }?.let { it * 3.6 }

    fun percent(v: Float?): Double? = range(v, 0f, 100f)

    fun rangeKm(v: Float?): Double? = range(v, 0f, 2000f)

    fun odometerKm(v: Float?): Double? = range(v, 0f, 9_999_999f)

    /** 0..100 % accelerator; brake depth as delivered (0..30 on the car). */
    fun pedal(v: Float?): Double? = range(v, 0f, 100f)

    /** m/s² → g. */
    fun accelG(v: Float?): Double? = range(v, -50f, 50f)?.let { it / G }

    /** Tyre pressure raw ÷ 100 = bar. */
    fun tirePressureBar(v: Float?): Double? = range(v, 1f, 1000f)?.let { it / 100.0 }

    fun tireTempC(v: Float?): Double? = range(v, -60f, 200f)

    fun gear(raw: Int?): Gear? {
        if (raw == null || raw == -1) return null
        return EcarxGear.gearOf(raw) ?: Gear.UNKNOWN
    }

    fun validInt(v: Int?): Int? = v?.takeIf { it != -1 && it != 253 && it != 254 && it != 255 }

    /** 1 → true, 0 → false, anything else (253 error, 254 none, 255 unknown, transients) → null. */
    fun onOff(v: Int?): Boolean? = when (v) {
        1 -> true
        0 -> false
        else -> null
    }

    /** Power flow per zHUD 4.10 (owner-verified on the car); null when it has no front / rear reading. */
    fun motorActivity(raw: Int?): MotorActivity? {
        val i = MotorState.IDLE
        val d = MotorState.DRIVE
        val g = MotorState.REGEN
        return when (validInt(raw)) {
            0x24010101, 0x2401010D, 0x2401010E, 0x2401010F, 0x24010110, 0x24010114 -> MotorActivity(i, i)
            0x24010111 -> MotorActivity(d, d)
            0x24010112 -> MotorActivity(d, i)
            0x24010113 -> MotorActivity(i, d)
            0x24010115 -> MotorActivity(i, g)
            0x24010116 -> MotorActivity(g, i)
            0x24010117 -> MotorActivity(g, g)
            else -> null
        }
    }

    /** DRIVE_MODE_SELECTION_* (IDriveMode) → short Chinese label; unknown values as hex. */
    fun driveModeLabel(raw: Int): String = DRIVE_MODES[raw] ?: EcarxIds.hex(raw)

    /** Suspension height value → label; source 0x2208BA00 has no SDK names, so hex. */
    fun suspensionHeightLabel(raw: Int): String = SUSPENSION_HEIGHT[raw] ?: EcarxIds.hex(raw)

    fun suspensionDampingLabel(raw: Int): String = SUSPENSION_DAMPING[raw] ?: EcarxIds.hex(raw)

    /** Names from the SDK constants (CarLight EcarxCatalog.FUNCTION_VALUES). */
    private val DRIVE_MODES = mapOf(
        0x22010101 to "经济", 0x22010102 to "舒适", 0x22010103 to "运动", 0x22010104 to "越野",
        0x22010105 to "陡坡缓降", 0x22010106 to "纯电", 0x22010107 to "混动", 0x22010108 to "动力",
        0x22010109 to "雪地", 0x2201010A to "泥地", 0x2201010B to "岩石", 0x2201010C to "PHEV",
        0x2201010D to "沙地", 0x2201010E to "四驱", 0x2201010F to "节能", 0x22010110 to "经济",
        0x22010111 to "标准", 0x22010113 to "越野", 0x22010114 to "经济+", 0x22010115 to "运动+",
        0x22010140 to "自定义", 0x22010141 to "湿滑", 0x22010142 to "草地碎石", 0x22010143 to "深雪",
        0x22010144 to "山地", 0x22010145 to "涉水", 0x22010146 to "防晕车", 0x22010147 to "自适应",
        0x22010148 to "运动+", 0x22010149 to "后排舒适",
    )

    private val SUSPENSION_HEIGHT = mapOf(
        0x20190101 to "高 2", 0x20190102 to "高 1", 0x20190103 to "标准", 0x20190104 to "低 1",
        0x20190105 to "低 2", 0x20190106 to "低 3", 0x20190107 to "高 3",
    )

    private val SUSPENSION_DAMPING = mapOf(
        0x22036601 to "舒适", 0x22036602 to "标准", 0x22036603 to "动态", 0x22036604 to "越野",
        0x22036605 to "赛道", 0x22036606 to "越野 2", 0x22036607 to "动态+",
        0x22030201 to "标准", 0x22030202 to "舒适", 0x22030203 to "运动", 0x22030204 to "越野",
        0x22030205 to "雪地", 0x22030206 to "自动",
    )
}
