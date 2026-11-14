package com.example.shizukukeypad

import android.view.Surface
import rikka.shizuku.Shizuku

/**
 * 通过 Shizuku（shell 身份）旁听触摸屏的原始事件节点 /dev/input/eventX。
 *
 * 关键点：**只读不消费**。事件已经被系统读走并照常发给游戏，我们只是同时看见
 * 每根手指的坐标。所以它不抢触摸、不影响原生操作，也不会和注入冲突。
 *
 * 用途：热区按键——按键窗口设为不可触摸，靠这里拿到的手指坐标触发行程。
 */
object EvdevTouch {

    const val DOWN = 0
    const val MOVE = 1
    const val UP = 2

    fun interface Listener {
        fun onTouch(action: Int, pointerId: Int, x: Float, y: Float)
    }

    @Volatile
    var listener: Listener? = null

    /** 启停过程中的诊断信息，接到观测面板里 */
    @Volatile
    var status: ((String) -> Unit)? = null

    @Volatile
    var screenW = 1080

    @Volatile
    var screenH = 1920

    @Volatile
    var rotation = Surface.ROTATION_0

    @Volatile
    var lastError: String = ""
        private set

    private var proc: Process? = null

    private var thread: Thread? = null

    @Volatile
    private var running = false

    /** 每次启停递增；旧线程收尾时发现代次变了就什么都不做，避免误杀新进程 */
    @Volatile
    private var generation = 0

    fun isRunning(): Boolean = running

    @Synchronized
    fun start() {
        if (running && thread?.isAlive == true) return
        running = true
        lastError = ""
        val myGen = ++generation
        status?.invoke("start")
        val t = Thread({ runLoop(myGen) }, "evdev-touch")
        t.isDaemon = true
        thread = t
        t.start()
    }

    @Synchronized
    fun stop() {
        generation++
        running = false
        runCatching { proc?.destroy() }
        proc = null
    }

    /** 以 shell 身份启动进程。Shizuku.newProcess 是私有方法，只能反射调用。 */
    private fun shellProcess(cmd: String): Process {
        val args = arrayOf("sh", "-c", cmd)
        val cls = Shizuku::class.java
        val m = cls.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        m.isAccessible = true
        val result = try {
            m.invoke(null, args, null, null)
        } catch (t: Throwable) {
            val f = cls.getDeclaredField("INSTANCE").apply { isAccessible = true }
            m.invoke(f.get(null), args, null, null)
        }
        return result as Process
    }

    private class Dev(
        val path: String,
        val minX: Float,
        val maxX: Float,
        val minY: Float,
        val maxY: Float
    )

    private fun parseDevice(text: String): Dev? {
        var path: String? = null
        var minX = 0f
        var maxX = -1f
        var minY = 0f
        var maxY = -1f
        var found: Dev? = null

        fun flush() {
            val p = path
            if (found == null && p != null && maxX > 0f && maxY > 0f) {
                found = Dev(p, minX, maxX, minY, maxY)
            }
            path = null
            minX = 0f
            maxX = -1f
            minY = 0f
            maxY = -1f
        }

        for (line in text.lineSequence()) {
            val t = line.trim()
            if (t.startsWith("add device")) {
                flush()
                path = t.substringAfterLast(':').trim()
            } else if (t.startsWith("ABS_MT_POSITION_X")) {
                rangeOf(t)?.let { minX = it.first; maxX = it.second }
            } else if (t.startsWith("ABS_MT_POSITION_Y")) {
                rangeOf(t)?.let { minY = it.first; maxY = it.second }
            }
        }
        flush()
        return found
    }

    private fun rangeOf(line: String): Pair<Float, Float>? {
        val min = Regex("min\\s+(-?\\d+)").find(line)?.groupValues?.get(1)?.toFloatOrNull() ?: return null
        val max = Regex("max\\s+(-?\\d+)").find(line)?.groupValues?.get(1)?.toFloatOrNull() ?: return null
        return min to max
    }

    private val lineRe = Regex("""(/dev/input/event\d+):\s+(\w+)\s+(\w+)\s+([0-9a-fA-F]+)""")

    private val listMarker = "---LIVE---"

