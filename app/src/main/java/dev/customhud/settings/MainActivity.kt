package dev.customhud.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.customhud.BuildConfig
import dev.customhud.CustomHudApplication
import dev.customhud.display.HudService
import dev.customhud.display.session.DisplaySession
import dev.customhud.display.session.SessionSnapshot
import dev.customhud.layout.LayoutFormatException
import java.io.File

/** Base for one screen behind the navigation rail. */
abstract class Page(protected val host: MainActivity) {
    protected val app: CustomHudApplication get() = host.app
    abstract val view: View
    open fun onShown() {}
    open fun onHidden() {}
    open fun onSession(snapshot: SessionSnapshot?) {}
    open fun onLog() {}
    open fun onMedia() {}
    open fun onLayout() {}
}

class MainActivity : Activity() {
    lateinit var app: CustomHudApplication
        private set
    lateinit var prefs: UiPrefs
        private set
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var content: FrameLayout
    private val pages = mutableListOf<Page>()
    private val navItems = mutableListOf<TextView>()
    private var current = -1
    private var started = false
    private var releaseMedia: (() -> Unit)? = null
    private val stops = mutableListOf<() -> Unit>()
    private var stopSessionObserver: (() -> Unit)? = null
    private var observedSession: DisplaySession? = null
    private var pendingExport = ""
    var sessionSnapshot: SessionSnapshot? = null
        private set

    private val sessionPoll = object : Runnable {
        override fun run() {
            observeSession()
            if (started) handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as CustomHudApplication
        prefs = UiPrefs(this)
        pages += HudPage(this)
        pages += LayoutPage(this)
        pages += ProbePage(this)
        pages += LogPage(this)
        setContentView(buildShell())
        select(prefs.tab.coerceIn(0, pages.lastIndex))
    }

    private fun buildShell(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Ui.BG)
        }
        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.RAIL)
            setPadding(dp(14), dp(24), dp(14), dp(20))
        }
        rail.addView(label("CustomHUD", Ui.BODY, Ui.ACCENT, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(24))
        })
        NAV.forEachIndexed { index, (glyph, title) ->
            val item = TextView(this).apply {
                text = "$glyph\n$title"
                textSize = Ui.BODY
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.25f)
                minHeight = dp(118)
                isClickable = true
                setOnClickListener { select(index) }
            }
            navItems += item
            rail.addView(item, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            })
        }
        rail.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        rail.addView(label("buildId\n${BuildConfig.BUILD_ID}", 13f, Ui.DIM, mono = true).apply { gravity = Gravity.CENTER })
        root.addView(rail, LinearLayout.LayoutParams(dp(176), ViewGroup.LayoutParams.MATCH_PARENT))

        content = FrameLayout(this)
        pages.forEach { page ->
            page.view.visibility = View.GONE
            content.addView(page.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        root.addView(content, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        return root
    }

    fun select(index: Int) {
        if (index == current) return
        pages.getOrNull(current)?.let {
            it.view.visibility = View.GONE
            if (started) it.onHidden()
        }
        current = index
        prefs.tab = index
        navItems.forEachIndexed { i, item ->
            val selected = i == index
            item.setTextColor(if (selected) Ui.ACCENT else Ui.DIM)
            item.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            item.background = pressable(if (selected) Ui.ACCENT_DARK else Ui.RAIL, 20, if (selected) Ui.ACCENT else null)
        }
        pages[index].view.visibility = View.VISIBLE
        if (started) pages[index].onShown()
    }

    override fun onStart() {
        super.onStart()
        started = true
        releaseMedia = app.media.acquire()
        stops += app.media.observe { pages.forEach { it.onMedia() } }
        stops += app.log.observe { handler.post { pages.forEach { it.onLog() } } }
        stops += app.layoutStore.observe { pages.forEach { it.onLayout() } }
        handler.post(sessionPoll)
        HudService.uiVisibility(this, true)
        pages.getOrNull(current)?.onShown()
    }

    override fun onStop() {
        pages.getOrNull(current)?.onHidden()
        started = false
        handler.removeCallbacks(sessionPoll)
        stops.forEach { it() }
        stops.clear()
        stopSessionObserver?.invoke()
        stopSessionObserver = null
        observedSession = null
        releaseMedia?.invoke()
        releaseMedia = null
        HudService.uiVisibility(this, false)
        super.onStop()
    }

    fun observeSession() {
        val session = HudService.activeSession
        if (session === observedSession) return
        stopSessionObserver?.invoke()
        observedSession = session
        stopSessionObserver = session?.observe { snapshot ->
            sessionSnapshot = snapshot
            pages.forEach { it.onSession(snapshot) }
        }
        if (session == null) {
            sessionSnapshot = null
            pages.forEach { it.onSession(null) }
        }
    }

    fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    fun logError(code: String, error: Exception) {
        app.log.add("UI", error.message ?: "Operation failed", "ERROR", code)
        toast("$code: ${error.message}")
    }

    /** Opens the system "create document" picker; falls back to app storage when the car has none. */
    fun exportText(fileName: String, text: String) {
        pendingExport = text
        try {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = if (fileName.endsWith(".json")) "application/json" else "text/plain"
                putExtra(Intent.EXTRA_TITLE, fileName)
            }, REQUEST_EXPORT)
        } catch (_: ActivityNotFoundException) {
            try {
                val dir = getExternalFilesDir(null) ?: filesDir
                val file = File(dir, fileName)
                file.writeText(text)
                app.log.add("EXPORT", "saved ${file.absolutePath}")
                toast("系统没有文件选择器，已保存到 ${file.absolutePath}")
            } catch (error: Exception) {
                logError("EXPORT_FAILED", error)
            }
        }
    }

    fun importLayout() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
            }, REQUEST_IMPORT)
        } catch (error: ActivityNotFoundException) {
            logError("NO_DOCUMENT_PICKER", error)
        }
    }

    fun openSettings(action: String, withPackage: Boolean) {
        try {
            val intent = Intent(action)
            if (withPackage) intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            logError("SETTINGS_UNAVAILABLE", error)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissions.forEachIndexed { index, permission ->
            app.log.add("PERMISSION", "$permission result=${grantResults.getOrNull(index)}")
        }
        pages.getOrNull(current)?.onShown()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            when (requestCode) {
                REQUEST_IMPORT -> {
                    val snapshot = app.layoutStore.replace(readBounded(uri))
                    app.log.add("LAYOUT", "import committed revision=${snapshot.revision}")
                    toast("布局已导入")
                }
                REQUEST_EXPORT -> {
                    contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(pendingExport) }
                        ?: error("Cannot open export URI")
                    app.log.add("EXPORT", "user export completed")
                    pendingExport = ""
                    toast("已导出")
                }
            }
        } catch (error: Exception) {
            logError(if (requestCode == REQUEST_IMPORT) "CONFIG_INVALID" else "EXPORT_FAILED", error)
        }
    }

    private fun readBounded(uri: Uri): String {
        val bytes = contentResolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val count = input.read(chunk)
                if (count < 0) break
                if (output.size() + count > MAX_LAYOUT_BYTES) throw LayoutFormatException("Layout exceeds 256 KiB")
                output.write(chunk, 0, count)
            }
            output.toByteArray()
        } ?: throw LayoutFormatException("Cannot open layout URI")
        return bytes.toString(Charsets.UTF_8)
    }

    companion object {
        const val REQUEST_PERMISSIONS = 13
        private const val REQUEST_IMPORT = 10
        private const val REQUEST_EXPORT = 11
        private const val MAX_LAYOUT_BYTES = 256 * 1024
        private val NAV = listOf("◉" to "HUD", "▦" to "布局", "⌕" to "探测", "≡" to "日志")
    }
}

