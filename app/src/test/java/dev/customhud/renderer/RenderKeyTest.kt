package dev.customhud.renderer

import dev.customhud.data.DataValue
import dev.customhud.data.SignalUnit
import dev.customhud.data.TimestampBasis
import dev.customhud.data.Validity
import dev.customhud.data.VehicleSignal
import dev.customhud.data.VehicleSnapshot
import dev.customhud.layout.LayoutDocument
import dev.customhud.layout.LayoutSnapshot
import dev.customhud.layout.WidgetConfig
import dev.customhud.layout.WidgetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RenderKeyTest {
    private val clockAndBattery = LayoutSnapshot(
        LayoutDocument(widgets = listOf(WidgetConfig(WidgetType.CLOCK, true, .5f, .5f, 1f, 0f), WidgetConfig(WidgetType.BATTERY, true, .2f, .5f, 1f, 0f))),
        3,
    )

    @Test
    fun timestampsAndUnusedSignalsDoNotChangeTheKey() {
        val a = snapshot(VehicleSignal.BATTERY_PERCENT to 76.2, VehicleSignal.SPEED to 40.0, received = 100)
        val b = snapshot(VehicleSignal.BATTERY_PERCENT to 76.4, VehicleSignal.SPEED to 55.0, received = 600)
        assertEquals(RenderKey.of(clockAndBattery, a), RenderKey.of(clockAndBattery, b))
    }

    @Test
    fun visibleChangeChangesTheKey() {
        val a = snapshot(VehicleSignal.BATTERY_PERCENT to 76.2, received = 100)
        val b = snapshot(VehicleSignal.BATTERY_PERCENT to 75.4, received = 600)
        assertNotEquals(RenderKey.of(clockAndBattery, a), RenderKey.of(clockAndBattery, b))
    }

    @Test
    fun clockOnlyLayoutIgnoresAllVehicleData() {
        val clock = LayoutSnapshot(LayoutDocument.clockOnly(), 1)
        assertEquals(
            RenderKey.of(clock, snapshot(VehicleSignal.BATTERY_PERCENT to 1.0, received = 1)),
            RenderKey.of(clock, snapshot(VehicleSignal.BATTERY_PERCENT to 99.0, received = 2)),
        )
    }

    private fun snapshot(vararg values: Pair<VehicleSignal, Double>, received: Long) = VehicleSnapshot(
        values = values.associate { (signal, number) ->
            signal to DataValue(number, Validity.KNOWN, received, received, TimestampBasis.DEVICE_SAMPLE, SignalUnit.NONE, "test", 1, 1000)
        },
        generation = 1,
    )
}
