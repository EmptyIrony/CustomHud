package dev.customhud.adapters

import dev.customhud.data.Gear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EcarxDecodeTest {
    @Test
    fun speedIsMetresPerSecondTimes3_6() {
        assertEquals(36.0, EcarxDecode.speedKmh(10f)!!, 1e-4)
        assertNull(EcarxDecode.speedKmh(null))
        assertNull(EcarxDecode.speedKmh(Float.NaN))
        assertNull(EcarxDecode.speedKmh(200f))
    }

    @Test
    fun gearUsesEcarxValuesAndNeverGuessesPark() {
        assertEquals(Gear.PARK, EcarxDecode.gear(0x200230))
        assertEquals(Gear.REVERSE, EcarxDecode.gear(0x200240))
        assertEquals(Gear.NEUTRAL, EcarxDecode.gear(0x200210))
        assertEquals(Gear.DRIVE, EcarxDecode.gear(0x200220))
        assertEquals(Gear.DRIVE, EcarxDecode.gear(0x200205))
        assertEquals(Gear.UNKNOWN, EcarxDecode.gear(0x200250))
        assertNull(EcarxDecode.gear(-1))
        assertNull(EcarxDecode.gear(null))
    }

    @Test
    fun rangesAndScaling() {
        assertEquals(76.5, EcarxDecode.percent(76.5f)!!, 1e-6)
        assertNull(EcarxDecode.percent(101f))
        assertEquals(2.5, EcarxDecode.tirePressureBar(250f)!!, 1e-6)
        assertNull(EcarxDecode.tirePressureBar(0f))
        assertEquals(1.0, EcarxDecode.accelG(9.8f)!!, 1e-6)
        assertNull(EcarxDecode.accelG(80f))
        assertNull(EcarxDecode.rangeKm(-1f))
        assertEquals(35.0, EcarxDecode.tireTempC(35f)!!, 1e-6)
    }

    @Test
    fun onOffOnlyAcceptsZeroAndOne() {
        assertEquals(true, EcarxDecode.onOff(1))
        assertEquals(false, EcarxDecode.onOff(0))
        assertNull(EcarxDecode.onOff(255))
        assertNull(EcarxDecode.onOff(0x21020101))
        assertNull(EcarxDecode.onOff(null))
    }

    @Test
    fun powerFlowFrontRear() {
        assertEquals(MotorActivity(MotorState.DRIVE, MotorState.DRIVE), EcarxDecode.motorActivity(0x24010111))
        assertEquals(MotorActivity(MotorState.IDLE, MotorState.DRIVE), EcarxDecode.motorActivity(0x24010113))
        assertEquals(MotorActivity(MotorState.REGEN, MotorState.REGEN), EcarxDecode.motorActivity(0x24010117))
        assertEquals(MotorActivity(MotorState.IDLE, MotorState.IDLE), EcarxDecode.motorActivity(0x24010114))
        assertNull(EcarxDecode.motorActivity(0x24010104)) // engine only: no front / rear reading
        assertNull(EcarxDecode.motorActivity(255))
        assertEquals("前 待机 · 后 驱动", MotorActivity(MotorState.IDLE, MotorState.DRIVE).toString())
    }

    @Test
    fun labels() {
        assertEquals("经济", EcarxDecode.driveModeLabel(0x22010101))
        assertEquals("自定义", EcarxDecode.driveModeLabel(0x22010140))
        assertEquals("0x22010199", EcarxDecode.driveModeLabel(0x22010199))
        assertEquals("标准", EcarxDecode.suspensionHeightLabel(0x20190103))
        assertEquals("0x2208BA01", EcarxDecode.suspensionHeightLabel(0x2208BA01))
        assertEquals("舒适", EcarxDecode.suspensionDampingLabel(0x22036601))
    }
}
