package dev.customhud.display.android

import dev.customhud.camera.CameraWindowLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowStackTest {
    private val sample = """
WINDOW MANAGER WINDOWS (dumpsys window windows)
  Window #0 Window{a1 u0 ClusterTelltale}:
    mDisplayId=1 rootTaskId=1 mSession=Session{x} mClient=android.os.BinderProxy@1
    mOwnerUid=1000 showForAllUsers=true package=com.example.cluster appop=NONE
    mAttrs={(0,0)(fillxfill) ty=SECURE_SYSTEM_OVERLAY fmt=TRANSLUCENT}
    isOnScreen=true
    isVisible=true
  Window #1 Window{b2 u0 NavigationBar0}:
    mDisplayId=0 rootTaskId=1
    mOwnerUid=10050 showForAllUsers=true package=com.android.systemui appop=NONE
    mAttrs={(0,0)(fillx48) ty=NAVIGATION_BAR fmt=TRANSLUCENT}
    isVisible=true
  Window #2 Window{c3 u0 dev.customhud}:
    mDisplayId=1 rootTaskId=1
    mOwnerUid=1000 showForAllUsers=false package=dev.customhud appop=SYSTEM_ALERT_WINDOW
    mAttrs={(0,0)(fillxfill) ty=APPLICATION_OVERLAY fmt=TRANSLUCENT}
    isVisible=true
  Window #3 Window{d4 u0 com.example.map/.ClusterMapActivity}:
    mDisplayId=1 rootTaskId=7
    package=com.example.map appop=NONE
    mAttrs={(0,0)(fillxfill) ty=BASE_APPLICATION fmt=OPAQUE}
    isVisible=false
""".trimIndent()

    @Test
    fun keepsOnlyTheDisplayInTopFirstOrder() {
        val entries = WindowStack.parse(sample, 1)
        assertEquals(listOf(0, 2, 3), entries.map { it.index })
        assertEquals("ClusterTelltale", entries[0].title)
        assertEquals("SECURE_SYSTEM_OVERLAY", entries[0].type)
        assertEquals("dev.customhud", entries[1].pkg)
        assertEquals("BASE_APPLICATION", entries[2].type)
        assertEquals("可见=false", entries[2].visible)
        assertEquals("com.example.map/.ClusterMapActivity", entries[2].title)
    }

    @Test
    fun emptyForUnknownDisplay() {
        assertTrue(WindowStack.parse(sample, 5).isEmpty())
    }

    @Test
    fun layersAreOrderedAndDefaultIsHighest() {
        val layers = CameraWindowLayer.entries.map { it.androidLayer }
        assertEquals(layers.sorted(), layers)
        assertEquals(CameraWindowLayer.SECURE_SYSTEM_OVERLAY, CameraWindowLayer.DEFAULT)
        assertEquals(CameraWindowLayer.DEFAULT, CameraWindowLayer.parse("bogus"))
    }
}
