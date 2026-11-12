package com.example.shizukukeypad

import android.content.Context
import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
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

    private const val MODE_WAIT_FOR_FINISH = 1
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

    private fun inject(event: InputEvent): Boolean {
        if (!ensureReady()) return false
        return try {
            val accepted = injectMethod!!.invoke(inputManager, event, MODE_WAIT_FOR_FINISH) as? Boolean ?: false
            if (!accepted) setError(R.string.injector_rejected)
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

    private fun mouseEvent(
        action: Int,
        x: Float,
        y: Float,
        buttonState: Int,
        downTime: Long,
        eventTime: Long,
        vScroll: Float = 0f
    ): MotionEvent {
        val props = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 1f
            size = 1f
            if (vScroll != 0f) setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
        }
        return MotionEvent.obtain(
            downTime, eventTime, action, 1,
            arrayOf(props), arrayOf(coords),
            0, buttonState, 1f, 1f, 0, 0,
            InputDevice.SOURCE_MOUSE, 0
        )
    }

    /** 真实鼠标按键：button 取 MotionEvent.BUTTON_PRIMARY / SECONDARY / TERTIARY */
    @Synchronized
    fun mouseClick(button: Int, x: Float, y: Float): Boolean {
        val downTime = SystemClock.uptimeMillis()
        var ok = inject(mouseEvent(MotionEvent.ACTION_DOWN, x, y, button, downTime, downTime))
        SystemClock.sleep(30)
        ok = inject(
            mouseEvent(MotionEvent.ACTION_UP, x, y, 0, downTime, SystemClock.uptimeMillis())
        ) && ok
        return ok
    }

    /** 按住鼠标键（拖拽锁定用），配合 [mouseButtonUp] */
    @Synchronized
    fun mouseButtonDown(button: Int, x: Float, y: Float): Boolean {
        val now = SystemClock.uptimeMillis()
        mouseDownTime = now
        return inject(mouseEvent(MotionEvent.ACTION_DOWN, x, y, button, now, now))
    }

    @Synchronized
    fun mouseButtonUp(x: Float, y: Float): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = if (mouseDownTime == 0L) now else mouseDownTime
        mouseDownTime = 0L
        return inject(mouseEvent(MotionEvent.ACTION_UP, x, y, 0, down, now))
    }

    /** 真实鼠标滚轮：vertical > 0 向上，< 0 向下 */
    @Synchronized
    fun mouseWheel(x: Float, y: Float, vertical: Float): Boolean {
        val now = SystemClock.uptimeMillis()
        return inject(mouseEvent(MotionEvent.ACTION_SCROLL, x, y, 0, now, now, vertical))
    }

    /** 移动鼠标（悬停移动）：拖动鼠标板时调用，不按下任何键 */
    @Synchronized
    fun mouseMove(x: Float, y: Float): Boolean {
        val now = SystemClock.uptimeMillis()
        return inject(mouseEvent(MotionEvent.ACTION_HOVER_MOVE, x, y, 0, now, now))
    }

    /** 按住左键状态下的移动（拖拽选择/拖动窗口） */
    @Synchronized
    fun mouseDrag(x: Float, y: Float): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = if (mouseDownTime == 0L) now else mouseDownTime
        return inject(
            mouseEvent(
                MotionEvent.ACTION_MOVE, x, y, MotionEvent.BUTTON_PRIMARY, down, now
            )
        )
    }
}
