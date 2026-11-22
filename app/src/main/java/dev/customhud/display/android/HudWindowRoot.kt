package dev.customhud.display.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import dev.customhud.camera.AndroidRearCamera
import dev.customhud.camera.CameraDisplay
import dev.customhud.camera.CameraPrefs
import dev.customhud.camera.RearCameraController
import dev.customhud.camera.RearCameraStatus
import dev.customhud.layout.CameraGeometry
import dev.customhud.layout.LayoutSnapshot
import dev.customhud.layout.WidgetType
import dev.customhud.logging.HudLog
import dev.customhud.renderer.HudCanvasView
import kotlin.math.roundToInt

/**
 * HUD window root: an optional camera TextureView placed at the REAR_CAMERA box, under the transparent
 * [HudCanvasView] that draws every other widget (and the camera placeholder). The window stays transparent.
 *
 * SurfaceTexture ownership follows CarLight's CameraStreamActivity: onSurfaceTextureDestroyed returns false
 * and the texture is released only after the camera close callback.
 */
@SuppressLint("ViewConstructor")
class HudWindowRoot(
    context: Context,
    val canvas: HudCanvasView,
    private val camera: CameraBinding?,
) : FrameLayout(context) {
    /** The camera pieces this window uses; null for a window that does not host REAR_CAMERA. */
    class CameraBinding(val backend: AndroidRearCamera, val controller: RearCameraController, val log: HudLog, val prefs: CameraPrefs)

    private val textureView: TextureView? = camera?.let { TextureView(context) }
    /**
     * Solid black under the TextureView so the camera box always leaves the window with alpha = 1. Without it
     * (and with a non-opaque TextureView) the cluster's own navigation map showed through every other camera
     * buffer — the ~15 Hz flicker in the 2026-11-06 field video.
     */
    private val backing: View? = camera?.let { View(context).apply { setBackgroundColor(Color.BLACK) } }
    private var opaqueApplied: Boolean? = null
    private var layout: LayoutSnapshot? = null
    private var status: RearCameraStatus? = null
    private var texture: SurfaceTexture? = null
    private var surface: Surface? = null

    init {
        setBackgroundColor(Color.TRANSPARENT)
        backing?.let { view ->
            view.visibility = View.INVISIBLE
            addView(view, LayoutParams(1, 1))
        }
        textureView?.let { view ->
            applyOpacity()
            view.visibility = View.INVISIBLE
            view.surfaceTextureListener = SurfaceListener()
            addView(view, LayoutParams(1, 1))
        }
        addView(canvas, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    val hostsCamera: Boolean get() = camera != null

    fun submitLayout(snapshot: LayoutSnapshot) {
        layout = snapshot
        placeTexture()
    }

    fun submitCamera(next: RearCameraStatus) {
        status = next
        canvas.submitCamera(next)
        // INVISIBLE keeps an existing SurfaceTexture (only detach destroys it) but hides a stale last frame.
        // A first VISIBLE draw is what creates the SurfaceTexture, so it turns visible as soon as the mode wants it.
        applyOpacity()
        val visible = next.display != CameraDisplay.HIDDEN
        textureView?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        backing?.visibility = if (visible && opaqueApplied == true) View.VISIBLE else View.INVISIBLE
    }

    /** Follows CameraPrefs.opaque (default on, like CarLight's TextureView); off only for the A/B check. */
    private fun applyOpacity() {
        val binding = camera ?: return
        val opaque = binding.prefs.opaque
        if (opaque == opaqueApplied) return
        opaqueApplied = opaque
        textureView?.isOpaque = opaque
        if (!opaque) backing?.visibility = View.INVISIBLE
        binding.log.add("CAMERA", "texture opaque=$opaque backing=$opaque", generation = binding.backend.generation)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { placeTexture() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        camera?.log?.add("CAMERA", "window attached hardwareAccelerated=$isHardwareAccelerated", generation = camera.backend.generation)
        if (camera != null && !isHardwareAccelerated) {
            camera.log.add("CAMERA", "TextureView needs a hardware-accelerated window", "WARN", "NOT_HW_ACCELERATED", camera.backend.generation)
        }
    }

    private fun placeTexture() {
        val view = textureView ?: return
        val widget = layout?.document?.widgets?.firstOrNull { it.type == WidgetType.REAR_CAMERA }
        val box = widget?.let { CameraGeometry.box(it, width, height) }
        val params = view.layoutParams as LayoutParams
        val w = box?.width?.roundToInt()?.coerceAtLeast(1) ?: 1
        val h = box?.height?.roundToInt()?.coerceAtLeast(1) ?: 1
        val left = box?.left?.roundToInt() ?: 0
        val top = box?.top?.roundToInt() ?: 0
        if (params.width == w && params.height == h && params.leftMargin == left && params.topMargin == top) return
        params.width = w
        params.height = h
        params.leftMargin = left
        params.topMargin = top
        view.layoutParams = params
        backing?.layoutParams = LayoutParams(w, h).apply {
            leftMargin = left
            topMargin = top
        }
    }

    /** Releases one surface/texture pair; clears the fields only if they still point at it. */
    private fun releaseTexture(oldSurface: Surface?, oldTexture: SurfaceTexture) {
        oldSurface?.release()
        oldTexture.release()
        if (surface === oldSurface) surface = null
        if (texture === oldTexture) texture = null
    }

    private inner class SurfaceListener : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
            val binding = camera ?: return
            val oldTexture = texture
            if (oldTexture != null && oldTexture !== st) {
                val oldSurface = surface
                if (oldSurface != null) binding.backend.detachSurface(oldSurface)
                binding.controller.whenClosed { releaseTexture(oldSurface, oldTexture) }
            }
            texture = st
            val next = Surface(st)
            surface = next
            binding.backend.attachSurface(next, width, height)
        }

        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
            val current = surface ?: return
            camera?.backend?.resizeSurface(current, width, height)
        }

        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
            val binding = camera ?: return true
            val current = surface
            if (current != null) binding.backend.detachSurface(current)
            // Keep the texture alive until the SDK renderer has let go of it.
            binding.controller.whenClosed { releaseTexture(if (texture === st) current else null, st) }
            return false
        }

        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
    }
}
