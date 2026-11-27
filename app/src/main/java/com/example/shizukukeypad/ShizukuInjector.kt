package com.example.shizukukeypad

import android.content.Context
import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * 通过 Shizuku（shell 身份）调用隐藏服务 IInputManager.injectInputEvent 注入事件。
 *
 * 关键点：
 * 1. 同步模式 INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH(1)：能拿到系统真实返回值。
 * 2. 键盘事件与系统 input keyevent 完全一致：SOURCE_KEYBOARD + flags 0。
 * 3. 修饰键（Shift/Ctrl/Alt）按住时，后续按键事件会带上对应 metaState。
 * 4. 触摸事件坐标就是屏幕真实坐标；多点触控共用一条指针流，每次按下分配独立 pointerId。
 */
object ShizukuInjector {

    /**
     * IInputManager.injectInputEvent 的三个模式：
     *   ASYNC(0)              —— 入队就返回，不等待系统分发，鼠标悬停这类高频事件用它，
     *                            低版本单次注入十几毫秒也不会把节拍线程堵住。
     *   WAIT_FOR_FINISH(1)    —— 等到目标应用处理完，能拿到真实结果（按键/按下抬起用）。
     *   WAIT_FOR_RESULT(2)    —— 等到事件分发出去，不等到消费（"先悬停再按键"的定序用）。
     */
    private const val MODE_ASYNC = 0
    private const val MODE_WAIT_FOR_FINISH = 1
    private const val MODE_WAIT_FOR_RESULT = 2
    private const val TAG = "ShizukuInjector"

    private var inputManager: Any? = null
    private var injectMethod: Method? = null
    private val heldKeys = linkedSetOf<Int>()
    private val downTimes = HashMap<Int, Long>()

    @Volatile
    private var lastErrorRes: Int = 0

    @Volatile
    private var lastErrorArgs: List<String> = emptyList()

    fun lastErrorText(ctx: Context): String =
        if (lastErrorRes == 0) "" else ctx.getString(lastErrorRes, *lastErrorArgs.toTypedArray())

    private fun setError(res: Int, vararg args: String) {
        lastErrorRes = res
        lastErrorArgs = args.toList()
    }

    @Volatile
    var ready: Boolean = false
        private set

    @Synchronized
    fun reset() {
        inputManager = null
        injectMethod = null
        heldKeys.clear()
        downTimes.clear()
        activePointers.clear()
        gestureDownTime = 0L
        nextPointerId = 0
        hoverActive = false
        lastMouseDiag = ""
        ready = false
        lastErrorRes = 0
        lastErrorArgs = emptyList()
    }

    /** 建立到 IInputManager 的远程代理 */
    @Synchronized
    private fun ensureReady(): Boolean {
        if (injectMethod != null) return true
        lastErrorRes = 0
        try {
            if (!Shizuku.pingBinder()) {
                setError(R.string.injector_shizuku_not_connected)
                return false
            }
            val binder: IBinder = SystemServiceHelper.getSystemService("input")
                ?: run {
                    setError(R.string.injector_no_input_service)
                    return false
                }
            val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
            val asInterface = stub.getMethod("asInterface", IBinder::class.java)
            val manager = asInterface.invoke(null, ShizukuBinderWrapper(binder))
                ?: run {
                    setError(R.string.injector_as_interface_null)
                    return false
                }
            val method = try {
                Class.forName("android.hardware.input.IInputManager")
                    .getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            } catch (t: Throwable) {
                // 部分 ROM 的接口类不可见，退回代理实例自己的方法
                manager.javaClass.getMethod(
                    "injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType
                )
            }
            inputManager = manager
            injectMethod = method
            ready = true
            return true
        } catch (t: Throwable) {
            setError(R.string.injector_init_failed, t.javaClass.simpleName, t.message ?: "")
            ready = false
            return false
        }
    }

