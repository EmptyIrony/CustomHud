package dev.customhud.camera

import android.content.Context
import android.content.SharedPreferences

/**
 * Rear camera settings that are not layout geometry: the display mode and which display shows the
 * camera. Default display: id 1 (HDMI 1 = instrument cluster, confirmed by the user 2026-11-06).
 */
class CameraPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("rear-camera", Context.MODE_PRIVATE)

    data class Target(val id: Int, val name: String, val width: Int, val height: Int)

    var mode: RearCameraMode
        get() = RearCameraMode.parse(prefs.getString("mode", null))
        set(value) = prefs.edit().putString("mode", value.name).apply()

    var target: Target
        get() = Target(
            prefs.getInt("displayId", DEFAULT_DISPLAY_ID),
            prefs.getString("displayName", "") ?: "",
            prefs.getInt("displayWidth", DEFAULT_WIDTH),
            prefs.getInt("displayHeight", DEFAULT_HEIGHT),
        )
        set(value) = prefs.edit()
            .putInt("displayId", value.id)
            .putString("displayName", value.name)
            .putInt("displayWidth", value.width)
            .putInt("displayHeight", value.height)
            .apply()

    /**
     * Opaque camera box (TextureView opaque + black backing). Off reproduces build 64795cc0, where the map
     * under the box showed through every other camera frame (~15 Hz flicker). For A/B only.
     */
    var opaque: Boolean
        get() = prefs.getBoolean("opaque", true)
        set(value) = prefs.edit().putBoolean("opaque", value).apply()

    /** SDK params fps (CarLight / com.zeekr.stream use 30). Applied on the next open. */
    var fps: Int
        get() = prefs.getInt("fps", DEFAULT_FPS).takeIf { it in FPS_STEPS } ?: DEFAULT_FPS
        set(value) = prefs.edit().putInt("fps", value).apply()

    /** Window type of the separate camera window (cluster). Changing it rebuilds that window. */
    var windowLayer: CameraWindowLayer
        get() = CameraWindowLayer.parse(prefs.getString("windowLayer", null))
        set(value) = prefs.edit().putString("windowLayer", value.name).apply()

    /** Diagnostic: static white box instead of the camera (persisted until switched off). */
    var testPattern: Boolean
        get() = prefs.getBoolean("testPattern", false)
        set(value) = prefs.edit().putBoolean("testPattern", value).apply()

    fun observe(listener: () -> Unit): () -> Unit {
        val callback = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> listener() }
        prefs.registerOnSharedPreferenceChangeListener(callback)
        held += callback
        return {
            prefs.unregisterOnSharedPreferenceChangeListener(callback)
            held -= callback
        }
    }

    companion object {
        const val DEFAULT_DISPLAY_ID = 1
        const val DEFAULT_WIDTH = 2560
        const val DEFAULT_HEIGHT = 536
        const val DEFAULT_FPS = 30
        val FPS_STEPS = listOf(30, 15)
        private val held = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
    }
}
