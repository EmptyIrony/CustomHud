package dev.customhud.logging

import android.os.SystemClock
import dev.customhud.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

data class LogEntry(
    val wallTimeMs: Long,
    val monotonicMs: Long,
    val level: String,
    val category: String,
    val message: String,
    val errorCode: String? = null,
    val generation: Long? = null,
)

class HudLog {
    private val entries = ArrayDeque<LogEntry>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Synchronized
    fun add(
        category: String,
        message: String,
        level: String = "INFO",
        errorCode: String? = null,
        generation: Long? = null,
    ) {
        entries.addLast(LogEntry(System.currentTimeMillis(), SystemClock.elapsedRealtime(), level, category, message, errorCode, generation))
        while (entries.size > 500) entries.removeFirst()
        listeners.forEach { it() }
    }

    @Synchronized
    fun snapshot(): List<LogEntry> = entries.toList()

    fun observe(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    fun exportText(): String = buildString {
        append("CustomHUD ").append(BuildConfig.VERSION_NAME)
        append(" buildId=").append(BuildConfig.BUILD_ID)
        append(" applicationId=").append(BuildConfig.APPLICATION_ID)
        append(" targetSdk=34 minSdk=29\n")
        snapshot().forEach { entry ->
            append(timeFormat.format(Date(entry.wallTimeMs)))
                .append(" elapsed=").append(entry.monotonicMs)
                .append(' ').append(entry.level)
                .append(' ').append(entry.category)
                .append(" gen=").append(entry.generation ?: "-")
                .append(" code=").append(entry.errorCode ?: "-")
                .append(' ').append(entry.message).append('\n')
        }
    }

    companion object {
        private val timeFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
    }
}