/** Small persisted UI choices: tab, target display, window options. */
class UiPrefs(context: android.content.Context) {
    private val prefs = context.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)

    var tab: Int
        get() = prefs.getInt("tab", 0)
        set(value) = prefs.edit().putInt("tab", value).apply()
    var keepScreenOn: Boolean
        get() = prefs.getBoolean("keepScreenOn", true)
        set(value) = prefs.edit().putBoolean("keepScreenOn", value).apply()
    var backgroundAllowed: Boolean
        get() = prefs.getBoolean("backgroundAllowed", true)
        set(value) = prefs.edit().putBoolean("backgroundAllowed", value).apply()

    data class Target(val id: Int, val name: String, val width: Int, val height: Int)

    var target: Target?
        get() {
            if (!prefs.contains("targetId")) return null
            return Target(
                prefs.getInt("targetId", -1),
                prefs.getString("targetName", "") ?: "",
                prefs.getInt("targetWidth", 1024),
                prefs.getInt("targetHeight", 576),
            )
        }
        set(value) {
            val edit = prefs.edit()
            if (value == null) {
                edit.remove("targetId").remove("targetName").remove("targetWidth").remove("targetHeight")
            } else {
                edit.putInt("targetId", value.id).putString("targetName", value.name)
                    .putInt("targetWidth", value.width).putInt("targetHeight", value.height)
            }
            edit.apply()
        }
}
