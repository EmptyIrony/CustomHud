package dev.customhud.camera

import dev.customhud.adapters.EcarxGear
import dev.customhud.data.DataValue
import dev.customhud.data.Gear
import dev.customhud.data.SignalUnit
import dev.customhud.data.TimestampBasis
import dev.customhud.data.Validity
import dev.customhud.data.VehicleSignal
import dev.customhud.data.VehicleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RearCameraPolicyTest {
    private fun gear(g: Gear) = GearReading(g, RearCameraPolicy.ECARX, "test")
    private val none = GearReading.unavailable(RearCameraPolicy.ECARX, "未连接")

    @Test
    fun defaultModeIsNotParkAndUnknownNamesFallBack() {
        assertEquals(RearCameraMode.NOT_PARK, RearCameraMode.DEFAULT)
        assertEquals(RearCameraMode.NOT_PARK, RearCameraMode.parse(null))
        assertEquals(RearCameraMode.NOT_PARK, RearCameraMode.parse("bogus"))
        assertEquals(RearCameraMode.REVERSE_ONLY, RearCameraMode.parse("REVERSE_ONLY"))
    }

    @Test
    fun alwaysShowsRegardlessOfGear() {
        assertEquals(ModeDecision.Show, RearCameraPolicy.decide(RearCameraMode.ALWAYS, true, none))
        assertEquals(ModeDecision.Show, RearCameraPolicy.decide(RearCameraMode.ALWAYS, true, gear(Gear.PARK)))
    }

    @Test
    fun notParkHidesInParkAndShowsInEveryOtherKnownGear() {
        assertTrue(RearCameraPolicy.decide(RearCameraMode.NOT_PARK, true, gear(Gear.PARK)) is ModeDecision.Hide)
        listOf(Gear.REVERSE, Gear.NEUTRAL, Gear.DRIVE, Gear.LOW).forEach {
            assertEquals(it.name, ModeDecision.Show, RearCameraPolicy.decide(RearCameraMode.NOT_PARK, true, gear(it)))
        }
    }

    @Test
    fun reverseOnlyShowsOnlyInReverse() {
        assertEquals(ModeDecision.Show, RearCameraPolicy.decide(RearCameraMode.REVERSE_ONLY, true, gear(Gear.REVERSE)))
        listOf(Gear.PARK, Gear.NEUTRAL, Gear.DRIVE).forEach {
            assertTrue(it.name, RearCameraPolicy.decide(RearCameraMode.REVERSE_ONLY, true, gear(it)) is ModeDecision.Hide)
        }
    }

    @Test
    fun missingGearIsUnavailableNeverAssumedPark() {
        val notPark = RearCameraPolicy.decide(RearCameraMode.NOT_PARK, true, none)
        val reverse = RearCameraPolicy.decide(RearCameraMode.REVERSE_ONLY, true, GearReading(Gear.UNKNOWN, "x", "y"))
        assertTrue(notPark is ModeDecision.Unavailable)
        assertTrue(reverse is ModeDecision.Unavailable)
        assertTrue((reverse as ModeDecision.Unavailable).reason.contains("仅 R 挡"))
    }

    @Test
    fun offAndDisabledWidgetHide() {
        assertTrue(RearCameraPolicy.decide(RearCameraMode.OFF, true, gear(Gear.REVERSE)) is ModeDecision.Hide)
        assertTrue(RearCameraPolicy.decide(RearCameraMode.ALWAYS, false, gear(Gear.REVERSE)) is ModeDecision.Hide)
    }

    @Test
    fun backoffIs2510ThenEvery30Seconds() {
        assertEquals(listOf(2_000L, 5_000L, 10_000L, 30_000L, 30_000L, 30_000L), (0..5).map { RearCameraPolicy.retryDelay(it) })
    }

    @Test
    fun androidCarSnapshotNeedsKnownFreshMappedValue() {
        fun snap(value: Gear?, validity: Validity = Validity.KNOWN, receivedAt: Long = 1_000) = VehicleSnapshot(
            values = mapOf(VehicleSignal.GEAR to DataValue(value, validity, receivedAt, receivedAt, TimestampBasis.DEVICE_SAMPLE,
                SignalUnit.ENUM, "android.car:GEAR_SELECTION", 1, 5_000)),
        )
        assertEquals(Gear.DRIVE, RearCameraPolicy.gearFromSnapshot(snap(Gear.DRIVE), 1_500).gear)
        assertFalse(RearCameraPolicy.gearFromSnapshot(snap(Gear.DRIVE), 7_000).available) // stale
        assertFalse(RearCameraPolicy.gearFromSnapshot(snap(Gear.UNKNOWN), 1_500).available)
        assertFalse(RearCameraPolicy.gearFromSnapshot(snap(null, Validity.UNAVAILABLE), 1_500).available)
        assertFalse(RearCameraPolicy.gearFromSnapshot(VehicleSnapshot(), 1_500).available)
    }

    @Test
    fun trackerPrefersEcarxAndFallsBackToFreshAndroidCar() {
        val tracker = GearTracker()
        assertFalse(tracker.effective(0).available)
        tracker.updateAndroidCar(GearReading(Gear.PARK, RearCameraPolicy.ANDROID_CAR, "GEAR_SELECTION"), 1_000)
        assertEquals(Gear.PARK, tracker.effective(2_000).gear)
        tracker.updateEcarx(GearReading(Gear.REVERSE, RearCameraPolicy.ECARX, "事件"))
        assertEquals(Gear.REVERSE, tracker.effective(2_000).gear)
        tracker.updateEcarx(GearReading.unavailable(RearCameraPolicy.ECARX, "已断开"))
        assertEquals(Gear.PARK, tracker.effective(5_000).gear)
        val stale = tracker.effective(6_001)
        assertFalse(stale.available)
        assertTrue(stale.detail.contains("数据过期"))
    }

    @Test
    fun ecarxMappingMatchesCarLight() {
        assertEquals(Gear.PARK, EcarxGear.gearOf(0x200230))
        assertEquals(Gear.REVERSE, EcarxGear.gearOf(0x200240))
        assertEquals(Gear.NEUTRAL, EcarxGear.gearOf(0x200210))
        assertEquals(Gear.DRIVE, EcarxGear.gearOf(0x200220))
        assertEquals(Gear.DRIVE, EcarxGear.gearOf(0x200205))
        assertNull(EcarxGear.gearOf(0x200250))
        assertNull(EcarxGear.gearOf(null))
    }
}
