package dev.customhud.settings

import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.customhud.BuildConfig
import dev.customhud.display.android.DeviceCapabilities
import dev.customhud.display.session.SessionSnapshot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogPage(host: MainActivity) : Page(host) {
    private val handler = Handler(Looper.getMainLooper())
    private val sessionText = host.label("", Ui.SMALL, Ui.DIM, mono = true)
    private val logText = host.label("", 15f, Ui.TEXT, mono = true)
    private val scroll = ScrollView(host)
    private var shown = false
    private var pending = false

    override val view: View = buildView()

    private fun buildView(): View {
        val c = host
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(c.dp(28), c.dp(28), c.dp(28), c.dp(20))
        }
        root.addView(LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(c.label("日志与诊断", Ui.H1, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(c.bigButton("导出诊断", ButtonStyle.GHOST) {
                val text = app.log.exportText() + "\nCAPABILITIES\n" +
                    DeviceCapabilities(c).report().joinToString("\n") { "${it.status} ${it.reason}" }
                host.exportText("customhud-${BuildConfig.BUILD_ID}-diagnostics.txt", text)
            })
        })
        root.addSpaced(c.card(paddingDp = 18).apply { addView(sessionText) }, 16)
        scroll.addView(logText)
        root.addView(c.card(paddingDp = 18).apply {
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = c.dp(16) })
        return root
    }

    override fun onShown() {
        shown = true
        render()
        onSession(host.sessionSnapshot)
    }

    override fun onHidden() {
        shown = false
    }

    override fun onSession(snapshot: SessionSnapshot?) {
        sessionText.text = if (snapshot == null) {
            "SERVICE stopped"
        } else {
            "state=${snapshot.state} gen=${snapshot.generation} desired=${snapshot.desiredRunning} addView=${snapshot.addViewReturned} " +
                "attach=${snapshot.viewAttached} draw=${snapshot.firstDrawAtMonotonic != null} resources=${snapshot.resourceCount} " +
                "pending=${snapshot.pendingCleanupCount} retry=${snapshot.retryCount} stale=${snapshot.staleCallbackCount} " +
                "observation=${snapshot.userObservation}" + (snapshot.lastError?.let { "\n${it.code}: ${it.message} (${it.causeType ?: "no cause"})" } ?: "")
        }
    }

    override fun onLog() {
        if (!shown || pending) return
        pending = true
        handler.postDelayed({
            pending = false
            if (shown) render()
        }, 300)
    }

    private fun render() {
        val atBottom = !scroll.canScrollVertically(1)
        val formatter = SimpleDateFormat("HH:mm:ss", Locale.US)
        logText.text = app.log.snapshot().takeLast(200).joinToString("\n") {
            "${formatter.format(Date(it.wallTimeMs))} ${it.category} gen=${it.generation ?: "-"} ${it.errorCode ?: ""} ${it.message}"
        }
        if (atBottom) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
}
