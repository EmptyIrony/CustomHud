package dev.customhud.adapters

import android.content.Context
import dev.customhud.data.CapabilityStatus
import dev.customhud.data.SignalCapability

class EcarxCapabilityProbe(private val context: Context) {
    fun inspect(): SignalCapability {
        val now = android.os.SystemClock.elapsedRealtime()
        return try {
            val carClass = Class.forName("com.ecarx.xui.adaptapi.car.Car")
            val create = try {
                carClass.getMethod("create", Context::class.java)
            } catch (_: NoSuchMethodException) {
                null
            }
            if (create == null) {
                SignalCapability(CapabilityStatus.MISSING_API, "ECARX Car.create(Context) is not visible; no connection attempted", now, "ecarx-candidate")
            } else {
                SignalCapability(CapabilityStatus.NOT_READY, "Factory visible only; authorization and members remain unverified", now, "ecarx-candidate")
            }
        } catch (_: ClassNotFoundException) {
            SignalCapability(CapabilityStatus.MISSING_API, "ECARX candidate class unavailable", now, "ecarx-candidate")
        } catch (error: Throwable) {
            SignalCapability(CapabilityStatus.UNKNOWN, "ECARX probe ${error::class.java.name}; no connection attempted", now, "ecarx-candidate")
        }
    }
}
