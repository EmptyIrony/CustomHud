package dev.customhud.adapters

import dev.customhud.data.Gear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarMappingTest {
    @Test
    fun propertyIdsMatchPlatformEncoding() {
        // group SYSTEM 0x10000000 | area | type | id, cross-checked with the android.car.jar constants.
        assertEquals(0x11400400, CarProps.GEAR_SELECTION)
        assertEquals(0x11600207, CarProps.PERF_VEHICLE_SPEED)
        assertEquals(0x11600309, CarProps.EV_BATTERY_LEVEL)
        assertEquals(0x11400408, CarProps.TURN_SIGNAL_STATE)
        assertEquals(0x17600309, CarProps.TIRE_PRESSURE)
        assertEquals(0x11400F41, CarProps.EV_CHARGE_STATE)
    }

    @Test
    fun gearUsesVehicleGearBitsAndLeavesNumberedGearsUnmapped() {
        assertEquals(Gear.PARK, CarMapping.gear(4))
        assertEquals(Gear.DRIVE, CarMapping.gear(8))
        assertEquals(Gear.REVERSE, CarMapping.gear(2))
        assertEquals(Gear.NEUTRAL, CarMapping.gear(1))
        assertNull(CarMapping.gear(0x400)) // GEAR_SEVENTH, not LOW
        assertNull(CarMapping.gear(0))
    }

    @Test
    fun batteryPercentNeedsSaneCapacity() {
        assertEquals(76.0, CarMapping.batteryPercent(76_000f, 100_000f)!!, 1e-6)
        assertNull(CarMapping.batteryPercent(50f, 0f))
        assertNull(CarMapping.batteryPercent(120_000f, 100_000f))
        assertEquals(100.0, CarMapping.batteryPercent(100_300f, 100_000f)!!, 1e-6)
    }

    @Test
    fun indicatorsAndHazard() {
        assertEquals(false to true, CarMapping.indicators(1, false))
        assertEquals(true to false, CarMapping.indicators(2, null))
        assertEquals(true to true, CarMapping.indicators(0, true))
        assertEquals(null to null, CarMapping.indicators(null, false))
    }

    @Test
    fun unitsAndCharging() {
        assertEquals(36.0, CarMapping.speedKmh(10f), 1e-4)
        assertEquals(12.5, CarMapping.powerKw(-12_500_000f), 1e-6)
        assertEquals(2.5, CarMapping.kpaToBar(250f), 1e-6)
        assertEquals(true, CarMapping.charging(1, null, null))
        assertEquals(false, CarMapping.charging(3, true, 5f))
        assertEquals(false, CarMapping.charging(null, true, -100f))
        assertEquals(true, CarMapping.charging(null, true, null))
        assertNull(CarMapping.charging(null, null, 10f))
    }
}
