package com.example.shizukukeypad

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.materialswitch.MaterialSwitch
import rikka.shizuku.Shizuku
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sin

/**
 * ============================================================================
 *  鼠标注入探针（实验模块，v1.7.4-probe）
 * ----------------------------------------------------------------------------
 *  独立性：整个实验只靠这一个文件 + 清单里的一行 <activity> + 主页一个入口卡片。
 *  要摘掉它：删本文件、删清单里 MouseProbeActivity、删 activity_main.xml 里
 *  btnMouseProbe 卡片、删 MainActivity 里那两行点击监听。其余代码一行都不碰。
 *
 *  窗口标记：悬浮面板的窗口标题固定为 "hjm-mouse-probe"，dumpsys 里一眼能认出：
 *      dumpsys window windows | grep hjm-mouse-probe
 *
 *  它要回答的问题（L1 可行性）：
 *   1. 用 Shizuku 注入的 SOURCE_MOUSE 事件，应用层到底收到什么（source/toolType/
 *      deviceId/buttonState/flags 有没有被系统改写）——[RX] 行就是答案；
 *   2. 云游戏客户端会不会把它当鼠标（看不看得见光标/视角跟不跟手）；
 *   3. 鼠标流与触摸流能不能并行（按住左键 + 另一根手指滑屏）。
 * ============================================================================
 */

// ============================== 日志 ==============================

object MouseProbe {

    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val lines = ArrayList<String>()
    private val lock = Any()
    private var logFile: File? = null

    val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    fun init(ctx: Context) {
        if (logFile != null) return
        synchronized(lock) {
            if (logFile != null) return
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            logFile = File(dir, "mouse-probe.log")
        }
    }

    val path: String get() = logFile?.absolutePath ?: "(未初始化)"

    fun log(channel: String, text: String) {
        val line = "${stamp.format(Date())} [$channel] $text"
        synchronized(lock) {
            lines.add(line)
            while (lines.size > 900) lines.removeAt(0)
            runCatching { logFile?.appendText(line + "\n") }
        }
        listeners.forEach { runCatching { it(line) } }
    }

    fun header(ctx: Context) {
        log("SYS", "device=${Build.MANUFACTURER} ${Build.MODEL} " +
                "android=${Build.VERSION.RELEASE}(sdk ${Build.VERSION.SDK_INT})")
        log("SYS", "screen=${ProbeOps.screenW}x${ProbeOps.screenH} " +
                "target=(${ProbeOps.targetX().toInt()},${ProbeOps.targetY().toInt()})")
        log("SYS", "logfile=$path")
    }

    fun dump(): String = synchronized(lock) { lines.joinToString("\n") }

    fun clear() = synchronized(lock) { lines.clear() }
}

// ============================== 注入动作 ==============================

