package dev.customhud.data

enum class Validity { KNOWN, UNKNOWN, STALE, UNAVAILABLE, ERROR }
enum class TimestampBasis { DEVICE_SAMPLE, CALLBACK_RECEIPT, READ_COMPLETION, UNKNOWN }
enum class SignalUnit { KMH, PERCENT, KM, CELSIUS, BOOLEAN, ENUM, MPS, KILOWATT, UNVERIFIED, NONE }
enum class ConnectionState { UNAVAILABLE, CONNECTING, CONNECTED, DISCONNECTED, ERROR }

enum class VehicleSignal(val unit: SignalUnit) {
    SPEED(SignalUnit.KMH),
    GEAR(SignalUnit.ENUM),
    POWER_STATE(SignalUnit.ENUM),
    BATTERY_PERCENT(SignalUnit.PERCENT),
    RANGE(SignalUnit.KM),
    BATTERY_TEMPERATURE(SignalUnit.CELSIUS),
    CHARGING(SignalUnit.BOOLEAN),
    VOLTAGE(SignalUnit.UNVERIFIED),
    CURRENT(SignalUnit.UNVERIFIED),
    TIME_REMAINING(SignalUnit.UNVERIFIED),
    TIRE_PRESSURE_FL(SignalUnit.UNVERIFIED),
    TIRE_PRESSURE_FR(SignalUnit.UNVERIFIED),
    TIRE_PRESSURE_RL(SignalUnit.UNVERIFIED),
    TIRE_PRESSURE_RR(SignalUnit.UNVERIFIED),
    TIRE_TEMPERATURE_FL(SignalUnit.CELSIUS),
    TIRE_TEMPERATURE_FR(SignalUnit.CELSIUS),
    TIRE_TEMPERATURE_RL(SignalUnit.CELSIUS),
    TIRE_TEMPERATURE_RR(SignalUnit.CELSIUS),
    MOTOR_TEMPERATURE_FRONT(SignalUnit.CELSIUS),
    MOTOR_TEMPERATURE_REAR(SignalUnit.CELSIUS),
    MOTOR_ACTIVITY(SignalUnit.ENUM),
    MOTOR_FAULT(SignalUnit.ENUM),
    ACCELERATOR(SignalUnit.UNVERIFIED),
    BRAKE(SignalUnit.UNVERIFIED),
    ACCELERATION_LONGITUDINAL(SignalUnit.UNVERIFIED),
    ACCELERATION_LATERAL(SignalUnit.UNVERIFIED),
    POWER(SignalUnit.KILOWATT),
    LEFT_INDICATOR(SignalUnit.BOOLEAN),
    RIGHT_INDICATOR(SignalUnit.BOOLEAN),
    HAZARD(SignalUnit.BOOLEAN),
    LOCKED(SignalUnit.BOOLEAN),
    DRIVE_MODE(SignalUnit.ENUM),
    SUSPENSION_HEIGHT(SignalUnit.ENUM),
    SUSPENSION_STIFFNESS(SignalUnit.ENUM),
}

data class DataError(val code: String, val message: String, val causeType: String? = null)

data class DataValue<T>(
    val value: T?,
    val validity: Validity,
    val observedAtMonotonic: Long?,
    val receivedAtMonotonic: Long,
    val timestampBasis: TimestampBasis,
    val unit: SignalUnit,
    val source: String,
    val connectionGeneration: Long,
    val freshnessBudgetMs: Long?,
    val error: DataError? = null,
) {
    init {
        require(freshnessBudgetMs == null || freshnessBudgetMs > 0)
    }

    fun isFresh(nowMonotonic: Long): Boolean = validity == Validity.KNOWN &&
        freshnessBudgetMs != null && nowMonotonic - receivedAtMonotonic <= freshnessBudgetMs

    companion object {
        fun <T> unavailable(
            signal: VehicleSignal,
            now: Long,
            generation: Long,
            source: String,
            code: String = "NOT_SUPPORTED",
        ): DataValue<T> = DataValue(
            value = null,
            validity = Validity.UNAVAILABLE,
            observedAtMonotonic = null,
            receivedAtMonotonic = now,
            timestampBasis = TimestampBasis.UNKNOWN,
            unit = signal.unit,
            source = source,
            connectionGeneration = generation,
            freshnessBudgetMs = null,
            error = DataError(code, "Signal unavailable"),
        )
    }
}

data class VehicleSnapshot(
    val values: Map<VehicleSignal, DataValue<*>> = emptyMap(),
    val connection: ConnectionState = ConnectionState.UNAVAILABLE,
    val generation: Long = 0,
    val profile: String = "none",
) {
    fun value(signal: VehicleSignal): DataValue<*>? = values[signal]
}

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, LOW, UNKNOWN }

data class SignalCapability(
    val status: CapabilityStatus,
    val reason: String,
    val checkedAtMonotonic: Long,
    val profile: String,
)

enum class CapabilityStatus { AVAILABLE, DENIED, MISSING_API, NOT_READY, UNSUPPORTED, UNKNOWN }

interface VehicleDataSource {
    val connection: ConnectionState
    val generation: Long
    fun open(profile: VehicleProfile, mode: DataMode, observer: (VehicleSnapshot) -> Unit): Long
    fun capabilities(): Map<VehicleSignal, SignalCapability>
    fun snapshot(): VehicleSnapshot
    fun close(): DataCloseResult
}

enum class DataMode { PREVIEW, DEVICE }
data class VehicleProfile(
    val name: String,
    val freshnessBudgetsMs: Map<VehicleSignal, Long> = emptyMap(),
)
data class DataCloseResult(val status: CloseStatus, val pendingResources: Int, val error: DataError? = null)
enum class CloseStatus { COMPLETE, PARTIAL, TIMEOUT }

object VehicleSnapshots {
    fun rejectOlder(previous: VehicleSnapshot, incoming: VehicleSnapshot): VehicleSnapshot {
        if (incoming.generation != previous.generation) return incoming
        val merged = previous.values.toMutableMap()
        incoming.values.forEach { (signal, next) ->
            val old = merged[signal]
            val oldTime = old?.observedAtMonotonic ?: old?.receivedAtMonotonic ?: Long.MIN_VALUE
            val nextTime = next.observedAtMonotonic ?: next.receivedAtMonotonic
            if (old == null || nextTime >= oldTime) merged[signal] = next
        }
        return previous.copy(values = merged, connection = incoming.connection, profile = incoming.profile)
    }
}
