package dev.customhud.renderer

import dev.customhud.data.VehicleSignal
import dev.customhud.data.VehicleSnapshot
import dev.customhud.layout.LayoutSnapshot
import dev.customhud.layout.WidgetType
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The part of a vehicle snapshot the HUD actually shows, at display precision. Two snapshots with
 * the same key produce the same pixels, so the frame can be skipped (timestamps, unused signals
 * and sub-display-precision jitter never cause a redraw).
 */
object RenderKey {
    fun of(layout: LayoutSnapshot, snapshot: VehicleSnapshot): Map<VehicleSignal, Any?> {
        val used = layout.document.widgets.filter { it.enabled }.flatMap { WIDGET_SIGNALS[it.type].orEmpty() }
        return used.associateWith { signal ->
            when (val value = snapshot.value(signal)?.value) {
                is Number -> formatNumber(value, DECIMALS[signal] ?: 1)
                else -> value
            }
        }
    }

    fun formatNumber(number: Number, decimals: Int): String =
        if (decimals == 0) number.toDouble().roundToInt().toString() else String.format(Locale.US, "%.${decimals}f", number.toDouble())

    /** Vehicle signals each widget draws; only these can trigger a redraw. */
    val WIDGET_SIGNALS: Map<WidgetType, List<VehicleSignal>> = mapOf(
        WidgetType.BATTERY to listOf(VehicleSignal.BATTERY_PERCENT),
        WidgetType.RANGE to listOf(VehicleSignal.RANGE),
        WidgetType.TEMP to listOf(VehicleSignal.BATTERY_TEMPERATURE),
        WidgetType.DRIVE_MODE to listOf(VehicleSignal.DRIVE_MODE),
        WidgetType.SUSP_HEIGHT to listOf(VehicleSignal.SUSPENSION_HEIGHT),
        WidgetType.SUSP_STIFF to listOf(VehicleSignal.SUSPENSION_STIFFNESS),
        WidgetType.POWER_METER to listOf(VehicleSignal.POWER),
        WidgetType.CHARGE to listOf(VehicleSignal.CHARGING),
        WidgetType.TURN to listOf(VehicleSignal.LEFT_INDICATOR, VehicleSignal.RIGHT_INDICATOR, VehicleSignal.HAZARD),
        WidgetType.TIRES to listOf(
            VehicleSignal.TIRE_PRESSURE_FL, VehicleSignal.TIRE_PRESSURE_FR,
            VehicleSignal.TIRE_PRESSURE_RL, VehicleSignal.TIRE_PRESSURE_RR,
        ),
    )

    val DECIMALS: Map<VehicleSignal, Int> = mapOf(
        VehicleSignal.BATTERY_PERCENT to 0,
        VehicleSignal.RANGE to 0,
        VehicleSignal.POWER to 0,
        VehicleSignal.BATTERY_TEMPERATURE to 1,
    )
}
