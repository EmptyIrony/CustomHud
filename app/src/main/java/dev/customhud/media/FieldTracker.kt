package dev.customhud.media

/**
 * Pure bookkeeping for every text field observed from media sessions / notifications.
 * A field that changes many times within one song is a strong lyric-line candidate.
 */
data class FieldKey(val packageName: String, val id: String) {
    val spec: String get() = "$packageName|$id"

    companion object {
        fun parse(spec: String?): FieldKey? {
            if (spec.isNullOrBlank()) return null
            val split = spec.indexOf('|')
            if (split <= 0 || split == spec.length - 1) return null
            return FieldKey(spec.substring(0, split), spec.substring(split + 1))
        }
    }
}

data class FieldValue(
    val key: FieldKey,
    val value: String,
    val changeCount: Int,
    val firstSeenMs: Long,
    val lastChangedMs: Long,
)

data class FieldChange(
    val atMs: Long,
    val key: FieldKey,
    val value: String,
)

class FieldTracker(
    private val maxValueLength: Int = 240,
    private val maxHistory: Int = 600,
) {
    private val values = LinkedHashMap<FieldKey, FieldValue>()
    private val history = ArrayDeque<FieldChange>()

    /** Returns true when the value is new or differs from the previous one. */
    @Synchronized
    fun update(key: FieldKey, rawValue: String?, nowMs: Long): Boolean {
        val value = normalize(rawValue) ?: return false
        val old = values[key]
        if (old != null && old.value == value) return false
        values[key] = if (old == null) {
            FieldValue(key, value, 0, nowMs, nowMs)
        } else {
            old.copy(value = value, changeCount = old.changeCount + 1, lastChangedMs = nowMs)
        }
        history.addLast(FieldChange(nowMs, key, value))
        while (history.size > maxHistory) history.removeFirst()
        return true
    }

    @Synchronized
    fun get(key: FieldKey): FieldValue? = values[key]

    /** Most frequently changing first, then most recently changed. */
    @Synchronized
    fun all(): List<FieldValue> = values.values.sortedWith(
        compareByDescending<FieldValue> { it.changeCount }.thenByDescending { it.lastChangedMs },
    )

    @Synchronized
    fun historySince(sinceMs: Long): List<FieldChange> = history.filter { it.atMs >= sinceMs }

    /** Text fields that changed at least [minChanges] times since [sinceMs]. */
    @Synchronized
    fun frequentSince(sinceMs: Long, minChanges: Int): List<Pair<FieldKey, Int>> =
        history.filter { it.atMs >= sinceMs }
            .groupingBy { it.key }
            .eachCount()
            .filter { it.value >= minChanges }
            .toList()
            .sortedByDescending { it.second }

    private fun normalize(raw: String?): String? {
        // Blank is a real value (e.g. an instrumental gap clearing the lyric line); null means absent.
        val trimmed = raw?.replace('\n', ' ')?.trim() ?: return null
        return if (trimmed.length > maxValueLength) trimmed.take(maxValueLength) + "…" else trimmed
    }
}
