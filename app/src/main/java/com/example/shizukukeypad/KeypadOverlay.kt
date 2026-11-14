package com.example.shizukukeypad

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Region
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 悬浮按键层。
 *
 * 设计要点（这是上一版所有毛病的根源，重写时必须保持）：
 * 1. 每个按键是**独立小窗口**，位置互不影响；注入坐标 = 该窗口的真实屏幕坐标，
 *    不做 LayoutParams/旋转之类的手工换算。
 * 2. 注入触摸前，把会挡住目标的那个窗口临时设为 FLAG_NOT_TOUCHABLE，
 *    否则注入的点击会被自己的悬浮窗吃掉（表现就是"按键只闪一下、游戏没反应"）。
 * 3. 屏幕尺寸用 currentWindowMetrics 拿当前旋转方向下的真实像素，横竖屏都不会错。
 * 4. 位置存 0..1 比例，旋转、换设备自动跟随。
 */
class KeypadOverlay(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val ui = Handler(Looper.getMainLooper())
    // 多线程注入：多个按键同时按下时不会排队等待，触控延迟更低
    private val worker = Executors.newFixedThreadPool(4)
    // 鼠标悬停单独一条串行线程：旧机型单次注入要十几毫秒，
    // 丢掉中间帧比排队更稳，拖动才不会一顿一顿
    private val hoverWorker = Executors.newSingleThreadExecutor()
    @Volatile
    private var hoverBusy = false
    private val density = context.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val themeCtx = ContextThemeWrapper(context, android.R.style.Theme_Material_Light)

    private var screenW = 1080
    private var screenH = 1920

    /** WindowManager.LayoutParams.FLAG_SPLIT_TOUCH（部分 SDK 里是隐藏常量） */
    private val FLAG_SPLIT_TOUCH = 0x20000000

    private val keys = mutableListOf<KeyItem>()
    private val keyViews = LinkedHashMap<Long, View>()

    private var toolbar: LinearLayout? = null
    private var toolbarParams: WindowManager.LayoutParams? = null
    private var btnEdit: ToolButton? = null
    private var btnMouse: ToolButton? = null
    private var btnHot: ToolButton? = null

    /** 热区模式总开关：true 时所有按键窗口不可触摸，靠旁听触摸屏坐标触发 */
    private var hotMode = false
    private var btnGame: ToolButton? = null
    private var btnOneKey: ToolButton? = null
    private var btnMacro: ToolButton? = null
    private var btnAllKeys: ToolButton? = null

    /** 菜单绑定模式：0 正常，1 点按键设置宏 */
    private var bindMode = 0

    /** 收缩后的悬浮球 */
    private var ballView: View? = null
    private var ballParams: WindowManager.LayoutParams? = null

    /** 整块游戏键盘 */
    private var panelView: LinearLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var panelHandle: TextView? = null
    private val panelKeys = mutableListOf<Pair<TextView, Int>>()
    private val panelCounts = HashMap<Int, Int>()
    private val panelPointerCode = HashMap<Int, Int>()
    private val panelPointerView = HashMap<Int, TextView>()
    private var panelDragPointer = -1
    private var panelDownX = 0f
    private var panelDownY = 0f
    private var panelStartX = 0
    private var panelStartY = 0
    /** 拖动手指是否为主指针：主指针可以用 rawX/rawY，避免窗口移动导致坐标回灌、乱跳 */
    private var panelUseRaw = false
    /** 双指缩放整块键盘 */
    private var panelPinchActive = false
    private var panelPinchPending = false
    private var panelPinchDist = 0f
    private var panelPinchStartW = 0
    private var panelPinchStartX = 0
    private var panelPinchMidX = 0f
    private var panelPinchIdA = -1
    private var panelPinchIdB = -1
    private var gameKeyboardOn = false

    /** 整块键盘：0=字母层，1=符号/功能层 */
    private var panelSymbolMode = false
    private var panelCapsOn = false
    private var panelBody: LinearLayout? = null
    private var panelCapsView: TextView? = null
    private val panelShiftViews = mutableListOf<TextView>()

    /** 符号层里需要按 Shift 才成立的键（! @ # ... _ + { } 等） */
    private val panelShiftedCodes = mutableSetOf<Int>()
    private val panelUpperCodes = setOf(
        KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_3,
        KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_6,
        KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_9,
        KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_EQUALS,
        KeyEvent.KEYCODE_LEFT_BRACKET, KeyEvent.KEYCODE_RIGHT_BRACKET,
        KeyEvent.KEYCODE_BACKSLASH, KeyEvent.KEYCODE_SEMICOLON,
        KeyEvent.KEYCODE_APOSTROPHE, KeyEvent.KEYCODE_COMMA,
        KeyEvent.KEYCODE_PERIOD, KeyEvent.KEYCODE_SLASH
    )

    private companion object {
        const val PANEL_CODE_SYMBOLS = -1
        const val PANEL_CODE_ABC = -2
        const val PANEL_CODE_CAPS = -3
    }

    /** 一键开关总开关：关闭时隐藏所有勾选了「一键开关」的按键 */
    private var oneKeyOn = true

    /** 全关：临时隐藏屏幕上所有按键（不看「一键开关」勾选），再点一下恢复 */
    private var allKeysOff = false

    private var cursorView: View? = null
    private var cursorParams: WindowManager.LayoutParams? = null
    private var dragLayer: View? = null
    private var dragParams: WindowManager.LayoutParams? = null
    private var fullScreenDrag = false
    private var mouseOn = false

    /** 触控板拖动状态：触控板窗口或按键窗口转发过来的手指共用 */
    private var padDragActive = false
    private var padDragMoved = false
    private var padDragLastX = 0f
    private var padDragLastY = 0f
    private var padDragLastInject = 0L

    /** 从按键窗口转交给触控板的那根手指 */
    private var externalPadToken: String? = null
    private var keyAlpha = 1f

    /** 鼠标灵敏度倍率，来源是主页滑条（50%..250%） */
    private var mouseGain = 1.5f

    /** 拖拽锁定：左键是否处于按住状态 */
    private var mouseLeftHeld = false

    // 虚拟鼠标当前位置（屏幕像素），鼠标按键就注入在这里
    private var mouseX = 0f
    private var mouseY = 0f

    // 光标窗口的真实屏幕位置与 LayoutParams 的差值（状态栏/挖孔/导航栏内缩）
    private var cursorOffX = 0
    private var cursorOffY = 0

    private var dialog: AlertDialog? = null
    private var editMode = false

    // 触摸键多点触控：keyId -> 当前触点 pointerId，以及先抬起后按下时的补释放标记
    private val touchLock = Any()
    private val touchPointerIds = HashMap<Long, Int>()
    private val pendingTouchRelease = HashSet<Long>()

    // 虚拟摇杆运行状态
    private val joyLock = Any()
    private val joyStates = HashMap<Long, JoyState>()
    private val joyWorker = Executors.newSingleThreadExecutor()
    private var anchorWarned = false

    /** 锁定键：当前处于"保持按下"状态的按键 id */
    private val latchedKeys = HashSet<Long>()

    /** 编辑模式下用于拖动的输出点准星（仅触摸模式摇杆） */
    private val anchorViews = LinkedHashMap<Long, View>()

    val isShowing: Boolean get() = toolbar != null || ballView != null || keyViews.isNotEmpty()

    // ==================== 基础工具 ====================

    private fun dp(v: Int): Int = (v * density).roundToInt()

    private fun keySize(key: KeyItem): Int = dp(key.sizeDp.coerceIn(40, 120))

    private fun toast(msg: String) {
        ui.post { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun getString(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun updateScreen() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val b = wm.currentWindowMetrics.bounds
                if (b.width() > 0 && b.height() > 0) {
                    screenW = b.width()
                    screenH = b.height()
                    return
                }
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        }
        try {
            val p = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(p)
            if (p.x > 0 && p.y > 0) {
                screenW = p.x
                screenH = p.y
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    private fun windowParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                // 关键：允许多根手指分别落到各自的按键窗口。
                // 不加这个标志时，第一根手指按住某窗口后，后续手指会被系统
                // 全部算作该窗口的 ACTION_POINTER_DOWN，别的按键永远收不到按下。
                FLAG_SPLIT_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(color)
            if (strokeColor != 0) setStroke(dp(strokeDp), strokeColor)
        }

    /** 按键外观：深色半透明底 + 白色边框，按下不变色；形状可选圆角方 / 圆形 */
    private fun keyBackground(key: KeyItem): GradientDrawable =
        if (key.type == KeyType.ANCHOR) {
            // 指托画得更淡：它没有键值，只是个占位区域
            if (key.shape == 1) {
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x26FFFFFF)
                    setStroke(dp(1), 0x66FFFFFF)
                }
            } else {
                rounded(0x26FFFFFF, 16, 0x66FFFFFF, 1)
            }
        } else if (key.shape == 1) {
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xC7181818.toInt())
                setStroke(dp(1), 0x99FFFFFF.toInt())
            }
        } else {
            rounded(0xC7181818.toInt(), 16, 0x99FFFFFF.toInt(), 1)
        }

    /** 按下时的外观：略微变灰 */
    private fun keyBackgroundPressed(key: KeyItem): GradientDrawable =
        if (key.shape == 1) {
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xE6505050.toInt())
                setStroke(dp(1), 0xCCFFFFFF.toInt())
            }
        } else {
            rounded(0xE6505050.toInt(), 16, 0xCCFFFFFF.toInt(), 1)
        }

    private fun setKeyLook(key: KeyItem, pressed: Boolean) {
        val v = keyViews[key.id] ?: return
        if (v is JoystickPad) {
            v.setActive(pressed)
            return
        }
        (v as? TextView)?.background =
            if (pressed) keyBackgroundPressed(key) else keyBackground(key)
    }

    // ==================== 生命周期 ====================

    fun show() {
        if (isShowing) return
        updateScreen()
        // 鼠标位置先按上次保存的位置初始化，这样不开鼠标模式也能直接按鼠标键
        val saved = KeyStore.loadCursor(context)
        mouseX = (saved.x * screenW).coerceIn(0f, (screenW - 1).toFloat())
        mouseY = (saved.y * screenH).coerceIn(0f, (screenH - 1).toFloat())
        keyAlpha = KeyStore.loadAlpha(context) / 100f
        mouseGain = KeyStore.loadMouseGain(context) / 100f
        oneKeyOn = KeyStore.loadOneKeyOn(context)
        gameKeyboardOn = KeyStore.loadGameKeyboard(context)
        hotMode = KeyStore.loadHotMode(context)
        loadOrSeedKeys()
        buildKeyWindows()
        buildToolbar()
        if (gameKeyboardOn) buildGameKeyboard()
        if (KeyStore.loadBlockIme(context)) ImeGuard.enter(context)
        refreshEvdev()
    }

    /** 全局按键不透明度，30..100 */
    fun setKeyAlpha(percent: Int) {
        keyAlpha = percent.coerceIn(30, 100) / 100f
        keyViews.values.forEach { it.alpha = keyAlpha }
        anchorViews.values.forEach { it.alpha = keyAlpha }
        toolbar?.alpha = keyAlpha
        ballView?.alpha = keyAlpha
        panelView?.alpha = keyAlpha
    }

    /** 鼠标灵敏度，50..250（百分比） */
    fun setMouseGain(percent: Int) {
        mouseGain = percent.coerceIn(50, 250) / 100f
    }

    fun hide() {
        dialog?.dismiss()
        dialog = null
        releaseHotKeys()
        EvdevTouch.listener = null
        EvdevTouch.stop()
        clearTouchState()
        releasePanelKeys()
        worker.execute {
            ShizukuInjector.releaseAll()
            ShizukuInjector.releaseAllTouches()
        }
        removeAllWindows()
        bindMode = 0
        allKeysOff = false
        keys.clear()
        mouseOn = false
        editMode = false
        ImeGuard.exit(context)
    }

    private fun removeAllWindows() {
        removeAnchorMarkers()
        keyViews.values.forEach { runCatching { wm.removeViewImmediate(it) } }
        keyViews.clear()
        removeGameKeyboard()
        removeDragLayer()
        removeCursor()
        removeBall()
        toolbar?.let { runCatching { wm.removeViewImmediate(it) } }
        toolbar = null
        toolbarParams = null
        btnEdit = null
        btnMouse = null
        btnGame = null
        btnOneKey = null
    }

    /** 屏幕旋转 / 尺寸变化后重排所有窗口 */
    fun relayout() {
        updateScreen()
        keyViews.forEach { (id, v) ->
            val key = keys.firstOrNull { it.id == id } ?: return@forEach
            val p = v.layoutParams as? WindowManager.LayoutParams ?: return@forEach
            placeKey(key, p)
            runCatching { wm.updateViewLayout(v, p) }
        }
        toolbar?.let { bar ->
            toolbarParams?.let { p ->
                p.x = p.x.coerceIn(0, max(0, screenW - dp(60)))
                p.y = p.y.coerceIn(0, max(0, screenH - dp(40)))
                runCatching { wm.updateViewLayout(bar, p) }
            }
        }
        ballView?.let { v ->
            ballParams?.let { p ->
                p.x = p.x.coerceIn(0, max(0, screenW - dp(48)))
                p.y = p.y.coerceIn(0, max(0, screenH - dp(48)))
                runCatching { wm.updateViewLayout(v, p) }
            }
        }
        if (gameKeyboardOn) buildGameKeyboard()
        if (editMode) syncAnchorMarkers()
        if (mouseOn) {
            placeCursor()
            cursorView?.let { v ->
                cursorParams?.let { p -> runCatching { wm.updateViewLayout(v, p) } }
            }
            dragParams?.let { p ->
                if (fullScreenDrag) {
                    p.width = screenW
                    p.height = screenH
                    p.x = 0
                    p.y = 0
                } else {
                    p.x = p.x.coerceIn(0, max(0, screenW - dp(140)))
                    p.y = p.y.coerceIn(0, max(0, screenH - dp(120)))
                }
                dragLayer?.let { v -> runCatching { wm.updateViewLayout(v, p) } }
            }
        }
    }

    /** 恢复默认布局 */
    fun resetLayout() {
        keys.clear()
        KeyStore.clear(context)
        updateScreen()
        seedDefaults()
        KeyStore.saveKeys(context, keys)
        KeyStore.markSeeded(context)
        buildKeyWindows()
        syncAnchorMarkers()
        toast(getString(R.string.toast_default_restored))
    }

    // ==================== 数据 ====================

    private fun loadOrSeedKeys() {
        keys.clear()
        val stored = KeyStore.loadKeys(context)
        val invalid = stored.any { it.cx !in 0f..1f || it.cy !in 0f..1f }
        val duplicateId = stored.map { it.id }.toSet().size != stored.size
        if (stored.isEmpty() || invalid || duplicateId) {
            seedDefaults()
            KeyStore.saveKeys(context, keys)
            KeyStore.markSeeded(context)
        } else {
            keys.addAll(stored)
        }
        var seq = 0L
        keys.forEach { if (it.id <= 0L) it.id = ++seq }
    }

    private fun seedDefaults() {
        val s = dp(60).toFloat()
        val gap = dp(10).toFloat()
        val marginX = dp(16).toFloat()
        val marginY = dp(24).toFloat()
        val w = screenW.toFloat().coerceAtLeast(1f)
        val h = screenH.toFloat().coerceAtLeast(1f)

        fun at(col: Int, row: Int, fromRight: Boolean): Pair<Float, Float> {
            val cx = if (fromRight) w - marginX - s / 2f - col * (s + gap)
            else marginX + s / 2f + col * (s + gap)
            val cy = h - marginY - s / 2f - row * (s + gap)
            return (cx / w).coerceIn(0.02f, 0.98f) to (cy / h).coerceIn(0.02f, 0.98f)
        }

        var seq = 0L
        fun add(label: String, code: Int, pos: Pair<Float, Float>, type: KeyType = KeyType.KEY) {
            keys.add(KeyItem(++seq, pos.first, pos.second, label, code, type))
        }

        add(getString(R.string.key_back), android.view.KeyEvent.KEYCODE_BACK, at(0, 0, false))
        add(
            KeyCatalog.defaultLabel(context, 0, KeyType.MOUSE_LEFT), 0,
            at(0, 0, true), KeyType.MOUSE_LEFT
        )
        add(
            KeyCatalog.defaultLabel(context, 0, KeyType.MOUSE_RIGHT), 0,
            at(1, 0, true), KeyType.MOUSE_RIGHT
        )
    }

    // ==================== 工具条 ====================

    /**
     * 工具条按钮：上图标、下小字的两行结构，比纯文字更容易一眼认出来。
     * text 直接改下面那行，active 用来高亮当前开启的功能。
     */
    private inner class ToolButton(
        glyph: String,
        widthDp: Int,
        onClick: () -> Unit
    ) : LinearLayout(themeCtx) {

        private val glyphView = TextView(themeCtx)
        private val labelView = TextView(themeCtx)

        var text: CharSequence
            get() = labelView.text
            set(value) {
                labelView.text = value
            }

        var active: Boolean = false
            set(value) {
                field = value
                render()
            }

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            setPadding(dp(2), dp(4), dp(2), dp(3))
            layoutParams = LinearLayout.LayoutParams(dp(widthDp), dp(46)).apply { marginStart = dp(3) }
            glyphView.text = glyph
            glyphView.textSize = 15f
            glyphView.gravity = Gravity.CENTER
            labelView.textSize = 9.5f
            labelView.gravity = Gravity.CENTER
            labelView.maxLines = 1
            labelView.ellipsize = android.text.TextUtils.TruncateAt.END
            addView(glyphView)
            addView(labelView)
            setOnClickListener { onClick() }
            render()
        }

        private fun render() {
            background = rounded(if (active) 0x4D5B7CFF else 0x14FFFFFF, 12)
            glyphView.setTextColor(if (active) 0xFFC9D4FF.toInt() else 0xE8FFFFFF.toInt())
            labelView.setTextColor(if (active) 0xFFC9D4FF.toInt() else 0x9EFFFFFF.toInt())
        }
    }

    private fun barButton(glyph: String, text: String, widthDp: Int, onClick: () -> Unit): ToolButton =
        ToolButton(glyph, widthDp, onClick).apply { this.text = text }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildToolbar() {
        val bar = LinearLayout(themeCtx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xF2151A24.toInt(), 20, 0x26FFFFFF)
            setPadding(dp(7), dp(5), dp(7), dp(5))
            elevation = dp(8).toFloat()
        }
        val row1 = LinearLayout(themeCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val row2 = LinearLayout(themeCtx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
        bar.addView(row1)
        bar.addView(row2)

        val handle = TextView(themeCtx).apply {
            text = "≡"
            textSize = 19f
            setTextColor(0xB3FFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(2), 0, dp(6), 0)
        }
        row1.addView(handle)

        btnEdit = barButton("✎", getString(if (editMode) R.string.toolbar_done else R.string.toolbar_edit), 48) { toggleEdit() }.also { row1.addView(it) }
        row1.addView(barButton("＋", getString(R.string.toolbar_add), 40) { showKeyDialog(null) })
        btnMacro = barButton("⚡", getString(R.string.toolbar_macro), 46) { toggleBindMode(1) }.also { row1.addView(it) }
        btnAllKeys = barButton(
            "◉",
            getString(if (allKeysOff) R.string.toolbar_all_on else R.string.toolbar_all_off),
            48
        ) {
            setAllKeysOff(!allKeysOff)
            refreshBarLabels()
        }.also { row1.addView(it) }
        row1.addView(barButton("✕", getString(R.string.toolbar_close), 40) { OverlayService.send(context, OverlayService.ACTION_HIDE) })

        row2.addView(barButton("↓", getString(R.string.toolbar_save), 40) { promptSavePreset() })
        row2.addView(barButton("↑", getString(R.string.toolbar_load), 40) { promptLoadPreset() })
        btnGame = barButton("▤", getString(if (gameKeyboardOn) R.string.toolbar_full_on else R.string.toolbar_full), 46) {
            toggleGameKeyboard()
            refreshBarLabels()
        }.also { row2.addView(it) }
        btnOneKey = barButton("⊙", getString(if (oneKeyOn) R.string.toolbar_one_key_on else R.string.toolbar_one_key_off), 46) {
            setOneKeyOn(!oneKeyOn)
            refreshBarLabels()
        }.also { row2.addView(it) }
        btnMouse = barButton("↖", getString(if (mouseOn) R.string.toolbar_mouse_on else R.string.toolbar_mouse), 46) { toggleMousePad() }.also { row2.addView(it) }
        // 长按鼠标键切换拖动方式：全屏滑动（默认） / 小触控板
        btnMouse?.setOnLongClickListener {
            toggleMouseProfile()
            true
        }
        btnHot = barButton("◈", getString(if (hotMode) R.string.toolbar_hot_on else R.string.toolbar_hot_off), 54) {
            setHotMode(!hotMode)
        }.also { row2.addView(it) }

        val p = windowParams().apply {
            val saved = KeyStore.loadToolbar(context)
            x = if (saved != null) (saved.x * screenW).roundToInt() else dp(12)
            y = if (saved != null) (saved.y * screenH).roundToInt() else dp(12)
            x = x.coerceIn(0, max(0, screenW - dp(60)))
            y = y.coerceIn(0, max(0, screenH - dp(96)))
        }
        // 点三条杠收缩成悬浮球；拖动仍然只移动工具条；长按收起菜单
        setupDrag(handle, bar, p, onTap = { collapseToolbar() }, onLongPress = { hideToolbar() })
        bar.alpha = keyAlpha
        runCatching { wm.addView(bar, p) }
        toolbar = bar
        toolbarParams = p
    }

    private fun refreshBarLabels() {
        btnGame?.text = getString(if (gameKeyboardOn) R.string.toolbar_full_on else R.string.toolbar_full)
        btnOneKey?.text = getString(if (oneKeyOn) R.string.toolbar_one_key_on else R.string.toolbar_one_key_off)
        btnMouse?.text = getString(if (mouseOn) R.string.toolbar_mouse_on else R.string.toolbar_mouse)
        btnEdit?.text = getString(if (editMode) R.string.toolbar_done else R.string.toolbar_edit)
        btnMacro?.text = getString(if (bindMode == 1) R.string.toolbar_macro_active else R.string.toolbar_macro)
        btnAllKeys?.text = getString(if (allKeysOff) R.string.toolbar_all_on else R.string.toolbar_all_off)
        btnHot?.text = getString(if (hotMode) R.string.toolbar_hot_on else R.string.toolbar_hot_off)
        btnHot?.active = hotMode
        btnEdit?.active = editMode
        btnMacro?.active = bindMode == 1
        btnMouse?.active = mouseOn
        btnGame?.active = gameKeyboardOn
        btnAllKeys?.active = allKeysOff
        btnOneKey?.active = !oneKeyOn
    }

    /** 总开关：false = 每个按键一个独立窗口（原方案）；true = 热区模式（窗口不接收触摸） */
    fun setHotMode(on: Boolean) {
        if (hotMode == on) return
        hotMode = on
        KeyStore.saveHotMode(context, on)
        releaseHotKeys()
        // 切换时先把按下的状态清干净，避免卡键
        worker.execute { ShizukuInjector.releaseAll() }
        applyKeyTouchability()
        // 全屏鼠标：热区模式下撤掉全屏触摸层，改由旁听坐标驱动光标
        if (mouseOn && fullScreenDrag) {
            if (on) removeDragLayer() else buildFullScreenLayer()
        }
        refreshEvdev()
        refreshBarLabels()
        toast(getString(if (on) R.string.toast_hot_on else R.string.toast_hot_off))
    }

    /** 进入/退出按键绑定模式：菜单点一下，接下来点的那个自定义按键生效 */
    private fun toggleBindMode(mode: Int) {
        bindMode = if (bindMode == mode) 0 else mode
        refreshBarLabels()
        toast(getString(if (bindMode == 0) R.string.toast_bind_exited else R.string.toast_bind_prompt))
    }

    /** 绑定模式下点到了某个按键 */
    private fun handleBindKey(key: KeyItem) {
        bindMode = 0
        refreshBarLabels()
        promptMacroDialog(key)
    }

    /** 悬浮条上的「存」：把当前布局存成命名预设 */
    private fun promptSavePreset() {
        val et = EditText(themeCtx).apply {
            hint = getString(R.string.preset_name_hint)
            setSingleLine()
            setText(getString(R.string.preset_default_name, KeyStore.presetNames(context).size + 1))
        }
        val box = FrameLayout(themeCtx).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(et)
        }
        showDialog(
            AlertDialog.Builder(themeCtx)
                .setTitle(R.string.dialog_save_current_keys)
                .setView(box)
                .setPositiveButton(R.string.action_save) { _, _ ->
                    val name = et.text.toString().trim()
                    if (name.isEmpty()) {
                        toast(getString(R.string.toast_name_empty))
                        return@setPositiveButton
                    }
                    KeyStore.savePreset(context, name, keys)
                    toast(getString(R.string.toast_preset_saved, name))
                }
                .setNegativeButton(R.string.action_cancel, null)
        )
    }

    /** 悬浮条上的「读」：选择预设并立即套用 */
    private fun promptLoadPreset() {
        val names = KeyStore.presetNames(context)
        if (names.isEmpty()) {
            toast(getString(R.string.toast_no_presets))
            return
        }
        showDialog(
            AlertDialog.Builder(themeCtx)
                .setTitle(R.string.dialog_load_preset)
                .setItems(names.toTypedArray()) { _, which -> applyPreset(names[which]) }
                .setNeutralButton(R.string.action_delete) { _, _ -> promptDeletePreset(names) }
                .setNegativeButton(R.string.action_cancel, null)
        )
    }

    private fun promptDeletePreset(names: List<String>) {
        showDialog(
            AlertDialog.Builder(themeCtx)
                .setTitle(R.string.dialog_delete_preset)
                .setItems(names.toTypedArray()) { _, which ->
                    KeyStore.deletePreset(context, names[which])
                    toast(getString(R.string.toast_preset_deleted, names[which]))
                }
                .setNegativeButton(R.string.action_cancel, null)
        )
    }

    private fun applyPreset(name: String) {
        val loaded = KeyStore.loadPreset(context, name)
        if (loaded.isEmpty()) {
            toast(getString(R.string.toast_preset_empty))
            return
        }
        KeyStore.saveKeys(context, loaded)
        reloadLayout()
        toast(getString(R.string.toast_preset_loaded, name))
    }

    private fun promptMacroDialog(key: KeyItem) {
        val et = EditText(themeCtx).apply {
            hint = getString(R.string.macro_hint)
            setText(key.macro)
            setHintTextColor(0x888888)
        }
        val box = FrameLayout(themeCtx).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(et)
        }
        showDialog(
            AlertDialog.Builder(themeCtx)
                .setTitle(getString(R.string.dialog_macro_title, key.label))
                .setView(box)
                .setMessage(R.string.dialog_macro_message)
                .setPositiveButton(R.string.action_save) { _, _ ->
                    val text = et.text.toString().trim()
                    if (text.isNotEmpty() && MacroParser.parse(text).isEmpty()) {
                        toast(getString(R.string.toast_macro_invalid))
                        return@setPositiveButton
                    }
                    key.macro = text
                    KeyStore.saveKeys(context, keys)
                    toast(getString(if (text.isEmpty()) R.string.toast_macro_disabled else R.string.toast_macro_saved))
                }
                .setNegativeButton(R.string.action_cancel, null)
        )
        bindMode = 0
        refreshBarLabels()
    }

    /** 收起工具条，只留一个可点的悬浮球 */
    private fun collapseToolbar() {
        val bar = toolbar ?: return
        val p = toolbarParams ?: return
        KeyStore.saveToolbar(context, p.x.toFloat() / screenW, p.y.toFloat() / screenH)
        val bx = p.x.coerceIn(0, max(0, screenW - dp(48)))
        val by = p.y.coerceIn(0, max(0, screenH - dp(48)))
        runCatching { wm.removeViewImmediate(bar) }
        toolbar = null
        toolbarParams = null
        btnEdit = null
        btnMouse = null
        btnGame = null
        btnOneKey = null
        buildBall(bx, by)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildBall(x: Int, y: Int) {
        removeBall()
        val size = dp(46)
        val ball = TextView(themeCtx).apply {
            text = "≡"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = rounded(0xE61B1B1B.toInt(), 23, 0x66FFFFFF)
            elevation = dp(6).toFloat()
            isClickable = true
        }
        val p = windowParams().apply {
            width = size
            height = size
            this.x = x
            this.y = y
        }
        var longFired = false
        var moved = false
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        val longPress = Runnable {
            longFired = true
            // 只收起菜单（按键保留），重新打开应用会自动恢复
            hideToolbar()
        }
        ball.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    longFired = false
                    moved = false
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    startX = p.x
                    startY = p.y
                    v.postDelayed(longPress, 550)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!moved && hypot(
                            (ev.rawX - downRawX).toDouble(),
                            (ev.rawY - downRawY).toDouble()
                        ) > touchSlop
                    ) {
                        moved = true
                        v.removeCallbacks(longPress)
                    }
                    if (moved) {
                        p.x = (startX + (ev.rawX - downRawX)).roundToInt()
                            .coerceIn(0, max(0, screenW - size))
                        p.y = (startY + (ev.rawY - downRawY)).roundToInt()
                            .coerceIn(0, max(0, screenH - size))
                        runCatching { wm.updateViewLayout(v, p) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPress)
                    if (moved) {
                        KeyStore.saveToolbar(context, p.x.toFloat() / screenW, p.y.toFloat() / screenH)
                    } else if (!longFired) {
                        expandToolbar()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(longPress)
                    if (moved) {
                        KeyStore.saveToolbar(context, p.x.toFloat() / screenW, p.y.toFloat() / screenH)
                    }
                    true
                }
                else -> true
            }
        }
        runCatching { wm.addView(ball, p) }
        ballView = ball
        ballParams = p
    }

    private fun removeBall() {
        ballView?.let { runCatching { wm.removeViewImmediate(it) } }
        ballView = null
        ballParams = null
    }

    private fun expandToolbar() {
        removeBall()
        if (toolbar == null) buildToolbar()
    }

    /** 长按菜单/悬浮球可收起控制菜单，重新打开应用后再出现 */
    fun hideToolbar() {
        if (toolbar == null && ballView == null) return
        toolbar?.let { runCatching { wm.removeViewImmediate(it) } }
        toolbar = null
        toolbarParams = null
        btnEdit = null
        btnMouse = null
        btnGame = null
        btnOneKey = null
        removeBall()
        toast(getString(R.string.toast_toolbar_hidden))
    }

    fun showToolbar() {
        if (ballView != null) {
            expandToolbar()
            return
        }
        if (toolbar != null) return
        buildToolbar()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag(
        handle: View,
        target: View,
        p: WindowManager.LayoutParams,
        onTap: (() -> Unit)? = null,
        onLongPress: (() -> Unit)? = null
    ) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        val longPress = Runnable { if (!moved) onLongPress?.invoke() }
        handle.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startX = p.x
                    startY = p.y
                    moved = false
                    if (onLongPress != null) handle.postDelayed(longPress, 550)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!moved && hypot((ev.rawX - downX).toDouble(), (ev.rawY - downY).toDouble()) >
                        touchSlop
                    ) {
                        moved = true
                        handle.removeCallbacks(longPress)
                    }
                    p.x = (startX + (ev.rawX - downX)).roundToInt()
                        .coerceIn(0, max(0, screenW - dp(60)))
                    p.y = (startY + (ev.rawY - downY)).roundToInt()
                        .coerceIn(0, max(0, screenH - dp(40)))
                    runCatching { wm.updateViewLayout(target, p) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handle.removeCallbacks(longPress)
                    KeyStore.saveToolbar(context, p.x.toFloat() / screenW, p.y.toFloat() / screenH)
                    if (!moved && onTap != null) onTap.invoke()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handle.removeCallbacks(longPress)
                    true
                }
                else -> false
            }
        }
    }

    private fun bringToolbarToFront() {
        val bar = toolbar ?: return
        val p = toolbarParams ?: return
        runCatching {
            wm.removeViewImmediate(bar)
            wm.addView(bar, p)
        }
    }

    // ==================== 按键窗口 ====================

    private fun buildKeyWindows() {
        keyViews.values.forEach { runCatching { wm.removeViewImmediate(it) } }
        keyViews.clear()
        keys.forEach { if (isKeyVisible(it)) addKeyWindow(it) }
    }

    private fun placeKey(key: KeyItem, p: WindowManager.LayoutParams) {
        val s = keySize(key)
        p.width = s
        p.height = s
        p.x = (key.cx * screenW - s / 2f).roundToInt().coerceIn(0, max(0, screenW - s))
        p.y = (key.cy * screenH - s / 2f).roundToInt().coerceIn(0, max(0, screenH - s))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addKeyWindow(key: KeyItem) {
        val p = windowParams().apply { placeKey(key, this) }
        val v: View = if (key.type == KeyType.JOYSTICK) {
            JoystickPad(themeCtx, keySize(key), density).apply {
                alpha = keyAlpha
                elevation = dp(4).toFloat()
                setOnTouchListener(KeyTouch(key, this, p))
            }
        } else {
            TextView(themeCtx).apply {
                // 默认名字（空格/回车…）跟随系统语言；用户自己改过的文字原样显示
                text = if (KeyCatalog.isDefaultLabel(themeCtx, key.keyCode, key.label, key.type)) {
                    KeyCatalog.defaultLabel(themeCtx, key.keyCode, key.type)
                } else {
                    key.label
                }
                setTextColor(Color.WHITE)
                textSize = if (key.type.isMouse) 11f else 15f
                gravity = Gravity.CENTER
                background = keyBackground(key)
                isClickable = true
                elevation = dp(4).toFloat()
                alpha = keyAlpha
                setOnTouchListener(KeyTouch(key, this, p))
            }
        }
        runCatching { wm.addView(v, p) }
        keyViews[key.id] = v
        if (allKeysOff) v.visibility = View.GONE
        applyKeyTouchability()
    }

    /**
     * 触摸键在正常模式下是"穿透区"：窗口不可触摸，手指直接落到下面的游戏，
     * 由系统提供原生多点触控，没有注入、没有等待、不会被打断。
     * 热区模式只让键盘按键穿透（靠旁听坐标触发）；
     * 摇杆、鼠标按键仍保持可触摸，它们本来就要占住手指或接住按下。
     * 编辑模式必须可触摸，否则没法拖动/删除。
     */
    private fun applyKeyTouchability() {
        keyViews.forEach { (id, v) ->
            val key = keys.firstOrNull { it.id == id } ?: return@forEach
            val p = v.layoutParams as? WindowManager.LayoutParams ?: return@forEach
            val hotKey = hotMode && (key.type == KeyType.KEY || key.type.isMouse)
            val passthrough = !editMode && (key.type == KeyType.TOUCH || hotKey)
            val flags = if (passthrough) {
                p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            } else {
                p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            }
            if (p.flags != flags) {
                p.flags = flags
                runCatching { wm.updateViewLayout(v, p) }
            }
        }
    }

    /**
     * 触摸键按下：注入一个持续存在的触点，抬起时才释放。
     * 这样同时按两个触摸键，游戏收到的是真正的双指操作，而不是两次点按。
     */
    private fun touchKeyDown(
        key: KeyItem,
        view: View,
        params: WindowManager.LayoutParams,
        x: Float,
        y: Float
    ) {
        synchronized(touchLock) { pendingTouchRelease.remove(key.id) }
        val touchable = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0
        if (touchable) {
            // 先把按键自身的窗口摘掉，否则注入的触点会被自己的悬浮窗吃掉
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            runCatching { wm.updateViewLayout(view, params) }
        }
        worker.execute {
            SystemClock.sleep(40)
            val id = ShizukuInjector.touchDown(x, y)
            val releaseNow = synchronized(touchLock) {
                touchPointerIds[key.id] = id
                val pending = pendingTouchRelease.remove(key.id)
                if (pending) touchPointerIds.remove(key.id)
                pending
            }
            if (releaseNow) ShizukuInjector.touchUp(id)
            if (touchable) ui.postDelayed({
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                runCatching { wm.updateViewLayout(view, params) }
            }, 15)
        }
    }

    private fun touchKeyUp(key: KeyItem) {
        worker.execute {
            val id = synchronized(touchLock) {
                val v = touchPointerIds.remove(key.id)
                // 按下注入还没完成就抬手了：记下标记，注入完成后立刻释放
                if (v == null) pendingTouchRelease.add(key.id)
                v
            }
            id?.let { ShizukuInjector.touchUp(it) }
        }
    }

    // ==================== 虚拟摇杆 ====================

    /** 一根手指按住摇杆期间的状态 */
    private class JoyState {
        /** 触摸模式的输出点（屏幕像素） */
        var anchorX = 0f
        var anchorY = 0f
        /** 按下点的屏幕坐标，触摸模式用它计算位移，避免手指没按在圆心时跳一下 */
        var downX = 0f
        var downY = 0f
        /** 触摸模式注入的触点 id，还没注入完时为 -1 */
        var touchId = -1
        /** 键值模式当前按住的方向键 */
        val codes = HashSet<Int>()
        /** 触摸模式移动合并：只注入最新一帧 */
        var moving = false
        var pendingX = Float.NaN
        var pendingY = Float.NaN
    }

    /** 摇杆输出点（屏幕像素）；还没设置时默认放在摇杆右侧，避免和自身窗口重叠 */
    private fun joyAnchor(key: KeyItem): PointF {
        val cx = if (key.anchorCx in 0f..1f) key.anchorCx
        else ((key.cx * screenW + keySize(key) / 2f + dp(26)) / screenW).coerceIn(0.03f, 0.97f)
        val cy = if (key.anchorCy in 0f..1f) key.anchorCy else key.cy
        return PointF(cx * screenW, cy * screenH)
    }

    private fun joyStart(key: KeyItem, screenX: Float, screenY: Float) {
        val anchor = joyAnchor(key)
        val st = JoyState()
        st.anchorX = anchor.x
        st.anchorY = anchor.y
        val v = keyViews[key.id]
        if (!screenX.isNaN() && !screenY.isNaN()) {
            st.downX = screenX
            st.downY = screenY
        } else if (v != null) {
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            st.downX = loc[0] + v.width / 2f
            st.downY = loc[1] + v.height / 2f
        } else {
            st.downX = anchor.x
            st.downY = anchor.y
        }
        synchronized(joyLock) { joyStates[key.id] = st }
        (v as? JoystickPad)?.resetKnob()
        if (key.stickMode != 1) return

        // 输出点落在某个悬浮按键上时，注入的触点会被自己挡住
        if (!anchorWarned && pointOnAnyKey(anchor.x, anchor.y)) {
            anchorWarned = true
            toast(getString(R.string.toast_anchor_warning))
        }
        joyWorker.execute {
            val id = ShizukuInjector.touchDown(st.anchorX, st.anchorY)
            synchronized(st) {
                st.touchId = id
                // 注入期间手指可能早就抬起
            }
        }
    }

    private fun joyMove(key: KeyItem, screenX: Float, screenY: Float) {
        val st = synchronized(joyLock) { joyStates[key.id] } ?: return
        val v = keyViews[key.id] ?: return
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val cx = loc[0] + v.width / 2f
        val cy = loc[1] + v.height / 2f
        val travel = (v.width / 2f - dp(6)).coerceAtLeast(dp(12).toFloat())

        var dx = screenX - cx
        var dy = screenY - cy
        val len = hypot(dx, dy)
        if (len > travel) {
            dx = dx / len * travel
            dy = dy / len * travel
        }
        (v as? JoystickPad)?.setVector(dx, dy)

        if (key.stickMode == 0) {
            joyApplyCodes(st, joyDirection(key, dx, dy, travel))
            return
        }

        // 触摸模式：输出点跟着手指位移走，行程同样限制在摇杆半径内
        var mx = screenX - st.downX
        var my = screenY - st.downY
        val mlen = hypot(mx, my)
        if (mlen > travel) {
            mx = mx / mlen * travel
            my = my / mlen * travel
        }
        queueJoyTouchMove(st, st.anchorX + mx, st.anchorY + my)
    }

    private fun joyDirection(key: KeyItem, dx: Float, dy: Float, travel: Float): Set<Int> {
        // 中心死区：轻微抖动不触发方向
        if (hypot(dx, dy) < travel * 0.22f) return emptySet()
        val angle = atan2(-dy, dx)
        val sector = ((Math.round(angle / (Math.PI / 4)).toInt() % 8) + 8) % 8
        val out = HashSet<Int>()
        fun add(code: Int) {
            if (code > 0) out.add(code)
        }
        when (sector) {
            0 -> add(key.rightCode)
            1 -> {
                add(key.rightCode); add(key.upCode)
            }
            2 -> add(key.upCode)
            3 -> {
                add(key.leftCode); add(key.upCode)
            }
            4 -> add(key.leftCode)
            5 -> {
                add(key.leftCode); add(key.downCode)
            }
            6 -> add(key.downCode)
            7 -> {
                add(key.rightCode); add(key.downCode)
            }
        }
        return out
    }

    /** 方向变化时只补发增删，避免每次移动都松开重按 */
    private fun joyApplyCodes(st: JoyState, want: Set<Int>) {
        val release = mutableListOf<Int>()
        val press = mutableListOf<Int>()
        synchronized(st) {
            st.codes.forEach { if (it !in want) release.add(it) }
            want.forEach { if (it !in st.codes) press.add(it) }
            if (release.isEmpty() && press.isEmpty()) return
            st.codes.clear()
            st.codes.addAll(want)
        }
        joyWorker.execute {
            release.forEach { ShizukuInjector.keyUp(it) }
            press.forEach { ShizukuInjector.keyDown(it) }
        }
    }

    /**
     * 触摸模式移动合帧：js 里注入一次要几毫秒，
     * 如果每个 MOVE 都排队，摇杆会越拖越滞后，所以只保留最新位置。
     */
    private fun queueJoyTouchMove(st: JoyState, x: Float, y: Float) {
        synchronized(st) {
            st.pendingX = x
            st.pendingY = y
            if (st.moving) return
            st.moving = true
        }
        joyWorker.execute {
            while (true) {
                val tx: Float
                val ty: Float
                val id: Int
                synchronized(st) {
                    if (st.pendingX.isNaN()) {
                        st.moving = false
                        return@execute
                    }
                    tx = st.pendingX
                    ty = st.pendingY
                    id = st.touchId
                    st.pendingX = Float.NaN
                    st.pendingY = Float.NaN
                }
                if (id >= 0) ShizukuInjector.touchMove(id, tx, ty)
            }
        }
    }

    private fun joyEnd(key: KeyItem) {
        val st = synchronized(joyLock) { joyStates.remove(key.id) } ?: return
        (keyViews[key.id] as? JoystickPad)?.resetKnob()
        if (key.stickMode == 0) {
            val codes = synchronized(st) {
                val list = st.codes.toList()
                st.codes.clear()
                list
            }
            if (codes.isNotEmpty()) {
                joyWorker.execute { codes.forEach { ShizukuInjector.keyUp(it) } }
            }
            return
        }
        joyWorker.execute {
            val id = synchronized(st) {
                val v = st.touchId
                st.touchId = -1
                v
            }
            if (id >= 0) ShizukuInjector.touchUp(id)
        }
    }

    /** 退出、切层、隐藏时兜底释放所有摇杆 */
    private fun releaseAllJoysticks() {
        val snapshot = synchronized(joyLock) { joyStates.entries.map { it.key to it.value } }
        synchronized(joyLock) { joyStates.clear() }
        if (snapshot.isEmpty()) return
        keyViews.values.forEach { (it as? JoystickPad)?.resetKnob() }
        joyWorker.execute {
            snapshot.forEach { (_, st) ->
                synchronized(st) {
                    st.codes.forEach { ShizukuInjector.keyUp(it) }
                    st.codes.clear()
                    val id = st.touchId
                    st.touchId = -1
                    if (id >= 0) ShizukuInjector.touchUp(id)
                }
            }
        }
    }

    /** 屏幕坐标是否落在某个按键窗口上 */
    private fun pointOnAnyKey(x: Float, y: Float): Boolean {
        val loc = IntArray(2)
        for ((id, v) in keyViews) {
            val key = keys.firstOrNull { it.id == id } ?: continue
            if (key.type == KeyType.TOUCH) continue
            v.getLocationOnScreen(loc)
            if (x >= loc[0] && x <= loc[0] + v.width && y >= loc[1] && y <= loc[1] + v.height) {
                return true
            }
        }
        return false
    }

    /**
     * 编辑模式下给触摸模式的摇杆显示输出点准星。
     * 准星可以拖动，拖到游戏自己的摇杆中心即可；拨出编辑模式自动移除。
     */
    private fun syncAnchorMarkers() {
        val wanted = if (editMode) {
            keys.filter { it.type == KeyType.JOYSTICK && it.stickMode == 1 }
        } else {
            emptyList()
        }
        val ids = wanted.map { it.id }.toSet()
        anchorViews.keys.toList().forEach { id ->
            if (id !in ids) {
                anchorViews.remove(id)?.let { runCatching { wm.removeViewImmediate(it) } }
            }
        }
        wanted.forEach { key ->
            val v = anchorViews[key.id]
            if (v == null) addAnchorMarker(key) else placeAnchorMarker(key, v)
        }
        if (allKeysOff) anchorViews.values.forEach { it.visibility = View.GONE }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addAnchorMarker(key: KeyItem) {
        val size = dp(40)
        val marker = TextView(themeCtx).apply {
            text = "✛"
            setTextColor(0xFFFFD54F.toInt())
            textSize = 17f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x33000000)
                setStroke(dp(2), 0xFFFFD54F.toInt())
            }
            alpha = keyAlpha
            elevation = dp(6).toFloat()
        }
        val a = joyAnchor(key)
        val p = windowParams().apply {
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            width = size
            height = size
            x = (a.x - size / 2f).roundToInt()
            y = (a.y - size / 2f).roundToInt()
        }
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        marker.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    startX = p.x
                    startY = p.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    p.x = (startX + ev.rawX - downRawX).roundToInt()
                        .coerceIn(-size / 2, max(0, screenW - size / 2))
                    p.y = (startY + ev.rawY - downRawY).roundToInt()
                        .coerceIn(-size / 2, max(0, screenH - size / 2))
                    key.anchorCx = ((p.x + size / 2f) / screenW).coerceIn(0f, 1f)
                    key.anchorCy = ((p.y + size / 2f) / screenH).coerceIn(0f, 1f)
                    runCatching { wm.updateViewLayout(v, p) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    KeyStore.saveKeys(context, keys)
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
        runCatching { wm.addView(marker, p) }
        anchorViews[key.id] = marker
    }

    private fun placeAnchorMarker(key: KeyItem, v: View) {
        val p = v.layoutParams as? WindowManager.LayoutParams ?: return
        val a = joyAnchor(key)
        p.x = (a.x - p.width / 2f).roundToInt()
        p.y = (a.y - p.height / 2f).roundToInt()
        runCatching { wm.updateViewLayout(v, p) }
    }

    private fun removeAnchorMarkers() {
        anchorViews.values.forEach { runCatching { wm.removeViewImmediate(it) } }
        anchorViews.clear()
    }

    // ==================== 多指路由 ====================
    // 系统在很多机型上会把所有手指都塞给第一根手指所在的窗口，
    // 第二个窗口永远收不到按下。这里不依赖窗口分派，自己按屏幕坐标分发。
    private val pointerTargets = HashMap<String, KeyItem>()
    private val pressCounts = HashMap<Long, Int>()

    // 空余区域的手指转发：部分机型会把点在所有按键之外的手指也塞给按键窗口，
    // 这些手指按坐标重新注入给下面的游戏，保证按住按键时屏幕其它区域照常可用
    private val forwardTokens = HashSet<String>()   // 主线程
    private val forwardIds = HashMap<String, Int>() // 注入线程
    private val forwardReleasedEarly = HashSet<String>()
    private val forwardWorker = Executors.newSingleThreadExecutor()

    private fun forwardDown(token: String, x: Float, y: Float) {
        forwardWorker.execute {
            val pid = ShizukuInjector.touchDown(x, y)
            val early = synchronized(forwardIds) {
                if (forwardReleasedEarly.remove(token)) {
                    true
                } else {
                    forwardIds[token] = pid
                    false
                }
            }
            if (early) ShizukuInjector.touchUp(pid)
        }
    }

    private fun forwardMove(token: String, x: Float, y: Float) {
        forwardWorker.execute {
            val pid = synchronized(forwardIds) { forwardIds[token] } ?: return@execute
            ShizukuInjector.touchMove(pid, x, y)
        }
    }

    private fun forwardUp(token: String) {
        forwardWorker.execute {
            val pid = synchronized(forwardIds) {
                val v = forwardIds.remove(token)
                if (v == null) forwardReleasedEarly.add(token)
                v
            }
            pid?.let { ShizukuInjector.touchUp(it) }
        }
    }

    private fun pointerToken(v: View, pointerId: Int): String =
        System.identityHashCode(v).toString() + ":" + pointerId

    private fun keyAtScreen(exclude: View, x: Float, y: Float): KeyItem? {
        val loc = IntArray(2)
        for ((id, v) in keyViews) {
            if (v === exclude) continue
            v.getLocationOnScreen(loc)
            if (x >= loc[0] && x <= loc[0] + v.width && y >= loc[1] && y <= loc[1] + v.height) {
                return keys.firstOrNull { it.id == id }
            }
        }
        return null
    }

    /** 屏幕坐标是否落在触控板上（全屏拖动模式不算） */
    private fun pointInTouchpad(x: Float, y: Float): Boolean {
        if (!mouseOn || fullScreenDrag) return false
        val v = dragLayer ?: return false
        val l = IntArray(2)
        v.getLocationOnScreen(l)
        return x >= l[0] && x <= l[0] + v.width && y >= l[1] && y <= l[1] + v.height
    }

    private fun pressKey(
        key: KeyItem,
        screenX: Float = Float.NaN,
        screenY: Float = Float.NaN
    ) {
        // 指托不发送任何按键，只负责占住手势
        if (key.type == KeyType.ANCHOR) return
        val n = pressCounts[key.id] ?: 0
        pressCounts[key.id] = n + 1
        if (n > 0) return
        setKeyLook(key, true)
        when {
            key.macro.isNotBlank() ->
                // 宏是一次性动作，按下时执行，松开不需要再发键
                worker.execute { runMacro(key.macro) }

            key.type == KeyType.KEY -> {
                worker.execute { ShizukuInjector.keyDown(key.keyCode) }
            }

            key.type == KeyType.JOYSTICK -> joyStart(key, screenX, screenY)

            key.type == KeyType.TOUCH -> {
                val v = keyViews[key.id] ?: return
                val p = v.layoutParams as? WindowManager.LayoutParams ?: return
                val loc = IntArray(2)
                v.getLocationOnScreen(loc)
                touchKeyDown(key, v, p, loc[0] + v.width / 2f, loc[1] + v.height / 2f)
            }

            else -> injectMouse { doMouseKey(key.type) }
        }
    }

    /** 宏：按步骤顺序执行，步骤内同时按下再反序松开 */
    private fun runMacro(text: String) {
        val steps = MacroParser.parse(text)
        steps.forEachIndexed { i, codes ->
            codes.forEach { ShizukuInjector.keyDown(it) }
            SystemClock.sleep(35)
            codes.asReversed().forEach { ShizukuInjector.keyUp(it) }
            if (i < steps.size - 1) SystemClock.sleep(45)
        }
    }

    private fun releaseKey(key: KeyItem) {
        if (key.type == KeyType.ANCHOR) return
        val n = pressCounts[key.id] ?: 0
        if (n <= 0) return
        if (n > 1) {
            pressCounts[key.id] = n - 1
            return
        }
        pressCounts.remove(key.id)
        setKeyLook(key, false)
        when {
            key.macro.isNotBlank() -> Unit
            key.type == KeyType.KEY -> worker.execute { ShizukuInjector.keyUp(key.keyCode) }
            key.type == KeyType.JOYSTICK -> joyEnd(key)
            key.type == KeyType.TOUCH -> touchKeyUp(key)
            else -> Unit
        }
    }

    private fun releaseViewPointers(v: View) {
        val prefix = System.identityHashCode(v).toString() + ":"
        val it = pointerTargets.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key.startsWith(prefix)) {
                releaseKey(e.value)
                it.remove()
            }
        }
    }

    // ==================== 一键开关（总开关） ====================

    /** 勾选了「一键开关」的按键，在总开关关闭时整体隐藏 */
    private fun isKeyVisible(key: KeyItem): Boolean = !(key.switchable && !oneKeyOn)

    /**
     * 锁定键只对普通键值键生效：
     * 触摸键常态是不可触摸的穿透区，摇杆要跟随手指，鼠标键是单击动作，宏是一次性动作。
     */
    private fun isLatchKey(key: KeyItem): Boolean =
        key.latch && key.type == KeyType.KEY && key.macro.isBlank()

    /** 点一下保持按住，再点一下松开 */
    private fun toggleLatch(key: KeyItem) {
        if (latchedKeys.remove(key.id)) {
            // 从"锁定"回到抬起：pressKey 时记过一次计数，这里正常释放
            releaseKey(key)
        } else {
            latchedKeys.add(key.id)
            pressKey(key)
        }
    }

    /** 兜底：隐藏 / 切层 / 关闭键盘时把锁定中的键全部松开，避免游戏里卡键 */
    private fun releaseLatchedKeys() {
        if (latchedKeys.isEmpty()) return
        val ids = latchedKeys.toList()
        latchedKeys.clear()
        ids.forEach { id ->
            keys.firstOrNull { it.id == id }?.let { releaseKey(it) }
        }
    }

    private fun setOneKeyOn(on: Boolean) {
        if (oneKeyOn == on) return
        oneKeyOn = on
        KeyStore.saveOneKeyOn(context, on)
        // 关闭前先松开这些按键，避免隐藏后键盘/触摸卡在按下状态
        if (!on) {
            keys.filter { it.switchable }.forEach { key ->
                keyViews[key.id]?.let { releaseViewPointers(it) }
                repeat(pressCounts[key.id] ?: 0) { releaseKey(key) }
            }
        }
        applyOneKeyVisibility()
        bringToolbarToFront()
        toast(
            if (on) getString(R.string.toast_one_key_on)
            else getString(R.string.toast_one_key_off)
        )
    }

    /** 按总开关状态增删勾选了「一键开关」的按键窗口 */
    private fun applyOneKeyVisibility() {
        keys.forEach { key ->
            val v = keyViews[key.id]
            if (!isKeyVisible(key)) {
                if (v != null) {
                    keyViews.remove(key.id)
                    runCatching { wm.removeViewImmediate(v) }
                }
            } else if (v == null) {
                addKeyWindow(key)
            }
            if (!isKeyVisible(key)) latchedKeys.remove(key.id)
        }
        if (editMode) syncAnchorMarkers()
    }

    /**
     * 全关：不看「一键开关」勾选，直接把屏幕上所有按键（含摇杆、输出点准星）藏起来。
     * 窗口不销毁，只置 GONE，恢复时立刻还原，不影响按次、位置和注入状态。
     */
    private fun setAllKeysOff(off: Boolean) {
        if (allKeysOff == off) return
        allKeysOff = off
        if (off) {
            // 关闭前松开所有正在按的键，避免隐藏后游戏里卡键
            hotPointers.clear()
            releaseLatchedKeys()
            releaseAllJoysticks()
            keys.forEach { key ->
                keyViews[key.id]?.let { releaseViewPointers(it) }
                repeat(pressCounts[key.id] ?: 0) { releaseKey(key) }
            }
        }
        applyAllKeysVisibility()
        bringToolbarToFront()
        toast(
            if (off) getString(R.string.toast_all_keys_off)
            else getString(R.string.toast_all_keys_on)
        )
    }

    private fun applyAllKeysVisibility() {
        val vis = if (allKeysOff) View.GONE else View.VISIBLE
        keyViews.values.forEach { it.visibility = vis }
        anchorViews.values.forEach { it.visibility = vis }
    }

    // ==================== 热区模式 ====================
    //
    // 工具栏总开关切换两种方案：
    //   关 = 每个按键一个独立窗口（系统按坐标分发，第一指必须在按键上）
    //   开 = 按键窗口一律不可触摸，靠 Shizuku 旁听触摸屏原始坐标触发按键
    // 热区模式下按键不抢触摸，滑视角和按按键互不干扰。

    // keyId -> 正在按住它的手指 id
    private val hotPointers = HashMap<Long, Int>()

    // 热区模式下的全屏滑动：每根手指的上一帧坐标，用来移动鼠标光标
    private val hotCursorLast = HashMap<Int, PointF>()
    // 每根手指累计移动距离：超过触摸阈值才算拖动，否则抬起时当作左键单击
    private val hotCursorTravel = HashMap<Int, Float>()
    private val hotCursorMoved = HashSet<Int>()
    private var hotCursorInjectAt = 0L

    private fun refreshEvdev() {
        if (!hotMode || (keys.isEmpty() && !mouseOn)) {
            EvdevTouch.listener = null
            EvdevTouch.stop()
            return
        }
        updateScreen()
        EvdevTouch.screenW = screenW
        EvdevTouch.screenH = screenH
        @Suppress("DEPRECATION")
        val r = runCatching { wm.defaultDisplay.rotation }.getOrDefault(Surface.ROTATION_0)
        EvdevTouch.rotation = r
        EvdevTouch.status = null
        EvdevTouch.listener = EvdevTouch.Listener { action, id, x, y -> onEvdev(action, id, x, y) }
        EvdevTouch.start()
    }

    private fun onEvdev(action: Int, id: Int, x: Float, y: Float) {
        ui.post { handleHotTouch(action, id, x, y) }
    }

    private fun handleHotTouch(action: Int, id: Int, x: Float, y: Float) {
        if (editMode || allKeysOff) return
        // 全屏鼠标在热区里靠旁听坐标驱动：多指都能拖，单指轻点即左键
        val mouseDrag = mouseOn && fullScreenDrag
        if (action == EvdevTouch.UP) {
            hotCursorLast.remove(id)?.let {
                val moved = hotCursorMoved.remove(id)
                hotCursorTravel.remove(id)
                if (mouseDrag && !moved) {
                    // 轻点空白处 = 左键单击（点击落在当前光标位置）
                    worker.execute { injectMouse { doMouseKey(KeyType.MOUSE_LEFT) } }
                } else if (mouseDrag) {
                    injectHoverAtCursor()
                }
            }
            hotCursorTravel.remove(id)
            val iter = hotPointers.entries.iterator()
            while (iter.hasNext()) {
                val e = iter.next()
                if (e.value == id) {
                    iter.remove()
                    keys.firstOrNull { k -> k.id == e.key }?.let { k -> releaseKey(k) }
                }
            }
            return
        }
        if (action == EvdevTouch.MOVE) {
            val last = hotCursorLast[id] ?: return
            val dx = x - last.x
            val dy = y - last.y
            last.x = x
            last.y = y
            // 累计位移过阈值才算拖动：慢速滑动也不会被误判成点击
            val travel = (hotCursorTravel[id] ?: 0f) + hypot(dx, dy)
            hotCursorTravel[id] = travel
            if (travel > touchSlop) hotCursorMoved.add(id)
            if (id !in hotCursorMoved) return
            mouseX = (mouseX + dx * mouseGain).coerceIn(0f, (screenW - 1).toFloat())
            mouseY = (mouseY + dy * mouseGain).coerceIn(0f, (screenH - 1).toFloat())
            moveCursorWindowToMouse()
            val now = SystemClock.uptimeMillis()
            if (now - hotCursorInjectAt >= 16L) {
                hotCursorInjectAt = now
                injectHoverAtCursor()
            }
            return
        }
        if (action != EvdevTouch.DOWN) return
        val loc = IntArray(2)
        var hitKey = false
        keys.forEach { k ->
            if ((k.type != KeyType.KEY && !k.type.isMouse) || !isKeyVisible(k)) return@forEach
            if (hotPointers.containsKey(k.id)) return@forEach
            val v = keyViews[k.id] ?: return@forEach
            v.getLocationOnScreen(loc)
            if (x >= loc[0] && x <= loc[0] + v.width && y >= loc[1] && y <= loc[1] + v.height) {
                hotPointers[k.id] = id
                hitKey = true
                if (k.type.isMouse) {
                    // 鼠标按键在热区里只做一次触发，按下后自动释放
                    val t = k.type
                    pressCounts[k.id] = (pressCounts[k.id] ?: 0) + 1
                    setKeyLook(k, true)
                    worker.execute { injectMouse { doMouseKey(t) } }
                    ui.postDelayed({ releaseKey(k) }, 90)
                } else {
                    pressKey(k, x, y)
                }
            }
        }
        // 没按到按键的手指都用来拖光标；轻点则当作左键
        if (!hitKey && mouseDrag) {
            hotCursorLast[id] = PointF(x, y)
            hotCursorTravel[id] = 0f
            hotCursorMoved.remove(id)
        }
    }

    private fun releaseHotKeys() {
        val ids = hotPointers.keys.toList()
        hotPointers.clear()
        ids.forEach { kid -> keys.firstOrNull { it.id == kid }?.let { k -> releaseKey(k) } }
    }

    private inner class KeyTouch(
        private val key: KeyItem,
        private val view: View,
        private val params: WindowManager.LayoutParams
    ) : View.OnTouchListener {

        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onTouch(v: View, ev: MotionEvent): Boolean =
            if (editMode) onEdit(ev) else onUse(ev)

        private fun onUse(ev: MotionEvent): Boolean {
            val loc = IntArray(2)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    view.getLocationOnScreen(loc)
                    val idx = ev.actionIndex
                    val sx = ev.getX(idx) + loc[0]
                    val sy = ev.getY(idx) + loc[1]
                    val token = pointerToken(view, ev.getPointerId(idx))
                    // 第一根手指必然落在本窗口
                    if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                        if (bindMode != 0) {
                            handleBindKey(key)
                            return true
                        }
                        pointerTargets[token] = key
                        // 锁定键在抬手时切换"保持/松开"，按下时只记下这根手指
                        if (!isLatchKey(key)) pressKey(key, sx, sy)
                        return true
                    }
                    // 后续手指如果也被系统塞进这个窗口，就按屏幕坐标自己判断归属
                    val selfHit = sx >= loc[0] && sx <= loc[0] + view.width &&
                        sy >= loc[1] && sy <= loc[1] + view.height
                    val target = keyAtScreen(view, sx, sy) ?: if (selfHit) key else null
                    if (target == null) {
                        // 落在触控板上：这根手指转交给触控板拖动鼠标
                        if (pointInTouchpad(sx, sy)) {
                            if (beginPadDrag(sx, sy)) externalPadToken = token
                        } else {
                            // 落在所有按键之外的空余区域：这根手指转发给下面的游戏，
                            // 否则按住某个键时就没法去点/拖屏幕其它地方
                            forwardTokens.add(token)
                            forwardDown(token, sx, sy)
                        }
                    } else {
                        pointerTargets[token] = target
                        if (!isLatchKey(target)) pressKey(target, sx, sy)
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val ext = externalPadToken
                    val hasJoy = pointerTargets.values.any { it.type == KeyType.JOYSTICK }
                    if (forwardTokens.isEmpty() && ext == null && !hasJoy) return false
                    view.getLocationOnScreen(loc)
                    for (i in 0 until ev.pointerCount) {
                        val id = ev.getPointerId(i)
                        val token = pointerToken(view, id)
                        val sx = ev.getX(i) + loc[0]
                        val sy = ev.getY(i) + loc[1]
                        if (token in forwardTokens) {
                            forwardMove(token, sx, sy)
                        } else if (token == ext) {
                            movePadDrag(sx, sy)
                        } else {
                            val k = pointerTargets[token]
                            if (k != null && k.type == KeyType.JOYSTICK) joyMove(k, sx, sy)
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    val idx = if (ev.actionMasked == MotionEvent.ACTION_UP) 0 else ev.actionIndex
                    val token = pointerToken(view, ev.getPointerId(idx))
                    if (forwardTokens.remove(token)) {
                        forwardUp(token)
                    } else if (token == externalPadToken) {
                        externalPadToken = null
                        endPadDrag()
                    } else {
                        val target = pointerTargets.remove(token) ?: key
                        if (isLatchKey(target)) toggleLatch(target) else releaseKey(target)
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    externalPadToken?.let {
                        externalPadToken = null
                        cancelPadDrag()
                    }
                    releaseViewPointers(view)
                    val prefix = System.identityHashCode(view).toString() + ":"
                    val it = forwardTokens.iterator()
                    while (it.hasNext()) {
                        val t = it.next()
                        if (t.startsWith(prefix)) {
                            forwardUp(t)
                            it.remove()
                        }
                    }
                    return true
                }
            }
            return false
        }

        private fun onEdit(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (!dragging && hypot(dx.toDouble(), dy.toDouble()) > touchSlop) dragging = true
                    if (dragging) {
                        val s = keySize(key)
                        params.x = (startX + dx).roundToInt().coerceIn(0, max(0, screenW - s))
                        params.y = (startY + dy).roundToInt().coerceIn(0, max(0, screenH - s))
                        key.cx = ((params.x + s / 2f) / screenW).coerceIn(0.01f, 0.99f)
                        key.cy = ((params.y + s / 2f) / screenH).coerceIn(0.01f, 0.99f)
                        runCatching { wm.updateViewLayout(view, params) }
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        KeyStore.saveKeys(context, keys)
                    } else {
                        showKeyMenu(key)
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> return true
            }
            return false
        }
    }

    // ==================== 触摸键（多点触控） ====================

    private fun clearTouchState() {
        releaseAllJoysticks()
        releaseLatchedKeys()
        synchronized(touchLock) {
            touchPointerIds.clear()
            pendingTouchRelease.clear()
        }
        pointerTargets.clear()
        pressCounts.clear()
        forwardTokens.clear()
        externalPadToken = null
        padDragActive = false
        synchronized(forwardIds) {
            forwardIds.clear()
            forwardReleasedEarly.clear()
        }
    }

    // ==================== 触摸穿透 ====================

    /**
     * 关键步骤：注入前把掩挡窗口临时设为不可触摸，注入完成后再恢复。
     * 不这么做的话，注入的触摸会被自己的悬浮窗接住，游戏永远收不到。
     */
    private fun injectThrough(
        view: View,
        params: WindowManager.LayoutParams,
        x: Float,
        y: Float,
        block: (Float, Float) -> Boolean
    ) {
        val touchable = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0
        if (!touchable) {
            worker.execute { block(x, y) }
            return
        }
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { wm.updateViewLayout(view, params) }
        worker.execute {
            SystemClock.sleep(40)
            val ok = runCatching { block(x, y) }.getOrDefault(false)
            val err = ShizukuInjector.lastErrorText(context)
            ui.postDelayed({
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                runCatching { wm.updateViewLayout(view, params) }
                if (!ok) toast(getString(R.string.toast_inject_failed, err.ifBlank { getString(R.string.unknown_reason) }))
            }, 15)
        }
    }

    /**
     * 鼠标按键不依赖某个悬浮窗的位置，所以注入前把所有悬浮窗一起设为不可触摸，
     * 避免注入的鼠标事件被自己的按钮接走。
     */
    private fun withOverlayUntouchable(block: () -> Boolean) {
        val all = mutableListOf<Pair<View, WindowManager.LayoutParams>>()
        fun add(v: View?, p: WindowManager.LayoutParams?) {
            if (v != null && p != null) all.add(v to p)
        }
        keyViews.forEach { (_, v) -> add(v, v.layoutParams as? WindowManager.LayoutParams) }
        add(toolbar, toolbarParams)
        add(cursorView, cursorParams)
        add(dragLayer, dragParams)

        val restore = mutableListOf<Pair<View, WindowManager.LayoutParams>>()
        all.forEach { (v, p) ->
            if (p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0) {
                p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                runCatching { wm.updateViewLayout(v, p) }
                restore.add(v to p)
            }
        }
        worker.execute {
            SystemClock.sleep(40)
            val ok = runCatching { block() }.getOrDefault(false)
            val err = ShizukuInjector.lastErrorText(context)
            ui.postDelayed({
                restore.forEach { (v, p) ->
                    p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    runCatching { wm.updateViewLayout(v, p) }
                }
                if (!ok) toast(getString(R.string.toast_mouse_inject_failed, err.ifBlank { getString(R.string.unknown_reason) }))
            }, 15)
        }
    }

    /**
     * 全屏模式专用：注入前把整块拖动层缩到屏幕外 1x1，注入完再恢复。
     *
     * 早先是把窗口 remove 再 add，那会让整个窗口栈重新排布，
     * 游戏和按键都会明显闪一下（用户说的“点一下控件就刷新”）。
     * 只改尺寸不会重新挂窗口，视觉上完全无感，同时屏幕上也不再有点被这层盖住。
     */
    private fun injectInFullScreen(block: () -> Boolean) {
        val v = dragLayer
        val p = dragParams
        if (v != null && p != null) {
            p.width = 1
            p.height = 1
            p.x = -2
            p.y = -2
            runCatching { wm.updateViewLayout(v, p) }
        }
        withOverlayUntouchable {
            // 系统刚分发完抬起手势，失败一次就稍等重试
            block() || run {
                SystemClock.sleep(70)
                block()
            }
        }
        ui.postDelayed({
            if (mouseOn && fullScreenDrag && dragLayer === v && v != null && p != null) {
                p.width = screenW
                p.height = screenH
                p.x = 0
                p.y = 0
                runCatching { wm.updateViewLayout(v, p) }
            }
        }, 120)
    }

    /** 全屏模式下先摘层再注入，其余情况沿用临时不可触摸 */
    private fun injectMouse(block: () -> Boolean) {
        if (fullScreenDrag && mouseOn) injectInFullScreen(block) else withOverlayUntouchable(block)
    }

    /** 鼠标按键注入在光标当前位置；鼠标模式只是提供触控板，不打开也能用按键 */
    private fun doMouseKey(type: KeyType): Boolean {
        val x = mouseX
        val y = mouseY
        return when (type) {
            KeyType.MOUSE_LEFT -> ShizukuInjector.mouseClick(MotionEvent.BUTTON_PRIMARY, x, y)
            KeyType.MOUSE_RIGHT -> ShizukuInjector.mouseClick(MotionEvent.BUTTON_SECONDARY, x, y)
            KeyType.MOUSE_MIDDLE -> ShizukuInjector.mouseClick(MotionEvent.BUTTON_TERTIARY, x, y)
            KeyType.WHEEL_UP -> ShizukuInjector.mouseWheel(x, y, -1f)
            KeyType.WHEEL_DOWN -> ShizukuInjector.mouseWheel(x, y, 1f)
            else -> false
        }
    }

    // ==================== 重载配置 ====================

    /** 应用内加载预设 / 导入配置后，让悬浮层重新读取按键 */
    fun reloadLayout() {
        bindMode = 0
        refreshBarLabels()
        clearTouchState()
        worker.execute {
            ShizukuInjector.releaseAll()
            ShizukuInjector.releaseAllTouches()
        }
        keys.clear()
        keys.addAll(KeyStore.loadKeys(context))
        buildKeyWindows()
        raiseOverlayAboveLayer()
    }

    // ==================== 编辑 / 对话框 ====================

    private fun toggleEdit() {
        editMode = !editMode
        if (editMode) releaseHotKeys()
        refreshBarLabels()
        anchorWarned = false
        applyKeyTouchability()
        syncAnchorMarkers()
        refreshEvdev()
        if (!editMode) {
            KeyStore.saveKeys(context, keys)
            toast(getString(R.string.toast_layout_saved))
        } else {
            clearTouchState()
            worker.execute {
                ShizukuInjector.releaseAll()
                ShizukuInjector.releaseAllTouches()
            }
            toast(getString(R.string.toast_edit_mode))
        }
    }

    private fun showKeyMenu(key: KeyItem) {
        showDialog(
            AlertDialog.Builder(themeCtx)
                .setTitle(getString(R.string.key_menu_title, key.label))
                .setItems(
                    arrayOf(
                        getString(R.string.key_menu_edit),
                        getString(R.string.key_menu_delete)
                    )
                ) { _, which ->
                    if (which == 0) showKeyDialog(key) else confirmDelete(key)
                }
        )
    }

    private fun confirmDelete(key: KeyItem) {
        showDialog(
            AlertDialog.Builder(themeCtx)
                .setTitle(R.string.dialog_delete_key)
                .setMessage(getString(R.string.dialog_delete_key_message, key.label))
                .setPositiveButton(R.string.action_delete_confirm) { _, _ ->
                    if (latchedKeys.remove(key.id)) releaseKey(key)
                    keys.removeAll { it.id == key.id }
                    keyViews.remove(key.id)?.let { runCatching { wm.removeViewImmediate(it) } }
                    KeyStore.saveKeys(context, keys)
                    syncAnchorMarkers()
                }
                .setNegativeButton(R.string.action_cancel, null)
        )
    }

    private fun showDialog(builder: AlertDialog.Builder) {
        dialog?.dismiss()
        val d = builder.create()
        d.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        // 横屏高度有限，允许内容滚动，输入法弹出时也压缩窗口而不是把底部字段挤出屏幕
        d.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        d.setOnDismissListener { if (dialog === d) dialog = null }
        dialog = d
        d.show()
    }

    private fun showKeyDialog(existing: KeyItem?) {
        val view = LayoutInflater.from(themeCtx).inflate(R.layout.dialog_key, null)
        val spinner = view.findViewById<Spinner>(R.id.spinnerKey)
        val etLabel = view.findViewById<EditText>(R.id.etLabel)
        val rgType = view.findViewById<RadioGroup>(R.id.rgType)
        val llCustomCode = view.findViewById<LinearLayout>(R.id.llCustomCode)
        val etCode = view.findViewById<EditText>(R.id.etCode)
        val etSize = view.findViewById<EditText>(R.id.etSize)
        val spinnerShape = view.findViewById<Spinner>(R.id.spinnerShape)
        val cbOneKey = view.findViewById<Switch>(R.id.cbOneKey)
        val cbLatch = view.findViewById<Switch>(R.id.cbLatch)
        val llJoystick = view.findViewById<LinearLayout>(R.id.llJoystick)
        val spinnerStickMode = view.findViewById<Spinner>(R.id.spinnerStickMode)
        val etStickUp = view.findViewById<EditText>(R.id.etStickUp)
        val etStickDown = view.findViewById<EditText>(R.id.etStickDown)
        val etStickLeft = view.findViewById<EditText>(R.id.etStickLeft)
        val etStickRight = view.findViewById<EditText>(R.id.etStickRight)
        val btnAnchorCenter = view.findViewById<Button>(R.id.btnAnchorCenter)
        val tvInjectTitle = view.findViewById<TextView>(R.id.tvInjectTitle)
        val tvInjectHint = view.findViewById<TextView>(R.id.tvInjectHint)

        cbOneKey.isChecked = existing?.switchable ?: false
        cbLatch.isChecked = existing?.latch ?: false

        spinnerStickMode.adapter = ArrayAdapter(
            themeCtx,
            android.R.layout.simple_spinner_dropdown_item,
            listOf(
                getString(R.string.dialog_stick_mode_option_key),
                getString(R.string.dialog_stick_mode_option_touch)
            )
        )
        spinnerStickMode.setSelection((existing?.stickMode ?: 0).coerceIn(0, 1))
        etStickUp.setText((existing?.upCode ?: KeyEvent.KEYCODE_W).toString())
        etStickDown.setText((existing?.downCode ?: KeyEvent.KEYCODE_S).toString())
        etStickLeft.setText((existing?.leftCode ?: KeyEvent.KEYCODE_A).toString())
        etStickRight.setText((existing?.rightCode ?: KeyEvent.KEYCODE_D).toString())
        var anchorReset = false
        btnAnchorCenter.setOnClickListener {
            anchorReset = true
            toast(getString(R.string.toast_anchor_reset))
        }

        spinnerShape.adapter = ArrayAdapter(
            themeCtx,
            android.R.layout.simple_spinner_dropdown_item,
            listOf(getString(R.string.shape_rounded), getString(R.string.shape_circle))
        )
        spinnerShape.setSelection((existing?.shape ?: 0).coerceIn(0, 1))
        etSize.setText((existing?.sizeDp ?: 60).toString())

        // 常用尺寸：不想敲数字时直接点；当前值不在档位里也会显示并选中
        val sizePresets = listOf(40, 50, 60, 70, 80, 90, 100, 120)
        view.findViewById<Button>(R.id.btnSizePreset).setOnClickListener {
            val cur = etSize.text.toString().trim().toIntOrNull()?.coerceIn(40, 120) ?: 60
            val items = if (cur in sizePresets) sizePresets else (sizePresets + cur).sorted()
            val pick = AlertDialog.Builder(themeCtx)
                .setTitle(R.string.dialog_key_size_pick_title)
                .setSingleChoiceItems(items.map { "$it dp" }.toTypedArray(), items.indexOf(cur)) { d, which ->
                    etSize.setText(items[which].toString())
                    d.dismiss()
                }
                .create()
            // 悬浮层里弹窗必须显式指定 overlay 类型，否则 BadTokenException
            pick.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            pick.show()
        }

        spinner.adapter = ArrayAdapter(
            themeCtx,
            android.R.layout.simple_spinner_dropdown_item,
            KeyCatalog.all.map { KeyCatalog.displayLabel(themeCtx, it) }
        )

        val customIndex = KeyCatalog.customIndex
        val startIndex = existing?.let { k ->
            val found = when {
                k.type.isMouse -> KeyCatalog.all.indexOfFirst { it.type == k.type }
                k.type == KeyType.JOYSTICK ->
                    KeyCatalog.all.indexOfFirst { it.type == KeyType.JOYSTICK }
                k.type == KeyType.ANCHOR ->
                    KeyCatalog.all.indexOfFirst { it.type == KeyType.ANCHOR }
                else -> KeyCatalog.all.indexOfFirst { it.keyCode == k.keyCode }
            }
            if (found >= 0) found else customIndex
        } ?: 0
        spinner.setSelection(startIndex)
        etLabel.setText(existing?.label ?: KeyCatalog.displayLabel(themeCtx, KeyCatalog.all[startIndex]))
        var autoLabel = if (startIndex == customIndex && existing != null) existing.label
        else KeyCatalog.displayLabel(themeCtx, KeyCatalog.all[startIndex])
        if (startIndex == customIndex && existing != null) {
            etCode.setText(existing.keyCode.takeIf { it > 0 }?.toString() ?: "")
        }

        fun syncTypeVisibility(position: Int) {
            // 鼠标动作没有"键盘/触摸"之分，直接隐藏选项
            val optionType = KeyCatalog.all[position].type
            val keyMode = optionType == KeyType.KEY
            rgType.visibility = if (keyMode) View.VISIBLE else View.GONE
            tvInjectTitle.visibility = if (keyMode) View.VISIBLE else View.GONE
            tvInjectHint.visibility = if (keyMode) View.VISIBLE else View.GONE
            llJoystick.visibility = if (optionType == KeyType.JOYSTICK) View.VISIBLE else View.GONE
            llCustomCode.visibility = if (position == customIndex) View.VISIBLE else View.GONE
        }
        syncTypeVisibility(startIndex)

        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                child: View?,
                position: Int,
                id: Long
            ) {
                val label = KeyCatalog.displayLabel(themeCtx, KeyCatalog.all[position])
                // 用户没手动改过文字时跟随键位，避免所有按键都叫同一个名字
                if (etLabel.text.toString() == autoLabel) {
                    etLabel.setText(label)
                    autoLabel = label
                }
                syncTypeVisibility(position)
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        if (existing?.type == KeyType.TOUCH) rgType.check(R.id.rbTouch) else rgType.check(R.id.rbKey)

        view.findViewById<TextView>(R.id.tvDialogTitle).text =
            getString(if (existing == null) R.string.dialog_add_key else R.string.dialog_edit_key)

        showDialog(
            AlertDialog.Builder(themeCtx)
                .setView(view)
                .setPositiveButton(if (existing == null) R.string.action_add else R.string.action_save) { _, _ ->
                    val position = spinner.selectedItemPosition.coerceIn(0, KeyCatalog.all.lastIndex)
                    val option = KeyCatalog.all[position]
                    val label = etLabel.text.toString().ifBlank { KeyCatalog.displayLabel(themeCtx, option) }
                    val type = when {
                        option.type != KeyType.KEY -> option.type
                        rgType.checkedRadioButtonId == R.id.rbTouch -> KeyType.TOUCH
                        else -> KeyType.KEY
                    }
                    val keyCode = if (position == customIndex) {
                        val code = etCode.text.toString().trim().toIntOrNull()
                        if (code == null || code <= 0 || code > 400) {
                            toast(getString(R.string.toast_invalid_keycode))
                            return@setPositiveButton
                        }
                        code
                    } else {
                        option.keyCode
                    }
                    val size = etSize.text.toString().trim().toIntOrNull()?.coerceIn(40, 120) ?: 60
                    val shape = spinnerShape.selectedItemPosition.coerceIn(0, 1)
                    val switchable = cbOneKey.isChecked
                    val latchFlag = cbLatch.isChecked
                    val isStick = type == KeyType.JOYSTICK
                    fun dirCode(et: EditText, def: Int): Int =
                        et.text.toString().trim().toIntOrNull()?.takeIf { it in 1..400 } ?: def
                    val upCode = dirCode(etStickUp, KeyEvent.KEYCODE_W)
                    val downCode = dirCode(etStickDown, KeyEvent.KEYCODE_S)
                    val leftCode = dirCode(etStickLeft, KeyEvent.KEYCODE_A)
                    val rightCode = dirCode(etStickRight, KeyEvent.KEYCODE_D)
                    val stickMode = spinnerStickMode.selectedItemPosition.coerceIn(0, 1)
                    if (existing == null) {
                        val offset = 0.035f * (keys.size % 5)
                        val item = KeyItem(
                            id = System.nanoTime(),
                            cx = (0.42f + offset).coerceIn(0.05f, 0.95f),
                            cy = (0.40f + offset).coerceIn(0.05f, 0.95f),
                            label = label,
                            keyCode = keyCode,
                            type = type,
                            shape = shape,
                            sizeDp = size,
                            switchable = switchable,
                            latch = latchFlag,
                            stickMode = stickMode,
                            upCode = upCode,
                            downCode = downCode,
                            leftCode = leftCode,
                            rightCode = rightCode
                        )
                        if (isStick && anchorReset) {
                            item.anchorCx = -1f
                            item.anchorCy = -1f
                        }
                        keys.add(item)
                        KeyStore.saveKeys(context, keys)
                        if (isKeyVisible(item)) {
                            addKeyWindow(item)
                        } else {
                            toast(getString(R.string.toast_hidden_by_one_key, label))
                        }
                        bringToolbarToFront()
                        if (!editMode) toggleEdit()
                        syncAnchorMarkers()
                        applyKeyTouchability()
                        refreshEvdev()
                    } else {
                        val oldIsStick = keyViews[existing.id] is JoystickPad
                        val oldSize = existing.sizeDp
                        // 改配置前先解掉锁定，避免旧窗口/旧键值残留按下状态
                        if (latchedKeys.remove(existing.id)) releaseKey(existing)
                        existing.label = label
                        existing.keyCode = keyCode
                        existing.type = type
                        existing.shape = shape
                        existing.sizeDp = size
                        existing.switchable = switchable
                        existing.latch = latchFlag
                        existing.stickMode = stickMode
                        existing.upCode = upCode
                        existing.downCode = downCode
                        existing.leftCode = leftCode
                        existing.rightCode = rightCode
                        if (isStick && anchorReset) {
                            existing.anchorCx = -1f
                            existing.anchorCy = -1f
                        }
                        if (!isKeyVisible(existing)) {
                            keyViews.remove(existing.id)?.let { v ->
                                releaseViewPointers(v)
                                repeat(pressCounts[existing.id] ?: 0) { releaseKey(existing) }
                                runCatching { wm.removeViewImmediate(v) }
                            }
                        } else {
                            val v = keyViews[existing.id]
                            // 摇杆和普通按键是两种 View，切换类型或改尺寸时要重建窗口
                            if (v != null && (oldIsStick != isStick || (isStick && oldSize != size))) {
                                keyViews.remove(existing.id)
                                runCatching { wm.removeViewImmediate(v) }
                                addKeyWindow(existing)
                            } else if (v != null) {
                                (v as? TextView)?.let {
                                    it.text = label
                                    it.background = keyBackground(existing)
                                }
                                (v.layoutParams as? WindowManager.LayoutParams)?.let { p ->
                                    placeKey(existing, p)
                                    runCatching { wm.updateViewLayout(v, p) }
                                }
                            } else {
                                addKeyWindow(existing)
                            }
                        }
                        KeyStore.saveKeys(context, keys)
                        syncAnchorMarkers()
                        applyKeyTouchability()
                        refreshEvdev()
                    }
                }
                .setNegativeButton(R.string.action_cancel, null)
        )
    }

    // ==================== 整块游戏键盘 ====================

    /**
     * 一整块 QWERTY 键盘：所有键在同一个窗口里，自己按 pointerId 分发，
     * 所以多指同时按多个键不会互相抢。顶部的三条杠用来整体拖动。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun buildGameKeyboard() {
        removeGameKeyboard()
        panelDragPointer = -1
        panelUseRaw = false
        val defaultW = min(screenW - dp(10), dp(380))
        val savedW = KeyStore.loadPanelWidthDp(context)
        val padW = if (savedW > 0) {
            min(screenW - dp(4), max(dp(220), dp(savedW)))
        } else defaultW
        val panel = LinearLayout(themeCtx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xB0000000.toInt(), 18, 0x55FFFFFF)
            setPadding(dp(4), dp(4), dp(4), dp(6))
            elevation = dp(6).toFloat()
        }
        val handle = TextView(themeCtx).apply {
            text = getString(R.string.panel_handle)
            textSize = 11f
            setTextColor(0xCCFFFFFF.toInt())
            gravity = Gravity.CENTER
            background = rounded(0x33FFFFFF, 8)
        }
        panel.addView(
            handle,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(24)
            )
        )
        panelHandle = handle
        panelBody = LinearLayout(themeCtx).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(
            panelBody,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        panelSymbolMode = false
        renderPanelKeys()

        val defaultX = (screenW - padW) / 2
        val p = windowParams().apply {
            // 允许窗口整体移出屏幕，否则系统会把负坐标夹回屏幕内，表现为只能上下移动
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            width = padW
            // 每次打开都复位：水平居中，落位后贴底
            this.x = defaultX
            this.y = screenH - dp(300)
        }

        panel.setOnTouchListener { _, ev -> panelTouch(ev) }
        panel.alpha = keyAlpha
        runCatching { wm.addView(panel, p) }
        panelView = panel
        panelParams = p
        panel.post {
            val ph = panel.height
            if (ph > 0) {
                p.y = (screenH - ph).coerceAtLeast(0)
                runCatching { wm.updateViewLayout(panel, p) }
            }
        }
    }

    /** 重绘整块键盘：字母层显示常用键，符号层显示符号与 F1-F12 */
    private fun renderPanelKeys() {
        val body = panelBody ?: return
        body.removeAllViews()
        panelKeys.clear()
        panelCapsView = null
        panelShiftViews.clear()

        fun addRow(entries: List<Pair<String, Int>>, weights: List<Float>? = null) {
            val row = LinearLayout(themeCtx).apply { orientation = LinearLayout.HORIZONTAL }
            entries.forEachIndexed { i, (label, code) ->
                val tv = TextView(themeCtx).apply {
                    text = label
                    textSize = when {
                        label.length >= 3 -> 8.5f
                        label.length == 2 -> 10f
                        else -> 12f
                    }
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    background = if (code < 0) rounded(0xE63A3A3A.toInt(), 8, 0x99FFFFFF.toInt())
                    else rounded(0xCC181818.toInt(), 8, 0x66FFFFFF.toInt())
                    isClickable = false
                }
                if (code == PANEL_CODE_CAPS) panelCapsView = tv
                if (code == KeyEvent.KEYCODE_SHIFT_LEFT) panelShiftViews.add(tv)
                val lp = LinearLayout.LayoutParams(0, dp(34), weights?.get(i) ?: 1f).apply {
                    marginStart = dp(1)
                    marginEnd = dp(1)
                }
                row.addView(tv, lp)
                panelKeys.add(tv to code)
            }
            body.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(3) }
            )
        }

        if (!panelSymbolMode) {
            addRow(
                listOf(
                    "Esc" to KeyEvent.KEYCODE_ESCAPE,
                    "1" to KeyEvent.KEYCODE_1, "2" to KeyEvent.KEYCODE_2,
                    "3" to KeyEvent.KEYCODE_3, "4" to KeyEvent.KEYCODE_4,
                    "5" to KeyEvent.KEYCODE_5, "6" to KeyEvent.KEYCODE_6,
                    "7" to KeyEvent.KEYCODE_7, "8" to KeyEvent.KEYCODE_8,
                    "9" to KeyEvent.KEYCODE_9, "0" to KeyEvent.KEYCODE_0,
                    "⌫" to KeyEvent.KEYCODE_DEL
                ),
                listOf(1.3f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1.2f)
            )
            addRow(
                listOf(
                    "Tab" to KeyEvent.KEYCODE_TAB,
                    "Q" to KeyEvent.KEYCODE_Q, "W" to KeyEvent.KEYCODE_W,
                    "E" to KeyEvent.KEYCODE_E, "R" to KeyEvent.KEYCODE_R,
                    "T" to KeyEvent.KEYCODE_T, "Y" to KeyEvent.KEYCODE_Y,
                    "U" to KeyEvent.KEYCODE_U, "I" to KeyEvent.KEYCODE_I,
                    "O" to KeyEvent.KEYCODE_O, "P" to KeyEvent.KEYCODE_P,
                    getString(R.string.panel_key_symbols) to PANEL_CODE_SYMBOLS
                ),
                listOf(1.3f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1.2f)
            )
            addRow(
                listOf(
                    "Caps" to PANEL_CODE_CAPS,
                    "A" to KeyEvent.KEYCODE_A, "S" to KeyEvent.KEYCODE_S,
                    "D" to KeyEvent.KEYCODE_D, "F" to KeyEvent.KEYCODE_F,
                    "G" to KeyEvent.KEYCODE_G, "H" to KeyEvent.KEYCODE_H,
                    "J" to KeyEvent.KEYCODE_J, "K" to KeyEvent.KEYCODE_K,
                    "L" to KeyEvent.KEYCODE_L,
                    "⏎" to KeyEvent.KEYCODE_ENTER
                ),
                listOf(1.55f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1.3f)
            )
            addRow(
                listOf(
                    "Shift" to KeyEvent.KEYCODE_SHIFT_LEFT,
                    "Z" to KeyEvent.KEYCODE_Z, "X" to KeyEvent.KEYCODE_X,
                    "C" to KeyEvent.KEYCODE_C, "V" to KeyEvent.KEYCODE_V,
                    "B" to KeyEvent.KEYCODE_B, "N" to KeyEvent.KEYCODE_N,
                    "M" to KeyEvent.KEYCODE_M,
                    "," to KeyEvent.KEYCODE_COMMA, "." to KeyEvent.KEYCODE_PERIOD,
                    "/" to KeyEvent.KEYCODE_SLASH
                ),
                listOf(1.6f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f)
            )
            addRow(
                listOf(
                    "Ctrl" to KeyEvent.KEYCODE_CTRL_LEFT,
                    "Alt" to KeyEvent.KEYCODE_ALT_LEFT,
                    getString(R.string.panel_key_space) to KeyEvent.KEYCODE_SPACE,
                    "←" to KeyEvent.KEYCODE_DPAD_LEFT, "↑" to KeyEvent.KEYCODE_DPAD_UP,
                    "↓" to KeyEvent.KEYCODE_DPAD_DOWN, "→" to KeyEvent.KEYCODE_DPAD_RIGHT
                ),
                listOf(1.3f, 1.3f, 3.2f, 1f, 1f, 1f, 1f)
            )
        } else {
            addRow(
                listOf(
                    "F1" to KeyEvent.KEYCODE_F1, "F2" to KeyEvent.KEYCODE_F2,
                    "F3" to KeyEvent.KEYCODE_F3, "F4" to KeyEvent.KEYCODE_F4,
                    "F5" to KeyEvent.KEYCODE_F5, "F6" to KeyEvent.KEYCODE_F6,
                    "F7" to KeyEvent.KEYCODE_F7, "F8" to KeyEvent.KEYCODE_F8,
                    "F9" to KeyEvent.KEYCODE_F9, "F10" to KeyEvent.KEYCODE_F10,
                    "F11" to KeyEvent.KEYCODE_F11, "F12" to KeyEvent.KEYCODE_F12
                )
            )
            addRow(
                listOf(
                    "Esc" to KeyEvent.KEYCODE_ESCAPE,
                    "`" to KeyEvent.KEYCODE_GRAVE, "-" to KeyEvent.KEYCODE_MINUS,
                    "=" to KeyEvent.KEYCODE_EQUALS,
                    "[" to KeyEvent.KEYCODE_LEFT_BRACKET, "]" to KeyEvent.KEYCODE_RIGHT_BRACKET,
                    "\\" to KeyEvent.KEYCODE_BACKSLASH,
                    ";" to KeyEvent.KEYCODE_SEMICOLON, "'" to KeyEvent.KEYCODE_APOSTROPHE,
                    "⌫" to KeyEvent.KEYCODE_DEL
                ),
                listOf(1.2f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1.2f)
            )
            addRow(
                listOf(
                    "Tab" to KeyEvent.KEYCODE_TAB,
                    "!" to KeyEvent.KEYCODE_1, "@" to KeyEvent.KEYCODE_2,
                    "#" to KeyEvent.KEYCODE_3, "$" to KeyEvent.KEYCODE_4,
                    "%" to KeyEvent.KEYCODE_5, "^" to KeyEvent.KEYCODE_6,
                    "&" to KeyEvent.KEYCODE_7, "*" to KeyEvent.KEYCODE_8,
                    "(" to KeyEvent.KEYCODE_9, ")" to KeyEvent.KEYCODE_0
                ),
                listOf(1.3f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f)
            )
            addRow(
                listOf(
                    "Shift" to KeyEvent.KEYCODE_SHIFT_LEFT,
                    "_" to KeyEvent.KEYCODE_MINUS, "+" to KeyEvent.KEYCODE_EQUALS,
                    "{" to KeyEvent.KEYCODE_LEFT_BRACKET, "}" to KeyEvent.KEYCODE_RIGHT_BRACKET,
                    "|" to KeyEvent.KEYCODE_BACKSLASH,
                    ":" to KeyEvent.KEYCODE_SEMICOLON, "\"" to KeyEvent.KEYCODE_APOSTROPHE,
                    "<" to KeyEvent.KEYCODE_COMMA, ">" to KeyEvent.KEYCODE_PERIOD,
                    "?" to KeyEvent.KEYCODE_SLASH
                ),
                listOf(1.6f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f)
            )
            addRow(
                listOf(
                    "ABC" to PANEL_CODE_ABC,
                    "Ctrl" to KeyEvent.KEYCODE_CTRL_LEFT,
                    "Alt" to KeyEvent.KEYCODE_ALT_LEFT,
                    getString(R.string.panel_key_space) to KeyEvent.KEYCODE_SPACE,
                    "Home" to KeyEvent.KEYCODE_MOVE_HOME,
                    "End" to KeyEvent.KEYCODE_MOVE_END,
                    "PgUp" to KeyEvent.KEYCODE_PAGE_UP,
                    "PgDn" to KeyEvent.KEYCODE_PAGE_DOWN
                ),
                listOf(1.5f, 1.2f, 1.2f, 2.4f, 1f, 1f, 1f, 1f)
            )
        }
        setCapsLook()
    }

    private fun setCapsLook() {
        val defaultBg = { rounded(0xCC181818.toInt(), 8, 0x66FFFFFF.toInt()) }
        panelCapsView?.background = if (panelCapsOn) {
            rounded(0xE63D7BFF.toInt(), 8, 0xFFD6E6FF.toInt())
        } else defaultBg()
        panelShiftViews.forEach { v ->
            v.background = if (panelCapsOn) {
                rounded(0xE62F5FA8.toInt(), 8, 0xFFBBD8FF.toInt())
            } else defaultBg()
        }
    }

    private fun toggleCaps() {
        panelCapsOn = !panelCapsOn
        val down = panelCapsOn
        worker.execute {
            if (down) ShizukuInjector.keyDown(KeyEvent.KEYCODE_SHIFT_LEFT)
            else ShizukuInjector.keyUp(KeyEvent.KEYCODE_SHIFT_LEFT)
        }
        setCapsLook()
    }

    /** 字母层 <-> 符号层，切换时先松开其它手指按着的键，避免残留 */
    private fun switchPanelLayer() {
        panelSymbolMode = !panelSymbolMode
        releasePressedPanelKeys()
        renderPanelKeys()
    }

    private fun releasePressedPanelKeys() {
        panelCounts.keys.toList().forEach { code ->
            if (code > 0) {
                val extra = if (panelShiftedCodes.remove(code)) KeyEvent.META_SHIFT_ON else 0
                worker.execute { ShizukuInjector.keyUp(code, extra) }
            }
        }
        panelCounts.clear()
        panelShiftedCodes.clear()
        panelPointerCode.clear()
        panelPointerView.clear()
    }

    private fun releaseCaps() {
        if (!panelCapsOn) return
        panelCapsOn = false
        worker.execute { ShizukuInjector.keyUp(KeyEvent.KEYCODE_SHIFT_LEFT) }
    }

    /** 松手后收回一点，保证还能看到/抓到键盘，不至于彻底丢在屏幕外 */
    private fun guardPanelOnScreen() {
        val panel = panelView ?: return
        val p = panelParams ?: return
        val w = panel.width.takeIf { it > 0 } ?: return
        val h = panel.height.takeIf { it > 0 } ?: return
        val keep = dp(40)
        if (p.x > screenW - keep) p.x = screenW - keep
        if (p.x + w < keep) p.x = keep - w
        if (p.y < -dp(12)) p.y = -dp(12)
        if (p.y > screenH - dp(28)) p.y = screenH - dp(28)
        runCatching { wm.updateViewLayout(panel, p) }
    }

    private fun panelTouch(ev: MotionEvent): Boolean {
        val panel = panelView ?: return false
        val p = panelParams ?: return false
        val loc = IntArray(2)
        panel.getLocationOnScreen(loc)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = ev.actionIndex
                val id = ev.getPointerId(idx)
                val sx = ev.getX(idx) + loc[0]
                val sy = ev.getY(idx) + loc[1]
                if (panelInHandle(sx, sy)) {
                    if (panelDragPointer == -1) {
                        panelDragPointer = id
                        // 主指针用 raw 坐标，避免窗口跟随移动时 getX+loc 来回补偿导致乱跳
                        panelUseRaw = ev.getPointerId(0) == id
                        panelDownX = if (panelUseRaw) ev.rawX else sx
                        panelDownY = if (panelUseRaw) ev.rawY else sy
                        panelStartX = p.x
                        panelStartY = p.y
                    }
                } else {
                    val hit = panelHit(sx, sy)
                    if (hit != null) {
                        panelPointerCode[id] = hit.second
                        panelPointerView[id] = hit.first
                        panelKeyDown(hit.second)
                        setPanelKeyLook(hit.first, true)
                    }
                }
                // 第二根手指落下：先记下距离，超过阈值才认定是缩放，避免误吞正常按键
                if (ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN &&
                    !panelPinchActive && !panelPinchPending && panelDragPointer == -1 &&
                    ev.pointerCount >= 2
                ) {
                    panelPinchIdA = ev.getPointerId(0)
                    panelPinchIdB = id
                    panelPinchStartW = p.width
                    panelPinchStartX = p.x
                    val m = panelPinchMetrics(ev, loc)
                    if (m != null && m[0] > dp(40)) {
                        panelPinchDist = m[0]
                        panelPinchMidX = m[1]
                        panelPinchPending = true
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (panelPinchActive) {
                    panelUpdatePinch(ev, loc, panel, p)
                    return true
                }
                if (panelPinchPending) {
                    val m = panelPinchMetrics(ev, loc)
                    if (m != null && abs(m[0] - panelPinchDist) > dp(24)) {
                        // 明确是缩放：把这两根手指刚按下的键松开，开始改宽度
                        panelReleasePointer(panelPinchIdA, fireSpecial = false)
                        panelReleasePointer(panelPinchIdB, fireSpecial = false)
                        panelPinchActive = true
                        panelPinchPending = false
                        panelPinchDist = m[0]
                        panelPinchMidX = m[1]
                        panelPinchStartW = p.width
                        panelPinchStartX = p.x
                        return true
                    }
                }
                if (panelDragPointer != -1) {
                    val idx = ev.findPointerIndex(panelDragPointer)
                    if (idx >= 0) {
                        val rawX = if (panelUseRaw) ev.rawX else ev.getX(idx) + loc[0]
                        val rawY = if (panelUseRaw) ev.rawY else ev.getY(idx) + loc[1]
                        p.x = (panelStartX + (rawX - panelDownX)).roundToInt()
                        p.y = (panelStartY + (rawY - panelDownY)).roundToInt()
                        runCatching { wm.updateViewLayout(panel, p) }
                    }
                }
                val ids = panelPointerCode.keys.toList()
                for (id in ids) {
                    val idx = ev.findPointerIndex(id)
                    if (idx < 0) continue
                    val sx = ev.getX(idx) + loc[0]
                    val sy = ev.getY(idx) + loc[1]
                    val v = panelPointerView[id] ?: continue
                    val vl = IntArray(2)
                    v.getLocationOnScreen(vl)
                    val m = dp(6)
                    if (sx < vl[0] - m || sx > vl[0] + v.width + m ||
                        sy < vl[1] - m || sy > vl[1] + v.height + m
                    ) {
                        panelReleasePointer(id)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val idx = if (ev.actionMasked == MotionEvent.ACTION_UP) 0 else ev.actionIndex
                val id = ev.getPointerId(idx)
                if (panelPinchActive || panelPinchPending) {
                    // 只剩一根手指时结束缩放并记住宽度
                    if (ev.pointerCount <= 2) {
                        if (panelPinchActive) {
                            KeyStore.savePanelWidthDp(context, (p.width / density).roundToInt())
                            guardPanelOnScreen()
                        }
                        panelPinchActive = false
                        panelPinchPending = false
                        panelPinchIdA = -1
                        panelPinchIdB = -1
                    }
                }
                if (id == panelDragPointer) {
                    panelDragPointer = -1
                    panelUseRaw = false
                    guardPanelOnScreen()
                } else {
                    panelReleasePointer(id)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (panelPinchActive) {
                    KeyStore.savePanelWidthDp(context, (p.width / density).roundToInt())
                }
                panelPinchActive = false
                panelPinchPending = false
                panelPinchIdA = -1
                panelPinchIdB = -1
                panelDragPointer = -1
                panelUseRaw = false
                guardPanelOnScreen()
                panelPointerCode.keys.toList().forEach {
                    panelReleasePointer(it, fireSpecial = false)
                }
                return true
            }
        }
        return false
    }

    /** 返回 [两指距离, 两指中点 X]，指针不全时返回 null */
    private fun panelPinchMetrics(ev: MotionEvent, loc: IntArray): FloatArray? {
        if (panelPinchIdA < 0 || panelPinchIdB < 0) return null
        val ia = ev.findPointerIndex(panelPinchIdA)
        val ib = ev.findPointerIndex(panelPinchIdB)
        if (ia < 0 || ib < 0) return null
        val ax = ev.getX(ia) + loc[0]
        val ay = ev.getY(ia) + loc[1]
        val bx = ev.getX(ib) + loc[0]
        val by = ev.getY(ib) + loc[1]
        return floatArrayOf(hypot(ax - bx, ay - by), (ax + bx) / 2f)
    }

    /** 按两指距离实时改窗口宽度，并让两指中点保持不动 */
    private fun panelUpdatePinch(
        ev: MotionEvent,
        loc: IntArray,
        panel: View,
        p: WindowManager.LayoutParams
    ) {
        if (panelPinchDist <= 0f || panelPinchStartW <= 0) return
        val m = panelPinchMetrics(ev, loc) ?: return
        val minW = dp(220)
        val maxW = max(minW, screenW - dp(4))
        val newW = (panelPinchStartW * (m[0] / panelPinchDist)).roundToInt().coerceIn(minW, maxW)
        if (newW == p.width) return
        p.width = newW
        val shift = (panelPinchMidX - panelPinchStartX) * (newW.toFloat() / panelPinchStartW)
        p.x = (panelPinchMidX - shift).roundToInt()
        runCatching { wm.updateViewLayout(panel, p) }
    }

    private fun panelInHandle(x: Float, y: Float): Boolean {
        val h = panelHandle ?: return false
        val l = IntArray(2)
        h.getLocationOnScreen(l)
        return x >= l[0] && x <= l[0] + h.width && y >= l[1] && y <= l[1] + h.height
    }

    private fun panelHit(x: Float, y: Float): Pair<TextView, Int>? {
        val l = IntArray(2)
        for ((v, code) in panelKeys) {
            v.getLocationOnScreen(l)
            if (x >= l[0] && x <= l[0] + v.width && y >= l[1] && y <= l[1] + v.height) {
                return v to code
            }
        }
        return null
    }

    private fun panelKeyDown(code: Int) {
        if (code < 0) return
        // 大写锁定时 Shift 已经按住，再按 Shift 不重复注入，也不要在抬手时松开它
        if (code == KeyEvent.KEYCODE_SHIFT_LEFT && panelCapsOn) return
        // 符号层里 ! @ # 这些键实际上是 Shift+数字/标点，注入时要带上 Shift meta
        val extra = if (panelSymbolMode && code in panelUpperCodes) KeyEvent.META_SHIFT_ON else 0
        val n = panelCounts[code] ?: 0
        panelCounts[code] = n + 1
        if (n == 0) {
            if (extra != 0) panelShiftedCodes.add(code)
            worker.execute { ShizukuInjector.keyDown(code, extra) }
        }
    }

    private fun panelKeyUp(code: Int) {
        when (code) {
            PANEL_CODE_SYMBOLS, PANEL_CODE_ABC -> {
                switchPanelLayer()
                return
            }
            PANEL_CODE_CAPS -> {
                toggleCaps()
                return
            }
        }
        if (code < 0) return
        if (code == KeyEvent.KEYCODE_SHIFT_LEFT && panelCapsOn) return
        val n = panelCounts[code] ?: 0
        if (n <= 0) return
        if (n > 1) {
            panelCounts[code] = n - 1
            return
        }
        panelCounts.remove(code)
        val extra = if (panelShiftedCodes.remove(code)) KeyEvent.META_SHIFT_ON else 0
        worker.execute { ShizukuInjector.keyUp(code, extra) }
    }

    private fun panelReleasePointer(id: Int, fireSpecial: Boolean = true) {
        val code = panelPointerCode.remove(id) ?: return
        panelPointerView.remove(id)?.let { setPanelKeyLook(it, false) }
        if (code < 0 && !fireSpecial) return
        panelKeyUp(code)
    }

    private fun setPanelKeyLook(v: TextView, pressed: Boolean) {
        v.background = when {
            pressed -> rounded(0xE6505050.toInt(), 8, 0xCCFFFFFF.toInt())
            v === panelCapsView && panelCapsOn -> rounded(0xE63D7BFF.toInt(), 8, 0xFFD6E6FF.toInt())
            panelCapsOn && panelShiftViews.contains(v) -> rounded(0xE62F5FA8.toInt(), 8, 0xFFBBD8FF.toInt())
            else -> rounded(0xCC181818.toInt(), 8, 0x66FFFFFF)
        }
    }

    private fun releasePanelKeys() {
        releasePressedPanelKeys()
        releaseCaps()
        panelDragPointer = -1
    }

    private fun removeGameKeyboard() {
        releasePanelKeys()
        panelKeys.forEach { (v, _) -> setPanelKeyLook(v, false) }
        panelKeys.clear()
        panelHandle = null
        panelBody = null
        panelCapsView = null
        panelShiftViews.clear()
        panelSymbolMode = false
        panelView?.let { runCatching { wm.removeViewImmediate(it) } }
        panelView = null
        panelParams = null
        panelDragPointer = -1
        panelUseRaw = false
    }

    private fun toggleGameKeyboard() {
        gameKeyboardOn = !gameKeyboardOn
        KeyStore.saveGameKeyboard(context, gameKeyboardOn)
        if (gameKeyboardOn) {
            buildGameKeyboard()
            toast(getString(R.string.toast_game_keyboard_on))
        } else {
            removeGameKeyboard()
            toast(getString(R.string.toast_game_keyboard_off))
        }
    }

    // ==================== 虚拟鼠标（光标） ====================

    /** 开关虚拟鼠标：打开时出现箭头光标，全屏任意位置拖动；关闭时光标消失、鼠标按键失效 */
    private fun toggleMousePad() {
        if (mouseOn) {
            mouseOn = false
            refreshBarLabels()
            hotCursorLast.clear()
            hotCursorTravel.clear()
            hotCursorMoved.clear()
            refreshEvdev()
            removeDragLayer()
            removeCursor()
            toast(getString(R.string.toast_mouse_off))
            return
        }
        mouseOn = true
        refreshBarLabels()
        fullScreenDrag = KeyStore.loadFullScreenMouse(context)
        buildCursor()
        if (fullScreenDrag) buildFullScreenLayer() else buildDragLayer()
        refreshEvdev()
        raiseOverlayAboveLayer()
        toast(
            getString(
                if (fullScreenDrag) R.string.toast_mouse_fullscreen_hint
                else R.string.toast_mouse_pad_hint
            )
        )
    }

    /** 主界面切换拖动方式后立刻生效 */
    fun setFullScreenMode(enabled: Boolean) {
        ui.post {
            if (fullScreenDrag == enabled) return@post
            fullScreenDrag = enabled
            if (!mouseOn) return@post
            removeDragLayer()
            if (fullScreenDrag) buildFullScreenLayer() else buildDragLayer()
            raiseOverlayAboveLayer()
            toast(getString(if (fullScreenDrag) R.string.toast_switched_fullscreen else R.string.toast_switched_touchpad))
        }
    }

    /** 工具条长按鼠标键：在全屏滑动与小触控板之间切换，并记住选择 */
    private fun toggleMouseProfile() {
        fullScreenDrag = !fullScreenDrag
        KeyStore.saveFullScreenMouse(context, fullScreenDrag)
        if (mouseOn) {
            removeDragLayer()
            if (fullScreenDrag) buildFullScreenLayer() else buildDragLayer()
            raiseOverlayAboveLayer()
        }
        toast(getString(if (fullScreenDrag) R.string.toast_switched_fullscreen else R.string.toast_switched_touchpad))
    }

    private fun buildCursor() {
        removeCursor()
        val size = dp(44)
        val stored = KeyStore.loadCursor(context)
        val p = windowParams().apply {
            width = size
            height = size
            // 光标本身不接收触摸，避免挡住注入的鼠标事件
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            x = (stored.x * screenW - dp(4)).roundToInt()
                .coerceIn(0, max(0, screenW - size))
            y = (stored.y * screenH - dp(4)).roundToInt()
                .coerceIn(0, max(0, screenH - size))
        }
        val cursor = MouseCursorView(themeCtx)
        runCatching { wm.addView(cursor, p) }
        cursorView = cursor
        cursorParams = p
        syncMousePosition(p)
        // 布局完成后再取一次真实屏幕位置，修正状态栏/导航栏造成的偏移
        cursor.post {
            updateCursorOffsets()
            val tx = mouseX
            val ty = mouseY
            worker.execute { ShizukuInjector.mouseMove(tx, ty) }
        }
    }

    /** 用 getLocationOnScreen 校准光标尖端的真实屏幕坐标 */
    private fun updateCursorOffsets() {
        val v = cursorView ?: return
        val p = cursorParams ?: return
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        cursorOffX = loc[0] - p.x
        cursorOffY = loc[1] - p.y
        syncMousePosition(p)
    }

    // ==================== 触控板拖动（支持从按键窗口转交手指） ====================

    private fun beginPadDrag(x: Float, y: Float): Boolean {
        if (padDragActive) return false
        padDragActive = true
        padDragMoved = false
        padDragLastX = x
        padDragLastY = y
        return true
    }

    private fun movePadDrag(x: Float, y: Float) {
        if (!padDragActive) return
        val dx = x - padDragLastX
        val dy = y - padDragLastY
        padDragLastX = x
        padDragLastY = y
        if (abs(dx) + abs(dy) > 1f) padDragMoved = true
        if (!padDragMoved) return
        val gain = mouseGain
        mouseX = (mouseX + dx * gain).coerceIn(0f, (screenW - 1).toFloat())
        mouseY = (mouseY + dy * gain).coerceIn(0f, (screenH - 1).toFloat())
        moveCursorWindowToMouse()
        val now = SystemClock.uptimeMillis()
        if (now - padDragLastInject >= 16L) {
            padDragLastInject = now
            injectHoverAtCursor()
        }
    }

    /** 手指离开触控板：没拖动过就当作一次左键单击（tap=false 时只保存位置） */
    private fun endPadDrag(tap: Boolean = true) {
        if (!padDragActive) return
        padDragActive = false
        if (!tap) {
            KeyStore.saveCursor(context, mouseX / screenW.toFloat(), mouseY / screenH.toFloat())
            return
        }
        if (padDragMoved) {
            injectHoverAtCursor()
        } else if (mouseLeftHeld) {
            // 左键锁定状态下，轻点触控板 = 松开左键
            setMouseLeftHeld(false)
        } else {
            val tx = mouseX
            val ty = mouseY
            withOverlayUntouchable {
                ShizukuInjector.mouseClick(MotionEvent.BUTTON_PRIMARY, tx, ty)
            }
        }
        KeyStore.saveCursor(context, mouseX / screenW.toFloat(), mouseY / screenH.toFloat())
    }

    /** 切换左键按住状态（长按触控板触发） */
    private fun setMouseLeftHeld(held: Boolean) {
        if (mouseLeftHeld == held) return
        mouseLeftHeld = held
        val tx = mouseX
        val ty = mouseY
        withOverlayUntouchable {
            if (held) {
                ShizukuInjector.mouseButtonDown(MotionEvent.BUTTON_PRIMARY, tx, ty)
            } else {
                ShizukuInjector.mouseButtonUp(tx, ty)
            }
        }
        toast(getString(if (held) R.string.toast_left_held else R.string.toast_left_released))
    }

    /** 触控板被移除时把锁定的左键松开，避免卡在按下状态 */
    private fun releaseMouseLeft() {
        if (!mouseLeftHeld) return
        mouseLeftHeld = false
        val tx = mouseX
        val ty = mouseY
        withOverlayUntouchable { ShizukuInjector.mouseButtonUp(tx, ty) }
    }

    private fun cancelPadDrag() {
        padDragActive = false
    }

    private fun padContains(x: Float, y: Float): Boolean {
        val v = dragLayer ?: return false
        val l = IntArray(2)
        v.getLocationOnScreen(l)
        return x >= l[0] && x <= l[0] + v.width && y >= l[1] && y <= l[1] + v.height
    }

    private fun releasePadPointer(token: String, keyTargets: HashMap<String, KeyItem>) {
        val key = keyTargets.remove(token)
        pointerTargets.remove(token)
        if (key != null) {
            releaseKey(key)
        } else if (forwardTokens.remove(token)) {
            forwardUp(token)
        }
    }

    /**
     * 鼠标触控板：固定的一小块区域，按住它拖动来移动鼠标。
     * 只是普通小窗口，不覆盖全屏，所以游戏本身仍然可以正常多点触摸，
     * 悬停事件也不会被挡住（箭头浮起效果可以保持）。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun buildDragLayer() {
        removeDragLayer()
        val padW = dp(min(220, (screenW / density * 0.42f).toInt()))
        val padH = dp(168)
        val pad = LinearLayout(themeCtx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0x66101010, 16, 0x99FFFFFF.toInt())
            elevation = dp(4).toFloat()
        }
        val handle = TextView(themeCtx).apply {
            text = "≡"
            textSize = 14f
            setTextColor(0xCCFFFFFF.toInt())
            gravity = Gravity.CENTER
        }
        pad.addView(handle, LinearLayout.LayoutParams(padW, dp(26)))
        val body = TextView(themeCtx).apply {
            text = getString(R.string.touchpad_hint)
            textSize = 10f
            setTextColor(0x66FFFFFF.toInt())
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.35f)
        }
        pad.addView(body, LinearLayout.LayoutParams(padW, padH - dp(26)))
        val p = windowParams().apply {
            x = dp(16)
            y = screenH - padH - dp(140)
        }
        setupDrag(handle, pad, p)

        // 只跟第一根落到触控板上的手指；其它手指按屏幕坐标自己分发：
        // 落在按键上就按键，落在空余区域就转发给游戏，保证触控板与按键能同时多点触控
        var activeId = -1
        var longFired = false
        var consumedTap = false
        var twoActive = false
        var twoMoved = false
        var twoStartTime = 0L
        var twoLastY = 0f
        var twoTotal = 0f
        var scrollAcc = 0f
        val padKeyTargets = HashMap<String, KeyItem>()
        val bodyLoc = IntArray(2)

        // 双指都落在触控板内时，返回两指的平均 Y；否则返回 MIN_VALUE
        fun twoFingerY(e: MotionEvent): Float {
            var sum = 0f
            var n = 0
            for (i in 0 until e.pointerCount) {
                val sx = e.getX(i) + bodyLoc[0]
                val sy = e.getY(i) + bodyLoc[1]
                if (padContains(sx, sy)) {
                    sum += sy
                    n++
                }
            }
            return if (n >= 2) sum / n else Float.MIN_VALUE
        }

        val longPress = Runnable {
            // 长按且没移动：切换左键按住（拖拽锁定）
            if (padDragActive && !padDragMoved && !twoActive) {
                longFired = true
                setMouseLeftHeld(!mouseLeftHeld)
            }
        }

        body.setOnTouchListener { _, ev ->
            body.getLocationOnScreen(bodyLoc)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val id = ev.getPointerId(0)
                    longFired = false
                    consumedTap = false
                    twoActive = false
                    twoMoved = false
                    scrollAcc = 0f
                    activeId = if (beginPadDrag(ev.getX(0) + bodyLoc[0], ev.getY(0) + bodyLoc[1])) id else -1
                    if (activeId != -1) body.postDelayed(longPress, 450)
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    body.removeCallbacks(longPress)
                    val idx = ev.actionIndex
                    val id = ev.getPointerId(idx)
                    val token = pointerToken(body, id)
                    val sx = ev.getX(idx) + bodyLoc[0]
                    val sy = ev.getY(idx) + bodyLoc[1]
                    val target = keyAtScreen(body, sx, sy)?.takeIf { it.type != KeyType.JOYSTICK }
                    if (target != null) {
                        padKeyTargets[token] = target
                        pointerTargets[token] = target
                        pressKey(target)
                    } else if (!padContains(sx, sy)) {
                        // 手指落在触控板外：转发给下面的游戏
                        forwardTokens.add(token)
                        forwardDown(token, sx, sy)
                    }
                    if (padContains(sx, sy)) {
                        // 第二根手指也落在触控板上：进入双指手势（右键 / 滚轮）
                        twoActive = true
                        twoMoved = false
                        twoStartTime = SystemClock.uptimeMillis()
                        twoLastY = twoFingerY(ev)
                        twoTotal = 0f
                        scrollAcc = 0f
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (twoActive) {
                        val avgY = twoFingerY(ev)
                        if (avgY != Float.MIN_VALUE) {
                            val dy = avgY - twoLastY
                            twoLastY = avgY
                            twoTotal += abs(dy)
                            if (twoTotal > dp(6)) twoMoved = true
                            scrollAcc += dy
                            if (abs(scrollAcc) >= dp(18)) {
                                // 上滑 = 滚轮向上（与滚轮键一致，用 -1）
                                val dir = if (scrollAcc < 0) -1f else 1f
                                scrollAcc = 0f
                                val tx = mouseX
                                val ty = mouseY
                                worker.execute { ShizukuInjector.mouseWheel(tx, ty, dir) }
                            }
                        }
                    } else {
                        val idx = ev.findPointerIndex(activeId)
                        if (idx >= 0) {
                            movePadDrag(ev.getX(idx) + bodyLoc[0], ev.getY(idx) + bodyLoc[1])
                        }
                    }
                    for (i in 0 until ev.pointerCount) {
                        val token = pointerToken(body, ev.getPointerId(i))
                        if (token in forwardTokens) {
                            forwardMove(token, ev.getX(i) + bodyLoc[0], ev.getY(i) + bodyLoc[1])
                        }
                    }
                    true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    body.removeCallbacks(longPress)
                    val id = ev.getPointerId(ev.actionIndex)
                    if (twoActive) {
                        twoActive = false
                        consumedTap = true
                        if (!twoMoved && SystemClock.uptimeMillis() - twoStartTime < 400L) {
                            // 双指轻点 = 右键
                            val tx = mouseX
                            val ty = mouseY
                            withOverlayUntouchable {
                                ShizukuInjector.mouseClick(MotionEvent.BUTTON_SECONDARY, tx, ty)
                            }
                        }
                    }
                    if (id == activeId) {
                        endPadDrag(tap = false)
                        activeId = -1
                    }
                    releasePadPointer(pointerToken(body, id), padKeyTargets)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    body.removeCallbacks(longPress)
                    if (activeId != -1) {
                        endPadDrag(tap = !longFired && !consumedTap)
                        activeId = -1
                    }
                    twoActive = false
                    releasePadPointer(pointerToken(body, ev.getPointerId(0)), padKeyTargets)
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    body.removeCallbacks(longPress)
                    cancelPadDrag()
                    activeId = -1
                    twoActive = false
                    padKeyTargets.keys.toList().forEach { t -> releasePadPointer(t, padKeyTargets) }
                    val prefix = System.identityHashCode(body).toString() + ":"
                    val it = forwardTokens.iterator()
                    while (it.hasNext()) {
                        val t = it.next()
                        if (t.startsWith(prefix)) {
                            forwardUp(t)
                            it.remove()
                        }
                    }
                    true
                }
                else -> false
            }
        }
        runCatching { wm.addView(pad, p) }
        dragLayer = pad
        dragParams = p
    }

    /**
     * 全屏拖动模式：整块屏幕都能拖动光标。
     *
     * 这一层完全透明但会接管全部触摸（游戏自身的触摸会被挡住），
     * 且拖动过程中不能注入悬停事件——注入的悬停会被这一层自己接住，
     * 所以拖动时只移动看得见的箭头，松手时再把最终位置同步给游戏。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun buildFullScreenLayer() {
        removeDragLayer()
        // 热区模式下不再铺全屏触摸层：滑动改由旁听的原始坐标驱动，
        // 否则这一层会把所有手指都吃掉，热区按键全都失效。
        if (hotMode) return
        val layer = View(themeCtx).apply {
            setBackgroundColor(0x01101010)
            isClickable = true
        }
        val p = windowParams().apply {
            width = screenW
            height = screenH
            x = 0
            y = 0
        }
        var lastX = 0f
        var lastY = 0f
        var moved = false
        var activeId = -1
        var consumedTap = false
        var twoActive = false
        var twoMoved = false
        var twoStartTime = 0L
        var twoLastY = 0f
        var twoNetY = 0f
        var twoTotal = 0f
        var pendingTwo = false
        var pendingTwoTap = false
        var pendingTicks = 0
        var pendingDir = 1f
        val layerKeyTargets = HashMap<String, KeyItem>()
        val layerLoc = IntArray(2)

        // 只统计落在这一层上的手指（按在按键上的不算），返回平均 Y
        fun layerAvgY(e: MotionEvent): Float {
            var sum = 0f
            var n = 0
            for (i in 0 until e.pointerCount) {
                if (pointerToken(layer, e.getPointerId(i)) !in layerKeyTargets) {
                    sum += e.getY(i)
                    n++
                }
            }
            return if (n >= 2) sum / n else Float.MIN_VALUE
        }

        layer.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    activeId = ev.getPointerId(0)
                    layer.getLocationOnScreen(layerLoc)
                    lastX = ev.getX(0) + layerLoc[0]
                    lastY = ev.getY(0) + layerLoc[1]
                    moved = false
                    consumedTap = false
                    twoActive = false
                    twoMoved = false
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    val idx = ev.actionIndex
                    val id = ev.getPointerId(idx)
                    val sx = ev.getX(idx) + layerLoc[0]
                    val sy = ev.getY(idx) + layerLoc[1]
                    val target = keyAtScreen(layer, sx, sy)?.takeIf { it.type != KeyType.JOYSTICK }
                    if (target != null) {
                        // 第二根手指落在按键上：无论系统是否分流，这里都按下去
                        val token = pointerToken(layer, id)
                        layerKeyTargets[token] = target
                        pointerTargets[token] = target
                        pressKey(target)
                    } else {
                        // 两根手指都在拖动层上：进入双指手势（右键 / 滚轮）
                        twoActive = true
                        twoMoved = false
                        twoStartTime = SystemClock.uptimeMillis()
                        twoLastY = layerAvgY(ev)
                        twoNetY = 0f
                        twoTotal = 0f
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (twoActive) {
                        val avgY = layerAvgY(ev)
                        if (avgY != Float.MIN_VALUE) {
                            val dy = avgY - twoLastY
                            twoLastY = avgY
                            twoNetY += dy
                            twoTotal += abs(dy)
                            if (twoTotal > dp(6)) twoMoved = true
                        }
                    } else {
                        val idx = ev.findPointerIndex(activeId)
                        if (idx < 0) return@setOnTouchListener true
                        val sx = ev.getX(idx) + layerLoc[0]
                        val sy = ev.getY(idx) + layerLoc[1]
                        val dx = sx - lastX
                        val dy = sy - lastY
                        lastX = sx
                        lastY = sy
                        if (abs(dx) + abs(dy) > 1f) moved = true
                        if (moved) {
                            val gain = mouseGain
                            mouseX = (mouseX + dx * gain).coerceIn(0f, (screenW - 1).toFloat())
                            mouseY = (mouseY + dy * gain).coerceIn(0f, (screenH - 1).toFloat())
                            moveCursorWindowToMouse()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val id = ev.getPointerId(ev.actionIndex)
                    layerKeyTargets.remove(pointerToken(layer, id))?.let { k ->
                        releaseKey(k)
                        pointerTargets.remove(pointerToken(layer, id))
                    }
                    if (twoActive) {
                        twoActive = false
                        consumedTap = true
                        // 还有一根手指按在层上，现在动窗口会打断手势，等手指全抬起再注入
                        pendingTwo = true
                        pendingTwoTap = !twoMoved && SystemClock.uptimeMillis() - twoStartTime < 400L
                        pendingTicks = if (twoMoved) {
                            (abs(twoNetY) / dp(22)).toInt().coerceIn(1, 10)
                        } else 0
                        pendingDir = if (twoNetY < 0) -1f else 1f
                    }
                    if (id == activeId) {
                        activeId = -1
                    } else if (activeId != -1) {
                        // 双指手势结束，剩下那根手指从当前位置继续拖，避免跳一下
                        val ai = ev.findPointerIndex(activeId)
                        if (ai >= 0) {
                            lastX = ev.getX(ai) + layerLoc[0]
                            lastY = ev.getY(ai) + layerLoc[1]
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (activeId != -1 && !consumedTap && !pendingTwo) {
                        val tx = mouseX
                        val ty = mouseY
                        // 手指已离开，此时临时让开这一层不会打断手势
                        if (moved) {
                            injectInFullScreen { ShizukuInjector.mouseMove(tx, ty) }
                        } else {
                            injectInFullScreen {
                                ShizukuInjector.mouseClick(MotionEvent.BUTTON_PRIMARY, tx, ty)
                            }
                        }
                    }
                    if (pendingTwo) {
                        pendingTwo = false
                        val tx = mouseX
                        val ty = mouseY
                        if (pendingTwoTap) {
                            injectInFullScreen {
                                ShizukuInjector.mouseClick(MotionEvent.BUTTON_SECONDARY, tx, ty)
                            }
                        } else if (pendingTicks > 0) {
                            injectInFullScreen {
                                repeat(pendingTicks) {
                                    ShizukuInjector.mouseWheel(tx, ty, pendingDir)
                                }
                                true
                            }
                        }
                    }
                    layerKeyTargets.remove(pointerToken(layer, ev.getPointerId(0)))?.let { k ->
                        releaseKey(k)
                        pointerTargets.remove(pointerToken(layer, ev.getPointerId(0)))
                    }
                    KeyStore.saveCursor(context, mouseX / screenW.toFloat(), mouseY / screenH.toFloat())
                    activeId = -1
                    twoActive = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    activeId = -1
                    twoActive = false
                    pendingTwo = false
                    layerKeyTargets.keys.toList().forEach { t ->
                        layerKeyTargets.remove(t)?.let { k ->
                            releaseKey(k)
                            pointerTargets.remove(t)
                        }
                    }
                    true
                }
                else -> false
            }
        }
        runCatching { wm.addView(layer, p) }
        dragLayer = layer
        dragParams = p
    }

    /** 触控板拖动时持续把鼠标位置注入给游戏（上一帧没注入完就丢掉这一帧） */
    private fun injectHoverAtCursor() {
        if (hoverBusy) return
        hoverBusy = true
        val tx = mouseX
        val ty = mouseY
        hoverWorker.execute {
            if (mouseLeftHeld) ShizukuInjector.mouseDrag(tx, ty)
            else ShizukuInjector.mouseMove(tx, ty)
            hoverBusy = false
        }
    }

    /** 触摸层在最上面，会把按键、工具条和光标挡住，重新添加一遍抬到最前 */
    private fun raiseOverlayAboveLayer() {
        keyViews.values.forEach { v ->
            val p = v.layoutParams as? WindowManager.LayoutParams ?: return@forEach
            runCatching { wm.removeViewImmediate(v) }
            runCatching { wm.addView(v, p) }
        }
        toolbar?.let { bar ->
            toolbarParams?.let { tp ->
                runCatching { wm.removeViewImmediate(bar) }
                runCatching { wm.addView(bar, tp) }
            }
        }
        ballView?.let { v ->
            ballParams?.let { bp ->
                runCatching { wm.removeViewImmediate(v) }
                runCatching { wm.addView(v, bp) }
            }
        }
        panelView?.let { v ->
            panelParams?.let { pp ->
                runCatching { wm.removeViewImmediate(v) }
                runCatching { wm.addView(v, pp) }
            }
        }
        cursorView?.let { v ->
            cursorParams?.let { cp ->
                runCatching { wm.removeViewImmediate(v) }
                runCatching { wm.addView(v, cp) }
            }
        }
        anchorViews.values.forEach { v ->
            val p = v.layoutParams as? WindowManager.LayoutParams ?: return@forEach
            runCatching { wm.removeViewImmediate(v) }
            runCatching { wm.addView(v, p) }
        }
    }

    private fun removeDragLayer() {
        releaseMouseLeft()
        dragLayer?.let { runCatching { wm.removeViewImmediate(it) } }
        dragLayer = null
        dragParams = null
    }

    /** 让箭头跟着虚拟鼠标走 */
    private fun moveCursorWindowToMouse() {
        val v = cursorView ?: return
        val p = cursorParams ?: return
        val size = dp(44)
        p.x = (mouseX - cursorOffX - dp(4)).roundToInt().coerceIn(0, max(0, screenW - size))
        p.y = (mouseY - cursorOffY - dp(4)).roundToInt().coerceIn(0, max(0, screenH - size))
        runCatching { wm.updateViewLayout(v, p) }
    }

    /** 光标窗口左上角 -> 箭头尖端的屏幕坐标 */
    private fun syncMousePosition(p: WindowManager.LayoutParams) {
        mouseX = (p.x + cursorOffX + dp(4)).toFloat().coerceIn(0f, (screenW - 1).toFloat())
        mouseY = (p.y + cursorOffY + dp(4)).toFloat().coerceIn(0f, (screenH - 1).toFloat())
    }

    private fun removeCursor() {
        cursorView?.let { runCatching { wm.removeViewImmediate(it) } }
        cursorView = null
        cursorParams = null
        cursorOffX = 0
        cursorOffY = 0
    }

    private fun placeCursor() {
        val p = cursorParams ?: return
        val v = cursorView ?: return
        val size = dp(44)
        p.x = p.x.coerceIn(0, max(0, screenW - size))
        p.y = p.y.coerceIn(0, max(0, screenH - size))
        runCatching { wm.updateViewLayout(v, p) }
        v.post { updateCursorOffsets() }
    }

    /** 鼠标箭头光标：白色箭头 + 黑色描边，尖端就是注入点 */
    private inner class MouseCursorView(ctx: Context) : View(ctx) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1).toFloat() + 1f
            color = Color.BLACK
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            val s = dp(44).toFloat() / 64f
            path.reset()
            path.moveTo(4 * s, 4 * s)
            path.lineTo(4 * s, 40 * s)
            path.lineTo(13 * s, 31 * s)
            path.lineTo(20 * s, 47 * s)
            path.lineTo(27 * s, 44 * s)
            path.lineTo(20 * s, 27 * s)
            path.lineTo(33 * s, 27 * s)
            path.close()
            canvas.drawPath(path, fill)
            canvas.drawPath(path, stroke)
        }
    }
}
