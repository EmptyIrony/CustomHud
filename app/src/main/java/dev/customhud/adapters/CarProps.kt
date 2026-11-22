package dev.customhud.adapters

import dev.customhud.data.Gear

/**
 * Standard android.car VehiclePropertyIds used by the HUD. Values copied from
 * `platforms/android-34/optional/android.car.jar` (javap -constants), not hand-assembled.
 */
object CarProps {
    const val PERF_VEHICLE_SPEED = 291504647 // 0x11600207 float m/s
    const val PERF_VEHICLE_SPEED_DISPLAY = 291504648 // 0x11600208 float m/s
    const val GEAR_SELECTION = 289408000 // 0x11400400 int VehicleGear
    const val CURRENT_GEAR = 289408001 // 0x11400401 int VehicleGear
    const val IGNITION_STATE = 289408009 // 0x11400409 int
    const val TURN_SIGNAL_STATE = 289408008 // 0x11400408 int 0 none / 1 right / 2 left
    const val HAZARD_LIGHTS_STATE = 289410563 // 0x11400E03 int 0 off / 1 on
    const val EV_BATTERY_LEVEL = 291504905 // 0x11600309 float Wh
    const val INFO_EV_BATTERY_CAPACITY = 291504390 // 0x11600106 float Wh
    const val EV_CURRENT_BATTERY_CAPACITY = 291504909 // 0x1160030D float Wh (Android 14+)
    const val RANGE_REMAINING = 291504904 // 0x11600308 float m
    const val EV_CHARGE_PORT_CONNECTED = 287310603 // 0x1120030B boolean
    const val EV_BATTERY_INSTANTANEOUS_CHARGE_RATE = 291504908 // 0x1160030C float mW, + = charging
    const val EV_CHARGE_STATE = 289410881 // 0x11400F41 int (Android 13+)
    const val TIRE_PRESSURE = 392168201 // 0x17600309 float kPa, wheel areas
    const val DOOR_LOCK = 371198722 // 0x16200B02 boolean, door areas
    const val ENV_OUTSIDE_TEMPERATURE = 291505923 // 0x11600703 float °C

    const val WHEEL_LEFT_FRONT = 1
    const val WHEEL_RIGHT_FRONT = 2
    const val WHEEL_LEFT_REAR = 4
    const val WHEEL_RIGHT_REAR = 8

    /** Every standard property the adapter may read, with a readable name for reports. */
    val CANDIDATES: Map<Int, String> = linkedMapOf(
        PERF_VEHICLE_SPEED to "PERF_VEHICLE_SPEED",
        PERF_VEHICLE_SPEED_DISPLAY to "PERF_VEHICLE_SPEED_DISPLAY",
        GEAR_SELECTION to "GEAR_SELECTION",
        CURRENT_GEAR to "CURRENT_GEAR",
        IGNITION_STATE to "IGNITION_STATE",
        TURN_SIGNAL_STATE to "TURN_SIGNAL_STATE",
        HAZARD_LIGHTS_STATE to "HAZARD_LIGHTS_STATE",
        EV_BATTERY_LEVEL to "EV_BATTERY_LEVEL",
        INFO_EV_BATTERY_CAPACITY to "INFO_EV_BATTERY_CAPACITY",
        EV_CURRENT_BATTERY_CAPACITY to "EV_CURRENT_BATTERY_CAPACITY",
        RANGE_REMAINING to "RANGE_REMAINING",
        EV_CHARGE_PORT_CONNECTED to "EV_CHARGE_PORT_CONNECTED",
        EV_BATTERY_INSTANTANEOUS_CHARGE_RATE to "EV_BATTERY_INSTANTANEOUS_CHARGE_RATE",
        EV_CHARGE_STATE to "EV_CHARGE_STATE",
        TIRE_PRESSURE to "TIRE_PRESSURE",
        DOOR_LOCK to "DOOR_LOCK",
        ENV_OUTSIDE_TEMPERATURE to "ENV_OUTSIDE_TEMPERATURE",
    )

    fun name(id: Int): String = CANDIDATES[id] ?: "0x${id.toString(16)}"

    /** Permissions that guard the candidates (system UID passes all of them). */
    val PERMISSIONS = listOf(
        "android.car.permission.CAR_SPEED",
        "android.car.permission.CAR_POWERTRAIN",
        "android.car.permission.CAR_INFO",
        "android.car.permission.CAR_ENERGY",
        "android.car.permission.CAR_ENERGY_PORTS",
        "android.car.permission.CAR_EXTERIOR_LIGHTS",
        "android.car.permission.CAR_TIRES",
        "android.car.permission.CONTROL_CAR_DOORS",
        "android.car.permission.CAR_EXTERIOR_ENVIRONMENT",
    )

    /** Runtime (dangerous) ones that a normal-UID install has to request. */
    val RUNTIME_PERMISSIONS = arrayOf("android.car.permission.CAR_SPEED", "android.car.permission.CAR_ENERGY")
}

/** Pure conversions from standard VHAL units; kept free of Android types for unit tests. */
object CarMapping {
    /** VehicleGear bit values. Numbered manual gears are left unmapped on purpose. */
    fun gear(raw: Int): Gear? = when (raw) {
        1 -> Gear.NEUTRAL
        2 -> Gear.REVERSE
        4 -> Gear.PARK
        8 -> Gear.DRIVE
        else -> null
    }

    fun speedKmh(metersPerSecond: Float): Double = metersPerSecond * 3.6

    /** EV_BATTERY_LEVEL / capacity (both Wh). Null when the pair cannot give a sane percentage. */
    fun batteryPercent(levelWh: Float, capacityWh: Float): Double? {
        if (!levelWh.isFinite() || !capacityWh.isFinite() || capacityWh <= 0f || levelWh < 0f) return null
        val percent = levelWh / capacityWh * 100.0
        return if (percent > 100.5) null else percent.coerceAtMost(100.0)
    }

    fun rangeKm(meters: Float): Double = meters / 1000.0

    /** TURN_SIGNAL_STATE (+ hazard) → (left, right). */
    fun indicators(turnState: Int?, hazardOn: Boolean?): Pair<Boolean?, Boolean?> {
        if (hazardOn == true) return true to true
        if (turnState == null) return null to null
        return (turnState == 2) to (turnState == 1)
    }

    /** Battery power shown as driving-positive kW: VHAL charge rate is mW with + = charging. */
    fun powerKw(chargeRateMw: Float): Double = -chargeRateMw / 1_000_000.0

    fun charging(chargeState: Int?, portConnected: Boolean?, chargeRateMw: Float?): Boolean? = when {
        chargeState != null && chargeState != 0 -> chargeState == 1
        portConnected != null -> portConnected && (chargeRateMw == null || chargeRateMw > 0f)
        else -> null
    }

    fun ignition(raw: Int): String? = when (raw) {
        1 -> "LOCK"
        2 -> "OFF"
        3 -> "ACC"
        4 -> "ON"
        5 -> "START"
        else -> null
    }

    fun kpaToBar(kpa: Float): Double = kpa / 100.0
}
