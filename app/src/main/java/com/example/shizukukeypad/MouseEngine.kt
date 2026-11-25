package com.example.shizukukeypad

import android.os.Process
import android.os.SystemClock

/**
 * 鼠标悬停注入节拍器。
 *
 * 为什么需要单独一条线程：
 *
 * 1. 手指拖动时触摸采样是「成串」下发的（系统会把一帧内的多个采样打包给你），
 *    收到一个采样就立刻注入一次，游戏侧看到的是「一坨一坨」的位移 —— 就是一顿一顿。
 * 2. Android 12/13 这类低版本单次注入要十几毫秒。同步注入会把调用线程堵住，
 *    这段时间里到达的采样只能被丢掉（旧代码就是靠 hoverBusy 丢帧），光标走得更抖。
 *
 * 现在的做法：界面侧只更新「目标坐标」，注入交给这条独立的高优先级线程，
 * 按固定节拍取最新目标、**同步**发出（WAIT_FOR_RESULT，只等「已分发」不等应用消费）。
 *
 * 为什么这一版不再用异步：
 * 异步注入只是把事件塞进队列，塞得比应用消化得快，队列就会越积越多，
 * 应用最后一次性把一堆位移处理掉 —— 观感还是「停一下、蹿一下」。
 * 改成同步之后，这一拍要等系统收下才发下一拍，节拍会自己贴合设备能接受的速度，
 * 队列永远是浅的，位移就是均匀的。
 */
object MouseEngine {

    /** 节拍周期（毫秒）。默认 8ms；这条线程是同步注入，慢了会自动被拖长，不会积压 */
    @Volatile
    var periodMs: Long = 8L
        private set

    /** 每拍发送：返回系统是否收下这个悬停事件 */
    @Volatile
    var sendPaced: ((Float, Float) -> Boolean)? = null

    /** 降级用的异步发送（返回即走，不等系统分发） */
    @Volatile
    var sendAsync: ((Float, Float) -> Boolean)? = null

    /** 连续几拍超时后自动切成异步：同步注入一旦被系统拖住，整条节拍就塌了 */
    @Volatile
    var usingAsync = false
        private set

    /** 松手定格时用：同步 + 等到分发，保证最终位置一定送达 */
    @Volatile
    var sendSync: ((Float, Float) -> Boolean)? = null

    // ---------------- 诊断计数（拖动时显示在触控板上） ----------------

    /** 收到的采样数（每次 setTarget +1） */
    @Volatile
    var samples: Long = 0L
        private set

    /** 真正发出去的悬停事件数 */
    @Volatile
    var sends: Long = 0L
        private set

    /** 被系统拒绝的悬停事件数 */
    @Volatile
    var rejects: Long = 0L
        private set

    /** 最近一次注入耗时（毫秒） */
    @Volatile
    var lastCostMs: Float = 0f
        private set

    /** 平均注入耗时（毫秒，滑动平均） */
    @Volatile
    var avgCostMs: Float = 0f
        private set

    @Volatile
    private var targetX = 0f

    @Volatile
    private var targetY = 0f

    /** 上一次真正发出去的坐标（NaN 表示还没有发过） */
    private var sentX = Float.NaN
    private var sentY = Float.NaN

    @Volatile
    private var running = false

    private var slowStreak = 0

    private val lock = Any()
    private var thread: Thread? = null

    fun isRunning(): Boolean = running

    /** 按屏幕刷新率调整节拍：不低于 6ms，不高过 16ms */
    fun setRefreshRate(hz: Float) {
        periodMs = if (hz <= 0f) 8L else (1000f / hz).toLong().coerceIn(6L, 16L)
    }

    @Synchronized
    fun start() {
        if (running) return
        running = true
        synchronized(lock) {
            sentX = Float.NaN
            sentY = Float.NaN
        }
        thread = Thread({ loop() }, "MouseTicker").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            runCatching { start() }
        }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        thread = null
        synchronized(lock) {
            sentX = Float.NaN
            sentY = Float.NaN
        }
    }

    /** 界面侧每收到一个采样就更新目标，注入由节拍线程负责 */
    fun setTarget(x: Float, y: Float) {
        targetX = x
        targetY = y
        samples++
    }

    /** 强制下一拍重新发送一次（光标刚出现、刚切换模式时用） */
    fun invalidatePosition() {
        synchronized(lock) {
            sentX = Float.NaN
            sentY = Float.NaN
        }
    }

    /** 重置诊断计数 */
    fun resetStats() {
        samples = 0
        sends = 0
        rejects = 0
        usingAsync = false
        slowStreak = 0
        lastCostMs = 0f
        avgCostMs = 0f
    }

    /**
     * 立刻同步发一次（松手、定格、注入鼠标键之前）。
     * 返回是否被系统接受；同时把该位置记为「已发送」，避免节拍线程重复发。
     */
    fun flush(x: Float, y: Float): Boolean {
        val send = sendSync ?: return false
        return synchronized(lock) {
            val ok = send(x, y)
            sentX = x
            sentY = y
            ok
        }
    }

    private fun loop() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) }
        var next = SystemClock.uptimeMillis()
        while (running) {
            next += periodMs
            val now = SystemClock.uptimeMillis()
            if (next > now) SystemClock.sleep(next - now) else next = now
            if (!running) break
            tick()
        }
        synchronized(lock) {
            sentX = Float.NaN
            sentY = Float.NaN
        }
    }

    private fun tick() {
        val tx = targetX
        val ty = targetY
        val send = sendPaced ?: return
        synchronized(lock) {
            if (!sentX.isNaN()) {
                val dx = tx - sentX
                val dy = ty - sentY
                if (dx * dx + dy * dy < 0.25f) return
            }
            val t0 = SystemClock.uptimeMillis()
            val goAsync = usingAsync
            val ok = runCatching {
                if (goAsync) (sendAsync ?: send)(tx, ty) else send(tx, ty)
            }.getOrDefault(false)
            val cost = (SystemClock.uptimeMillis() - t0).toFloat()
            if (!goAsync) {
                if (cost > 40f) slowStreak++ else slowStreak = 0
                if (slowStreak >= 3) {
                    usingAsync = true
                    slowStreak = 0
                    android.util.Log.i("KeypadPad", "hover inject slow (${cost}ms) -> switch to async")
                }
            }
            lastCostMs = cost
            avgCostMs = if (avgCostMs <= 0f) cost else avgCostMs * 0.8f + cost * 0.2f
            if (ok) {
                sentX = tx
                sentY = ty
                sends++
            } else {
                rejects++
            }
        }
    }
}
