package dev.customhud.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** Car-sized dark theme: large touch targets, high contrast, rounded cards. */
object Ui {
    val BG = Color.rgb(9, 13, 16)
    val RAIL = Color.rgb(14, 20, 24)
    val SURFACE = Color.rgb(20, 27, 32)
    val SURFACE_HI = Color.rgb(29, 38, 44)
    val STROKE = Color.rgb(40, 52, 60)
    val TEXT = Color.rgb(236, 244, 240)
    val DIM = Color.rgb(146, 162, 156)
    val ACCENT = Color.rgb(79, 224, 160)
    val ACCENT_DARK = Color.rgb(17, 56, 42)
    val ON_ACCENT = Color.rgb(4, 28, 19)
    val DANGER = Color.rgb(255, 110, 110)
    val DANGER_DARK = Color.rgb(64, 22, 24)
    val WARN = Color.rgb(255, 200, 97)

    const val H1 = 30f
    const val H2 = 23f
    const val BODY = 19f
    const val SMALL = 16f
    const val BUTTON = 21f
    const val BUTTON_HEIGHT = 76
    const val GAP = 16
    const val RADIUS = 18

    val MONO: Typeface = Typeface.MONOSPACE
}

enum class ButtonStyle { PRIMARY, SECONDARY, DANGER, GHOST }

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
fun Context.dpf(value: Float): Float = value * resources.displayMetrics.density

fun Context.rounded(color: Int, radiusDp: Int = Ui.RADIUS, strokeColor: Int? = null, strokeDp: Int = 2): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = dpf(radiusDp.toFloat())
        if (strokeColor != null) setStroke(dp(strokeDp), strokeColor)
    }

fun Context.pressable(color: Int, radiusDp: Int = Ui.RADIUS, strokeColor: Int? = null): Drawable =
    RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), rounded(color, radiusDp, strokeColor), rounded(Color.WHITE, radiusDp))

fun Context.label(
    text: CharSequence,
    size: Float = Ui.BODY,
    color: Int = Ui.TEXT,
    bold: Boolean = false,
    mono: Boolean = false,
): TextView = TextView(this).apply {
    this.text = text
    textSize = size
    setTextColor(color)
    setLineSpacing(0f, 1.18f)
    typeface = when {
        mono && bold -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        mono -> Typeface.MONOSPACE
        bold -> Typeface.DEFAULT_BOLD
        else -> Typeface.DEFAULT
    }
}

fun Context.bigButton(text: String, style: ButtonStyle = ButtonStyle.SECONDARY, heightDp: Int = Ui.BUTTON_HEIGHT, onClick: () -> Unit): TextView =
    TextView(this).apply {
        this.text = text
        textSize = Ui.BUTTON
        gravity = Gravity.CENTER
        minHeight = dp(heightDp)
        setPadding(dp(22), dp(10), dp(22), dp(10))
        typeface = Typeface.DEFAULT_BOLD
        isClickable = true
        isFocusable = true
        applyButtonStyle(style)
        setOnClickListener { onClick() }
    }

fun TextView.applyButtonStyle(style: ButtonStyle) {
    val (bg, fg, stroke) = when (style) {
        ButtonStyle.PRIMARY -> Triple(Ui.ACCENT, Ui.ON_ACCENT, null)
        ButtonStyle.SECONDARY -> Triple(Ui.SURFACE_HI, Ui.TEXT, Ui.STROKE)
        ButtonStyle.DANGER -> Triple(Ui.DANGER_DARK, Ui.DANGER, Ui.DANGER)
        ButtonStyle.GHOST -> Triple(Color.TRANSPARENT, Ui.ACCENT, Ui.STROKE)
    }
    background = context.pressable(bg, strokeColor = stroke)
    setTextColor(fg)
}