    /** 只跑一条 sh 命令：先列设备，再持续输出实时事件。和最早验证成功的探针完全一致。 */
    private fun runLoop(myGen: Int) {
        var p: Process? = null
        try {
            p = shellProcess("getevent -lp 2>&1; echo $listMarker; getevent -lt 2>&1")
            proc = p
            status?.invoke("proc ok")
            val slotIds = HashMap<Int, Int>()
            val slotX = HashMap<Int, Float>()
            val slotY = HashMap<Int, Float>()
            val prev = HashMap<Int, Pair<Float, Float>>()
            var curSlot = 0
            var dev: Dev? = null
            val info = StringBuilder()
            var live = false
            var rawLeft = 6
            val reader = p.inputStream.bufferedReader()
            while (true) {
                val line = reader.readLine() ?: break
                if (myGen != generation) break
                if (!live) {
                    if (line.contains(listMarker)) {
                        dev = parseDevice(info.toString())
                        val d0 = dev
                        if (d0 == null) {
                            lastError = "no touchscreen in getevent -lp"
                            status?.invoke(lastError)
                            break
                        }
                        status?.invoke("dev=${d0.path} ${d0.maxX.toInt()}x${d0.maxY.toInt()}")
                        status?.invoke("live")
                        live = true
                    } else {
                        info.append(line).append('\n')
                    }
                    continue
                }
                val d = dev ?: break
                if (rawLeft > 0 && line.isNotBlank()) {
                    rawLeft--
                    status?.invoke("raw ${line.trim()}")
                }
                val m = lineRe.find(line) ?: continue
                if (m.groupValues[1] != d.path) continue
                val type = m.groupValues[2]
                val code = m.groupValues[3]
                val value = m.groupValues[4].toLong(16)
                when {
                    type == "EV_ABS" && code == "ABS_MT_SLOT" -> curSlot = value.toInt()
                    type == "EV_ABS" && code == "ABS_MT_TRACKING_ID" -> {
                        if (value == 0xffffffffL) slotIds.remove(curSlot) else slotIds[curSlot] = value.toInt()
                    }
                    type == "EV_ABS" && code == "ABS_MT_POSITION_X" -> slotX[curSlot] = value.toFloat()
                    type == "EV_ABS" && code == "ABS_MT_POSITION_Y" -> slotY[curSlot] = value.toFloat()
                    type == "EV_SYN" && code == "SYN_REPORT" -> emit(d, slotIds, slotX, slotY, prev)
                }
            }
            if (myGen == generation) status?.invoke("stream ended")
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            status?.invoke("ERR $lastError")
        } finally {
            runCatching { p?.destroy() }
            if (myGen == generation) {
                running = false
                proc = null
            }
        }
    }

    private fun emit(
        dev: Dev,
        slotIds: Map<Int, Int>,
        slotX: Map<Int, Float>,
        slotY: Map<Int, Float>,
        prev: MutableMap<Int, Pair<Float, Float>>
    ) {
        val now = HashMap<Int, Pair<Float, Float>>()
        slotIds.forEach { (slot, id) ->
            val rx = slotX[slot] ?: return@forEach
            val ry = slotY[slot] ?: return@forEach
            now[id] = mapToScreen(dev, rx, ry)
        }
        val l = listener ?: return
        now.forEach { (id, p) ->
            val old = prev[id]
            if (old == null) l.onTouch(DOWN, id, p.first, p.second)
            else if (old != p) l.onTouch(MOVE, id, p.first, p.second)
        }
        prev.keys.filter { it !in now }.forEach { l.onTouch(UP, it, 0f, 0f) }
        prev.clear()
        prev.putAll(now)
    }

    /** 原始坐标 → 屏幕像素，按当前屏幕方向旋转 */
    private fun mapToScreen(dev: Dev, rx: Float, ry: Float): Pair<Float, Float> {
        val nx = ((rx - dev.minX) / (dev.maxX - dev.minX)).coerceIn(0f, 1f)
        val ny = ((ry - dev.minY) / (dev.maxY - dev.minY)).coerceIn(0f, 1f)
        return when (rotation) {
            Surface.ROTATION_90 -> (ny * screenW) to ((1f - nx) * screenH)
            Surface.ROTATION_180 -> ((1f - nx) * screenW) to ((1f - ny) * screenH)
            Surface.ROTATION_270 -> ((1f - ny) * screenW) to (nx * screenH)
            else -> (nx * screenW) to (ny * screenH)
        }
    }
}
