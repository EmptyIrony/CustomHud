package dev.customhud.data

import android.os.SystemClock

class MockVehicleDataSource : VehicleDataSource {
    override var connection: ConnectionState = ConnectionState.UNAVAILABLE
        private set
    override var generation: Long = 0
        private set
    private var current = VehicleSnapshot()

    override fun open(profile: VehicleProfile, mode: DataMode, observer: (VehicleSnapshot) -> Unit): Long {
        check(mode == DataMode.PREVIEW) { "Mock data is forbidden in DEVICE mode" }
        generation += 1
        connection = ConnectionState.CONNECTED
        val now = SystemClock.elapsedRealtime()
        fun <T : Any> value(signal: VehicleSignal, value: T): DataValue<T> = DataValue(
            value = value,
            validity = Validity.KNOWN,
            observedAtMonotonic = now,
            receivedAtMonotonic = now,
            timestampBasis = TimestampBasis.DEVICE_SAMPLE,
            unit = signal.unit,
            source = "explicit-preview-mock",
            connectionGeneration = generation,
            freshnessBudgetMs = Long.MAX_VALUE,
        )
        current = VehicleSnapshot(
            values = mapOf(
                VehicleSignal.SPEED to value(VehicleSignal.SPEED, 42.0),
                VehicleSignal.GEAR to value(VehicleSignal.GEAR, Gear.DRIVE),
                VehicleSignal.BATTERY_PERCENT to value(VehicleSignal.BATTERY_PERCENT, 76.0),
                VehicleSignal.RANGE to value(VehicleSignal.RANGE, 385.0),
                VehicleSignal.BATTERY_TEMPERATURE to value(VehicleSignal.BATTERY_TEMPERATURE, 27.5),
                VehicleSignal.CHARGING to value(VehicleSignal.CHARGING, false),
                VehicleSignal.DRIVE_MODE to value(VehicleSignal.DRIVE_MODE, "COMFORT"),
                VehicleSignal.LEFT_INDICATOR to value(VehicleSignal.LEFT_INDICATOR, false),
                VehicleSignal.RIGHT_INDICATOR to value(VehicleSignal.RIGHT_INDICATOR, false),
            ),
            connection = connection,
            generation = generation,
            profile = profile.name,
        )
        observer(current)
        return generation
    }

    override fun capabilities(): Map<VehicleSignal, SignalCapability> = VehicleSignal.entries.associateWith {
        SignalCapability(CapabilityStatus.AVAILABLE, "Explicit simulated value", 0, "preview")
    }

    override fun snapshot(): VehicleSnapshot = current

    override fun close(): DataCloseResult {
        connection = ConnectionState.DISCONNECTED
        return DataCloseResult(CloseStatus.COMPLETE, 0)
    }
}