fun Context.card(vertical: Boolean = true, paddingDp: Int = 24): LinearLayout = LinearLayout(this).apply {
    orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
    setPadding(dp(paddingDp), dp(paddingDp - 4), dp(paddingDp), dp(paddingDp))
    background = rounded(Ui.SURFACE, strokeColor = Ui.STROKE, strokeDp = 1)
}

fun Context.sectionTitle(text: String): TextView = label(text, Ui.H2, Ui.TEXT, bold = true).apply {
    setPadding(0, 0, 0, dp(12))
}

/** Lays out [views] side by side with equal weight and a gap. */
fun Context.row(vararg views: View, gapDp: Int = Ui.GAP): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    views.forEachIndexed { index, view ->
        addView(view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (index > 0) marginStart = dp(gapDp)
        })
    }
}

fun LinearLayout.addSpaced(view: View, topDp: Int = Ui.GAP, width: Int = ViewGroup.LayoutParams.MATCH_PARENT) {
    addView(view, LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        if (childCount > 0) topMargin = context.dp(topDp)
    })
}

/** A whole-row tap target with title, subtitle and an on/off pill. */
class ToggleRow(context: Context, title: String, subtitle: String, initial: Boolean, private val onChange: (Boolean) -> Unit) :
    LinearLayout(context) {
    private val pill = context.label("", Ui.SMALL, bold = true).apply {
        gravity = Gravity.CENTER
        minWidth = context.dp(96)
        setPadding(context.dp(18), context.dp(10), context.dp(18), context.dp(10))
    }
    var checked = initial
        private set

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dp(Ui.BUTTON_HEIGHT + 8)
        setPadding(context.dp(20), context.dp(12), context.dp(16), context.dp(12))
        background = context.pressable(Ui.SURFACE_HI, strokeColor = Ui.STROKE)
        isClickable = true
        val texts = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(context.label(title, Ui.BODY, bold = true))
            if (subtitle.isNotEmpty()) addView(context.label(subtitle, Ui.SMALL, Ui.DIM))
        }
        addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(pill, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = context.dp(16) })
        render()
        setOnClickListener {
            checked = !checked
            render()
            onChange(checked)
        }
    }

    private fun render() {
        pill.text = if (checked) "开启" else "关闭"
        pill.setTextColor(if (checked) Ui.ON_ACCENT else Ui.DIM)
        pill.background = context.rounded(if (checked) Ui.ACCENT else Ui.SURFACE, 40, if (checked) null else Ui.STROKE)
    }
}

/** Selectable chip used for widget lists, presets and sub-tabs. */
fun Context.chip(text: String, selected: Boolean, onClick: () -> Unit): TextView = TextView(this).apply {
    this.text = text
    textSize = Ui.BODY
    gravity = Gravity.CENTER
    minHeight = dp(64)
    setPadding(dp(20), dp(8), dp(20), dp(8))
    isClickable = true
    setChipSelected(selected)
    setOnClickListener { onClick() }
}

fun TextView.setChipSelected(selected: Boolean) {
    setTextColor(if (selected) Ui.ACCENT else Ui.TEXT)
    typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    background = context.pressable(if (selected) Ui.ACCENT_DARK else Ui.SURFACE_HI, 40, if (selected) Ui.ACCENT else Ui.STROKE)
}

/** Simple wrapping flow of children for chip grids. */
class FlowLayout(context: Context, private val gapPx: Int) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            child.measure(MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + child.measuredWidth > maxWidth) {
                x = 0
                y += rowHeight + gapPx
                rowHeight = 0
            }
            x += child.measuredWidth + gapPx
            rowHeight = maxOf(rowHeight, child.measuredHeight)
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), y + rowHeight + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxWidth = r - l - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            if (x > 0 && x + child.measuredWidth > maxWidth) {
                x = 0
                y += rowHeight + gapPx
                rowHeight = 0
            }
            child.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + child.measuredWidth, paddingTop + y + child.measuredHeight)
            x += child.measuredWidth + gapPx
            rowHeight = maxOf(rowHeight, child.measuredHeight)
        }
    }
}