    private fun inject(event: InputEvent): Boolean = injectMode(event, MODE_WAIT_FOR_FINISH)

    /** 异步注入：只保证事件进了系统队列，返回 false 表示连队列都没进去 */
    private fun injectAsync(event: InputEvent): Boolean = injectMode(event, MODE_ASYNC)

    /** 只等「已分发」：不等应用消费完，既有系统回执又不会被忙的应用拖成假失败 */
    private fun injectResult(event: InputEvent): Boolean = injectMode(event, MODE_WAIT_FOR_RESULT)

    private fun injectMode(event: InputEvent, mode: Int): Boolean {
        if (!ensureReady()) return false
        return try {
            val accepted = injectMethod!!.invoke(inputManager, event, mode) as? Boolean ?: false
            if (!accepted && mode != MODE_ASYNC) setError(R.string.injector_rejected)
            accepted
        } catch (t: Throwable) {
            val cause = (t as? InvocationTargetException)?.targetException ?: t
            setError(R.string.injector_exception, cause.javaClass.simpleName, cause.message ?: "")
            false
        } finally {
            if (event is MotionEvent) event.recycle()
        }
    }

    // ==================== 键盘 ====================

    private fun metaFor(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> KeyEvent.META_SHIFT_ON
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> KeyEvent.META_CTRL_ON
        KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> KeyEvent.META_ALT_ON
        KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> KeyEvent.META_META_ON
        else -> 0
    }

    private fun currentMeta(): Int {
        var meta = 0
        heldKeys.forEach { meta = meta or metaFor(it) }
        return meta
    }

    private fun keyEvent(
        keyCode: Int,
        action: Int,
        repeat: Int,
        metaState: Int,
        downTime: Long
    ): KeyEvent {
        val now = SystemClock.uptimeMillis()
        // 与系统 /system/bin/input keyevent 生成的完全一致：
        // source 固定 SOURCE_KEYBOARD，flags 为 0，deviceId 为 VIRTUAL_KEYBOARD。
        // 之前把方向键标成 SOURCE_DPAD 会让不少应用把它当手柄事件直接过滤掉。
        return KeyEvent(
            downTime, now, action, keyCode, repeat,
            metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
            0, InputDevice.SOURCE_KEYBOARD
        )
    }

    /** 按下。重复按下同一键会带 repeat 计数。extraMeta 用于注入上档符号（Shift+数字） */
    @Synchronized
    fun keyDown(keyCode: Int, extraMeta: Int = 0): Boolean {
        val first = heldKeys.add(keyCode)
        val now = SystemClock.uptimeMillis()
        val downTime = if (first) now else (downTimes[keyCode] ?: now)
        downTimes[keyCode] = downTime
        val meta = currentMeta() or extraMeta
        return inject(keyEvent(keyCode, KeyEvent.ACTION_DOWN, if (first) 0 else 1, meta, downTime))
    }

    /** 抬起。metaState 用抬起前的状态（修饰键抬起时自身也算在内） */
    @Synchronized
    fun keyUp(keyCode: Int, extraMeta: Int = 0): Boolean {
        val meta = currentMeta() or extraMeta
        heldKeys.remove(keyCode)
        val now = SystemClock.uptimeMillis()
        val downTime = downTimes.remove(keyCode) ?: now
        return inject(keyEvent(keyCode, KeyEvent.ACTION_UP, 0, meta, downTime))
    }

    /** 松手/退出时兜底，避免修饰键卡住 */
    @Synchronized
    fun releaseAll() {
        heldKeys.toList().forEach { keyUp(it) }
    }

    // ==================== 触摸（多点触控） ====================

    /** 当前按下的所有触点：pointerId -> [x, y] */
    private val activePointers = LinkedHashMap<Int, FloatArray>()
    private var gestureDownTime = 0L
    private var nextPointerId = 0