object ProbeOps {

    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mouse-probe").apply { isDaemon = true }
    }

    @Volatile var screenW = 1080
    @Volatile var screenH = 2400

    /** 落点纵向比例，默认 45%（避开底部的面板和游戏自己的摇杆区） */
    @Volatile var yRatio = 0.45f

    val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    @Volatile private var busy = false

    fun targetX(): Float = screenW * 0.5f
    fun targetY(): Float = screenH * yRatio

    private fun status(text: String) {
        MouseProbe.log("OPS", text)
        listeners.forEach { runCatching { it(text) } }
    }

    private fun submit(name: String, block: () -> Unit) {
        if (busy) {
            status("上一步还在跑，稍等")
            return
        }
        busy = true
        status("▶ $name")
        exec.execute {
            try {
                block()
                status("■ $name 结束")
            } catch (t: Throwable) {
                status("✗ $name 异常 ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                busy = false
            }
        }
    }

    private fun injectOk(ok: Boolean) {
        if (!ok) {
            val err = ShizukuInjector.lastErrorText(AppCtx.get())
            MouseProbe.log("ERR", "注入被拒：${err.ifEmpty { "无更多信息" }}")
        }
    }

    /** 悬停巡检：横穿屏幕中段，看有没有光标/视角跟着动 */
    fun sweep(durationMs: Long = 2200, circle: Boolean = false) = submit("悬停巡检") {
        val y = targetY()
        val cx = targetX()
        if (circle) {
            val r = screenW * 0.22f
            val steps = 90
            for (i in 0..steps) {
                val a = i / steps.toFloat() * 2f * Math.PI.toFloat()
                val x = cx + r * kotlin.math.cos(a)
                val yy = (y + r * 0.6f * sin(a)).coerceIn(0f, screenH - 1f)
                val ok = ShizukuInjector.mouseMove(x, yy)
                MouseProbe.log("TX", "hoverCircle x=${x.toInt()} y=${yy.toInt()} ok=$ok")
                injectOk(ok)
                SystemClock.sleep((durationMs / (steps + 1)).coerceAtLeast(8))
            }
        } else {
            val x0 = screenW * 0.15f
            val x1 = screenW * 0.85f
            val steps = 60
            for (i in 0..steps) {
                val t = i / steps.toFloat()
                val x = x0 + (x1 - x0) * t
                val yy = (y + sin(t * 6.28f) * screenH * 0.03f).coerceIn(0f, screenH - 1f)
                val ok = ShizukuInjector.mouseMove(x, yy)
                MouseProbe.log("TX", "hoverSweep x=${x.toInt()} y=${yy.toInt()} ok=$ok")
                injectOk(ok)
                SystemClock.sleep((durationMs / (steps + 1)).coerceAtLeast(8))
            }
        }
        ShizukuInjector.mouseMoveSync(cx, y)
    }

    /** 一次完整拖拽：悬停 → 按下 → 横移 → 抬起 */
    fun dragOnce() = submit("按住拖动") {
        val y = targetY()
        val x0 = screenW * 0.25f
        val x1 = screenW * 0.75f
        ShizukuInjector.mouseMove(x0, y)
        SystemClock.sleep(60)
        val down = ShizukuInjector.mouseButtonDown(MotionEvent.BUTTON_PRIMARY, x0, y)
        MouseProbe.log("TX", "buttonDown x=${x0.toInt()} y=${y.toInt()} ok=$down")
        injectOk(down)
        var ok = true
        for (i in 1..24) {
            val x = x0 + (x1 - x0) * i / 24f
            ok = ShizukuInjector.mouseDrag(x, y) && ok
            MouseProbe.log("TX", "drag x=${x.toInt()} y=${y.toInt()}")
            SystemClock.sleep(28)
        }
        val up = ShizukuInjector.mouseButtonUp(x1, y)
        MouseProbe.log("TX", "buttonUp x=${x1.toInt()} ok=$up")
        injectOk(up)
        if (!ok) MouseProbe.log("ERR", "拖动过程中有一次 MOVE 没进队列")
    }

    /** 连点：验证 ACTION_DOWN/UP 能不能被目标当成左键单击 */
    fun taps(count: Int = 5) = submit("连点 ×$count") {
        val x = targetX()
        val y = targetY()
        repeat(count) { i ->
            val ok = ShizukuInjector.mouseClick(MotionEvent.BUTTON_PRIMARY, x, y)
            MouseProbe.log("TX", "click#${i + 1} x=${x.toInt()} y=${y.toInt()} ok=$ok " +
                    "diag=${ShizukuInjector.lastMouseDiag}")
            injectOk(ok)
            SystemClock.sleep(260)
        }
    }

    fun wheel() = submit("滚轮") {
        val x = targetX()
        val y = targetY()
        repeat(3) { i ->
            val ok = ShizukuInjector.mouseWheel(x, y, 1f)
            MouseProbe.log("TX", "wheel+#${i + 1} ok=$ok")
            injectOk(ok)
            SystemClock.sleep(180)
        }
        val back = ShizukuInjector.mouseWheel(x, y, -1f)
        MouseProbe.log("TX", "wheel- ok=$back")
    }

    /** 延时自动：给用户时间切到云游戏，然后按住左键画圈 6 秒（观察按住+拖动是否被认） */
    fun autoHoldCircle(delayMs: Long = 3000L, holdMs: Long = 6000L) = submit("自动按住+画圈") {
        status("${delayMs}ms 后开始，请切到目标应用")
        SystemClock.sleep(delayMs)
        val y = targetY()
        val cx = targetX()
        ShizukuInjector.mouseMove(cx, y)
        SystemClock.sleep(60)
        val down = ShizukuInjector.mouseButtonDown(MotionEvent.BUTTON_PRIMARY, cx, y)
        MouseProbe.log("TX", "autoHold buttonDown ok=$down")
        injectOk(down)
        val r = screenW * 0.18f
        val steps = (holdMs / 32).toInt().coerceAtLeast(20)
        var ok = down
        for (i in 0..steps) {
            val a = i / steps.toFloat() * 2f * Math.PI.toFloat()
            val x = (cx + r * kotlin.math.cos(a)).coerceIn(0f, screenW - 1f)
            val yy = (y + r * 0.6f * sin(a)).coerceIn(0f, screenH - 1f)
            ok = ShizukuInjector.mouseDrag(x, yy) && ok
            if (i % 10 == 0) MouseProbe.log("TX", "autoHold drag i=$i x=${x.toInt()} y=${yy.toInt()}")
            SystemClock.sleep(32)
        }
        val up = ShizukuInjector.mouseButtonUp(cx, y)
        MouseProbe.log("TX", "autoHold buttonUp ok=$up")
        injectOk(up)
    }

    /** 手动按住/抬起：方便自己掐时间切应用 */
    fun holdDown() = submit("按住左键") {
        val x = targetX()
        val y = targetY()
        val ok = ShizukuInjector.mouseButtonDown(MotionEvent.BUTTON_PRIMARY, x, y)
        MouseProbe.log("TX", "manualHold down ok=$ok")
        injectOk(ok)
    }

    fun holdUp() = submit("抬起左键") {
        val x = targetX()
        val y = targetY()
        val ok = ShizukuInjector.mouseButtonUp(x, y)
        MouseProbe.log("TX", "manualHold up ok=$ok")
        injectOk(ok)
    }

    fun runAll() = submit("全流程") {
        sweepShort()
        SystemClock.sleep(300)
        tapOnce()
        SystemClock.sleep(300)
        dragBlock()
    }

    // runAll 内部用不带 submit 的实现，避免 busy 自锁
    private fun sweepShort() {
        val y = targetY()
        val steps = 30
        for (i in 0..steps) {
            val x = screenW * (0.2f + 0.6f * i / steps.toFloat())
            val ok = ShizukuInjector.mouseMove(x, y)
            MouseProbe.log("TX", "all.hover x=${x.toInt()} ok=$ok")
            SystemClock.sleep(20)
        }
    }

    private fun tapOnce() {
        val x = targetX()
        val y = targetY()
        val ok = ShizukuInjector.mouseClick(MotionEvent.BUTTON_PRIMARY, x, y)
        MouseProbe.log("TX", "all.click ok=$ok diag=${ShizukuInjector.lastMouseDiag}")
    }

    private fun dragBlock() {
        val y = targetY()
        val x0 = screenW * 0.3f
        val x1 = screenW * 0.7f
        ShizukuInjector.mouseMove(x0, y)
        SystemClock.sleep(50)
        val down = ShizukuInjector.mouseButtonDown(MotionEvent.BUTTON_PRIMARY, x0, y)
        MouseProbe.log("TX", "all.down ok=$down")
        for (i in 1..15) {
            ShizukuInjector.mouseDrag(x0 + (x1 - x0) * i / 15f, y)
            SystemClock.sleep(30)
        }
        val up = ShizukuInjector.mouseButtonUp(x1, y)
        MouseProbe.log("TX", "all.up ok=$up")
    }

    fun shiftTarget(delta: Float) {
        yRatio = (yRatio + delta).coerceIn(0.12f, 0.9f)
        status("落点 = (${targetX().toInt()}, ${targetY().toInt()})")
    }
}

