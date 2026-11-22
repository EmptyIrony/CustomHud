package dev.customhud.settings

import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.os.SystemClock
import dev.customhud.BuildConfig
import dev.customhud.adapters.AndroidCarDataSource
import dev.customhud.data.DataMode
import dev.customhud.data.VehicleProfile
import dev.customhud.data.VehicleSignal
import dev.customhud.media.FieldKey
import dev.customhud.media.FieldValue
import dev.customhud.media.LyricMode
import dev.customhud.media.TitleLyricMode
import dev.customhud.media.ZeekrLyricClient
import dev.customhud.media.ZeekrStage
import dev.customhud.probe.ProbeRunner
import dev.customhud.probe.ReportPager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProbePage(host: MainActivity) : Page(host) {
    private val handler = Handler(Looper.getMainLooper())
    private val progressText = host.label("准备好后，在车机 QQ 音乐播放一首有歌词的歌，再点击开始。", Ui.BODY, Ui.DIM)
    private val progressBar = ProgressBar(host, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        visibility = View.GONE
    }
    private lateinit var startButton: TextView
    private val liveTab = host.chip("实时字段 · 选歌词来源", true) { showTab(Tab.LIVE) }
    private val zeekrTab = host.chip("极氪歌词接口 · 实时", false) { showTab(Tab.ZEEKR) }
    private val carTab = host.chip("车况 · 实时", false) { showTab(Tab.CAR) }
    private val reportTab = host.chip("报告 · 用手机拍照", false) { showTab(Tab.REPORT) }
    private val livePanel = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
    private val carPanel = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
    private val zeekrPanel = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
    private val zeekrStage = host.label("", Ui.BODY, Ui.ACCENT, bold = true)
    private val zeekrDetail = host.label("", Ui.SMALL, Ui.DIM)
    private val zeekrError = host.label("", Ui.SMALL, Ui.DANGER).apply { visibility = View.GONE }
    private val zeekrLine = host.label("", 34f, Ui.TEXT, bold = true).apply { gravity = Gravity.CENTER }
    private val zeekrSong = host.label("", Ui.BODY, Ui.DIM).apply { gravity = Gravity.CENTER }
    private val zeekrInfo = host.label("", Ui.SMALL, Ui.TEXT, mono = true)
    private val zeekrHistory = host.label("", Ui.SMALL, Ui.DIM, mono = true)
    private lateinit var zeekrConnect: TextView
    private lateinit var zeekrUse: TextView
    private lateinit var zeekrTitleMode: TextView
    private val carStatus = host.label("", Ui.BODY, Ui.ACCENT, bold = true)
    private val carSignals = host.label("", 19f, Ui.TEXT, mono = true)
    private val carProperties = host.label("", Ui.SMALL, Ui.DIM, mono = true)
    private var carSource: AndroidCarDataSource? = null
    private var tab = Tab.LIVE
    private val carTick = object : Runnable {
        override fun run() {
            if (!shown || tab != Tab.CAR) return
            renderCar()
            handler.postDelayed(this, 1_000)
        }
    }
    private val reportPanel = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
    private val accessText = host.label("", Ui.SMALL, Ui.DIM)
    private val sourceText = host.label("", Ui.BODY, Ui.ACCENT, bold = true)
    private val fieldList = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
    private val pageHeader = host.label("", Ui.H2, Ui.ACCENT, bold = true, mono = true)
    private val reportText = host.label("还没有报告。点击上方“开始探测”。", 20f, Ui.TEXT, mono = true)
    private val pageCounter = host.label("", Ui.BODY, bold = true).apply { gravity = Gravity.CENTER }
    private var report: List<String> = emptyList()
    private var reportTime = ""
    private var pages: List<List<String>> = emptyList()
    private var pageIndex = 0
    private var cancelProbe: (() -> Unit)? = null
    private var shown = false
    private var renderPending = false

    override val view: View = buildView()

    private fun buildView(): View {
        val c = host
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(c.dp(28), c.dp(28), c.dp(28), c.dp(20))
        }
        root.addView(c.label("环境探测", Ui.H1, bold = true))
        root.addSpaced(c.label("只读取系统公开接口：车辆属性、媒体会话、媒体通知、应用公开声明的组件、系统状态查询和网络。不修改任何设置。", Ui.SMALL, Ui.DIM), 6)

        startButton = c.bigButton("开始探测（40 秒）", ButtonStyle.PRIMARY, 88) { toggleProbe() }
        root.addSpaced(LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(startButton, LinearLayout.LayoutParams(c.dp(360), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                addView(progressText)
                addSpaced(progressBar, 8)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = c.dp(24) })
        }, 18)

        root.addSpaced(LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(liveTab)
            addView(zeekrTab, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = c.dp(12) })
            addView(carTab, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = c.dp(12) })
            addView(reportTab, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = c.dp(12) })
        }, 20)

        buildLivePanel()
        buildZeekrPanel()
        buildCarPanel()
        buildReportPanel()
        val panels = FrameLayout(c).apply {
            addView(livePanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(zeekrPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(carPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(reportPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        root.addView(panels, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = c.dp(16) })
        showTab(Tab.LIVE)
        return root
    }

    private fun buildZeekrPanel() {
        val c = host
        zeekrConnect = c.bigButton("连接", ButtonStyle.PRIMARY) {
            val zeekr = app.media.zeekr
            if (zeekr.state.enabled) zeekr.disable() else zeekr.enable()
            app.log.add("MEDIA", "zeekr lyric ${if (zeekr.state.enabled) "disable" else "enable"} requested")
        }
        zeekrUse = c.bigButton("设为 HUD 歌词来源", ButtonStyle.GHOST) {
            val key = FieldKey(ZeekrLyricClient.FIELD_PACKAGE, ZeekrLyricClient.FIELD_LINE)
            app.media.lyricSource = key
            app.log.add("MEDIA", "lyric source set ${key.spec}")
            host.toast("HUD 歌词改为读取极氪媒体中心当前句")
        }
        zeekrTitleMode = c.bigButton("标题当歌词：自动", ButtonStyle.GHOST) {
            val zeekr = app.media.zeekr
            val modes = TitleLyricMode.entries
            val next = modes[(zeekr.state.titleMode.ordinal + 1) % modes.size]
            zeekr.setTitleMode(next)
            app.log.add("MEDIA", "zeekr title-as-lyric mode ${next.name}")
            host.toast("标题当歌词：${next.label}")
        }
        zeekrPanel.addView(c.card().apply {
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(c).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(zeekrStage)
                    addSpaced(zeekrDetail, 6)
                    addSpaced(zeekrError, 6)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(zeekrConnect, LinearLayout.LayoutParams(c.dp(180), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = c.dp(12) })
                addView(zeekrUse, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = c.dp(12) })
                addView(zeekrTitleMode, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = c.dp(12) })
            })
            addSpaced(c.label("直接绑定车机媒体中心（com.zeekr.mediacenter）的 Widget 接口：订阅歌曲与播放进度，按整首 LRC 计算当前句；蓝牙音源用推送的当前句；手机蓝牙歌词（标题即歌词）会自动识别，也可用「标题当歌词」强制开启。只读，不控制播放。", Ui.SMALL, Ui.DIM), 10)
        })
        zeekrPanel.addView(ScrollView(c).apply {
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                addView(LinearLayout(c).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    minimumHeight = c.dp(150)
                    background = c.rounded(android.graphics.Color.BLACK, 12, Ui.STROKE, 1)
                    setPadding(c.dp(20), c.dp(16), c.dp(20), c.dp(16))
                    addView(zeekrLine)
                    addSpaced(zeekrSong, 10)
                })
                addSpaced(zeekrInfo, 14)
                addSpaced(c.sectionTitle("最近收到的歌词句"), 20)
                addView(zeekrHistory)
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = c.dp(14) })
    }

    private fun renderZeekr() {
        val state = app.media.zeekr.state
        val stageColor = when (state.stage) {
            ZeekrStage.RECEIVING, ZeekrStage.REGISTERED -> Ui.ACCENT
            ZeekrStage.FAILED -> Ui.DANGER
            ZeekrStage.OFF -> Ui.DIM
            else -> Ui.WARN
        }
        zeekrStage.text = "状态：${state.stage.label}${if (state.enabled) "" else "（未启用）"}"
        zeekrStage.setTextColor(stageColor)
        zeekrDetail.text = listOf(state.detail, state.bindInfo, state.descriptorCheck.orEmpty())
            .filter { it.isNotBlank() }.joinToString("\n")
        val errorTime = state.lastErrorAt?.let { "（${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(it))}）" }.orEmpty()
        zeekrError.text = state.lastError?.let { "最近错误$errorTime：$it" }.orEmpty()
        zeekrError.visibility = if (state.lastError == null) View.GONE else View.VISIBLE
        zeekrConnect.text = if (state.enabled) "断开" else "连接"
        zeekrConnect.applyButtonStyle(if (state.enabled) ButtonStyle.DANGER else ButtonStyle.PRIMARY)
        val isSource = app.media.lyricSource?.packageName == ZeekrLyricClient.FIELD_PACKAGE
        zeekrTitleMode.text = "标题当歌词：${state.titleMode.label}"
        zeekrUse.text = if (isSource) "★ 已是 HUD 歌词来源" else "设为 HUD 歌词来源"
        zeekrLine.text = when {
            state.uuid == null && state.title == null -> "（还没有歌曲数据）"
            state.line.isNullOrBlank() -> "（当前没有歌词句）"
            else -> state.line
        }
        zeekrSong.text = listOfNotNull(
            state.translatedLine?.takeIf { it.isNotBlank() },
            // When the Bluetooth title *is* the lyric line, don't repeat it under the line.
            listOfNotNull(state.title.takeUnless { state.mode == LyricMode.BLUETOOTH_TITLE }, state.artist).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { null },
        ).joinToString("\n").ifEmpty { "—" }
        val time = SimpleDateFormat("HH:mm:ss", Locale.US)
        zeekrInfo.text = listOf(
            "歌词模式   ${state.mode.label}${if (state.showsLyric) "" else "（暂停中，HUD 不显示）"}",
            "标题当歌词 ${state.titleMode.label} → ${state.titleDecision}",
            "当前行     ${if (state.lineCount > 0) "${state.lineIndex + 1} / ${state.lineCount}" else "—"}   进度 ${state.progressMs?.let(::clock) ?: "—"}",
            "下一句     ${state.nextLine ?: "—"}",
            "当前句快照 ${state.sentence?.ifBlank { "（空）" } ?: "—"}",
            "音乐应用   ${state.packageName ?: "—"}   音源 ${state.sourceType?.let { "${ZeekrLyricClient.SOURCE_TYPES[it] ?: "?"}($it)" } ?: "—"}",
            "播放状态   ${state.playbackStatus?.let { "${ZeekrLyricClient.PLAYBACK_STATUSES[it] ?: "?"}($it)" } ?: "—"}   歌词可见位 ${state.lyricVisible?.let { if (it) "是" else "否" } ?: "—"}",
            "歌词长度   原文 ${state.lyricLength} 字   翻译 ${state.translatedLength} 字",
            "uuid       ${state.uuid?.take(40) ?: "—"}",
            "推送       歌曲 ${state.infoPushes}   进度 ${state.progressPushes}   其他 ${state.otherCallbacks}   （${time.format(Date())}）",
            "构建       ${BuildConfig.BUILD_ID}",
        ).joinToString("\n")
        zeekrHistory.text = state.history.asReversed().joinToString("\n") { (at, line) -> "${time.format(Date(at))}  $line" }
            .ifEmpty { "（暂无）" }
    }

    private fun clock(ms: Long): String = String.format(Locale.US, "%d:%02d.%01d", ms / 60_000, ms / 1000 % 60, ms / 100 % 10)

    private fun buildCarPanel() {
        val c = host
        carPanel.addView(c.card().apply {
            addView(carStatus)
            addSpaced(c.label("直接读取 Android 标准车辆属性（与 HUD 相同的读取方式），每秒刷新。✗ 后面是不可用的原因，可拍照反馈。", Ui.SMALL, Ui.DIM), 6)
        })
        carPanel.addView(ScrollView(c).apply {
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                addView(carSignals)
                addSpaced(c.sectionTitle("标准属性与权限"), 20)
                addSpaced(carProperties, 8)
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = c.dp(14) })
    }

    private fun renderCar() {
        val source = carSource ?: AndroidCarDataSource(host).also { source ->
            carSource = source
            source.open(VehicleProfile("probe-live"), DataMode.DEVICE) { }
        }
        carStatus.text = "android.car：${source.statusLine}"
        val snapshot = source.snapshot()
        val capabilities = source.capabilities()
        val now = SystemClock.elapsedRealtime()
        carSignals.text = CAR_ROWS.joinToString("\n") { (signal, name) ->
            val value = snapshot.value(signal)
            val shown = value?.value
            when {
                shown != null -> "✓ ${name.padEnd(6, '　')} ${formatValue(shown)}  ← ${value.source.removePrefix("android.car:")}" +
                    if (!value.isFresh(now)) "（过期）" else ""
                value?.error != null -> "✗ ${name.padEnd(6, '　')} ${value.error.code}：${value.error.message.take(90)}"
                else -> "… ${name.padEnd(6, '　')} ${capabilities[signal]?.reason?.take(90) ?: "等待连接"}"
            }
        }
        carProperties.text = source.propertyReport().joinToString("\n")
    }

    private fun closeCar() {
        handler.removeCallbacks(carTick)
        carSource?.close()
        carSource = null
    }

    private fun formatValue(value: Any): String = when (value) {
        is Double -> String.format(Locale.US, "%.1f", value)
        is Boolean -> if (value) "是" else "否"
        else -> value.toString()
    }

    private fun buildLivePanel() {
        val c = host
        livePanel.addView(c.card().apply {
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(c).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(sourceText)
                    addSpaced(accessText, 6)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(c.bigButton("清除歌词来源", ButtonStyle.GHOST) { app.media.lyricSource = null })
                addView(c.bigButton("通知使用权", ButtonStyle.GHOST) {
                    host.openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, false)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = c.dp(12) })
            })
            addSpaced(c.label("边听歌边看：跟着歌词一句一句变化的那个字段就是歌词。点它即可设为 HUD 大字歌词的来源。", Ui.SMALL, Ui.DIM), 10)
        })
        livePanel.addView(ScrollView(c).apply { addView(fieldList) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = c.dp(14)
        })
    }

    private fun buildReportPanel() {
        val c = host
        reportPanel.addView(pageHeader)
        val sheet = FrameLayout(c).apply {
            background = c.rounded(android.graphics.Color.BLACK, 12, Ui.STROKE, 1)
            setPadding(c.dp(20), c.dp(16), c.dp(20), c.dp(16))
            addView(reportText, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        reportPanel.addView(sheet, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = c.dp(10) })
        reportPanel.addSpaced(c.row(
            c.bigButton("◀  上一页") { turn(-1) },
            pageCounter,
            c.bigButton("下一页  ▶") { turn(1) },
            c.bigButton("导出为文件", ButtonStyle.GHOST) {
                if (report.isEmpty()) host.toast("还没有报告") else host.exportText("customhud-probe-${BuildConfig.BUILD_ID}.txt", report.joinToString("\n"))
            },
        ), 12)
        reportText.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
            if (r - l != orr - ol || b - t != ob - ot) reportText.post { repaginate() }
        }
    }

    private fun showTab(next: Tab) {
        tab = next
        liveTab.setChipSelected(next == Tab.LIVE)
        zeekrTab.setChipSelected(next == Tab.ZEEKR)
        zeekrPanel.visibility = if (next == Tab.ZEEKR) View.VISIBLE else View.GONE
        carTab.setChipSelected(next == Tab.CAR)
        reportTab.setChipSelected(next == Tab.REPORT)
        livePanel.visibility = if (next == Tab.LIVE) View.VISIBLE else View.GONE
        carPanel.visibility = if (next == Tab.CAR) View.VISIBLE else View.GONE
        reportPanel.visibility = if (next == Tab.REPORT) View.VISIBLE else View.GONE
        if (next == Tab.LIVE) renderLive()
        if (next == Tab.ZEEKR) renderZeekr()
        if (next == Tab.CAR && shown) {
            handler.removeCallbacks(carTick)
            handler.post(carTick)
        } else if (next != Tab.CAR) {
            closeCar()
        }
    }

    override fun onShown() {
        shown = true
        showTab(tab)
    }

    override fun onHidden() {
        shown = false
        closeCar()
    }

    override fun onMedia() {
        if (!shown || renderPending) return
        renderPending = true
        handler.postDelayed({
            renderPending = false
            if (!shown) return@postDelayed
            when (tab) {
                Tab.LIVE -> renderLive()
                Tab.ZEEKR -> renderZeekr()
                else -> Unit
            }
        }, if (tab == Tab.ZEEKR) ZEEKR_REFRESH_MS else LIVE_REFRESH_MS)
    }

    private fun renderLive() {
        val c = host
        val media = app.media
        val source = media.lyricSource
        sourceText.text = source?.let { "当前歌词来源：${it.packageName.takeLast(28)} · ${it.id}" } ?: "尚未设置歌词来源"
        accessText.text = "媒体读取：${media.access.label}  ·  通知监听：${if (media.listenerConnected) "已连接" else "未连接"}  ·  会话 ${media.controllers().size} 个"
        val values = media.fields.all().filterNot { it.value.startsWith("[bitmap") }.take(80)
        fieldList.removeAllViews()
        if (values.isEmpty()) {
            fieldList.addView(c.label("还没有读到任何媒体字段。请在 QQ 音乐播放一首歌；如果一直为空，请到“通知使用权”里打开 CustomHUD。", Ui.BODY, Ui.WARN))
        }
        values.forEach { value -> fieldList.addSpaced(fieldRow(value, value.key == source), 10) }
    }

    private fun fieldRow(value: FieldValue, isSource: Boolean): View {
        val c = host
        return LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(c.dp(20), c.dp(14), c.dp(20), c.dp(14))
            minimumHeight = c.dp(84)
            background = c.pressable(if (isSource) Ui.ACCENT_DARK else Ui.SURFACE, 14, if (isSource) Ui.ACCENT else Ui.STROKE)
            isClickable = true
            val changes = if (value.changeCount > 0) "变化 ${value.changeCount} 次" else "未变化"
            addView(c.label(
                "${if (isSource) "★ 歌词来源  " else ""}${value.key.id}   ·   $changes   ·   ${value.key.packageName.takeLast(28)}",
                Ui.SMALL, if (isSource) Ui.ACCENT else if (value.changeCount >= 3) Ui.WARN else Ui.DIM, mono = true,
            ))
            addView(c.label(value.value.ifBlank { "（空）" }, Ui.BODY).apply { maxLines = 2 })
            setOnClickListener {
                app.media.lyricSource = value.key
                app.log.add("MEDIA", "lyric source set ${value.key.spec}")
                host.toast("已设为歌词来源：${value.key.id}")
            }
        }
    }

    private fun toggleProbe() {
        cancelProbe?.let {
            it()
            cancelProbe = null
            probeFinished("已停止")
            return
        }
        startButton.text = "停止探测"
        startButton.applyButtonStyle(ButtonStyle.DANGER)
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        app.log.add("PROBE", "started")
        cancelProbe = ProbeRunner(host).run(
            durationMs = 40_000,
            onProgress = { message, fraction ->
                progressText.text = message
                progressBar.progress = (fraction * 1000).toInt()
            },
            onDone = { lines ->
                cancelProbe = null
                report = lines
                reportTime = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date())
                app.log.add("PROBE", "finished lines=${lines.size}")
                probeFinished("探测完成，共 ${lines.size} 行。请切换到“报告”逐页拍照。")
                pageIndex = 0
                repaginate()
                showTab(Tab.REPORT)
            },
        )
    }

    private fun probeFinished(message: String) {
        startButton.text = "重新探测（40 秒）"
        startButton.applyButtonStyle(ButtonStyle.PRIMARY)
        progressBar.visibility = View.GONE
        progressText.text = message
    }

    private fun repaginate() {
        if (report.isEmpty()) return
        val paint = Paint().apply {
            typeface = Ui.MONO
            textSize = reportText.textSize
        }
        val charWidth = paint.measureText("0").coerceAtLeast(1f)
        val columns = ((reportText.width - reportText.paddingLeft - reportText.paddingRight) / charWidth).toInt().takeIf { it > 20 } ?: 80
        val rows = (reportText.height / reportText.lineHeight.toFloat()).toInt().takeIf { it > 4 } ?: 18
        pages = ReportPager.paginate(report, columns - 1, rows)
        pageIndex = pageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        renderPage()
    }

    private fun turn(delta: Int) {
        if (pages.isEmpty()) return
        pageIndex = (pageIndex + delta).coerceIn(0, pages.lastIndex)
        renderPage()
    }

    private fun renderPage() {
        val total = pages.size
        pageHeader.text = "CustomHUD 探测  ·  第 ${pageIndex + 1} / $total 页  ·  $reportTime  ·  ${BuildConfig.BUILD_ID}"
        pageCounter.text = "${pageIndex + 1} / $total"
        reportText.text = pages.getOrNull(pageIndex)?.joinToString("\n") ?: ""
    }

    private enum class Tab { LIVE, ZEEKR, CAR, REPORT }

    private companion object {
        const val ZEEKR_REFRESH_MS = 300L
        const val LIVE_REFRESH_MS = 700L
        val CAR_ROWS = listOf(
            VehicleSignal.SPEED to "车速 km/h",
            VehicleSignal.GEAR to "挡位",
            VehicleSignal.POWER_STATE to "电源",
            VehicleSignal.BATTERY_PERCENT to "电量 %",
            VehicleSignal.RANGE to "续航 km",
            VehicleSignal.POWER to "功率 kW",
            VehicleSignal.CHARGING to "充电",
            VehicleSignal.LEFT_INDICATOR to "左转向",
            VehicleSignal.RIGHT_INDICATOR to "右转向",
            VehicleSignal.HAZARD to "双闪",
            VehicleSignal.LOCKED to "车门锁",
            VehicleSignal.TIRE_PRESSURE_FL to "胎压左前",
            VehicleSignal.TIRE_PRESSURE_FR to "胎压右前",
            VehicleSignal.TIRE_PRESSURE_RL to "胎压左后",
            VehicleSignal.TIRE_PRESSURE_RR to "胎压右后",
            VehicleSignal.BATTERY_TEMPERATURE to "电池温度",
            VehicleSignal.DRIVE_MODE to "驾驶模式",
        )
    }
}
