package dev.customhud.adapters

/**
 * ECARX AdaptAPI ids used for vehicle data, from CarLight's `EcarxVehicle` / docs/vehicle-interfaces.md §27.
 * [VERIFIED] ones were confirmed on the car by the owner (2026-11-07); the rest are read for the probe
 * page only (raw value, no claimed unit or meaning) until a photo confirms them.
 */
object EcarxIds {
    // ---- ISensor, verified
    const val SENSOR_SPEED = 0x100100           // m/s; ×3.6 matches the cluster
    const val SENSOR_GEAR = EcarxGear.SENSOR_GEAR // 0x200200
    const val SENSOR_SOC = 0x404000             // IVirtualSensor.TYPE_EV_BATTERY_PERCENTAGE
    const val SENSOR_SOC_LEGACY = 0x100A00      // SENSOR_TYPE_EV_BATTERY_LEVEL, fallback
    const val SENSOR_RANGE = 0x100800           // general range (the one the cluster matches), km
    const val SENSOR_ODOMETER = 0x100700        // km
    const val SENSOR_ACCELERATOR = 0x101400     // 0..100
    const val SENSOR_BRAKE = 0x101300           // 0..30 observed, not a percentage
    const val SENSOR_ACCEL_LON = 0x102D00       // m/s², ÷9.8 = g
    const val SENSOR_ACCEL_LAT = 0x102E00
    /** Tyre pressure FL, FR, RL, RR; raw ÷ 100 = bar. */
    val SENSOR_TIRE_PRESSURE = intArrayOf(0x500100, 0x500200, 0x500300, 0x500400)
    /** Tyre temperature FL, FR, RL, RR; °C. */
    val SENSOR_TIRE_TEMP = intArrayOf(0x500500, 0x500600, 0x500700, 0x500800)

    // ---- ICarFunction, verified (SDK IBcm names)
    const val FUNC_LEFT_TURN = 0x21051100
    const val FUNC_RIGHT_TURN = 0x21051200
    const val FUNC_HAZARD = 0x21050F00
    const val FUNC_CENTRAL_LOCK = 0x20100900    // 1 locked, 0 unlocked
    const val FUNC_POWER_FLOW = 0x24010100      // front / rear motor IDLE / DRIVE / REGEN
    const val FUNC_DRIVE_MODE = 0x22010100      // DRIVE_MODE_SELECTION_*
    /** Suspension height sources in zHUD's priority order; first valid wins. */
    val FUNC_SUSPENSION_HEIGHT = intArrayOf(0x20190100, 0x22036700, 0x2208BA00)
    /** Suspension damping / mode sources in zHUD's priority order. */
    val FUNC_SUSPENSION_DAMPING = intArrayOf(0x20311F00, 0x22036600, 0x22030200)

    // ---- not verified on the car: raw only
    const val SENSOR_IGNITION = 0x200100        // deprecated in the SDK
    const val SENSOR_BATTERY_TEMP = 0x102A00
    const val SENSOR_MOTOR_TEMP_FRONT = 0x102B00
    const val SENSOR_MOTOR_TEMP_REAR = 0x102F00
    const val SENSOR_EV_BATTERY_STATE = 0x201500
    const val SENSOR_DISCHARGE_POWER = 0x103600 // SENSOR_TYPE_DCHA_PWR_ACT, unit unknown
    const val FUNC_CHARGE_PLUG_STATE = 0x24130200
    const val FUNC_CHARGE_VOLTAGE = 0x24140100
    const val FUNC_CHARGE_CURRENT = 0x24140200
    const val FUNC_CHARGE_ETA = 0x24140300

    fun hex(id: Int): String = "0x" + Integer.toHexString(id).uppercase()
}
