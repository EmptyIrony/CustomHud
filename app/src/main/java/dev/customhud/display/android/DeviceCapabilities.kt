package dev.customhud.display.android

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.view.Display
import dev.customhud.data.CapabilityStatus
import dev.customhud.data.SignalCapability
import dev.customhud.display.session.DisplayDescriptor

class DeviceCapabilities(private val context: Context) {
    private val displayManager = context.getSystemService(DisplayManager::class.java)

    fun displays(generation: Long = 0): List<DisplayDescriptor> = displayManager.displays.map { display ->
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        DisplayDescriptor(
            id = display.displayId,
            name = display.name,
            flags = display.flags,
            width = metrics.widthPixels,
            height = metrics.heightPixels,
            densityDpi = metrics.densityDpi,
            rotation = display.rotation,
            valid = display.isValid,
            state = display.state,
            lastSeenMonotonic = android.os.SystemClock.elapsedRealtime(),
            sessionGeneration = generation,
        )
    }

    fun report(): List<SignalCapability> {
        val now = android.os.SystemClock.elapsedRealtime()
        val overlay = Settings.canDrawOverlays(context)
        val appOps = try {
            val manager = context.getSystemService(AppOpsManager::class.java)
            val mode = manager.checkOpNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, Process.myUid(), context.packageName)
            when (mode) {
                AppOpsManager.MODE_ALLOWED -> "allowed"
                AppOpsManager.MODE_IGNORED -> "ignored"
                AppOpsManager.MODE_ERRORED -> "errored"
                else -> "default"
            }
        } catch (error: Exception) {
            "unknown:${error::class.java.simpleName}"
        }
        val notification = if (Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) CapabilityStatus.AVAILABLE else CapabilityStatus.DENIED
        return listOf(
            SignalCapability(if (displays().any { it.id != Display.DEFAULT_DISPLAY }) CapabilityStatus.AVAILABLE else CapabilityStatus.NOT_READY, "Secondary display visibility", now, Build.FINGERPRINT),
            SignalCapability(if (overlay) CapabilityStatus.AVAILABLE else CapabilityStatus.DENIED, "Overlay Settings=$overlay AppOps=$appOps", now, Build.FINGERPRINT),
            SignalCapability(CapabilityStatus.AVAILABLE, "Foreground service declared; actual start recorded separately", now, Build.FINGERPRINT),
            SignalCapability(notification, "Notification permission is independent of display permission", now, Build.FINGERPRINT),
        ) + dev.customhud.adapters.VehiclePermissions.PERMISSIONS.map { vehiclePermission(it, now) }
    }

    private fun vehiclePermission(permission: String, now: Long): SignalCapability {
        val granted = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        return SignalCapability(if (granted) CapabilityStatus.AVAILABLE else CapabilityStatus.DENIED, permission, now, Build.FINGERPRINT)
    }
}