/** 给 ProbeOps 拿个全局 Context（只用于读注入错误文案） */
object AppCtx {
    private var app: Context? = null
    fun set(c: Context) { if (app == null) app = c.applicationContext }
    fun get(): Context = app ?: throw IllegalStateException("context 未初始化")
}

// ============================== 悬浮面板 ==============================

/**
 * 独立悬浮面板。窗口标题固定 "hjm-mouse-probe"，
 * 类型 TYPE_APPLICATION_OVERLAY，可拖动、可收成小圆点。
 */
class MouseProbePanel(private val ctx: Context) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var lp: WindowManager.LayoutParams? = null
    private var root: LinearLayout? = null
    private var full: LinearLayout? = null
    private var dot: TextView? = null
    private var statusView: TextView? = null

    var showing = false
        private set

    private var dragX = 0f
    private var dragY = 0f
    private var originX = 0
    private var originY = 0

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    private fun pill(text: String, onClick: () -> Unit): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 11f
        gravity = Gravity.CENTER
        setPadding(dp(6), dp(5), dp(6), dp(5))
        background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(0x33FFFFFF)
            setStroke(dp(1), 0x55FFFFFF)
        }
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun row(vararg items: View): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        for (v in items) {
            addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(2), dp(2), dp(2), dp(2))
            })
        }
    }

    fun show() {
        if (showing) return
        if (!Settings.canDrawOverlays(ctx)) {
            Toast.makeText(ctx, "没有悬浮窗权限", Toast.LENGTH_SHORT).show()
            return
        }
        MouseProbe.init(ctx)

        val header = TextView(ctx).apply {
            text = "MOUSE PROBE  ·  hjm"
            setTextColor(0xFF9FD5FF.toInt())
            textSize = 11f
            setPadding(dp(6), dp(2), dp(6), dp(6))
        }
        val close = TextView(ctx).apply {
            text = "×"
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(dp(8), 0, dp(4), 0)
            setOnClickListener { hide() }
        }
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(header, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(close)
        }

        val statusTv = TextView(ctx).apply {
            text = "待命"
            setTextColor(0xFFDDDDDD.toInt())
            textSize = 10f
            setPadding(dp(6), 0, dp(6), dp(4))
        }
        statusView = statusTv

        val buttonRows = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(row(
                pill("巡检") { ProbeOps.sweep() },
                pill("画圈") { ProbeOps.sweep(circle = true) },
                pill("拖动") { ProbeOps.dragOnce() }
            ))
            addView(row(
                pill("连点") { ProbeOps.taps() },
                pill("滚轮") { ProbeOps.wheel() },
                pill("按住") { ProbeOps.holdDown() })
            )
            addView(row(
                pill("抬起") { ProbeOps.holdUp() },
                pill("自动") { ProbeOps.autoHoldCircle() },
                pill("收起") { minimize() })
            )
            addView(row(
                pill("落点↑") { ProbeOps.shiftTarget(-0.08f) },
                pill("落点↓") { ProbeOps.shiftTarget(0.08f) },
                pill("坐标") {
                    Toast.makeText(
                        ctx,
                        "(${ProbeOps.targetX().toInt()}, ${ProbeOps.targetY().toInt()})  " +
                                "${ProbeOps.screenW}x${ProbeOps.screenH}",
                        Toast.LENGTH_SHORT
                    ).show()
                })
            )
        }

        val foot = TextView(ctx).apply {
            text = "window: hjm-mouse-probe"
            setTextColor(0xFF888888.toInt())
            textSize = 9f
            setPadding(dp(6), dp(4), dp(6), 0)
        }

        val fullBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            minimumWidth = dp(248)
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xE60E1218.toInt())
                setStroke(dp(1), 0x66A9D6FF.toInt())
            }
            setPadding(dp(8), dp(8), dp(6), dp(8))
            addView(headerRow)
            addView(statusTv)
            addView(buttonRows)
            addView(foot)
        }
        full = fullBox

        val dotView = TextView(ctx).apply {
            text = "M"
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(100).toFloat()
                setColor(0xE6A9D6FF.toInt())
            }
            visibility = View.GONE
        }
        dot = dotView

        val rootBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(fullBox)
            addView(dotView)
        }
        root = rootBox

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(120)
            // 窗口标记：dumpsys window windows | grep hjm-mouse-probe
            setTitle("hjm-mouse-probe")
        }
        lp = params

        headerRow.setOnTouchListener(dragListener(true, fullLayoutUpdater = true))
        dotView.setOnTouchListener(dotListener())

        wm.addView(rootBox, params)
        showing = true

        ProbeOps.listeners.add(statusListener)
        MouseProbe.log("UI", "面板已挂载, title=hjm-mouse-probe, " +
                "type=TYPE_APPLICATION_OVERLAY, size=${ProbeOps.screenW}x${ProbeOps.screenH}")
    }

    private val statusListener: (String) -> Unit = { text ->
        Handler(Looper.getMainLooper()).post { statusView?.text = text }
    }

    fun hide() {
        if (!showing) return
        ProbeOps.listeners.remove(statusListener)
        runCatching { root?.let { wm.removeView(it) } }
        root = null
        full = null
        dot = null
        statusView = null
        showing = false
        MouseProbe.log("UI", "面板已移除")
    }

    fun setStatus(text: String) {
        statusView?.post { statusView?.text = text }
    }

    private fun minimize() {
        full?.visibility = View.GONE
        dot?.visibility = View.VISIBLE
        lp?.let { p ->
            p.width = dp(44)
            p.height = dp(44)
            root?.let { wm.updateViewLayout(it, p) }
        }
    }

    private fun restore() {
        full?.visibility = View.VISIBLE
        dot?.visibility = View.GONE
        lp?.let { p ->
            p.width = WindowManager.LayoutParams.WRAP_CONTENT
            p.height = WindowManager.LayoutParams.WRAP_CONTENT
            root?.let { wm.updateViewLayout(it, p) }
        }
    }

    private fun dragListener(@Suppress("UNUSED_PARAMETER") ignored: Boolean, fullLayoutUpdater: Boolean): View.OnTouchListener =
        View.OnTouchListener { _, e ->
            val p = lp ?: return@OnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragX = e.rawX
                    dragY = e.rawY
                    originX = p.x
                    originY = p.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    p.x = (originX + e.rawX - dragX).toInt()
                    p.y = (originY + e.rawY - dragY).toInt()
                    root?.let { wm.updateViewLayout(it, p) }
                    true
                }
                else -> true
            }
        }

    private fun dotListener(): View.OnTouchListener {
        var moved = false
        var downX = 0f
        var downY = 0f
        var ox = 0
        var oy = 0
        return View.OnTouchListener { _, e ->
            val p = lp ?: return@OnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    moved = false
                    downX = e.rawX
                    downY = e.rawY
                    ox = p.x
                    oy = p.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - downX) > dp(6) || abs(e.rawY - downY) > dp(6)) moved = true
                    if (moved) {
                        p.x = (ox + e.rawX - downX).toInt()
                        p.y = (oy + e.rawY - downY).toInt()
                        root?.let { wm.updateViewLayout(it, p) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) restore()
                    true
                }
                else -> false
            }
        }
    }
}

