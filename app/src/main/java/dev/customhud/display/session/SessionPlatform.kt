package dev.customhud.display.session

interface Cancellable {
    fun cancel()
}

interface SessionScheduler {
    fun now(): Long
    fun postDelayed(delayMs: Long, task: () -> Unit): Cancellable
}

interface WindowCallbacks {
    fun onAttach()
    fun onDetach()
    fun onDraw(layoutRevision: Long, frameGeneration: Long)
    fun onError(code: String, message: String, causeType: String?)
}

interface SessionWindow {
    val isAttached: Boolean
    fun add()
    fun remove()
    fun refresh()
}

interface DisplayListenerHandle {
    fun close()
}

interface DataHandle {
    fun close()
}

interface SessionPlatform {
    fun displays(generation: Long): List<DisplayDescriptor>
    fun overlayGranted(): Boolean
    fun registerDisplayListener(listener: () -> Unit): DisplayListenerHandle
    fun createWindow(target: DisplayTarget, spec: WindowSpec, callbacks: WindowCallbacks): SessionWindow
    fun startData(generation: Long): DataHandle?
}
