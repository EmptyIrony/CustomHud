package dev.customhud.adapters

import android.content.Context
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Read-only reflection wrapper over the public android.car API (Car / CarPropertyManager /
 * CarPropertyValue). android.car is not part of the SDK android.jar, so it is resolved at runtime.
 * Never writes properties. Blocking calls: use from a background thread.
 */
class CarPropertyAccess private constructor(private val car: Any, private val manager: Any) {
    data class Config(
        val id: Int,
        val areaType: Int,
        val access: Int,
        val changeMode: Int,
        val areaIds: List<Int>,
        val type: String,
    ) {
        val readable: Boolean get() = access == ACCESS_READ || access == ACCESS_READ_WRITE
    }

    /** Outcome of one getProperty(id, area) call. */
    data class Read(val status: Int?, val value: Any?, val timestampNanos: Long, val error: Throwable?) {
        val ok: Boolean get() = error == null && status == STATUS_AVAILABLE && value != null

        /** Short machine-ish code for reports and DataError. */
        val code: String
            get() = when {
                ok -> "OK"
                error is SecurityException -> "PERMISSION_DENIED"
                error is IllegalArgumentException -> "UNSUPPORTED"
                error != null && error.javaClass.simpleName.contains("NotAvailable") -> "NOT_AVAILABLE_NOW"
                error != null -> "READ_FAILED"
                status == STATUS_UNAVAILABLE -> "NOT_AVAILABLE_NOW"
                status == STATUS_ERROR -> "STATUS_ERROR"
                else -> "NO_VALUE"
            }

        val detail: String
            get() = error?.let { "${it.javaClass.simpleName}: ${it.message?.take(80) ?: ""}" } ?: "status=$status"
    }

    private val managerClass = manager.javaClass
    private val getPropertyList: Method = managerClass.getMethod("getPropertyList")
    private val getProperty: Method = managerClass.getMethod("getProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    private val getCarPropertyConfig: Method = managerClass.getMethod("getCarPropertyConfig", Int::class.javaPrimitiveType)
    private var valueMethods: Triple<Method, Method, Method>? = null

    fun configs(): List<Config> = (unwrap { getPropertyList.invoke(manager) } as? List<*>).orEmpty().mapNotNull { it?.let(::toConfig) }

    fun config(id: Int): Config? = try {
        unwrap { getCarPropertyConfig.invoke(manager, id) }?.let(::toConfig)
    } catch (_: Throwable) {
        null
    }

    fun read(id: Int, area: Int): Read = try {
        val value = unwrap { getProperty.invoke(manager, id, area) }
        if (value == null) {
            Read(null, null, 0, null)
        } else {
            val (status, raw, timestamp) = methodsFor(value)
            Read(status.invoke(value) as? Int, raw.invoke(value), (timestamp.invoke(value) as? Long) ?: 0L, null)
        }
    } catch (error: Throwable) {
        Read(null, null, 0, error)
    }

    fun disconnect() {
        try {
            car.javaClass.getMethod("disconnect").invoke(car)
        } catch (_: Throwable) {
        }
    }

    private fun methodsFor(value: Any): Triple<Method, Method, Method> = valueMethods ?: value.javaClass.let { cls ->
        Triple(cls.getMethod("getStatus"), cls.getMethod("getValue"), cls.getMethod("getTimestamp")).also { valueMethods = it }
    }

    private fun toConfig(config: Any): Config? = try {
        val cls = config.javaClass
        val areas = (cls.getMethod("getAreaIds").invoke(config) as? IntArray)?.toList().orEmpty()
        Config(
            id = cls.getMethod("getPropertyId").invoke(config) as Int,
            areaType = cls.getMethod("getAreaType").invoke(config) as Int,
            access = cls.getMethod("getAccess").invoke(config) as Int,
            changeMode = cls.getMethod("getChangeMode").invoke(config) as Int,
            areaIds = areas,
            type = (cls.getMethod("getPropertyType").invoke(config) as? Class<*>)?.simpleName ?: "?",
        )
    } catch (_: Throwable) {
        null
    }

    companion object {
        const val STATUS_AVAILABLE = 0
        const val STATUS_UNAVAILABLE = 1
        const val STATUS_ERROR = 2
        const val ACCESS_READ = 1
        const val ACCESS_WRITE = 2
        const val ACCESS_READ_WRITE = 3

        /** Blocking Car.createCar(Context) + property manager. Throws on failure. */
        fun connect(context: Context): CarPropertyAccess {
            val carClass = Class.forName("android.car.Car")
            val car = unwrap { carClass.getMethod("createCar", Context::class.java).invoke(null, context.applicationContext) }
                ?: throw IllegalStateException("Car.createCar returned null")
            val manager = try {
                unwrap { carClass.getMethod("getCarManager", String::class.java).invoke(car, "property") }
                    ?: throw IllegalStateException("Property manager unavailable")
            } catch (error: Throwable) {
                try {
                    carClass.getMethod("disconnect").invoke(car)
                } catch (_: Throwable) {
                }
                throw error
            }
            return CarPropertyAccess(car, manager)
        }

        /** VehiclePropertyIds.toString when the platform has it, else hex. */
        fun propertyName(id: Int): String = try {
            Class.forName("android.car.VehiclePropertyIds")
                .getMethod("toString", Int::class.javaPrimitiveType).invoke(null, id) as String
        } catch (_: Throwable) {
            CarProps.name(id)
        }

        fun describe(value: Any?): String = when (value) {
            null -> "null"
            is IntArray -> value.take(8).joinToString(",", "[", if (value.size > 8) ",…(${value.size})]" else "]")
            is FloatArray -> value.take(8).joinToString(",", "[", if (value.size > 8) ",…(${value.size})]" else "]")
            is LongArray -> value.take(8).joinToString(",", "[", if (value.size > 8) ",…(${value.size})]" else "]")
            is ByteArray -> "bytes(${value.size})"
            is Array<*> -> value.take(8).joinToString(",", "[", if (value.size > 8) ",…(${value.size})]" else "]") { describe(it) }
            is String -> "\"${value.take(60)}\""
            else -> value.toString().take(80)
        }

        private inline fun <T> unwrap(block: () -> T): T = try {
            block()
        } catch (error: InvocationTargetException) {
            throw error.targetException ?: error
        }
    }
}
