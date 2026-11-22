package dev.customhud.adapters

import android.content.Context
import android.os.SystemClock

/**
 * Read-only listing of every vehicle property CarPropertyManager reports for this app, with the
 * current value of each area. Uses only the public android.car API; write-only properties are
 * skipped and nothing is ever set. Blocking: call from a background thread.
 */
object CarPropertyDump {
    /** [summary]: connection + the standard properties the HUD uses; [full]: every listed property. */
    data class Result(val summary: List<String>, val full: List<String>)

    fun run(context: Context, maxReads: Int = 1500, timeBudgetMs: Long = 20_000): Result {
        val lines = mutableListOf("== A 车况：HUD 使用的标准属性（android.car） ==")
        val full = mutableListOf("== B 车辆属性全表（android.car，报告末尾） ==")
        val started = SystemClock.elapsedRealtime()
        val access = try {
            CarPropertyAccess.connect(context)
        } catch (error: Throwable) {
            lines += "  连接失败 ${error.javaClass.name}: ${error.message?.take(100)}"
            return Result(lines + "", emptyList())
        }
        try {
            lines += "  连接耗时 ${SystemClock.elapsedRealtime() - started} ms"
            val configs = access.configs().sortedBy { it.id.toUInt() }
            val vendor = configs.count { isVendor(it.id) }
            lines += "  系统列出 ${configs.size} 个可访问属性（标准 ${configs.size - vendor}，厂商自定义 $vendor）"
            lines += ""
            lines += "  -- HUD 使用的标准属性 --"
            CarProps.CANDIDATES.forEach { (id, name) ->
                val config = configs.firstOrNull { it.id == id }
                lines += if (config != null) {
                    "  ✓ $name ${values(access, config, 4)}"
                } else {
                    val probe = access.read(id, 0)
                    "  ✗ $name ${probe.code} ${probe.detail}"
                }
            }
            lines += ""
            full += "  格式：id 名称 [区域类型/访问/变化方式 类型] = 值"
            var reads = 0
            for (config in configs) {
                if (reads >= maxReads || SystemClock.elapsedRealtime() - started > timeBudgetMs) {
                    full += "  …读取上限，剩余未列出（已读 $reads 个值）"
                    break
                }
                val name = CarPropertyAccess.propertyName(config.id).let { if (it.startsWith("0x")) "" else it }
                val meta = "${AREA_TYPES[config.areaType] ?: config.areaType}/${ACCESS[config.access] ?: config.access}/${CHANGE[config.changeMode] ?: config.changeMode}"
                val head = "  0x${config.id.toString(16)}${if (isVendor(config.id)) "*" else ""} $name [$meta ${config.type}]"
                if (!config.readable) {
                    full += "$head 只写，未读取"
                    continue
                }
                val areaCount = config.areaIds.size.coerceAtLeast(1)
                reads += areaCount.coerceAtMost(6)
                full += "$head = ${values(access, config, 6)}"
            }
            full += "  （* = 厂商自定义属性；值为探测瞬间读数）"
        } finally {
            access.disconnect()
        }
        return Result(lines + "", full + "")
    }

    private fun values(access: CarPropertyAccess, config: CarPropertyAccess.Config, maxAreas: Int): String {
        val areas = config.areaIds.ifEmpty { listOf(0) }
        val shown = areas.take(maxAreas).joinToString("  ") { area ->
            val read = access.read(config.id, area)
            val value = if (read.ok) CarPropertyAccess.describe(read.value) else read.code
            if (areas.size == 1 && area == 0) value else "a0x${area.toString(16)}:$value"
        }
        return if (areas.size > maxAreas) "$shown …共 ${areas.size} 个区域" else shown
    }

    /** VehiclePropertyGroup.VENDOR = 0x20000000. */
    private fun isVendor(id: Int): Boolean = id and 0x70000000 == 0x20000000

    private val AREA_TYPES = mapOf(0 to "全局", 2 to "车窗", 3 to "座椅", 4 to "车门", 5 to "后视镜", 6 to "车轮")
    private val ACCESS = mapOf(0 to "无", 1 to "读", 2 to "写", 3 to "读写")
    private val CHANGE = mapOf(0 to "静态", 1 to "变化时", 2 to "连续")
}