    private fun multiTouchEvent(action: Int, eventTime: Long): MotionEvent? {
        if (activePointers.isEmpty()) return null
        val ids = activePointers.keys.toList()
        val props = Array(ids.size) { i ->
            MotionEvent.PointerProperties().apply {
                id = ids[i]
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(ids.size) { i ->
            val p = activePointers[ids[i]]!!
            MotionEvent.PointerCoords().apply {
                x = p[0]
                y = p[1]
                pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f
                size = 1f
            }
        }
        return MotionEvent.obtain(
            gestureDownTime, eventTime, action, ids.size,
            props, coords, 0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0
        )
    }

    /**
     * 按下屏幕坐标，返回本次触点的 pointerId。
     * 多点同时按下时，后按的手指用 ACTION_POINTER_DOWN，游戏能识别为真正的多指操作。
     */
    @Synchronized
    fun touchDown(x: Float, y: Float): Int {
        if (activePointers.isEmpty()) {
            gestureDownTime = SystemClock.uptimeMillis()
            nextPointerId = 0
        }
        val id = nextPointerId++
        activePointers[id] = floatArrayOf(x, y)
        val index = activePointers.size - 1
        val action = if (index == 0) {
            MotionEvent.ACTION_DOWN
        } else {
            MotionEvent.ACTION_POINTER_DOWN or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        }
        multiTouchEvent(action, SystemClock.uptimeMillis())?.let { inject(it) }
        return id
    }

    @Synchronized
    fun touchMove(id: Int, x: Float, y: Float): Boolean {
        val p = activePointers[id] ?: return false
        p[0] = x
        p[1] = y
        val event = multiTouchEvent(MotionEvent.ACTION_MOVE, SystemClock.uptimeMillis())
        return event != null && inject(event)
    }

    @Synchronized
    fun touchUp(id: Int): Boolean {
        if (!activePointers.containsKey(id)) return false
        val index = activePointers.keys.indexOf(id)
        val action = if (activePointers.size == 1) {
            MotionEvent.ACTION_UP
        } else {
            MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        }
        val event = multiTouchEvent(action, SystemClock.uptimeMillis())
        activePointers.remove(id)
        val ok = if (event != null) inject(event) else false
        if (activePointers.isEmpty()) gestureDownTime = 0L
        return ok
    }

    @Synchronized
    fun releaseAllTouches() {
        if (activePointers.isEmpty()) return
        val event = multiTouchEvent(MotionEvent.ACTION_CANCEL, SystemClock.uptimeMillis())
        activePointers.clear()
        gestureDownTime = 0L
        event?.let { inject(it) }
    }

    // ==================== 单点触摸 ====================

    /** 在屏幕坐标 (x, y) 点按一次（原生手游兼容性最好），holdMs > 40 即为长按 */
    fun tap(x: Float, y: Float, holdMs: Long = 40L): Boolean {
        val id = touchDown(x, y)
        if (holdMs > 0) SystemClock.sleep(holdMs)
        return touchUp(id)
    }

    /** 从 (x, y) 滑动到 (x + dx, y + dy)，用于滚轮/翻页 */
    fun swipe(x: Float, y: Float, dx: Float, dy: Float, durationMs: Int = 240): Boolean {
        val id = touchDown(x, y)
        var ok = true
        val steps = 12
        for (i in 1..steps) {
            SystemClock.sleep((durationMs / steps).toLong())
            ok = touchMove(id, x + dx * i / steps, y + dy * i / steps) && ok
        }
        ok = touchUp(id) && ok
        return ok
    }

    // ==================== 鼠标 ====================

    private var mouseDownTime = 0L


    /** 指针是否已经在系统里"悬停"过（低版本没有这层状态时按键事件会被直接丢掉） */
    private var hoverActive = false

    /** 最近一次鼠标注入的过程记录：失败时拼进提示，方便在低版本机型上一眼定位 */
    @Volatile
    var lastMouseDiag: String = ""
        private set

    private fun diag(text: String) {
        lastMouseDiag = text
        Log.i(TAG, "mouse: $text")
    }

    /**
     * 悬停事件用 downTime = 0、pressure = 0 —— 这是真实鼠标悬停事件的样子。
     * 早先按"按下"的写法造悬停事件，Android 12/13 的 InputDispatcher 会认为
     * 事件状态自相矛盾，直接丢弃，表现就是光标不动或移动一顿一顿。
     */
    private fun mouseEvent(
        action: Int,
        x: Float,
        y: Float,
        buttonState: Int,
        downTime: Long,
        eventTime: Long,
        vScroll: Float = 0f,
        hover: Boolean = false,
        actionButton: Int = 0
    ): MotionEvent {
        val props = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = if (hover) 0f else 1f
            size = if (hover) 0f else 1f
            if (vScroll != 0f) setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
        }
        val ev = MotionEvent.obtain(
            if (hover) 0L else downTime, eventTime, action, 1,
            arrayOf(props), arrayOf(coords),
            0, buttonState, 1f, 1f, 0, 0,
            InputDevice.SOURCE_MOUSE, 0
        )
        // 只有 buttonState 不够：串流/游戏/桌面类应用多数用 getActionButton()
        // 判断"哪个键按下去 / 哪个键松开"。这个字段只能反射补（注入在 shell 进程里跑，
        // 不受应用层隐藏 API 限制）；补不上就算了，不影响本来能用的场景。
        if (!hover && actionButton != 0) {
            runCatching {
                val f = MotionEvent::class.java.getDeclaredField("mActionButton")
                f.isAccessible = true
                f.setInt(ev, actionButton)
            }
        }
        return ev
    }

    /**
     * 按下/滚动鼠标键之前，先把指针"摆"到目标位置。
     *
     * 真鼠标的按键事件前面必然跟着一串悬停事件；而 12/13 的 InputDispatcher
     * 在缺少"指针已在该窗口悬停"这层状态时，按键事件会因为找不到落点窗口
     * 被判定为注入失败（injectInputEvent 返回 false）—— 这正是"左右键注入失败"。
     * 用 WAIT_FOR_RESULT 是为了拿到"已分发"的回执，保证按键事件排它后面。
     */
    @Synchronized
    private fun hoverFirst(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        if (!hoverActive) {
            // 第一次：ENTER 与 MOVE 都等「已分发」。低版本上 ENTER 还在队列里就发按键，
            // InputDispatcher 那边「指针已悬停」的状态是空的，按键会被判失败
            injectMode(
                mouseEvent(MotionEvent.ACTION_HOVER_ENTER, x, y, 0, 0L, now, hover = true),
                MODE_WAIT_FOR_RESULT
            )
            hoverActive = true
            injectMode(
                mouseEvent(MotionEvent.ACTION_HOVER_MOVE, x, y, 0, 0L, now, hover = true),
                MODE_WAIT_FOR_RESULT
            )
        } else {
            // 指针已经在悬停状态里了，这一发只为把落点对齐，不必再等回执（省 10ms 的点击延迟）
            injectAsync(
                mouseEvent(MotionEvent.ACTION_HOVER_MOVE, x, y, 0, 0L, now, hover = true)
            )
            SystemClock.sleep(3)
        }
    }

    /** 悬停移动（异步）：拖动时由 MouseEngine 按固定节拍调用，不阻塞、不丢帧 */
    @Synchronized
    fun mouseMove(x: Float, y: Float): Boolean {
        val now = SystemClock.uptimeMillis()
        hoverActive = true
        return injectAsync(
            mouseEvent(MotionEvent.ACTION_HOVER_MOVE, x, y, 0, 0L, now, hover = true)
        )
    }


    /** 同步悬停移动：松手定格用，等系统分发完再返回，保证最终位置一定落地 */
    @Synchronized
    fun mouseMoveSync(x: Float, y: Float): Boolean {
        val now = SystemClock.uptimeMillis()
        hoverActive = true
        return injectMode(
            mouseEvent(MotionEvent.ACTION_HOVER_MOVE, x, y, 0, 0L, now, hover = true),
            MODE_WAIT_FOR_RESULT
        )
    }

    /** 一次完整点击：悬停对齐 → 按下 → 90ms → 抬起（再补一发抬起兜底） */
    @Synchronized
    fun mouseClick(button: Int, x: Float, y: Float, touchFallback: Boolean = true): Boolean {
        val t0 = SystemClock.uptimeMillis()
        val name = when (button) {
            MotionEvent.BUTTON_PRIMARY -> "左键"
            MotionEvent.BUTTON_SECONDARY -> "右键"
            else -> "中键"
        }
        // 先把上一轮可能卡住的"按下"清掉：没有按下配对的抬起系统直接丢，不会有副作用
        runCatching {
            injectResult(mouseEvent(MotionEvent.ACTION_UP, x, y, 0,
                SystemClock.uptimeMillis(), SystemClock.uptimeMillis()))
        }
        hoverFirst(x, y)
        val holdMs = if (button == MotionEvent.BUTTON_PRIMARY) 60L else 120L
        val mouseOk = clickViaMouse(button, x, y, holdMs)  // 只看"按下"有没有被接受

        // 串流类应用（Moonlight / RustDesk / ToDesk 等）会把指针独占捕获（requestPointerCapture），
        // 有的还只收自己那套触摸手势，鼠标事件会被输入分发直接丢掉，注入返回 false ——
        // 就是我们看到的"注入失败"提示。这种情况改发一次真正的触摸点按：
        // 这类客户端本来就支持"点一下 = 左键单击"，右键同理，等于把键值送到了。
        if (!mouseOk && touchFallback) {
            val touchOk = clickViaTouch(x, y, holdMs)
            diag("$name 鼠标注入被拒,改用触摸${if (touchOk) "ok" else "也失败"}")
            return touchOk
        }
        diag("$name ${if (mouseOk) "ok" else "失败"} ${SystemClock.uptimeMillis() - t0}ms")
        return mouseOk
    }

    /** 绝对坐标鼠标点击（正常应用走这条） */
    private fun clickViaMouse(button: Int, x: Float, y: Float, holdMs: Long): Boolean {
        val downTime = SystemClock.uptimeMillis()
        val down = injectResult(
            mouseEvent(MotionEvent.ACTION_DOWN, x, y, button, downTime, downTime,
                actionButton = button)
        )
        var up = false
        try {
            // 左键 60ms（"点一下就是点一下"），右/中键 120ms（右键菜单需要按住一小会儿）
            SystemClock.sleep(holdMs)
            up = injectResult(
                mouseEvent(MotionEvent.ACTION_UP, x, y, 0, downTime, SystemClock.uptimeMillis(),
                    actionButton = button)
            )
            if (!up) {
                SystemClock.sleep(40)
                up = injectResult(
                    mouseEvent(MotionEvent.ACTION_UP, x, y, 0, downTime, SystemClock.uptimeMillis(),
                        actionButton = button)
                )
            }
        } catch (t: Throwable) {
            diag("抬起异常 ${t.javaClass.simpleName}")
        } finally {
            // 中途出什么事都要把抬起送出去：缺抬起 = 应用一直以为还按着
            runCatching {
                injectResult(mouseEvent(MotionEvent.ACTION_UP, x, y, 0, downTime,
                    SystemClock.uptimeMillis(), actionButton = button))
            }
        }
        return down
    }

    /** 触摸点按兜底：给只认触摸手势的串流客户端用 */
    private fun clickViaTouch(x: Float, y: Float, holdMs: Long): Boolean {
        val downTime = SystemClock.uptimeMillis()
        val down = injectResult(touchEvent(MotionEvent.ACTION_DOWN, x, y, downTime, downTime))
        var up = false
        try {
            SystemClock.sleep(holdMs)
            up = injectResult(
                touchEvent(MotionEvent.ACTION_UP, x, y, downTime, SystemClock.uptimeMillis())
            )
            if (!up) {
                SystemClock.sleep(40)
                up = injectResult(
                    touchEvent(MotionEvent.ACTION_UP, x, y, downTime, SystemClock.uptimeMillis())
                )
            }
        } finally {
            runCatching {
                injectResult(
                    touchEvent(MotionEvent.ACTION_UP, x, y, downTime, SystemClock.uptimeMillis())
                )
            }
        }
        return down && up
    }

    private fun touchEvent(
        action: Int,
        x: Float,
        y: Float,
        downTime: Long,
        eventTime: Long
    ): MotionEvent {
        val props = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 1f
            size = 1f
        }
        return MotionEvent.obtain(
            downTime, eventTime, action, 1,
            arrayOf(props), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0
        )
    }

