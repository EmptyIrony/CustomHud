package dev.customhud.adapters

import dev.customhud.data.Gear
import dev.customhud.data.Validity
import dev.customhud.data.VehicleSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EcarxSnapshotBuilderTest {
    private class FakeReads(
        val floats: MutableMap<Int, Float> = mutableMapOf(),
        val events: MutableMap<Int, Int> = mutableMapOf(),
        val functions: MutableMap<Int, Int> = mutableMapOf(),
    ) : EcarxReads {
        override fun sensorFloat(id: Int): Float? = floats[id]
        override fun sensorEvent(id: Int): Int? = events[id]
        override fun functionIntAny(id: Int): Int? = functions[id]
        override fun functionFloat(id: Int): Float? = null
    }

    @Test
    fun everySignalIsCovered() {
        val b = EcarxSnapshotBuilder()
        val r = FakeReads()
        val all = b.slow(r, 0, 1) + b.fast(r, 0, 1)
        assertEquals(VehicleSignal.entries.toSet(), all.keys)
    }

    @Test
    fun verifiedSignalsAreKnownWithAppUnits() {
        val r = FakeReads(
            floats = mutableMapOf(EcarxIds.SENSOR_SPEED to 20f, EcarxIds.SENSOR_SOC to 81f, EcarxIds.SENSOR_RANGE to 402f,
                EcarxIds.SENSOR_TIRE_PRESSURE[0] to 251f),
            events = mutableMapOf(EcarxIds.SENSOR_GEAR to 0x200240),
            functions = mutableMapOf(EcarxIds.FUNC_CENTRAL_LOCK to 1, EcarxIds.FUNC_DRIVE_MODE to 0x22010102),
        )
        val b = EcarxSnapshotBuilder()
        val v = b.slow(r, 100, 7) + b.fast(r, 100, 7)
        assertEquals(72.0, v[VehicleSignal.SPEED]!!.value as Double, 1e-4)
        assertEquals(Gear.REVERSE, v[VehicleSignal.GEAR]!!.value)
        assertEquals(81.0, v[VehicleSignal.BATTERY_PERCENT]!!.value as Double, 1e-6)
        assertEquals(402.0, v[VehicleSignal.RANGE]!!.value as Double, 1e-6)
        assertEquals(2.51, v[VehicleSignal.TIRE_PRESSURE_FL]!!.value as Double, 1e-6)
        assertEquals(true, v[VehicleSignal.LOCKED]!!.value)
        assertEquals("舒适", v[VehicleSignal.DRIVE_MODE]!!.value)
        assertTrue(v[VehicleSignal.SPEED]!!.isFresh(100))
        assertEquals(7L, v[VehicleSignal.SPEED]!!.connectionGeneration)
    }

    @Test
    fun socFallsBackToLegacySensor() {
        val r = FakeReads(floats = mutableMapOf(EcarxIds.SENSOR_SOC to -1f, EcarxIds.SENSOR_SOC_LEGACY to 55f))
        assertEquals(55.0, EcarxSnapshotBuilder().slow(r, 0, 1)[VehicleSignal.BATTERY_PERCENT]!!.value as Double, 1e-6)
    }

    @Test
    fun failedOrInvalidReadsAreUnavailableNotZero() {
        val r = FakeReads(floats = mutableMapOf(EcarxIds.SENSOR_SPEED to Float.NaN), events = mutableMapOf(EcarxIds.SENSOR_GEAR to 0x200299))
        val v = EcarxSnapshotBuilder().fast(r, 0, 1)
        assertNull(v[VehicleSignal.SPEED]!!.value)
        assertEquals(Validity.UNAVAILABLE, v[VehicleSignal.SPEED]!!.validity)
        assertNull(v[VehicleSignal.GEAR]!!.value)
        assertEquals("ENUM_UNMAPPED", v[VehicleSignal.GEAR]!!.error!!.code)
        assertNull(v[VehicleSignal.LEFT_INDICATOR]!!.value)
    }

    @Test
    fun unverifiedCandidatesCarryRawValueButNoValue() {
        val r = FakeReads(floats = mutableMapOf(EcarxIds.SENSOR_BATTERY_TEMP to 27.5f))
        val temp = EcarxSnapshotBuilder().slow(r, 0, 1)[VehicleSignal.BATTERY_TEMPERATURE]!!
        assertNull(temp.value)
        assertEquals(Validity.UNKNOWN, temp.validity)
        assertEquals(EcarxSnapshotBuilder.UNVERIFIED, temp.error!!.code)
        assertTrue(temp.error!!.message, temp.error!!.message.contains("27.5"))
    }

    @Test
    fun turnSignalHoldsThroughShortOffAndDropsAfter() {
        val r = FakeReads(functions = mutableMapOf(EcarxIds.FUNC_LEFT_TURN to 1))
        val b = EcarxSnapshotBuilder(turnHoldMs = 800)
        assertEquals(true, b.fast(r, 1_000, 1)[VehicleSignal.LEFT_INDICATOR]!!.value)
        r.functions[EcarxIds.FUNC_LEFT_TURN] = 0
        assertEquals(true, b.fast(r, 1_400, 1)[VehicleSignal.LEFT_INDICATOR]!!.value)
        assertEquals(true, b.fast(r, 1_800, 1)[VehicleSignal.LEFT_INDICATOR]!!.value)
        assertEquals(false, b.fast(r, 1_900, 1)[VehicleSignal.LEFT_INDICATOR]!!.value)
        assertEquals(false, b.fast(r, 1_900, 1)[VehicleSignal.RIGHT_INDICATOR]?.value ?: false)
    }

    @Test
    fun unavailableAllMarksEverySignal() {
        val all = EcarxSnapshotBuilder().unavailableAll(0, 3, "DISCONNECTED", "x")
        assertEquals(VehicleSignal.entries.size, all.size)
        assertTrue(all.values.all { it.value == null && it.error?.code == "DISCONNECTED" })
    }
}
