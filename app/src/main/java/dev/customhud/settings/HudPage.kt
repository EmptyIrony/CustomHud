package dev.customhud.settings

import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.customhud.adapters.CarProps
import dev.customhud.adapters.EcarxCapabilityProbe
import dev.customhud.camera.CameraPhase
import dev.customhud.camera.CameraPrefs
import dev.customhud.camera.CameraWindowLayer
import dev.customhud.display.android.WindowStack
import dev.customhud.camera.RearCameraController
import dev.customhud.camera.RearCameraMode
import dev.customhud.camera.RearCameraStatus
import dev.customhud.layout.LayoutDocument
import dev.customhud.layout.WidgetConfig
import dev.customhud.layout.WidgetType
import java.util.Locale
import dev.customhud.renderer.HudStyle
import dev.customhud.renderer.HudStylePrefs
import dev.customhud.renderer.LyricSplitter
import dev.customhud.renderer.MotionPreset
import dev.customhud.data.CapabilityStatus
import dev.customhud.display.HudService
import dev.customhud.display.android.DeviceCapabilities
import dev.customhud.display.session.DisplayDescriptor
import dev.customhud.display.session.DisplayTarget
import dev.customhud.display.session.SessionSnapshot
import dev.customhud.display.session.SessionState
import dev.customhud.display.session.UserObservation

class HudPage(host: MainActivity) : Page(host) {
    private val capabilities = DeviceCapabilities(host)
    private val statePill = host.label("", Ui.H2, bold = true)
    private val stageText = host.label("", Ui.BODY, Ui.DIM)
    private val targetText = host.label("", Ui.BODY)
    private val errorText = host.label("", Ui.SMALL, Ui.DANGER)
    private val observationText = host.label("", Ui.SMALL, Ui.DIM)
    private val displayList = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
    private val capabilityList = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
    private var displays = listOf<DisplayDescriptor>()
    private val stylePrefs = HudStylePrefs(host)
    private val cameraPrefs = CameraPrefs(host)
    private val cameraState = host.label("", Ui.BODY, bold = true)
    private val cameraDetail = host.label("", Ui.SMALL, Ui.DIM, mono = true)
    private val cameraLayoutHint = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
    private val modeButtons = mutableMapOf<RearCameraMode, TextView>()
    private lateinit var cameraDisplayButton: TextView
    private lateinit var opaqueButton: TextView
    private lateinit var layerButton: TextView
    private lateinit var testButton: TextView
    private val windowStackText = host.label("", Ui.SMALL, Ui.TEXT, mono = true)
    private var observedCameraSession: dev.customhud.display.session.DisplaySession? = null
    private var stopCameraSessionObserver: (() -> Unit)? = null
    private lateinit var fpsButton: TextView
    private var observedCamera: RearCameraController? = null
    private var stopCameraObserver: (() -> Unit)? = null

    override val view: View = buildView()

