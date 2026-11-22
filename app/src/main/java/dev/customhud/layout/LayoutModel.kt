package dev.customhud.layout

enum class WidgetType(
    val cx: Float,
    val cy: Float,
    val sc: Float,
    val sp: Float,
    val enabled: Boolean,
    val label: String,
) {
    CLOCK(.84f, .50f, .30f, 0f, true, "时钟"),
    BATTERY(.17f, .39f, .30f, 0f, true, "电量"),
    RANGE(.23f, .39f, .30f, 0f, true, "续航"),
    TRACK(.64f, .60f, .30f, 0f, true, "歌曲"),
    TURN(.50f, .49f, .35f, .08f, true, "转向灯"),
    CHARGE(.75f, .39f, .30f, 0f, true, "充电"),
    TEMP(.50f, .38f, .40f, 0f, true, "电池温度"),
    DRIVE_MODE(.20f, .60f, .25f, 0f, true, "驾驶模式"),
    TIRES(.20f, .50f, .30f, 0f, true, "胎压"),
    MOTORS(.20f, .52f, .35f, 0f, true, "电机"),
    SUSP_HEIGHT(.25f, .65f, .20f, 0f, true, "悬架高度"),
    SUSP_STIFF(.18f, .65f, .20f, 0f, true, "悬架软硬"),
    POWER_METER(.20f, .45f, .30f, 0f, false, "功率"),
    G_METER(.80f, .45f, .30f, 0f, true, "G 值"),
    PEDALS(.50f, .50f, .30f, .40f, false, "踏板"),
    SPEED_LIMIT(.86f, .62f, .30f, 0f, true, "限速"),
    CAM_AHEAD(.14f, .62f, .30f, 0f, true, "前方摄像头"),
    AVG_SECTION(.50f, .72f, .30f, 0f, true, "区间测速"),
    AMBUSH_HEAT(.86f, .62f, .30f, 0f, false, "测速热区"),
    POST_AHEAD(.14f, .78f, .30f, 0f, false, "前方岗亭"),
    AMBUSH_OBJ(.14f, .46f, .30f, 0f, false, "测速目标"),
    SIGN_AHEAD(.14f, .62f, .30f, 0f, false, "前方标志"),
    LYRIC(.50f, .80f, .50f, 0f, true, "大字歌词"),
    /**
     * Rear camera (zeekr:202) as a 16:9 box. Its geometry is relative to the display that shows the
     * camera (by default HDMI 1 = instrument cluster), which may differ from the HUD target display.
     * Default: centre of the cluster, over the navigation area; the speed readout is on the left.
     */
    REAR_CAMERA(.50f, .50f, .90f, 0f, true, "后视"),
    ;

    /** Pair widgets use `sp` as their horizontal spread. */
    val usesSpread: Boolean get() = this == TURN || this == PEDALS

    /** Upper bound of `sc` the editor offers (the schema itself allows up to 4). */
    val maxEditorScale: Float get() = if (this == REAR_CAMERA) CameraGeometry.MAX_SCALE else 4f
}

/** Pixel box in view coordinates (plain floats so it is usable in JVM unit tests). */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * REAR_CAMERA geometry: a 16:9 box whose height is `sc` × view height (sc 1.0 = full height),
 * shrunk to fit the view width, centred on (cx, cy) and then shifted so it stays fully inside the view.
 */
object CameraGeometry {
    const val ASPECT = 16f / 9f
    const val MAX_SCALE = 1f

    fun box(widget: WidgetConfig, width: Int, height: Int): Box? {
        if (width <= 0 || height <= 0) return null
        var h = height * widget.scale.coerceAtMost(MAX_SCALE)
        var w = h * ASPECT
        if (w > width) {
            w = width.toFloat()
            h = w / ASPECT
        }
        val left = (widget.cx * width - w / 2f).coerceIn(0f, width - w)
        val top = (widget.cy * height - h / 2f).coerceIn(0f, height - h)
        return Box(left, top, left + w, top + h)
    }
}

data class WidgetConfig(
    val type: WidgetType,
    val enabled: Boolean,
    val cx: Float,
    val cy: Float,
    val scale: Float,
    val spread: Float,
) {
    init {
        require(cx.isFinite() && cx in 0f..1f) { "cx out of range" }
        require(cy.isFinite() && cy in 0f..1f) { "cy out of range" }
        require(scale.isFinite() && scale in .2f..4f) { "scale out of range" }
        require(spread.isFinite() && spread in 0f..0.5f) { "spread out of range" }
    }

    fun geometry(width: Int, height: Int): WidgetGeometry = WidgetGeometry(
        anchorX = cx * width,
        anchorY = cy * height,
        baseUnit = .16f * height * scale,
        pairSpreadPx = spread * width,
    )
}

data class WidgetGeometry(
    val anchorX: Float,
    val anchorY: Float,
    val baseUnit: Float,
    val pairSpreadPx: Float,
)

data class LayoutDocument(
    val schemaVersion: Int = 1,
    val widgets: List<WidgetConfig>,
) {
    init {
        require(schemaVersion == 1) { "Unsupported schemaVersion" }
        require(widgets.size <= WidgetType.entries.size) { "Too many widgets" }
        require(widgets.map { it.type }.distinct().size == widgets.size) { "Duplicate widget type" }
    }

    fun orderedWidgets(): List<WidgetConfig> = WidgetType.entries.mapNotNull { type ->
        widgets.firstOrNull { it.type == type }
    }

    companion object {
        fun default(): LayoutDocument = LayoutDocument(
            widgets = WidgetType.entries.map {
                WidgetConfig(it, it.enabled, it.cx, it.cy, it.sc, it.sp)
            },
        )

        fun clockOnly(): LayoutDocument = LayoutDocument(
            widgets = listOf(WidgetConfig(WidgetType.CLOCK, true, .84f, .5f, .3f, 0f)),
        )

        fun lyricAndClock(): LayoutDocument = LayoutDocument(
            widgets = listOf(
                WidgetConfig(WidgetType.CLOCK, true, .88f, .14f, .25f, 0f),
                WidgetConfig(WidgetType.TRACK, true, .50f, .94f, .22f, 0f),
                WidgetConfig(WidgetType.LYRIC, true, .50f, .78f, .50f, 0f),
                WidgetConfig(WidgetType.REAR_CAMERA, true, WidgetType.REAR_CAMERA.cx, WidgetType.REAR_CAMERA.cy, WidgetType.REAR_CAMERA.sc, 0f),
            ),
        )
    }
}

data class LayoutSnapshot(
    val document: LayoutDocument,
    val revision: Long,
)

data class WidgetPatch(
    val type: WidgetType,
    val enabled: Boolean? = null,
    val cx: Float? = null,
    val cy: Float? = null,
    val scale: Float? = null,
    val spread: Float? = null,
)
