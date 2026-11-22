package dev.customhud.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VehicleSnapshotTest {
    @Test
    fun olderSampleCannotOverwriteNewerSample() {
        val old = value(20.0, observed = 100, received = 110)
        val new = value(30.0, observed = 200, received = 210)
        val newerSnapshot = VehicleSnapshot(mapOf(VehicleSignal.SPEED to new), generation = 1)
        val lateSnapshot = VehicleSnapshot(mapOf(VehicleSignal.SPEED to old), generation = 1)
        val merged = VehicleSnapshots.rejectOlder(newerSnapshot, lateSnapshot)
        assertEquals(30.0, merged.value(VehicleSignal.SPEED)?.value)
    }

    @Test
    fun readCompletionIsNotDeviceSampleAndMissingSpeedIsNotZero() {
        val value = DataValue<Double>(
            value = null,
            validity = Validity.UNAVAILABLE,
            observedAtMonotonic = null,
            receivedAtMonotonic = 50,
            timestampBasis = TimestampBasis.READ_COMPLETION,
            unit = SignalUnit.KMH,
            source = "test",
            connectionGeneration = 1,
            freshnessBudgetMs = 1000,
        )
        assertNull(value.value)
        assertNull(value.observedAtMonotonic)
        assertEquals(TimestampBasis.READ_COMPLETION, value.timestampBasis)
    }

    private fun value(number: Double, observed: Long, received: Long) = DataValue(
        value = number,
        validity = Validity.KNOWN,
        observedAtMonotonic = observed,
        receivedAtMonotonic = received,
        timestampBasis = TimestampBasis.DEVICE_SAMPLE,
        unit = SignalUnit.KMH,
        source = "test",
        connectionGeneration = 1,
        freshnessBudgetMs = 1000,
    )
}