    private fun buildView(): View {
        val c = host
        val wide = c.resources.configuration.screenWidthDp >= 1000
        val columns = LinearLayout(c).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setPadding(c.dp(28), c.dp(28), c.dp(28), c.dp(28))
        }
        val left = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val right = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }

        left.addSpaced(c.label("HUD 控制", Ui.H1, bold = true))
        left.addSpaced(c.card().apply {
            addView(c.label("当前状态", Ui.SMALL, Ui.DIM))
            addSpaced(statePill, 6)
            addSpaced(stageText, 8)
            addSpaced(targetText, 8)
            addSpaced(errorText, 8)
        }, 20)
        left.addSpaced(c.row(
            c.bigButton("▶  启动 HUD", ButtonStyle.PRIMARY, 104) { start() },
            c.bigButton("■  停止", ButtonStyle.DANGER, 104) {
                HudService.stop(c)
                app.log.add("UI", "stopRequested")
            },
        ), 20)
        left.addSpaced(c.card().apply {
            addView(c.sectionTitle("挡风玻璃上看得到吗？"))
            addView(c.label("软件状态只代表窗口已创建、已绘制，不代表实际可见。请在车上看过之后标记。", Ui.SMALL, Ui.DIM))
            addSpaced(c.row(
                c.bigButton("看得到") { report(UserObservation.VISIBLE_CONFIRMED) },
                c.bigButton("看不到") { report(UserObservation.NOT_VISIBLE_REPORTED) },
            ))
            addSpaced(observationText, 10)
        }, 20)

        left.addSpaced(buildCameraCard(), 20)

        right.addSpaced(c.card().apply {
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(c.sectionTitle("目标屏幕"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(c.bigButton("刷新", ButtonStyle.GHOST, 60) { refresh() })
            })
            addView(c.label("必须手动选择；目标屏不存在时不会改投其他屏。", Ui.SMALL, Ui.DIM))
            addSpaced(displayList)
        })
        right.addSpaced(c.card().apply {
            addView(c.sectionTitle("选项"))
            addSpaced(ToggleRow(c, "保持屏幕常亮", "仅设置 HUD 窗口标志，不保证唤醒整车", host.prefs.keepScreenOn) { host.prefs.keepScreenOn = it }, 0)
            addSpaced(ToggleRow(c, "离开本界面后继续显示", "关闭后切到其他应用时 HUD 暂停", host.prefs.backgroundAllowed) { host.prefs.backgroundAllowed = it })
        }, 20)
        right.addSpaced(c.card().apply {
            addView(c.sectionTitle("HUD 文字"))
            addView(c.label("改动立即作用于正在运行的 HUD。HUD 上越粗、越白越亮。", Ui.SMALL, Ui.DIM))
            lateinit var weightButton: TextView
            lateinit var brightnessButton: TextView
            lateinit var widthButton: TextView
            lateinit var rowsButton: TextView
            lateinit var secondButton: TextView
            lateinit var contextButton: TextView
            lateinit var motionButton: TextView
            lateinit var splitButton: TextView
            lateinit var splitCharsButton: TextView
            fun labels() {
                val style = stylePrefs.style
                weightButton.text = "粗细：${HudStyle.WEIGHT_LABELS[style.weight]}"
                brightnessButton.text = "亮度：${style.brightness}%"
                widthButton.text = "歌词宽度：${style.textWidth}%"
                rowsButton.text = "歌词：${if (style.lyricRows >= 2) "两行" else "单行"}"
                secondButton.text = "第二行：${HudStyle.SECOND_LABELS[style.lyricSecond]}"
                contextButton.text = "第二行亮度：${style.contextLevel}%"
                motionButton.text = "动效：${MotionPreset.LABELS[style.lyricMotionStyle]}"
                splitButton.text = "长句拆分：${LyricSplitter.MODE_LABELS[style.splitMode]}"
                splitCharsButton.text = if (style.splitMode == LyricSplitter.CHARS) "每段最多：${style.splitChars} 字" else "每段字数（按字数时）：${style.splitChars}"
            }
            fun change(next: (HudStyle) -> HudStyle) {
                stylePrefs.style = next(stylePrefs.style)
                labels()
                app.log.add("UI", "hud style ${stylePrefs.style}")
            }
            rowsButton = c.bigButton("") { change { it.copy(lyricRows = if (it.lyricRows >= 2) 1 else 2) } }
            secondButton = c.bigButton("") { change { it.copy(lyricSecond = (it.lyricSecond + 1) % HudStyle.SECOND_LABELS.size) } }
            contextButton = c.bigButton("") {
                val steps = HudStyle.CONTEXT_LEVEL_STEPS
                change { it.copy(contextLevel = steps[(steps.indexOf(it.contextLevel).coerceAtLeast(0) + 1) % steps.size]) }
            }
            weightButton = c.bigButton("") {
                val style = stylePrefs.style
                stylePrefs.style = style.copy(weight = (style.weight + 1) % HudStyle.WEIGHT_LABELS.size)
                labels()
                app.log.add("UI", "hud style ${stylePrefs.style}")
            }
            brightnessButton = c.bigButton("") {
                val style = stylePrefs.style
                val steps = BRIGHTNESS_STEPS
                stylePrefs.style = style.copy(brightness = steps[(steps.indexOf(style.brightness).coerceAtLeast(0) + 1) % steps.size])
                labels()
                app.log.add("UI", "hud style ${stylePrefs.style}")
            }
            fun widthBy(delta: Int) = change {
                it.copy(textWidth = (it.textWidth + delta).coerceIn(HudStyle.MIN_TEXT_WIDTH, HudStyle.MAX_TEXT_WIDTH))
            }
            val widthMinus = c.bigButton("宽度 −5%") { widthBy(-HudStyle.TEXT_WIDTH_STEP) }
            val widthPlus = c.bigButton("宽度 +5%") { widthBy(HudStyle.TEXT_WIDTH_STEP) }
            widthButton = c.bigButton("") { change { it.copy(textWidth = HudStyle.DEFAULT_TEXT_WIDTH) } }
            motionButton = c.bigButton("") { change { it.copy(lyricMotionStyle = (it.lyricMotionStyle + 1) % MotionPreset.LABELS.size) } }
            splitButton = c.bigButton("") { change { it.copy(splitMode = (it.splitMode + 1) % LyricSplitter.MODE_LABELS.size) } }
            splitCharsButton = c.bigButton("") {
                val steps = HudStyle.SPLIT_CHAR_STEPS
                change { it.copy(splitChars = steps[(steps.indexOf(it.splitChars).coerceAtLeast(0) + 1) % steps.size]) }
            }
            labels()
            addSpaced(c.row(weightButton, brightnessButton))
            addSpaced(c.row(widthMinus, widthButton, widthPlus))
            addView(c.label("歌词宽度：歌词和歌名最多占 HUD 宽度的百分比（30–100%，点中间恢复 80%）；挡风玻璃上两侧被切掉时调小。放不下时先拆句/缩小，单行模式还会折成两行，最后以“…”结尾。", Ui.SMALL, Ui.DIM))
            addSpaced(c.row(rowsButton, secondButton))
            addSpaced(c.row(contextButton, motionButton))
            addView(c.label("两行：上面是当前句（大、亮），下面是下一句（小、暗）；换句时整体向上滚。蓝牙/标题歌词没有下一句，改为上一句在上、当前句在下。动效：灵动弹入 / 轻盈波浪 / 丝滑滑动 / 柔光放大 / 关闭（直接切换，不额外重绘，用来对比闪烁）。只在换句的约 1 秒内重绘。", Ui.SMALL, Ui.DIM))
            addSpaced(c.row(splitButton, splitCharsButton))
            addView(c.label("长句拆分：一句太长时拆成几段，按这句的时间依次显示，每段像新的一句一样滚上来。自动 = 需要缩小到 85% 以下才放得下时拆；按字数 = 超过设定字数就拆（英文字母算半个字）。需要 QQ 音乐等带时间轴的歌词，蓝牙歌词没有时间，只能缩小。", Ui.SMALL, Ui.DIM))
            addSpaced(ToggleRow(c, "防闪烁：内容不变时不重绘", "关闭则每秒重绘（仅用于对比测试闪烁）", stylePrefs.style.redrawOnlyOnChange) {
                stylePrefs.style = stylePrefs.style.copy(redrawOnlyOnChange = it)
                app.log.add("UI", "hud style ${stylePrefs.style}")
            })
        }, 20)
        right.addSpaced(c.card().apply {
            addView(c.sectionTitle("权限与能力"))
            addSpaced(capabilityList, 0)
            addSpaced(c.row(
                c.bigButton("悬浮窗设置") { host.openSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, true) },
                c.bigButton("请求车况权限") {
                    host.requestPermissions(CarProps.PERMISSIONS.toTypedArray(), MainActivity.REQUEST_PERMISSIONS)
                },
            ))
        }, 20)

        if (wide) {
            columns.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            columns.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = c.dp(28) })
        } else {
            columns.addView(left)
            columns.addSpaced(right, 28)
        }
        return ScrollView(c).apply { addView(columns) }
    }

    private fun buildCameraCard(): View {
        val c = host
        return c.card().apply {
            addView(c.sectionTitle("后视摄像头 · zeekr:202"))
            addView(c.label("画面显示在“画面屏幕”上（默认屏幕 1 = 仪表，HDMI 1），和 HUD 目标屏不同时会单独开一个透明窗口，随 HUD 自动启动。" +
                "只有 platform 签名的 release 包能取流。", Ui.SMALL, Ui.DIM))
            addSpaced(cameraState, 12)
            addSpaced(cameraDetail, 6)
            addSpaced(c.label("什么时候显示", Ui.BODY, bold = true), 16)
            val modes = RearCameraMode.entries.map { mode ->
                c.bigButton(mode.label) {
                    cameraPrefs.mode = mode
                    app.log.add("UI", "rear camera mode=$mode")
                    renderCameraControls()
                }.also { modeButtons[mode] = it }
            }
            addSpaced(c.row(*modes.toTypedArray()), 8)
            addView(c.label("非 P 挡：P 挡不打开相机（默认）。挡位优先用 ECARX 0x200200（与 CarLight 换挡音效相同），android.car GEAR_SELECTION 兜底；都读不到时显示“挡位不可用”，不猜。", Ui.SMALL, Ui.DIM))
            opaqueButton = c.bigButton("") {
                cameraPrefs.opaque = !cameraPrefs.opaque
                app.log.add("UI", "rear camera opaque=${cameraPrefs.opaque}")
                renderCameraControls()
            }
            fpsButton = c.bigButton("") {
                val steps = CameraPrefs.FPS_STEPS
                cameraPrefs.fps = steps[(steps.indexOf(cameraPrefs.fps) + 1) % steps.size]
                app.log.add("UI", "rear camera fps=${cameraPrefs.fps}")
                renderCameraControls()
            }
            addSpaced(c.row(opaqueButton, fpsButton), 16)
            layerButton = c.bigButton("") {
                val all = CameraWindowLayer.entries
                cameraPrefs.windowLayer = all[(all.indexOf(cameraPrefs.windowLayer) + 1) % all.size]
                app.log.add("UI", "rear camera window layer=${cameraPrefs.windowLayer}")
                renderCameraControls()
            }
            testButton = c.bigButton("") {
                cameraPrefs.testPattern = !cameraPrefs.testPattern
                app.log.add("UI", "rear camera test pattern=${cameraPrefs.testPattern}")
                renderCameraControls()
            }
            addSpaced(c.row(layerButton, testButton), 12)
            addView(c.label("测试画面：用不刷新的纯白方块代替相机，看地图是否照样闪。层级：切换后立即重建仪表上的相机窗口（HUD 不受影响）。只有盖在画面上的地图 / 指示灯也是 Android 窗口时才有效；若真的盖住了，框内的指示灯（如左上角红色 P）也会被挡，请把框避开指示灯。和 HUD 同屏时此项不生效。", Ui.SMALL, Ui.DIM))
            addSpaced(c.bigButton("列出画面屏幕上的窗口（从上到下）", ButtonStyle.GHOST) { listWindows() }, 12)
            addSpaced(windowStackText, 8)
            addView(c.label("闪烁对照：默认“不透明”。只有闪烁仍在时才试“透明（旧版）”，若旧版明显更闪，说明原因是画面透出底下的导航。帧率下次打开相机生效（挂 P 再挂 D，或重启 HUD）。", Ui.SMALL, Ui.DIM))
            cameraDisplayButton = c.bigButton("") { cycleCameraDisplay() }
            addSpaced(cameraDisplayButton, 16)
            addView(c.label("切换后下次启动 HUD 生效。位置和大小在「布局」页的仪表预览里调整。", Ui.SMALL, Ui.DIM))
            addSpaced(cameraLayoutHint, 12)
        }
    }

    private fun cycleCameraDisplay() {
        val candidates = capabilities.displays().filter { it.id != Display.DEFAULT_DISPLAY && it.valid }
        if (candidates.isEmpty()) {
            host.toast("没有找到副屏")
            return
        }
        val current = cameraPrefs.target
        val index = candidates.indexOfFirst { it.id == current.id }
        val next = candidates[(index + 1) % candidates.size]
        cameraPrefs.target = CameraPrefs.Target(next.id, next.name, next.width, next.height)
        app.log.add("UI", "rear camera display=${next.id} name=${next.name} ${next.width}x${next.height}")
        renderCameraControls()
    }

    private fun renderCameraControls() {
        val c = host
        val mode = cameraPrefs.mode
        modeButtons.forEach { (m, button) -> button.applyButtonStyle(if (m == mode) ButtonStyle.PRIMARY else ButtonStyle.SECONDARY) }
        opaqueButton.text = if (cameraPrefs.opaque) "画面：不透明（防闪烁）" else "画面：透明（旧版，对照用）"
        fpsButton.text = "相机帧率：${cameraPrefs.fps} fps"
        val layer = cameraPrefs.windowLayer
        layerButton.text = "层级：${layer.label}（type ${layer.type}，约第 ${layer.androidLayer} 层）"
        testButton.text = if (cameraPrefs.testPattern) "测试画面：静止白块 · 开" else "测试画面：关（显示相机）"
        val t = cameraPrefs.target
        val hudTarget = host.prefs.target
        val where = if (hudTarget != null && hudTarget.id == t.id) "（与 HUD 同屏）" else "（单独窗口）"
        cameraDisplayButton.text = "画面屏幕：屏幕 ${t.id}${if (t.name.isNotEmpty()) " · ${t.name}" else ""} · ${t.width}×${t.height} $where · 点击切换"
        cameraLayoutHint.removeAllViews()
        val widget = app.layoutStore.current.document.widgets.firstOrNull { it.type == WidgetType.REAR_CAMERA }
        if (widget == null || !widget.enabled) {
            cameraLayoutHint.addView(c.label(if (widget == null) "当前布局里没有“后视”组件。" else "“后视”组件在布局里被隐藏了。", Ui.SMALL, Ui.WARN))
            cameraLayoutHint.addSpaced(c.bigButton(if (widget == null) "把后视加入布局" else "显示后视组件") { addCameraWidget() }, 8)
        }
    }

    private fun addCameraWidget() {
        val type = WidgetType.REAR_CAMERA
        try {
            app.layoutStore.update { doc ->
                if (doc.widgets.any { it.type == type }) {
                    LayoutDocument(widgets = doc.widgets.map { if (it.type == type) it.copy(enabled = true) else it })
                } else {
                    LayoutDocument(widgets = doc.widgets + WidgetConfig(type, true, type.cx, type.cy, type.sc, type.sp))
                }
            }
            app.log.add("LAYOUT", "rear camera widget added/enabled revision=${app.layoutStore.current.revision}")
        } catch (error: Exception) {
            host.logError("LAYOUT", error)
        }
        renderCameraControls()
    }

    private fun listWindows() {
        val displayId = cameraPrefs.target.id
        windowStackText.text = "正在读取屏幕 $displayId 的窗口…"
        Thread {
            val lines = WindowStack.query(displayId)
            app.log.add("CAMERA", "windows on display $displayId (top first):\n" + lines.joinToString("\n"))
            host.runOnUiThread {
                windowStackText.text = "屏幕 $displayId 的窗口（#0 在最上面；本应用包名 ${host.packageName}）：\n" + lines.take(30).joinToString("\n")
            }
        }.apply { name = "window-stack" }.start()
    }

    private fun observeCamera() {
        val clusterSession = HudService.cameraSession
        if (clusterSession !== observedCameraSession) {
            stopCameraSessionObserver?.invoke()
            observedCameraSession = clusterSession
            stopCameraSessionObserver = clusterSession?.observe { host.runOnUiThread { renderCamera(observedCamera?.status) } }
        }
        val camera = HudService.activeCamera
        if (camera === observedCamera) return
        stopCameraObserver?.invoke()
        observedCamera = camera
        stopCameraObserver = camera?.observe { status -> host.runOnUiThread { renderCamera(status) } }
        if (camera == null) renderCamera(null)
    }

    private fun renderCamera(status: RearCameraStatus?) {
        if (status == null) {
            cameraState.text = "HUD 未运行 · 模式：${cameraPrefs.mode.label}"
            cameraState.setTextColor(Ui.DIM)
            cameraDetail.text = ""
            return
        }
        cameraState.text = "${phaseLabel(status.phase)} · ${status.reason}"
        cameraState.setTextColor(when (status.phase) {
            CameraPhase.STREAMING -> Ui.ACCENT
            CameraPhase.RETRY_WAIT -> Ui.DANGER
            else -> if (status.gear.available || !status.mode.needsGear) Ui.TEXT else Ui.WARN
        })
        cameraDetail.text = buildString {
            append("模式 ").append(status.mode.label).append(" · 挡位 ").append(status.gear.label()).append('\n')
            append("会话 gen=").append(status.generation).append(" 窗口").append(if (status.hostActive) "已附着" else "未运行")
            append(" · 画面区域").append(if (status.surfaceReady) "就绪" else "未就绪")
            append(" · 打开 ").append(status.openCount).append(" 次")
            status.firstFrameMs?.let { append(" · 首帧 ").append(it).append(" ms") }
            status.renderFps?.let { append(String.format(Locale.ROOT, " · %.1f fps", it)) }
            status.lastError?.let { append("\n最近错误：").append(it) }
            HudService.cameraSession?.snapshot?.takeIf { it.desiredRunning || it.lastError != null }?.let { cluster ->
                append("\n仪表窗口 ").append(cluster.state.name).append(" gen=").append(cluster.generation)
                cluster.lastError?.let { append(" · ").append(it.code).append(' ').append(it.message) }
            }
        }
    }

    private fun phaseLabel(phase: CameraPhase): String = when (phase) {
        CameraPhase.CLOSED -> "未打开"
        CameraPhase.OPENING -> "正在打开"
        CameraPhase.STREAMING -> "出图中"
        CameraPhase.CLOSING -> "正在关闭"
        CameraPhase.RETRY_WAIT -> "等待重试"
        CameraPhase.RELEASED -> "已释放"
    }

    override fun onShown() {
        refresh()
        onSession(host.sessionSnapshot)
        renderCameraControls()
        observeCamera()
    }

    override fun onHidden() {
        stopCameraObserver?.invoke()
        stopCameraObserver = null
        observedCamera = null
        stopCameraSessionObserver?.invoke()
        stopCameraSessionObserver = null
        observedCameraSession = null
    }

    override fun onLayout() {
        renderCameraControls()
    }

    override fun onSession(snapshot: SessionSnapshot?) {
        if (view.isShown) observeCamera()
        val (text, color) = stateLabel(snapshot)
        statePill.text = "●  $text"
        statePill.setTextColor(color)
        if (snapshot == null) {
            stageText.text = "服务未运行"
            targetText.text = host.prefs.target?.let { "已选目标：屏幕 ${it.id} · ${it.name}" } ?: "尚未选择目标屏幕"
            errorText.visibility = View.GONE
            observationText.text = ""
            return
        }
        fun mark(ok: Boolean) = if (ok) "✓" else "–"
        stageText.text = "窗口已添加 ${mark(snapshot.addViewReturned)}   已附着 ${mark(snapshot.viewAttached)}   已绘制 ${mark(snapshot.firstDrawAtMonotonic != null)}" +
            if (snapshot.retryCount > 0) "   重试 ${snapshot.retryCount}" else ""
        targetText.text = snapshot.target?.let { "目标：屏幕 ${it.displayId} · ${it.name}" } ?: "目标：—"
        val error = snapshot.lastError
        errorText.visibility = if (error == null) View.GONE else View.VISIBLE
        errorText.text = error?.let { "${it.code}：${it.message}" } ?: ""
        observationText.text = when (snapshot.userObservation) {
            UserObservation.UNKNOWN -> "本次启动尚未标记"
            UserObservation.VISIBLE_CONFIRMED -> "已标记：看得到 ✓"
            UserObservation.NOT_VISIBLE_REPORTED -> "已标记：看不到"
        }
    }

    private fun refresh() {
        displays = capabilities.displays().filter { it.id != Display.DEFAULT_DISPLAY && it.valid }
        renderDisplays()
        renderCapabilities()
    }

    private fun renderDisplays() {
        val c = host
        displayList.removeAllViews()
        val saved = host.prefs.target
        if (displays.isEmpty()) {
            displayList.addSpaced(c.label("没有找到副屏", Ui.BODY, Ui.WARN), 12)
        }
        displays.forEach { display ->
            val selected = saved != null && saved.id == display.id && saved.name == display.name
            val item = TextView(c).apply {
                text = "${if (selected) "◉" else "○"}  屏幕 ${display.id} · ${display.name}\n     ${display.width}×${display.height} · ${display.densityDpi} dpi · flags 0x${display.flags.toString(16)}"
                textSize = Ui.BODY
                setLineSpacing(0f, 1.25f)
                minHeight = c.dp(96)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(c.dp(20), c.dp(12), c.dp(20), c.dp(12))
                setTextColor(if (selected) Ui.ACCENT else Ui.TEXT)
                background = c.pressable(if (selected) Ui.ACCENT_DARK else Ui.SURFACE_HI, strokeColor = if (selected) Ui.ACCENT else Ui.STROKE)
                setOnClickListener {
                    host.prefs.target = UiPrefs.Target(display.id, display.name, display.width, display.height)
                    app.log.add("UI", "target selected display=${display.id} name=${display.name}")
                    renderDisplays()
                    onSession(host.sessionSnapshot)
                }
            }
            displayList.addSpaced(item, 12)
        }
        if (saved != null && displays.none { it.id == saved.id && it.name == saved.name }) {
            displayList.addSpaced(c.label("上次选择的屏幕 ${saved.id} · ${saved.name} 当前不存在", Ui.SMALL, Ui.WARN), 12)
        }
    }

    private fun renderCapabilities() {
        val c = host
        capabilityList.removeAllViews()
        val items = capabilities.report() + EcarxCapabilityProbe(c).inspect()
        items.forEach { item ->
            val ok = item.status == CapabilityStatus.AVAILABLE
            capabilityList.addSpaced(c.label("${if (ok) "✓" else "✗"}  ${item.reason}", Ui.SMALL, if (ok) Ui.TEXT else Ui.WARN), 6)
        }
        app.log.add("CAPABILITY", "displays=${displays.map { "id=${it.id},name=${it.name},size=${it.width}x${it.height}" }}")
    }

    private fun start() {
        val saved = host.prefs.target
        val display = displays.firstOrNull { saved != null && it.id == saved.id && it.name == saved.name }
        if (display == null) {
            host.toast("请先在右侧选择目标屏幕")
            return
        }
        try {
            val target = DisplayTarget(display.id, display.name)
            app.log.add("UI", "startRequested display=${target.displayId} name=${target.name} keepScreenOn=${host.prefs.keepScreenOn}")
            HudService.start(host, target, host.prefs.keepScreenOn, host.prefs.backgroundAllowed)
            host.observeSession()
        } catch (error: Exception) {
            host.logError("SERVICE_UNAVAILABLE", error)
        }
    }

    private fun report(observation: UserObservation) {
        val session = HudService.activeSession
        if (session == null) {
            host.toast("HUD 没有在运行")
            return
        }
        session.reportObservation(observation, session.snapshot.generation)
    }

    companion object {
        private val BRIGHTNESS_STEPS = listOf(100, 85, 70, 55, 40)

        fun stateLabel(snapshot: SessionSnapshot?): Pair<String, Int> = when (snapshot?.state) {
            null, SessionState.STOPPED -> "未启动" to Ui.DIM
            SessionState.PREPARING -> "准备中" to Ui.WARN
            SessionState.WAITING_DISPLAY -> "等待目标屏幕" to Ui.WARN
            SessionState.ATTACHING -> "正在显示" to Ui.WARN
            SessionState.ATTACHED -> "运行中" to Ui.ACCENT
            SessionState.SUSPENDED -> "已暂停" to Ui.WARN
            SessionState.RECOVERING -> "恢复中" to Ui.WARN
            SessionState.STOPPING -> "正在停止" to Ui.WARN
            SessionState.FAILED -> "失败" to Ui.DANGER
        }
    }
}
