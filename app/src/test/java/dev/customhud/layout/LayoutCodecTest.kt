package dev.customhud.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LayoutCodecTest {
    @Test
    fun fullDocumentRoundTripHasEveryTypeOnce() {
        val original = LayoutDocument.default()
        val decoded = LayoutCodec.parse(LayoutCodec.encode(original))
        assertEquals(WidgetType.entries.size, decoded.widgets.size)
        assertEquals(original.orderedWidgets(), decoded.orderedWidgets())
    }

    @Test
    fun invalidFullDocumentDoesNotPartiallyApply() {
        val json = """{"schemaVersion":1,"widgets":[
            {"t":"CLOCK","en":true,"cx":0.5,"cy":0.5,"sc":0.3,"sp":0},
            {"t":"CLOCK","en":false,"cx":0.2,"cy":0.5,"sc":0.3,"sp":0}
        ]}"""
        expectInvalid(json)
        expectInvalid("""{"schemaVersion":2,"widgets":[]}""")
        expectInvalid("""{"schemaVersion":1,"widgets":[],"script":"x"}""")
        expectInvalid("""{"schemaVersion":1,"widgets":[{"t":"CLOCK","en":true,"cx":1.1,"cy":0.5,"sc":0.3,"sp":0}]}""")
        expectInvalid("""{"schemaVersion":1,"widgets":[{"t":"CLOCK","en":true,"cx":0,"cy":0.5,"sc":0.3,"sp":0,"extra":1}]}""")
    }

    @Test
    fun patchIsAtomicAndCannotAddTypes() {
        val original = LayoutDocument.clockOnly()
        val updated = LayoutCodec.patch(original, listOf(WidgetPatch(WidgetType.CLOCK, cx = 0f, scale = 4f)))
        assertEquals(0f, updated.widgets.single().cx)
        assertEquals(4f, updated.widgets.single().scale)
        assertEquals(.5f, original.widgets.single().cy)
        try {
            LayoutCodec.patch(original, listOf(WidgetPatch(WidgetType.BATTERY, enabled = true)))
            fail("absent type accepted")
        } catch (_: LayoutFormatException) {
        }
        try {
            LayoutCodec.patch(original, listOf(WidgetPatch(WidgetType.CLOCK, cx = 2f)))
            fail("invalid patch accepted")
        } catch (_: LayoutFormatException) {
        }
        assertEquals(original, LayoutDocument.clockOnly())
    }

    @Test
    fun geometryUsesActualSizeAndBoundaries() {
        val widget = WidgetConfig(WidgetType.TURN, true, 0f, 1f, .2f, .5f)
        val wide = widget.geometry(1000, 400)
        assertEquals(0f, wide.anchorX)
        assertEquals(400f, wide.anchorY)
        assertEquals(12.8f, wide.baseUnit, .0001f)
        assertEquals(500f, wide.pairSpreadPx)
        val tall = widget.geometry(300, 900)
        assertEquals(28.8f, tall.baseUnit, .0001f)
        assertEquals(150f, tall.pairSpreadPx)
        val empty = widget.geometry(0, 0)
        assertEquals(0f, empty.baseUnit)
    }

    @Test
    fun omittedWidgetsAreNotAddedBack() {
        val decoded = LayoutCodec.parse("""{"schemaVersion":1,"widgets":[]}""")
        assertTrue(decoded.widgets.isEmpty())
    }

    private fun expectInvalid(json: String) {
        try {
            LayoutCodec.parse(json)
            fail("Invalid document accepted: $json")
        } catch (_: LayoutFormatException) {
        }
    }
}

class RearCameraLayoutTest {
    @Test
    fun rearCameraRoundTripsThroughSchemaVersion1() {
        val doc = LayoutDocument(widgets = listOf(
            WidgetConfig(WidgetType.CLOCK, true, .84f, .5f, .3f, 0f),
            WidgetConfig(WidgetType.REAR_CAMERA, true, .55f, .45f, .8f, 0f),
        ))
        val json = LayoutCodec.encode(doc)
        assertTrue(json.contains("\"REAR_CAMERA\""))
        assertTrue(json.contains("\"schemaVersion\": 1"))
        assertEquals(doc.orderedWidgets(), LayoutCodec.parse(json).orderedWidgets())
    }

    @Test
    fun layoutsWrittenBeforeRearCameraLoadUnchanged() {
        val old = """{"schemaVersion":1,"widgets":[
            {"t":"CLOCK","en":true,"cx":0.88,"cy":0.14,"sc":0.25,"sp":0},
            {"t":"TRACK","en":true,"cx":0.5,"cy":0.94,"sc":0.22,"sp":0},
            {"t":"LYRIC","en":true,"cx":0.5,"cy":0.78,"sc":0.5,"sp":0}
        ]}"""
        val parsed = LayoutCodec.parse(old)
        assertEquals(listOf(WidgetType.CLOCK, WidgetType.TRACK, WidgetType.LYRIC), parsed.orderedWidgets().map { it.type })
        assertTrue(parsed.widgets.none { it.type == WidgetType.REAR_CAMERA }) // never added behind the user's back
        assertEquals(parsed, LayoutCodec.parse(LayoutCodec.encode(parsed)))
    }

    @Test
    fun defaultCameraBoxIs16By9InsideTheClusterAndRightOfTheSpeed() {
        val widget = LayoutDocument.default().widgets.single { it.type == WidgetType.REAR_CAMERA }
        val box = CameraGeometry.box(widget, 2560, 536)!!
        assertEquals(16f / 9f, box.width / box.height, .001f)
        assertTrue(box.left >= 0f && box.top >= 0f && box.right <= 2560f && box.bottom <= 536f)
        assertEquals(1280f, (box.left + box.right) / 2, .5f)
        assertTrue("left third holds the speed readout", box.left > 2560 * .3f)
    }

    @Test
    fun cameraBoxIsClampedInsideTheViewAndLimitedByWidth() {
        val corner = WidgetConfig(WidgetType.REAR_CAMERA, true, 0f, 1f, 1f, 0f)
        val box = CameraGeometry.box(corner, 2560, 536)!!
        assertEquals(0f, box.left, .001f)
        assertEquals(536f, box.bottom, .001f)
        assertEquals(536f, box.height, .001f)
        val tall = CameraGeometry.box(WidgetConfig(WidgetType.REAR_CAMERA, true, .5f, .5f, 4f, 0f), 800, 1000)!!
        assertEquals(800f, tall.width, .001f)
        assertEquals(450f, tall.height, .001f)
    }
}