    /** 当前按住的是哪个键（抬起时 getActionButton 要用） */
    private var heldButton = 0

    @Synchronized
    fun mouseButtonDown(button: Int, x: Float, y: Float): Boolean {
        hoverFirst(x, y)
        heldButton = button
        var ok = false
        var tries = 0
        while (tries < 2 && !ok) {
            tries++
            val now = SystemClock.uptimeMillis()
            mouseDownTime = now
            ok = injectResult(mouseEvent(MotionEvent.ACTION_DOWN, x, y, button, now, now,
                actionButton = button))
            if (!ok) {
                SystemClock.sleep(50)
                hoverFirst(x, y)
            }
        }
        diag(if (ok) "左键按住 ok" else "左键按住 失败(${tries}次)")
        return ok
    }

    @Synchronized
    fun mouseButtonUp(x: Float, y: Float): Boolean {
        var ok = false
        var tries = 0
        while (tries < 2 && !ok) {
            tries++
            val now = SystemClock.uptimeMillis()
            val down = if (mouseDownTime == 0L) now else mouseDownTime
            mouseDownTime = 0L
            ok = injectResult(mouseEvent(MotionEvent.ACTION_UP, x, y, 0, down, now,
                actionButton = heldButton))
            if (!ok) SystemClock.sleep(40)
        }
        diag(if (ok) "左键松开 ok" else "左键松开 失败(${tries}次)")
        heldButton = 0
        return ok
    }

