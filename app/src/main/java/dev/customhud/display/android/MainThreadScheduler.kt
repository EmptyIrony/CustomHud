package dev.customhud.display.android

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.customhud.display.session.Cancellable
import dev.customhud.display.session.SessionScheduler

class MainThreadScheduler : SessionScheduler {
    private val handler = Handler(Looper.getMainLooper())

    override fun now(): Long = SystemClock.elapsedRealtime()

    override fun postDelayed(delayMs: Long, task: () -> Unit): Cancellable {
        val runnable = Runnable(task)
        handler.postDelayed(runnable, delayMs)
        return Cancellable { handler.removeCallbacks(runnable) }
    }

    private fun Cancellable(block: () -> Unit): Cancellable = object : Cancellable {
        override fun cancel() = block()
    }
}