// ============================== 控制台 Activity ==============================

class MouseProbeActivity : Activity() {

    private lateinit var panel: MouseProbePanel
    private lateinit var tvStatus: TextView
    private lateinit var tvTarget: TextView
    private lateinit var tvLog: TextView
    private lateinit var sw: MaterialSwitch
    private val ui = Handler(Looper.getMainLooper())
    private val logBuf = StringBuilder()

    private val onLine: (String) -> Unit = { line ->
        ui.post { appendLog(line) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCtx.set(this)
        MouseProbe.init(this)
        ProbeOps.screenW = resources.displayMetrics.widthPixels
        ProbeOps.screenH = resources.displayMetrics.heightPixels
        panel = MouseProbePanel(this)
        setContentView(buildUi())
        MouseProbe.listeners.add(onLine)
        ProbeOps.listeners.add(statusUpdater)
        refreshStatus()
        MouseProbe.log("UI", "控制台打开, 屏幕 ${ProbeOps.screenW}x${ProbeOps.screenH}, " +
                "日志文件 ${MouseProbe.path}")
    }

    private val statusUpdater: (String) -> Unit = { text -> ui.post { tvStatus.text = text } }

    override fun onResume() {
        super.onResume()
        ProbeOps.screenW = resources.displayMetrics.widthPixels
        ProbeOps.screenH = resources.displayMetrics.heightPixels
        sw.isChecked = panel.showing
        refreshTarget()
        refreshStatus()
    }

    override fun onDestroy() {
        MouseProbe.listeners.remove(onLine)
        ProbeOps.listeners.remove(statusUpdater)
        // 面板故意不随 Activity 一起消失：要切到云游戏里看效果
        super.onDestroy()
    }

    // ---------- 事件接收探针：把"系统交给应用层的真实字段"打出来 ----------
    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        logRx("GENERIC", ev)
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        logRx("TOUCH", ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun logRx(channel: String, ev: MotionEvent) {
        val tool = when (ev.getToolType(0)) {
            MotionEvent.TOOL_TYPE_FINGER -> "FINGER"
            MotionEvent.TOOL_TYPE_MOUSE -> "MOUSE"
            MotionEvent.TOOL_TYPE_STYLUS -> "STYLUS"
            MotionEvent.TOOL_TYPE_ERASER -> "ERASER"
            MotionEvent.TOOL_TYPE_UNKNOWN -> "UNKNOWN"
            else -> "T${ev.getToolType(0)}"
        }
        val src = when (ev.source and InputDevice.SOURCE_CLASS_MASK) {
            InputDevice.SOURCE_CLASS_POINTER -> "POINTER"
            InputDevice.SOURCE_CLASS_POSITION -> "POSITION"
            InputDevice.SOURCE_CLASS_MASK -> "?"
            else -> "CLS${ev.source and InputDevice.SOURCE_CLASS_MASK}"
        }
        val axRel = runCatching {
            " relX=${ev.getAxisValue(MotionEvent.AXIS_RELATIVE_X)}" +
                    " relY=${ev.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)}"
        }.getOrDefault("")
        MouseProbe.log(
            "RX", "$channel action=${actionName(ev.actionMasked)} " +
                    "src=0x${ev.source.toString(16)}($src) tool=$tool dev=${ev.deviceId} " +
                    "btn=0x${ev.buttonState.toString(16)} actBtn=${ev.actionButton} " +
                    "flags=0x${ev.flags.toString(16)} meta=0x${ev.metaState.toString(16)} " +
                    "x=${"%.1f".format(ev.x)} y=${"%.1f".format(ev.y)}$axRel"
        )
    }

    private fun actionName(a: Int): String = when (a) {
        MotionEvent.ACTION_DOWN -> "DOWN"
        MotionEvent.ACTION_UP -> "UP"
        MotionEvent.ACTION_MOVE -> "MOVE"
        MotionEvent.ACTION_CANCEL -> "CANCEL"
        MotionEvent.ACTION_OUTSIDE -> "OUTSIDE"
        MotionEvent.ACTION_HOVER_MOVE -> "HOVER_MOVE"
        MotionEvent.ACTION_HOVER_ENTER -> "HOVER_ENTER"
        MotionEvent.ACTION_HOVER_EXIT -> "HOVER_EXIT"
        MotionEvent.ACTION_SCROLL -> "SCROLL"
        MotionEvent.ACTION_POINTER_DOWN -> "POINTER_DOWN"
        MotionEvent.ACTION_POINTER_UP -> "POINTER_UP"
        else -> "A$a"
    }

    // ---------- UI ----------

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(0xFF101418.toInt())
        }

        val title = TextView(this).apply {
            text = "鼠标注入探针"
            setTextColor(Color.WHITE)
            textSize = 18f
        }
        root.addView(title)

        root.addView(TextView(this).apply {
            text = "实验模块 · 独立开关 / 独立窗口 / 独立日志\n" +
                    "目的：确认 Shizuku 注入的 SOURCE_MOUSE 事件能否被云游戏当作鼠标，\n" +
                    "并且和触摸流并行（按住左键 + 另一根手指滑屏）。"
            setTextColor(0xFF9AA7B4.toInt())
            textSize = 11f
            setPadding(0, dp(6), 0, dp(10))
        })

        tvStatus = TextView(this).apply {
            text = "待命"
            setTextColor(0xFF7FD8A0.toInt())
            textSize = 12f
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(tvStatus)

        val swRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        sw = MaterialSwitch(this).apply {
            text = "启用探测悬浮面板"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(0, dp(4), dp(8), dp(4))
        }
        swRow.addView(sw, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        swRow.addView(Button(this).apply {
            text = "申请授权"
            textSize = 11f
            setOnClickListener { requestShizuku() }
        })
        root.addView(swRow)

        sw.setOnCheckedChangeListener { _, checked ->
            if (checked == panel.showing) return@setOnCheckedChangeListener
            if (checked) {
                if (!shizukuGranted()) {
                    sw.isChecked = false
                    requestShizuku()
                    return@setOnCheckedChangeListener
                }
                panel.show()
                MouseProbe.header(this)
            } else {
                panel.hide()
            }
            ui.post { sw.isChecked = panel.showing }
            refreshStatus()
        }

        tvTarget = TextView(this).apply {
            setTextColor(0xFF9AA7B4.toInt())
            textSize = 11f
            setPadding(0, dp(6), 0, dp(6))
        }
        root.addView(tvTarget)

        // 按钮区
        fun mkRow(vararg labels: Pair<String, () -> Unit>): LinearLayout =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                for ((label, action) in labels) {
                    addView(Button(this@MouseProbeActivity).apply {
                        text = label
                        textSize = 10f
                        setPadding(dp(2), 0, dp(2), 0)
                        minWidth = 0
                        minimumWidth = 0
                        setOnClickListener { action() }
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(2), dp(2), dp(2), dp(2))
                    })
                }
            }

        root.addView(mkRow(
            "悬停巡检" to { ProbeOps.sweep() },
            "悬停画圈" to { ProbeOps.sweep(circle = true) }
        ))
        root.addView(mkRow(
            "按住拖动" to { ProbeOps.dragOnce() },
            "连点" to { ProbeOps.taps() },
            "滚轮" to { ProbeOps.wheel() }
        ))
        root.addView(mkRow(
            "自动按住+画圈" to { ProbeOps.autoHoldCircle() }
        ))
        root.addView(mkRow(
            "按住左键" to { ProbeOps.holdDown() },
            "抬起左键" to { ProbeOps.holdUp() },
            "全流程" to { ProbeOps.runAll() }
        ))
        root.addView(mkRow(
            "落点↑" to { ProbeOps.shiftTarget(-0.08f) },
            "落点↓" to { ProbeOps.shiftTarget(0.08f) }
        ))

        root.addView(TextView(this).apply {
            text = "日志（[TX]=我们注入的，[RX]=应用层真收到的）"
            setTextColor(0xFF9AA7B4.toInt())
            textSize = 11f
            setPadding(0, dp(12), 0, dp(4))
        })

        tvLog = TextView(this).apply {
            setTextColor(0xFFCFE7FF.toInt())
            textSize = 9f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(0xFF06090C.toInt())
        }
        val logScroll = ScrollView(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(0xFF06090C.toInt())
            }
            addView(tvLog)
        }
        root.addView(logScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(240)
        ))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        fun bottomBtn(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            textSize = 10f
            minWidth = 0
            minimumWidth = 0
            setOnClickListener { action() }
        }
        bottom.addView(bottomBtn("复制日志") { copyLog() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(bottomBtn("分享日志") { shareLog() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(bottomBtn("清空") { logBuf.clear(); MouseProbe.clear(); tvLog.text = "" },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(bottomBtn("关闭应用") { finish() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bottom)

        return ScrollView(this).apply { addView(root) }
    }

    private fun appendLog(line: String) {
        logBuf.append(line).append('\n')
        tvLog.text = logBuf.toString()
        val scroll = tvLog.parent as? ScrollView
        scroll?.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun refreshStatus() {
        val s = when {
            !runCatching { Shizuku.pingBinder() }.getOrDefault(false) -> "Shizuku 未运行"
            !shizukuGranted() -> "Shizuku 未授权"
            else -> "就绪（注入可用）"
        }
        tvStatus.text = "状态：$s"
        refreshTarget()
    }

    private fun refreshTarget() {
        tvTarget.text = "屏幕 ${ProbeOps.screenW}×${ProbeOps.screenH}　" +
                "落点 (${ProbeOps.targetX().toInt()}, ${ProbeOps.targetY().toInt()})　" +
                "面板：${if (panel.showing) "已挂载(hjm-mouse-probe)" else "未挂载"}"
    }

    private fun shizukuGranted(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun requestShizuku() {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            toast("Shizuku 没在运行")
            return
        }
        when {
            shizukuGranted() -> toast("已经授权了")
            Shizuku.shouldShowRequestPermissionRationale() -> toast("请在 Shizuku 里允许")
            else -> Shizuku.requestPermission(0)
        }
    }

    private fun copyLog() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("mouse-probe", MouseProbe.dump()))
        toast("日志已复制（${MouseProbe.dump().length} 字符）")
    }

    private fun shareLog() {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, MouseProbe.dump())
        }
        runCatching { startActivity(Intent.createChooser(i, "分享探针日志")) }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