    /** 真实鼠标滚轮：vertical > 0 向上，< 0 向下 */
    @Synchronized
    fun mouseWheel(x: Float, y: Float, vertical: Float): Boolean {
        hoverFirst(x, y)
        var ok = false
        var tries = 0
        while (tries < 2 && !ok) {
            tries++
            val now = SystemClock.uptimeMillis()
            ok = injectResult(mouseEvent(MotionEvent.ACTION_SCROLL, x, y, 0, now, now, vertical))
            if (!ok) {
                SystemClock.sleep(40)
                hoverFirst(x, y)
            }
        }
        diag(if (ok) "滚轮 ok" else "滚轮 失败(${tries}次)")
        return ok
    }

    /** 按住某个鼠标键状态下的移动（拖拽选择/拖动窗口），节拍线程用：等到分发再返回 */
    @Synchronized
    fun mouseDragSync(x: Float, y: Float, button: Int = MotionEvent.BUTTON_PRIMARY): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = if (mouseDownTime == 0L) now else mouseDownTime
        return injectResult(mouseEvent(MotionEvent.ACTION_MOVE, x, y, button, down, now))
    }

    /** 同上，不等待回执的版本（一次性同步位置时用） */
    @Synchronized
    fun mouseDrag(x: Float, y: Float, button: Int = MotionEvent.BUTTON_PRIMARY): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = if (mouseDownTime == 0L) now else mouseDownTime
        return injectAsync(mouseEvent(MotionEvent.ACTION_MOVE, x, y, button, down, now))
    }
}
