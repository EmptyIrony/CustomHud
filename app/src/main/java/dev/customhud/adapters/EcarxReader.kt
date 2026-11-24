package dev.customhud.adapters

import android.os.DeadObjectException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/** Raw ECARX reads; implemented by [EcarxReader] on the car and by fakes in tests. */
interface EcarxReads {
    /** ISensor.getSensorLatestValue(id); null on failure or NaN. */
    fun sensorFloat(id: Int): Float?

    /** ISensor.getSensorEvent(id); null on failure. */
    fun sensorEvent(id: Int): Int?

    /** ICarFunction.getFunctionValue with zHUD's zone fallback (none, MIN_VALUE, 0, 1). */
    fun functionIntAny(id: Int): Int?

    /** ICarFunction.getCustomizeFunctionValue(id); null on failure or NaN. */
    fun functionFloat(id: Int): Float?
}

/**
 * Read-only reflection over a connected ECARX `ICar` (ported from CarLight's `EcarxVehicle` raw
 * access). Only getters are invoked, never a setter. Every call is a synchronous binder call: use
 * from the poller thread only. Counts calls and failures so the poller can tell a dead connection
 * from values that are merely unavailable.
 */
class EcarxReader(private val car: Any) : EcarxReads {
    private val methods = ConcurrentHashMap<String, Any>()
    private var sensor: Any? = null
    private var carFunction: Any? = null
    /** Function id → the zone variant that last gave a valid value (NO_ZONE = no zone argument). */
    private val zoneVariant = HashMap<Int, Int>()

    @Volatile
    var lastError: String? = null
        private set
    var calls = 0
        private set
    var failures = 0
        private set
    /** A call threw DeadObjectException: the connection is gone. */
    var deadObject = false
        private set

    fun resetCounters() {
        calls = 0
        failures = 0
    }

    /** True when the sensor manager can be obtained (function-based signals degrade on their own). */
    fun sensorReady(): Boolean = sensor() != null

    /** Whether ICarFunction answered; without it turn signals, lock, drive mode etc. are unavailable. */
    val functionReady: Boolean get() = carFunction != null

    override fun sensorFloat(id: Int): Float? {
        val s = sensor() ?: return null
        return EcarxDecode.finite((call(method(s, I_SENSOR, "getSensorLatestValue", Int::class.javaPrimitiveType!!), s, id) as? Number)?.toFloat())
    }

    override fun sensorEvent(id: Int): Int? {
        val s = sensor() ?: return null
        return (call(method(s, I_SENSOR, "getSensorEvent", Int::class.javaPrimitiveType!!), s, id) as? Number)?.toInt()
    }

    fun functionInt(id: Int): Int? {
        val f = function() ?: return null
        return (call(method(f, I_FUNCTION, "getFunctionValue", Int::class.javaPrimitiveType!!), f, id) as? Number)?.toInt()
    }

    fun functionInt(id: Int, zone: Int): Int? {
        val f = function() ?: return null
        val m = method(f, I_FUNCTION, "getFunctionValue", Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
        return (call(m, f, id, zone) as? Number)?.toInt()
    }

    private fun variant(id: Int, zone: Int): Int? = if (zone == NO_ZONE) functionInt(id) else functionInt(id, zone)

    /**
     * zHUD's fallback: no zone, then zone MIN_VALUE, 0, 1; the first answer that is not an invalid
     * marker wins (else the first answer at all). The working variant is cached per id, so a steady
     * state costs one binder call instead of four.
     */
    override fun functionIntAny(id: Int): Int? {
        zoneVariant[id]?.let { cached ->
            val v = variant(id, cached)
            if (EcarxDecode.validInt(v) != null) return v
        }
        var first: Int? = null
        for (zone in ZONES) {
            val v = variant(id, zone)
            if (EcarxDecode.validInt(v) != null) {
                zoneVariant[id] = zone
                return v
            }
            if (first == null) first = v
        }
        return first
    }

    override fun functionFloat(id: Int): Float? {
        val f = function() ?: return null
        return EcarxDecode.finite((call(method(f, I_FUNCTION, "getCustomizeFunctionValue", Int::class.javaPrimitiveType!!), f, id) as? Number)?.toFloat())
    }

    private fun sensor(): Any? = sensor ?: call(method(car, I_CAR, "getSensorManager"), car)?.also { sensor = it }

    private var functionRetryAt = 0L

    /** A missing ICarFunction is retried at most every [FUNCTION_RETRY_MS], not on every read. */
    private fun function(): Any? {
        carFunction?.let { return it }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now < functionRetryAt) return null
        functionRetryAt = now + FUNCTION_RETRY_MS
        val f = call(method(car, I_CAR, "getICarFunction"), car)
        if (f == null) lastError = "getICarFunction 返回 null" else carFunction = f
        return f
    }

    private fun call(m: Method?, target: Any, vararg args: Any): Any? {
        if (m == null) return null
        calls++
        return try {
            m.invoke(target, *args)
        } catch (error: Throwable) {
            if (error !is Exception && error !is LinkageError) throw error
            val cause = if (error is InvocationTargetException && error.cause != null) error.cause!! else error
            failures++
            if (cause is DeadObjectException) deadObject = true
            lastError = "${m.name}: ${cause.javaClass.simpleName} ${cause.message?.take(80) ?: ""}"
            null
        }
    }

    /** Resolves on the public SDK interface when it loads (impl classes may be hidden), else on the object. */
    private fun method(target: Any, iface: String, name: String, vararg params: Class<*>): Method? {
        val key = target.javaClass.name + "|" + iface + "#" + name + params.size
        methods[key]?.let { return it as? Method }
        var found: Method? = try {
            val c = Class.forName(iface, false, EcarxReader::class.java.classLoader)
            if (c.isInstance(target)) c.getMethod(name, *params) else null
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: RuntimeException) {
            null
        } catch (_: LinkageError) {
            null
        }
        if (found == null) {
            found = try {
                target.javaClass.getMethod(name, *params)
            } catch (_: ReflectiveOperationException) {
                null
            } catch (_: RuntimeException) {
                null
            } catch (_: LinkageError) {
                null
            }
        }
        if (found != null && !Modifier.isPublic(found.declaringClass.modifiers)) {
            try {
                found.isAccessible = true
            } catch (_: RuntimeException) {
                // best effort
            }
        }
        methods[key] = found ?: MISSING
        if (found == null) lastError = "方法不存在 $name"
        return found
    }

    private companion object {
        const val PKG = "com.ecarx.xui.adaptapi."
        const val I_CAR = PKG + "car.ICar"
        const val I_SENSOR = PKG + "car.sensor.ISensor"
        const val I_FUNCTION = PKG + "car.base.ICarFunction"
        const val NO_ZONE = 0x7fff_fffe
        const val FUNCTION_RETRY_MS = 5_000L
        val ZONES = intArrayOf(NO_ZONE, Int.MIN_VALUE, 0, 1)
        val MISSING = Any()
    }
}
