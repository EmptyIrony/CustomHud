package dev.customhud.layout

import org.json.JSONArray
import org.json.JSONObject

class LayoutFormatException(message: String) : Exception(message)

object LayoutCodec {
    private val topKeys = setOf("schemaVersion", "widgets")
    private val widgetKeys = setOf("t", "en", "cx", "cy", "sc", "sp")

    fun parse(json: String): LayoutDocument {
        try {
            val root = JSONObject(json)
            requireKeys(root, topKeys, "layout")
            val schema = root["schemaVersion"]
            if (schema !is Int || schema != 1) throw LayoutFormatException("Unsupported schemaVersion")
            val array = root["widgets"] as? JSONArray
                ?: throw LayoutFormatException("widgets must be an array")
            if (array.length() > WidgetType.entries.size) throw LayoutFormatException("Too many widgets")
            val seen = mutableSetOf<WidgetType>()
            val widgets = (0 until array.length()).map { index ->
                val item = array[index] as? JSONObject
                    ?: throw LayoutFormatException("widgets[$index] must be an object")
                requireKeys(item, widgetKeys, "widgets[$index]")
                val rawType = item["t"] as? String
                    ?: throw LayoutFormatException("widgets[$index].t must be a string")
                val type = WidgetType.entries.firstOrNull { it.name == rawType }
                    ?: throw LayoutFormatException("Unknown widget type: $rawType")
                if (!seen.add(type)) throw LayoutFormatException("Duplicate widget type: $type")
                val enabled = item["en"] as? Boolean
                    ?: throw LayoutFormatException("widgets[$index].en must be boolean")
                WidgetConfig(
                    type = type,
                    enabled = enabled,
                    cx = number(item, "cx", index),
                    cy = number(item, "cy", index),
                    scale = number(item, "sc", index),
                    spread = number(item, "sp", index),
                )
            }
            return LayoutDocument(schemaVersion = schema, widgets = widgets)
        } catch (error: LayoutFormatException) {
            throw error
        } catch (error: Exception) {
            throw LayoutFormatException(error.message ?: "Invalid layout")
        }
    }

    fun encode(document: LayoutDocument): String {
        val items = JSONArray()
        document.orderedWidgets().forEach { widget ->
            items.put(JSONObject().apply {
                put("t", widget.type.name)
                put("en", widget.enabled)
                put("cx", widget.cx.toDouble())
                put("cy", widget.cy.toDouble())
                put("sc", widget.scale.toDouble())
                put("sp", widget.spread.toDouble())
            })
        }
        return JSONObject()
            .put("schemaVersion", document.schemaVersion)
            .put("widgets", items)
            .toString(2)
    }

    fun patch(document: LayoutDocument, patches: List<WidgetPatch>): LayoutDocument {
        if (patches.map { it.type }.distinct().size != patches.size) {
            throw LayoutFormatException("Duplicate patch type")
        }
        val byType = patches.associateBy { it.type }
        byType.keys.forEach { type ->
            if (document.widgets.none { it.type == type }) {
                throw LayoutFormatException("Cannot patch absent widget: $type")
            }
        }
        return try {
            LayoutDocument(widgets = document.widgets.map { old ->
                val patch = byType[old.type] ?: return@map old
                WidgetConfig(
                    type = old.type,
                    enabled = patch.enabled ?: old.enabled,
                    cx = patch.cx ?: old.cx,
                    cy = patch.cy ?: old.cy,
                    scale = patch.scale ?: old.scale,
                    spread = patch.spread ?: old.spread,
                )
            })
        } catch (error: IllegalArgumentException) {
            throw LayoutFormatException(error.message ?: "Invalid patch")
        }
    }

    private fun requireKeys(item: JSONObject, allowed: Set<String>, path: String) {
        val actual = item.keys().asSequence().toSet()
        val missing = allowed - actual
        val unknown = actual - allowed
        if (missing.isNotEmpty()) throw LayoutFormatException("$path missing $missing")
        if (unknown.isNotEmpty()) throw LayoutFormatException("$path unknown fields $unknown")
    }

    private fun number(item: JSONObject, key: String, index: Int): Float {
        val raw = item[key] as? Number
            ?: throw LayoutFormatException("widgets[$index].$key must be a number")
        val value = raw.toFloat()
        if (!value.isFinite()) throw LayoutFormatException("widgets[$index].$key must be finite")
        return value
    }
}
