package dev.customhud.settings

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import dev.customhud.camera.CameraPrefs
import dev.customhud.data.DataMode
import dev.customhud.data.MockVehicleDataSource
import dev.customhud.data.VehicleProfile
import dev.customhud.layout.CameraGeometry
import dev.customhud.layout.LayoutDocument
import dev.customhud.layout.WidgetConfig
import dev.customhud.layout.WidgetType
import dev.customhud.renderer.HudCanvasView
import java.util.Locale

class LayoutPage(host: MainActivity) : Page(host) {
    private val mock = MockVehicleDataSource()
    private val preview = HudCanvasView(host).apply {
        mode = DataMode.PREVIEW
        setBackgroundColor(Color.BLACK)
    }
    private val overlay = EditorOverlay(host, preview, { app.layoutStore.current.document }, ::select, ::move)
    private val aspect = AspectBox(host)
    private val ratioText = host.label("", Ui.SMALL, Ui.DIM)
    private val cameraPrefs = CameraPrefs(host)
    /** The camera display (default HDMI 1 = cluster) when it is not the HUD target: only REAR_CAMERA lives there. */
    private val cameraPreview = HudCanvasView(host).apply {
        mode = DataMode.PREVIEW
        setBackgroundColor(Color.rgb(10, 12, 16))
        widgetFilter = { it == WidgetType.REAR_CAMERA }
    }
    private val cameraOverlay = EditorOverlay(host, cameraPreview, { app.layoutStore.current.document }, ::select, ::move)
    private val cameraAspect = AspectBox(host)
    private val cameraTitle = host.label("", Ui.SMALL, Ui.DIM)
    private lateinit var cameraStage: View
    private var cameraSeparate = true
    private val widgetChips = FlowLayout(host, host.dp(12))
    private val selectedTitle = host.label("", Ui.H2, bold = true)
    private val selectedInfo = host.label("", Ui.SMALL, Ui.DIM, mono = true)
    private val editorControls = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
    private val emptyHint = host.label("在左侧画面里点一个组件，或在上面选择一个组件。", Ui.BODY, Ui.DIM)
    private val scaleBar = SeekBar(host)
    private val spreadRow = LinearLayout(host)
    private lateinit var visibleToggle: TextView
    private var selected: WidgetType? = null
    private var suppressSeek = false
    private var chipKey = ""

    override val view: View = buildView()

    private fun buildView(): View {
        val c = host
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(c.dp(28), c.dp(28), c.dp(28), c.dp(28))
        }

