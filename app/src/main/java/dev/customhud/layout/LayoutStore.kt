package dev.customhud.layout

import android.content.Context
import java.util.concurrent.CopyOnWriteArrayList

class LayoutStore(context: Context) {
    private val preferences = context.getSharedPreferences("layout", Context.MODE_PRIVATE)
    private val listeners = CopyOnWriteArrayList<(LayoutSnapshot) -> Unit>()

    @Volatile
    var current: LayoutSnapshot = load()
        private set

    @Synchronized
    fun replace(json: String): LayoutSnapshot {
        val parsed = LayoutCodec.parse(json)
        return commit(parsed)
    }

    @Synchronized
    fun replace(document: LayoutDocument): LayoutSnapshot = commit(document)

    @Synchronized
    fun applyPatch(patches: List<WidgetPatch>): LayoutSnapshot {
        val updated = LayoutCodec.patch(current.document, patches)
        return commit(updated)
    }

    /**
     * Builds a new document from the current one. With [persist] = false the snapshot is
     * published (preview + HUD update live) but not written, e.g. while a finger is dragging.
     */
    @Synchronized
    fun update(persist: Boolean = true, transform: (LayoutDocument) -> LayoutDocument): LayoutSnapshot {
        val updated = try {
            transform(current.document)
        } catch (error: IllegalArgumentException) {
            throw LayoutFormatException(error.message ?: "Invalid layout")
        }
        return commit(updated, persist)
    }

    /** Writes the current (possibly unpersisted) snapshot. */
    @Synchronized
    fun persistCurrent() {
        if (!preferences.edit().putString("document", LayoutCodec.encode(current.document)).putLong("revision", current.revision).commit()) {
            throw LayoutFormatException("Layout persistence failed")
        }
    }

    fun export(): String = LayoutCodec.encode(current.document)

    fun observe(listener: (LayoutSnapshot) -> Unit): () -> Unit {
        listeners.add(listener)
        listener(current)
        return { listeners.remove(listener) }
    }

    private fun commit(document: LayoutDocument, persist: Boolean = true): LayoutSnapshot {
        val encoded = LayoutCodec.encode(document)
        val validated = LayoutCodec.parse(encoded)
        val snapshot = LayoutSnapshot(validated, current.revision + 1)
        if (persist && !preferences.edit().putString("document", encoded).putLong("revision", snapshot.revision).commit()) {
            throw LayoutFormatException("Layout persistence failed; previous layout retained")
        }
        current = snapshot
        listeners.forEach { it(snapshot) }
        return snapshot
    }

    private fun load(): LayoutSnapshot {
        val revision = preferences.getLong("revision", 0L).coerceAtLeast(1L)
        val json = preferences.getString("document", null)
        if (json != null) {
            try {
                return LayoutSnapshot(LayoutCodec.parse(json), revision)
            } catch (_: LayoutFormatException) {
            }
        }
        return LayoutSnapshot(LayoutDocument.default(), revision)
    }
}
