package dev.customhud.camera

/**
 * Window type of the separate camera window on the cluster display. Higher system types need
 * INTERNAL_SYSTEM_WINDOW (system uid + platform signature). Android 12 z-order (PhoneWindowManager
 * layer): APPLICATION_OVERLAY 12 < SYSTEM_ERROR 27 < DISPLAY_OVERLAY 29 < SECURE_SYSTEM_OVERLAY 33.
 * Only helps if what covers the camera is an Android window on that display.
 */
enum class CameraWindowLayer(val type: Int, val label: String, val androidLayer: Int) {
    APPLICATION_OVERLAY(2038, "普通悬浮窗（原来）", 12),
    SYSTEM_ERROR(2010, "系统错误层", 27),
    DISPLAY_OVERLAY(2026, "显示覆盖层", 29),
    SECURE_SYSTEM_OVERLAY(2015, "安全覆盖层（最高）", 33),
    ;

    companion object {
        val DEFAULT = SECURE_SYSTEM_OVERLAY
        val TYPES: Set<Int> = entries.map { it.type }.toSet()

        fun parse(raw: String?): CameraWindowLayer = entries.firstOrNull { it.name == raw } ?: DEFAULT
    }
}