        val left = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        left.addView(c.label("HUD 布局", Ui.H1, bold = true))
        left.addSpaced(c.label("拖动组件调整位置，靠近中线会自动吸附。改动会实时同步到正在运行的 HUD。", Ui.SMALL, Ui.DIM), 6)
        aspect.addView(preview)
        aspect.addView(overlay)
        val stage = FrameLayout(c).apply {
            addView(aspect, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        left.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = c.dp(16) })
        left.addSpaced(ratioText, 8)
        cameraAspect.addView(cameraPreview)
        cameraAspect.addView(cameraOverlay)
        cameraStage = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(cameraTitle)
            addView(FrameLayout(c).apply {
                addView(cameraAspect, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = c.dp(6) })
        }
        left.addView(cameraStage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, .42f).apply { topMargin = c.dp(12) })
        left.addSpaced(c.row(
            c.bigButton("预设：时钟") { replace(LayoutDocument.clockOnly()) },
            c.bigButton("预设：歌词+时钟") { replace(LayoutDocument.lyricAndClock()) },
            c.bigButton("预设：全部") { replace(LayoutDocument.default()) },
        ), 12)
        left.addSpaced(c.row(
            c.bigButton("导入布局", ButtonStyle.GHOST) { host.importLayout() },
            c.bigButton("导出布局", ButtonStyle.GHOST) {
                host.exportText("customhud-layout-r${app.layoutStore.current.revision}.json", app.layoutStore.export())
            },
        ), 12)
        root.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 3f))

        val panel = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(c.card().apply {
            addView(c.sectionTitle("组件"))
            addView(c.label("亮色 = 已显示。点一下选中；不在布局里的会被加入。", Ui.SMALL, Ui.DIM))
            addSpaced(widgetChips, 12)
        })
        panel.addSpaced(c.card().apply {
            addView(selectedTitle)
            addSpaced(selectedInfo, 4)
            addSpaced(emptyHint, 12)
            addSpaced(editorControls, 12)
        }, 20)
        buildEditorControls()
        root.addView(ScrollView(c).apply { addView(panel) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2f).apply {
            marginStart = c.dp(28)
        })

        preview.onDrawCompleted = { _, _ -> overlay.postInvalidateOnAnimation() }
        cameraPreview.onDrawCompleted = { _, _ -> cameraOverlay.postInvalidateOnAnimation() }
        return root
    }

    private fun buildEditorControls() {
        val c = host
        visibleToggle = c.bigButton("") { edit { it.copy(enabled = !it.enabled) } }
        editorControls.addView(visibleToggle)

        editorControls.addSpaced(c.label("大小", Ui.BODY, bold = true), 20)
        scaleBar.max = 380 // 0.20 … 4.00 (REAR_CAMERA: 0.20 … 1.00, set per selection)
        scaleBar.minimumHeight = c.dp(56)
        scaleBar.setPadding(c.dp(24), c.dp(16), c.dp(24), c.dp(16))
        scaleBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && !suppressSeek) edit(persist = false) { it.copy(scale = (.2f + progress / 100f).coerceAtMost(it.type.maxEditorScale)) }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                edit { it }
            }
        })
        editorControls.addSpaced(c.row(
            c.bigButton("－  小") { edit { it.copy(scale = (it.scale - .05f).coerceIn(.2f, it.type.maxEditorScale)) } },
            c.bigButton("大  ＋") { edit { it.copy(scale = (it.scale + .05f).coerceIn(.2f, it.type.maxEditorScale)) } },
        ), 10)
        editorControls.addSpaced(scaleBar, 4)

        editorControls.addSpaced(c.label("微调位置", Ui.BODY, bold = true), 20)
        val step = .01f
        editorControls.addSpaced(c.row(
            View(c),
            c.bigButton("↑") { edit { it.copy(cy = (it.cy - step).coerceIn(0f, 1f)) } },
            View(c),
        ), 10)
        editorControls.addSpaced(c.row(
            c.bigButton("←") { edit { it.copy(cx = (it.cx - step).coerceIn(0f, 1f)) } },
            c.bigButton("↓") { edit { it.copy(cy = (it.cy + step).coerceIn(0f, 1f)) } },
            c.bigButton("→") { edit { it.copy(cx = (it.cx + step).coerceIn(0f, 1f)) } },
        ), 10)
        editorControls.addSpaced(c.row(
            c.bigButton("水平居中", ButtonStyle.GHOST) { edit { it.copy(cx = .5f) } },
            c.bigButton("垂直居中", ButtonStyle.GHOST) { edit { it.copy(cy = .5f) } },
        ), 10)

        spreadRow.orientation = LinearLayout.VERTICAL
        spreadRow.addView(c.label("左右间距", Ui.BODY, bold = true))
        spreadRow.addSpaced(c.row(
            c.bigButton("－  收窄") { edit { it.copy(spread = (it.spread - .02f).coerceIn(0f, .5f)) } },
            c.bigButton("放宽  ＋") { edit { it.copy(spread = (it.spread + .02f).coerceIn(0f, .5f)) } },
        ), 10)
        editorControls.addSpaced(spreadRow, 20)

        editorControls.addSpaced(c.row(
            c.bigButton("恢复默认位置") { val t = selected ?: return@bigButton; edit { WidgetConfig(t, it.enabled, t.cx, t.cy, t.sc, t.sp) } },
            c.bigButton("从布局移除", ButtonStyle.DANGER) { removeSelected() },
        ), 24)
    }

    override fun onShown() {
        val target = host.prefs.target
        val w = target?.width ?: 1024
        val h = target?.height ?: 576
        aspect.ratio = w.toFloat() / h
        ratioText.text = if (target != null) "按目标屏幕 ${target.id} 的比例 ${w}×${h} 预览" else "未选择目标屏幕，按 1024×576 预览"
        val cam = cameraPrefs.target
        cameraSeparate = target == null || target.id != cam.id
        preview.widgetFilter = if (cameraSeparate) { type -> type != WidgetType.REAR_CAMERA } else { _ -> true }
        cameraStage.visibility = if (cameraSeparate) View.VISIBLE else View.GONE
        cameraAspect.ratio = cam.width.toFloat() / cam.height.coerceAtLeast(1)
        cameraTitle.text = "后视画面所在屏幕 ${cam.id}（仪表）${cam.width}×${cam.height}：拖动“后视”框盖住中间的导航区，车速在左侧不要挡住"
        mock.open(VehicleProfile("preview"), DataMode.PREVIEW) { snapshot -> preview.submitFrame(app.layoutStore.current, snapshot) }
        onMedia()
        onLayout()
    }

    override fun onMedia() {
        preview.submitMedia(app.media.nowPlaying())
    }

    override fun onLayout() {
        preview.submitFrame(app.layoutStore.current, mock.snapshot())
        cameraPreview.submitFrame(app.layoutStore.current, mock.snapshot())
        renderChips()
        renderSelected()
    }

    private fun select(type: WidgetType?) {
        selected = type
        overlay.selected = type
        cameraOverlay.selected = type
        renderChips()
        renderSelected()
    }

    private fun move(type: WidgetType, cx: Float, cy: Float, persist: Boolean) {
        selected = type
        editType(type, persist) { widget ->
            val moved = widget.copy(cx = cx, cy = cy)
            if (type != WidgetType.REAR_CAMERA) return@editType moved
            // Keep the stored centre where the box actually is (it is clamped inside the display).
            val (w, h) = cameraDisplaySize()
            val box = CameraGeometry.box(moved, w, h) ?: return@editType moved
            moved.copy(cx = ((box.left + box.right) / 2f / w).coerceIn(0f, 1f), cy = ((box.top + box.bottom) / 2f / h).coerceIn(0f, 1f))
        }
    }

    private fun cameraDisplaySize(): Pair<Int, Int> = if (cameraSeparate) cameraPrefs.target.let { it.width to it.height }
        else (host.prefs.target?.let { it.width to it.height } ?: (1024 to 576))

    private fun renderChips() {
        val c = host
        val document = app.layoutStore.current.document
        // Dragging publishes many layouts per second; only rebuild chips when their look changes.
        val key = "$selected|" + document.widgets.joinToString(",") { "${it.type}:${it.enabled}" }
        if (key == chipKey) return
        chipKey = key
        widgetChips.removeAllViews()
        CHIP_ORDER.forEach { type ->
            val widget = document.widgets.firstOrNull { it.type == type }
            val chip = c.chip(type.label, type == selected) { chooseFromList(type) }
            if (widget == null || !widget.enabled) chip.alpha = .55f
            widgetChips.addView(chip)
        }
    }

    private fun renderSelected() {
        val type = selected
        val widget = type?.let { t -> app.layoutStore.current.document.widgets.firstOrNull { it.type == t } }
        if (type == null || widget == null) {
            selectedTitle.text = "未选择组件"
            selectedInfo.text = ""
            emptyHint.visibility = View.VISIBLE
            editorControls.visibility = View.GONE
            return
        }
        emptyHint.visibility = View.GONE
        editorControls.visibility = View.VISIBLE
        selectedTitle.text = type.label
        selectedInfo.text = String.format(Locale.US, "位置 x=%.2f  y=%.2f   大小 %.2f%s", widget.cx, widget.cy, widget.scale,
            if (type.usesSpread) String.format(Locale.US, "   间距 %.2f", widget.spread) else "") + cameraInfo(widget)
        visibleToggle.text = if (widget.enabled) "显示中 · 点击隐藏" else "隐藏中 · 点击显示"
        visibleToggle.applyButtonStyle(if (widget.enabled) ButtonStyle.SECONDARY else ButtonStyle.PRIMARY)
        spreadRow.visibility = if (type.usesSpread) View.VISIBLE else View.GONE
        suppressSeek = true
        scaleBar.max = ((type.maxEditorScale - .2f) * 100).toInt()
        scaleBar.progress = ((widget.scale.coerceAtMost(type.maxEditorScale) - .2f) * 100).toInt()
        suppressSeek = false
    }

    /** Camera box in pixels of the display that shows it (16:9, height = size × screen height). */
    private fun cameraInfo(widget: WidgetConfig): String {
        if (widget.type != WidgetType.REAR_CAMERA) return ""
        val (w, h) = cameraDisplaySize()
        val box = CameraGeometry.box(widget, w, h) ?: return ""
        return String.format(Locale.US, "\n16:9 · %d×%d px · 左 %d 上 %d（屏幕 %d×%d）· 模式：%s",
            box.width.toInt(), box.height.toInt(), box.left.toInt(), box.top.toInt(), w, h, cameraPrefs.mode.label)
    }

    private fun chooseFromList(type: WidgetType) {
        val document = app.layoutStore.current.document
        if (document.widgets.none { it.type == type }) {
            update { doc -> LayoutDocument(widgets = doc.widgets + WidgetConfig(type, true, type.cx, type.cy, type.sc, type.sp)) }
        }
        select(type)
    }

    private fun removeSelected() {
        val type = selected ?: return
        update { doc -> LayoutDocument(widgets = doc.widgets.filterNot { it.type == type }) }
        select(null)
    }

    private fun replace(document: LayoutDocument) {
        try {
            app.layoutStore.replace(document)
            app.log.add("LAYOUT", "preset committed revision=${app.layoutStore.current.revision}")
            select(null)
        } catch (error: Exception) {
            host.logError("LAYOUT", error)
        }
    }

    private fun edit(persist: Boolean = true, transform: (WidgetConfig) -> WidgetConfig) {
        val type = selected ?: return
        editType(type, persist, transform)
    }

    private fun editType(type: WidgetType, persist: Boolean, transform: (WidgetConfig) -> WidgetConfig) {
        update(persist) { doc -> LayoutDocument(widgets = doc.widgets.map { if (it.type == type) transform(it) else it }) }
    }

    private fun update(persist: Boolean = true, transform: (LayoutDocument) -> LayoutDocument) {
        try {
            app.layoutStore.update(persist, transform)
        } catch (error: Exception) {
            host.logError("LAYOUT", error)
        }
    }

    private companion object {
        val CHIP_ORDER = listOf(WidgetType.LYRIC, WidgetType.TRACK, WidgetType.CLOCK) +
            WidgetType.entries.filterNot { it == WidgetType.LYRIC || it == WidgetType.TRACK || it == WidgetType.CLOCK }
    }
}
