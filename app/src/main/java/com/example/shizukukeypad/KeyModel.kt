package com.example.shizukukeypad

import android.content.Context
import android.content.res.Configuration
import android.graphics.PointF
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 按键动作类型。
 * KEY / TOUCH / JOYSTICK 分别是键盘键、触摸键与虚拟摇杆；其余是鼠标按键。
 */
enum class KeyType {
    KEY,
    TOUCH,
    JOYSTICK,
    /** 指托：只占住手势，不发任何键，用来让后续手指能被浮层接管 */
    ANCHOR,
    MOUSE_LEFT,
    MOUSE_RIGHT,
    MOUSE_MIDDLE,
    WHEEL_UP,
    WHEEL_DOWN;

    val isMouse: Boolean
        get() = this == MOUSE_LEFT || this == MOUSE_RIGHT || this == MOUSE_MIDDLE ||
            this == WHEEL_UP || this == WHEEL_DOWN
}

/**
 * 一个虚拟按键。
 * 位置用屏幕比例（0..1）保存，旋转屏幕、换分辨率都不会错位。
 */
data class KeyItem(
    var id: Long,
    var cx: Float,
    var cy: Float,
    var label: String,
    var keyCode: Int,
    var type: KeyType = KeyType.KEY,
    /** 0 = 圆角方形，1 = 圆形 */
    var shape: Int = 0,
    /** 按键直径（dp），40..120 */
    var sizeDp: Int = 60,
    /** 一键开关：勾选后，工具栏总开关关闭时这个按键会整体隐藏 */
    var switchable: Boolean = false,
    /** 锁定：点一下保持按住，再点一下松开（仅键值/鼠标键生效） */
    var latch: Boolean = false,
    /** 宏：逗号/空格分隔的步骤，步骤内用 + 表示同时按，例如 "Ctrl+Shift+K, A" */
    var macro: String = "",
    /** 摇杆模式：0 = 键值（输出 WASD），1 = 触摸（向输出点注入手指） */
    var stickMode: Int = 0,
    var upCode: Int = KeyEvent.KEYCODE_W,
    var downCode: Int = KeyEvent.KEYCODE_S,
    var leftCode: Int = KeyEvent.KEYCODE_A,
    var rightCode: Int = KeyEvent.KEYCODE_D,
    /** 触摸模式输出点（屏幕比例）；小于 0 表示还没设置，自动放在摇杆右侧 */
    var anchorCx: Float = -1f,
    var anchorCy: Float = -1f
)

/** 键位目录中的一项；鼠标动作没有 keyCode，用 type 区分。
 *  label 保留中文原文，用于宏解析与匹配；labelRes 非 0 时优先用它显示本地化文字。 */
data class KeyOption(
    val label: String,
    val keyCode: Int,
    val type: KeyType = KeyType.KEY,
    val labelRes: Int = 0
)

/** 可选键位。顺序固定，Spinner 直接用下标对应 */
object KeyCatalog {

    /** 指托：只占住手势，不发送任何按键 */
    private val anchor = listOf(KeyOption("指托", 0, KeyType.ANCHOR, R.string.key_anchor))

    /** 游戏里最常用的 WASD，挪到最前面 */
    private val wasd = listOf("W", "A", "S", "D")
        .map { KeyOption(it, KeyEvent.KEYCODE_A + (it[0] - 'A')) }

    /** 其余字母 */
    private val otherLetters = ('A'..'Z')
        .filter { it !in "WASD" }
        .map { KeyOption("$it", KeyEvent.KEYCODE_A + (it - 'A')) }

    /** 最常用的功能键 */
    private val common = listOf(
        KeyOption("空格", KeyEvent.KEYCODE_SPACE, labelRes = R.string.key_space),
        KeyOption("回车", KeyEvent.KEYCODE_ENTER, labelRes = R.string.key_enter),
        KeyOption("Shift", KeyEvent.KEYCODE_SHIFT_LEFT),
        KeyOption("Ctrl", KeyEvent.KEYCODE_CTRL_LEFT),
        KeyOption("Alt", KeyEvent.KEYCODE_ALT_LEFT),
        KeyOption("Esc", KeyEvent.KEYCODE_ESCAPE),
        KeyOption("Tab", KeyEvent.KEYCODE_TAB),
        KeyOption("退格", KeyEvent.KEYCODE_DEL, labelRes = R.string.key_backspace)
    )

    /** 方向键 */
    private val arrows = listOf(
        KeyOption("↑", KeyEvent.KEYCODE_DPAD_UP),
        KeyOption("↓", KeyEvent.KEYCODE_DPAD_DOWN),
        KeyOption("←", KeyEvent.KEYCODE_DPAD_LEFT),
        KeyOption("→", KeyEvent.KEYCODE_DPAD_RIGHT),
        KeyOption("确定", KeyEvent.KEYCODE_DPAD_CENTER, labelRes = R.string.key_dpad_center)
    )

    private val digits = ('0'..'9').map { KeyOption("$it", KeyEvent.KEYCODE_0 + (it - '0')) }

    private val functionKeys = (1..12).map { KeyOption("F$it", KeyEvent.KEYCODE_F1 + (it - 1)) }

    private val special = listOf(
        KeyOption("Insert", KeyEvent.KEYCODE_INSERT),
        KeyOption("Delete", KeyEvent.KEYCODE_FORWARD_DEL),
        KeyOption("Home", KeyEvent.KEYCODE_MOVE_HOME),
        KeyOption("End", KeyEvent.KEYCODE_MOVE_END),
        KeyOption("PgUp", KeyEvent.KEYCODE_PAGE_UP),
        KeyOption("PgDn", KeyEvent.KEYCODE_PAGE_DOWN),
        KeyOption("CapsLock", KeyEvent.KEYCODE_CAPS_LOCK),
        KeyOption("NumLock", KeyEvent.KEYCODE_NUM_LOCK),
        KeyOption("ScrollLock", KeyEvent.KEYCODE_SCROLL_LOCK),
        KeyOption("PrintScreen", KeyEvent.KEYCODE_SYSRQ),
        KeyOption("R-Shift", KeyEvent.KEYCODE_SHIFT_RIGHT),
        KeyOption("R-Ctrl", KeyEvent.KEYCODE_CTRL_RIGHT),
        KeyOption("R-Alt", KeyEvent.KEYCODE_ALT_RIGHT),
        KeyOption("Win", KeyEvent.KEYCODE_META_LEFT),
        KeyOption("返回", KeyEvent.KEYCODE_BACK, labelRes = R.string.key_back),
        KeyOption("主页", KeyEvent.KEYCODE_HOME, labelRes = R.string.key_home),
        KeyOption("菜单", KeyEvent.KEYCODE_MENU, labelRes = R.string.key_menu),
        KeyOption("-", KeyEvent.KEYCODE_MINUS),
        KeyOption("=", KeyEvent.KEYCODE_EQUALS),
        KeyOption("[", KeyEvent.KEYCODE_LEFT_BRACKET),
        KeyOption("]", KeyEvent.KEYCODE_RIGHT_BRACKET),
        KeyOption(";", KeyEvent.KEYCODE_SEMICOLON),
        KeyOption("'", KeyEvent.KEYCODE_APOSTROPHE),
        KeyOption(",", KeyEvent.KEYCODE_COMMA),
        KeyOption(".", KeyEvent.KEYCODE_PERIOD),
        KeyOption("/", KeyEvent.KEYCODE_SLASH),
        KeyOption("\\", KeyEvent.KEYCODE_BACKSLASH),
        KeyOption("`", KeyEvent.KEYCODE_GRAVE)
    )

    /** 小键盘，远程桌面/云电脑常用 */
    private val numpad = listOf(
        KeyOption("小键盘0", KeyEvent.KEYCODE_NUMPAD_0, labelRes = R.string.key_numpad_0),
        KeyOption("小键盘1", KeyEvent.KEYCODE_NUMPAD_1, labelRes = R.string.key_numpad_1),
        KeyOption("小键盘2", KeyEvent.KEYCODE_NUMPAD_2, labelRes = R.string.key_numpad_2),
        KeyOption("小键盘3", KeyEvent.KEYCODE_NUMPAD_3, labelRes = R.string.key_numpad_3),
        KeyOption("小键盘4", KeyEvent.KEYCODE_NUMPAD_4, labelRes = R.string.key_numpad_4),
        KeyOption("小键盘5", KeyEvent.KEYCODE_NUMPAD_5, labelRes = R.string.key_numpad_5),
        KeyOption("小键盘6", KeyEvent.KEYCODE_NUMPAD_6, labelRes = R.string.key_numpad_6),
        KeyOption("小键盘7", KeyEvent.KEYCODE_NUMPAD_7, labelRes = R.string.key_numpad_7),
        KeyOption("小键盘8", KeyEvent.KEYCODE_NUMPAD_8, labelRes = R.string.key_numpad_8),
        KeyOption("小键盘9", KeyEvent.KEYCODE_NUMPAD_9, labelRes = R.string.key_numpad_9),
        KeyOption("小键盘+", KeyEvent.KEYCODE_NUMPAD_ADD, labelRes = R.string.key_numpad_add),
        KeyOption("小键盘-", KeyEvent.KEYCODE_NUMPAD_SUBTRACT, labelRes = R.string.key_numpad_subtract),
        KeyOption("小键盘*", KeyEvent.KEYCODE_NUMPAD_MULTIPLY, labelRes = R.string.key_numpad_multiply),
        KeyOption("小键盘/", KeyEvent.KEYCODE_NUMPAD_DIVIDE, labelRes = R.string.key_numpad_divide),
        KeyOption("小键盘.", KeyEvent.KEYCODE_NUMPAD_DOT, labelRes = R.string.key_numpad_dot),
        KeyOption("小键盘回车", KeyEvent.KEYCODE_NUMPAD_ENTER, labelRes = R.string.key_numpad_enter)
    )

    /** 自定义键值：keyCode 为 -1 时在对话框里手动输入 */
    private val custom = listOf(KeyOption("自定义键值…", -1, labelRes = R.string.key_custom))

    private val mouse = listOf(
        KeyOption("鼠标左键", 0, KeyType.MOUSE_LEFT, R.string.key_mouse_left),
        KeyOption("鼠标右键", 0, KeyType.MOUSE_RIGHT, R.string.key_mouse_right),
        KeyOption("鼠标中键", 0, KeyType.MOUSE_MIDDLE, R.string.key_mouse_middle),
        KeyOption("滚轮上", 0, KeyType.WHEEL_UP, R.string.key_wheel_up),
        KeyOption("滚轮下", 0, KeyType.WHEEL_DOWN, R.string.key_wheel_down)
    )

    /** 虚拟摇杆：键值模式输出 WASD，触摸模式在输出点注入手指 */
    private val joystick = listOf(KeyOption("虚拟摇杆", 0, KeyType.JOYSTICK, R.string.key_joystick))

    val all: List<KeyOption> by lazy {
        anchor + joystick + wasd + common + mouse + otherLetters + digits + arrows +
            functionKeys + special + numpad + custom
    }

    val customIndex: Int by lazy { all.indexOfFirst { it.keyCode < 0 } }

    /** 目录项的显示文字：有 labelRes 时跟随系统语言，否则用原始 label（字母、数字、符号等） */
    fun displayLabel(ctx: Context, option: KeyOption): String =
        if (option.labelRes != 0) ctx.getString(option.labelRes) else option.label

    fun labelOf(ctx: Context, keyCode: Int): String =
        all.firstOrNull { it.keyCode == keyCode }?.let { displayLabel(ctx, it) }
            ?: ctx.getString(R.string.key_fallback, keyCode)

    /** 某个键码/动作在目录中的默认显示名（跟随系统语言） */
    fun defaultLabel(ctx: Context, keyCode: Int, type: KeyType = KeyType.KEY): String {
        val option = findOption(keyCode, type)
        return option?.let { displayLabel(ctx, it) }
            ?: ctx.getString(R.string.key_fallback, keyCode)
    }

    fun isDefaultLabel(
        ctx: Context,
        keyCode: Int,
        text: String,
        type: KeyType = KeyType.KEY
    ): Boolean {
        val option = findOption(keyCode, type) ?: return false
        if (text == option.label) return true
        if (option.labelRes == 0) return false
        for (locale in listOf(Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE)) {
            val config = Configuration(ctx.resources.configuration).apply { setLocale(locale) }
            if (text == ctx.createConfigurationContext(config).getString(option.labelRes)) return true
        }
        return false
    }

    /** 鼠标类动作的 keyCode 都是 0，只能按 type 匹配 */
    private fun findOption(keyCode: Int, type: KeyType): KeyOption? =
        all.firstOrNull { it.type == type && (type != KeyType.KEY || it.keyCode == keyCode) }

    private val aliases = mapOf(
        "ctrl" to KeyEvent.KEYCODE_CTRL_LEFT,
        "control" to KeyEvent.KEYCODE_CTRL_LEFT,
        "shift" to KeyEvent.KEYCODE_SHIFT_LEFT,
        "alt" to KeyEvent.KEYCODE_ALT_LEFT,
        "option" to KeyEvent.KEYCODE_ALT_LEFT,
        "win" to KeyEvent.KEYCODE_META_LEFT,
        "meta" to KeyEvent.KEYCODE_META_LEFT,
        "cmd" to KeyEvent.KEYCODE_META_LEFT,
        "enter" to KeyEvent.KEYCODE_ENTER,
        "return" to KeyEvent.KEYCODE_ENTER,
        "space" to KeyEvent.KEYCODE_SPACE,
        "esc" to KeyEvent.KEYCODE_ESCAPE,
        "escape" to KeyEvent.KEYCODE_ESCAPE,
        "tab" to KeyEvent.KEYCODE_TAB,
        "backspace" to KeyEvent.KEYCODE_DEL,
        "del" to KeyEvent.KEYCODE_FORWARD_DEL,
        "delete" to KeyEvent.KEYCODE_FORWARD_DEL,
        "ins" to KeyEvent.KEYCODE_INSERT,
        "up" to KeyEvent.KEYCODE_DPAD_UP,
        "down" to KeyEvent.KEYCODE_DPAD_DOWN,
        "left" to KeyEvent.KEYCODE_DPAD_LEFT,
        "right" to KeyEvent.KEYCODE_DPAD_RIGHT,
        "pgup" to KeyEvent.KEYCODE_PAGE_UP,
        "pageup" to KeyEvent.KEYCODE_PAGE_UP,
        "pgdn" to KeyEvent.KEYCODE_PAGE_DOWN,
        "pagedown" to KeyEvent.KEYCODE_PAGE_DOWN,
        "空格" to KeyEvent.KEYCODE_SPACE,
        "回车" to KeyEvent.KEYCODE_ENTER,
        "退格" to KeyEvent.KEYCODE_DEL,
        "删除" to KeyEvent.KEYCODE_FORWARD_DEL,
        "上" to KeyEvent.KEYCODE_DPAD_UP,
        "下" to KeyEvent.KEYCODE_DPAD_DOWN,
        "左" to KeyEvent.KEYCODE_DPAD_LEFT,
        "右" to KeyEvent.KEYCODE_DPAD_RIGHT
    )

    /** 把宏里的一个记号解析成键值：支持标签、英文别名、1..400 的原始键值 */
    fun resolveToken(token: String): Int? {
        val t = token.trim()
        if (t.isEmpty()) return null
        t.toIntOrNull()?.let { if (it in 1..400) return it }
        val low = t.lowercase()
        aliases[low]?.let { return it }
        return all.firstOrNull { it.keyCode > 0 && it.label.lowercase() == low }?.keyCode
    }
}

/** 宏文本解析：逗号/空格分隔步骤，步骤内 + 表示同时按 */
object MacroParser {

    fun parse(text: String): List<List<Int>> =
        text.split(Regex("[,，\\s]+"))
            .filter { it.isNotBlank() }
            .mapNotNull { step ->
                val codes = step.split('+', '＋')
                    .mapNotNull { KeyCatalog.resolveToken(it) }
                    .distinct()
                if (codes.isEmpty()) null else codes
            }
}

/**
 * 本地持久化。全部用 SharedPreferences + JSON，卸载前一直保留。
 * 位置一律存比例，不存像素。
 */
object KeyStore {

    private const val PREF = "keypad"
    private const val K_KEYS = "keys"
    private const val K_CURSOR = "cursor"
    private const val K_BAR = "bar"
    private const val K_SEEDED = "seeded"
    private const val K_ALPHA = "alpha"
    private const val K_ONE_KEY = "one_key_on"
    private const val K_HOT_MODE = "hot_mode"
    private const val K_MOUSE_GAIN = "mouse_gain"
    private const val K_CLOUD_MOUSE = "cloud_mouse"
    private const val K_GAME_KEYBOARD = "game_keyboard"
    private const val K_PANEL_WIDTH = "panel_width_dp"
    private const val K_BLOCK_IME = "block_ime"
    private const val K_PRESETS = "presets"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun isSeeded(ctx: Context): Boolean = sp(ctx).getBoolean(K_SEEDED, false)

    fun markSeeded(ctx: Context) {
        sp(ctx).edit().putBoolean(K_SEEDED, true).apply()
    }

    fun loadKeys(ctx: Context): MutableList<KeyItem> {
        val raw = sp(ctx).getString(K_KEYS, null) ?: return mutableListOf()
        return parseKeys(raw)
    }

    fun saveKeys(ctx: Context, keys: List<KeyItem>) {
        sp(ctx).edit().putString(K_KEYS, keysToJson(keys).toString()).apply()
    }

    private fun parseKeys(raw: String): MutableList<KeyItem> {
        val out = mutableListOf<KeyItem>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val type = runCatching { KeyType.valueOf(o.optString("type")) }
                    .getOrDefault(KeyType.KEY)
                var upCode = o.optInt("upCode", KeyEvent.KEYCODE_W)
                var downCode = o.optInt("downCode", KeyEvent.KEYCODE_S)
                var leftCode = o.optInt("leftCode", KeyEvent.KEYCODE_A)
                var rightCode = o.optInt("rightCode", KeyEvent.KEYCODE_D)
                // 旧版本摇杆默认输出方向键，游戏里会当成视角键；
                // 四个码刚好是旧默认值时自动迁移到 WASD，用户自定义过的不动。
                if (type == KeyType.JOYSTICK &&
                    upCode == KeyEvent.KEYCODE_DPAD_UP && downCode == KeyEvent.KEYCODE_DPAD_DOWN &&
                    leftCode == KeyEvent.KEYCODE_DPAD_LEFT && rightCode == KeyEvent.KEYCODE_DPAD_RIGHT
                ) {
                    upCode = KeyEvent.KEYCODE_W
                    downCode = KeyEvent.KEYCODE_S
                    leftCode = KeyEvent.KEYCODE_A
                    rightCode = KeyEvent.KEYCODE_D
                }
                out.add(
                    KeyItem(
                        id = o.optLong("id", i + 1L),
                        cx = o.optDouble("cx", 0.5).toFloat(),
                        cy = o.optDouble("cy", 0.5).toFloat(),
                        label = o.optString("label", "?"),
                        keyCode = o.optInt("keyCode", KeyEvent.KEYCODE_ENTER),
                        type = type,
                        shape = o.optInt("shape", 0),
                        sizeDp = o.optInt("sizeDp", 60).coerceIn(40, 120),
                        switchable = o.optBoolean("switchable", false),
                        latch = o.optBoolean("latch", false),
                        macro = o.optString("macro", ""),
                        stickMode = o.optInt("stickMode", 0).coerceIn(0, 2),
                        upCode = upCode,
                        downCode = downCode,
                        leftCode = leftCode,
                        rightCode = rightCode,
                        anchorCx = o.optDouble("anchorCx", -1.0).toFloat(),
                        anchorCy = o.optDouble("anchorCy", -1.0).toFloat()
                    )
                )
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        }
        return out
    }

    private fun keysToJson(keys: List<KeyItem>): JSONArray {
        val arr = JSONArray()
        keys.forEach { k ->
            arr.put(
                JSONObject()
                    .put("id", k.id)
                    .put("cx", k.cx.toDouble())
                    .put("cy", k.cy.toDouble())
                    .put("label", k.label)
                    .put("keyCode", k.keyCode)
                    .put("type", k.type.name)
                    .put("shape", k.shape)
                    .put("sizeDp", k.sizeDp)
                    .put("switchable", k.switchable)
                    .put("latch", k.latch)
                    .put("macro", k.macro)
                    .put("stickMode", k.stickMode)
                    .put("upCode", k.upCode)
                    .put("downCode", k.downCode)
                    .put("leftCode", k.leftCode)
                    .put("rightCode", k.rightCode)
                    .put("anchorCx", k.anchorCx.toDouble())
                    .put("anchorCy", k.anchorCy.toDouble())
            )
        }
        return arr
    }

    // ==================== 按键预设 ====================

    /** 保存当前全部按键为一个命名预设 */
    fun savePreset(ctx: Context, name: String, keys: List<KeyItem>) {
        val all = presetsJson(ctx)
        all.put(name, keysToJson(keys).toString())
        sp(ctx).edit().putString(K_PRESETS, all.toString()).apply()
    }

    fun presetNames(ctx: Context): List<String> {
        val all = presetsJson(ctx)
        return all.keys().asSequence().toList().sorted()
    }

    fun loadPreset(ctx: Context, name: String): MutableList<KeyItem> {
        val raw = presetsJson(ctx).optString(name, null) ?: return mutableListOf()
        return parseKeys(raw)
    }

    fun deletePreset(ctx: Context, name: String) {
        val all = presetsJson(ctx)
        all.remove(name)
        sp(ctx).edit().putString(K_PRESETS, all.toString()).apply()
    }

    // ==================== 整份布局档案（文件导入导出） ====================

    /** 档案格式标识，写在文件头，导入时用来认领 */
    const val PROFILE_FORMAT = "hjm-keypad-layout"

    /** 档案版本：1 = 只有按键数组（旧版剪贴板导出），2 = 按键 + 外观 + 鼠标参数 + 预设 */
    const val PROFILE_VERSION = 2

    /** 导入前的概览，给确认框用 */
    data class ProfilePreview(
        val version: Int,
        val keyCount: Int,
        val presetCount: Int,
        val hasSettings: Boolean
    )

    /** 把整份布局（按键 + 工具栏位置 + 外观/鼠标参数 + 预设）打成一个可存档的 JSON 文件 */
    fun exportProfile(ctx: Context): String {
        val root = JSONObject()
        root.put("format", PROFILE_FORMAT)
        root.put("version", PROFILE_VERSION)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("keys", keysToJson(loadKeys(ctx)))
        root.put("alpha", loadAlpha(ctx))
        root.put("mouseGain", loadMouseGain(ctx))
        root.put("cloudMouse", loadCloudMouse(ctx))
        root.put("hotMode", loadHotMode(ctx))
        root.put("oneKeyOn", loadOneKeyOn(ctx))
        root.put("gameKeyboard", loadGameKeyboard(ctx))
        root.put("panelWidthDp", loadPanelWidthDp(ctx))
        root.put("blockIme", loadBlockIme(ctx))
        val bar = loadToolbar(ctx)
        root.put(
            "toolbar",
            if (bar == null) JSONObject.NULL
            else JSONArray().put(bar.x.toDouble()).put(bar.y.toDouble())
        )
        root.put("presets", presetsJson(ctx))
        return root.toString(2)
    }

    /** 只解析档案、不落盘，用于导入前确认 */
    fun previewProfile(raw: String): ProfilePreview? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        // 旧格式兼容：整份就是一个按键数组
        if (text.startsWith("[")) {
            val n = sanitizeImport(text).size
            return if (n > 0) ProfilePreview(1, n, 0, false) else null
        }
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val arr = root.optJSONArray("keys") ?: return null
        val n = sanitizeImport(arr.toString()).size
        if (n == 0) return null
        val presets = root.optJSONObject("presets")?.length() ?: 0
        val hasSettings = root.has("alpha") || root.has("mouseGain") || root.has("hotMode") ||
            root.has("cloudMouse") ||
            root.has("panelWidthDp") || root.has("toolbar")
        return ProfilePreview(root.optInt("version", PROFILE_VERSION), n, presets, hasSettings)
    }

    /** 导入整份布局档案；返回导入的按键数，0 = 文件无效（原配置不动） */
    fun importProfile(ctx: Context, raw: String): Int {
        val text = raw.trim()
        if (text.isEmpty()) return 0
        val keysJson: String
        val root: JSONObject?
        if (text.startsWith("[")) {
            keysJson = text
            root = null
        } else {
            root = runCatching { JSONObject(text) }.getOrNull() ?: return 0
            keysJson = root.optJSONArray("keys")?.toString() ?: return 0
        }
        val parsed = normalizeIds(sanitizeImport(keysJson))
        if (parsed.isEmpty()) return 0
        val e = sp(ctx).edit()
        e.putString(K_KEYS, keysToJson(parsed).toString())
        root?.let { o ->
            // 外观与鼠标参数一起带过来，换机/分享时才是一份「完整布局」
            if (o.has("alpha")) e.putInt(K_ALPHA, o.optInt("alpha", 100).coerceIn(30, 100))
            if (o.has("mouseGain")) e.putInt(K_MOUSE_GAIN, o.optInt("mouseGain", 150).coerceIn(50, 250))
            if (o.has("cloudMouse")) e.putBoolean(K_CLOUD_MOUSE, o.optBoolean("cloudMouse", false))
            if (o.has("hotMode")) e.putBoolean(K_HOT_MODE, o.optBoolean("hotMode", true))
            if (o.has("oneKeyOn")) e.putBoolean(K_ONE_KEY, o.optBoolean("oneKeyOn", true))
            if (o.has("gameKeyboard")) e.putBoolean(K_GAME_KEYBOARD, o.optBoolean("gameKeyboard", false))
            if (o.has("panelWidthDp")) {
                e.putInt(K_PANEL_WIDTH, o.optInt("panelWidthDp", 0).coerceIn(200, 1400))
            }
            if (o.has("blockIme")) e.putBoolean(K_BLOCK_IME, o.optBoolean("blockIme", false))
            o.optJSONArray("toolbar")?.let { t ->
                val x = t.optDouble(0, -1.0).toFloat()
                val y = t.optDouble(1, -1.0).toFloat()
                if (x in 0f..1f && y in 0f..1f) {
                    e.putString(K_BAR, JSONArray().put(x.toDouble()).put(y.toDouble()).toString())
                }
            }
            o.optJSONObject("presets")?.let { p -> e.putString(K_PRESETS, p.toString()) }
        }
        e.apply()
        return parsed.size
    }

    /** 把重复 / 非法 id 重新编号 */
    private fun normalizeIds(keys: List<KeyItem>): List<KeyItem> {
        val used = HashSet<Long>()
        var next = (keys.maxOfOrNull { it.id } ?: 0L) + 1
        keys.forEach { k ->
            if (k.id <= 0L || !used.add(k.id)) {
                k.id = next++
                used.add(k.id)
            }
        }
        return keys
    }

    /** 当前按键配置导出成可复制的 JSON */
    fun exportKeys(ctx: Context): String =
        sp(ctx).getString(K_KEYS, "[]") ?: "[]"

    /**
     * 从 JSON 导入按键配置。成功返回导入数量，失败返回 0（原配置保持不变）。
     * 坐标超出 0..1 或没有标签的条目会被丢弃；id 重复时重新编号。
     */
    fun importKeys(ctx: Context, raw: String): Int {
        val parsed = normalizeIds(sanitizeImport(raw))
        if (parsed.isEmpty()) return 0
        sp(ctx).edit().putString(K_KEYS, keysToJson(parsed).toString()).apply()
        return parsed.size
    }

    /** 只统计能导入的按键数量，不写入（用于导入前确认） */
    fun importableCount(raw: String): Int = sanitizeImport(raw).size

    private fun sanitizeImport(raw: String): List<KeyItem> =
        parseKeys(raw.trim()).filter {
            it.label.isNotBlank() && it.cx in 0f..1f && it.cy in 0f..1f
        }

    private fun presetsJson(ctx: Context): JSONObject =
        runCatching { JSONObject(sp(ctx).getString(K_PRESETS, "{}") ?: "{}") }
            .getOrDefault(JSONObject())

    fun loadCursor(ctx: Context): PointF {
        val raw = sp(ctx).getString(K_CURSOR, null) ?: return PointF(0.5f, 0.5f)
        return try {
            val a = JSONArray(raw)
            PointF(a.getDouble(0).toFloat(), a.getDouble(1).toFloat())
        } catch (t: Throwable) {
            PointF(0.5f, 0.5f)
        }
    }

    fun saveCursor(ctx: Context, x: Float, y: Float) {
        sp(ctx).edit().putString(K_CURSOR, JSONArray().put(x.toDouble()).put(y.toDouble()).toString()).apply()
    }

    fun loadToolbar(ctx: Context): PointF? {
        val raw = sp(ctx).getString(K_BAR, null) ?: return null
        return try {
            val a = JSONArray(raw)
            PointF(a.getDouble(0).toFloat(), a.getDouble(1).toFloat())
        } catch (t: Throwable) {
            null
        }
    }

    fun saveToolbar(ctx: Context, x: Float, y: Float) {
        sp(ctx).edit().putString(K_BAR, JSONArray().put(x.toDouble()).put(y.toDouble()).toString()).apply()
    }

    /** 按键不透明度，30..100 */
    fun loadAlpha(ctx: Context): Int = sp(ctx).getInt(K_ALPHA, 100).coerceIn(30, 100)

    fun saveAlpha(ctx: Context, value: Int) {
        sp(ctx).edit().putInt(K_ALPHA, value.coerceIn(30, 100)).apply()
    }

    /** 鼠标拖动灵敏度，50..250（百分比） */
    fun loadMouseGain(ctx: Context): Int = sp(ctx).getInt(K_MOUSE_GAIN, 150).coerceIn(50, 250)

    fun saveMouseGain(ctx: Context, value: Int) {
        sp(ctx).edit().putInt(K_MOUSE_GAIN, value.coerceIn(50, 250)).apply()
    }

    /**
     * 云游戏鼠标：坐标直通（不做相对累积、不做灵敏度放大），
     * 并且鼠标左键支持「长按按住」，用来在云游戏里按住开火同时滑动。
     */
    fun loadCloudMouse(ctx: Context): Boolean = sp(ctx).getBoolean(K_CLOUD_MOUSE, false)

    fun saveCloudMouse(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean(K_CLOUD_MOUSE, value).apply()
    }

    /** 一键开关总开关：默认打开 */
    fun loadOneKeyOn(ctx: Context): Boolean = sp(ctx).getBoolean(K_ONE_KEY, true)

    fun saveOneKeyOn(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean(K_ONE_KEY, value).apply()
    }

    /** 多点触控总开关：true = 所有按键穿透触摸，靠旁听触摸屏坐标触发 */
    fun loadHotMode(ctx: Context): Boolean = sp(ctx).getBoolean(K_HOT_MODE, false)

    fun saveHotMode(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean(K_HOT_MODE, value).apply()
    }

    /** 整块游戏键盘开关 */
    fun loadGameKeyboard(ctx: Context): Boolean = sp(ctx).getBoolean(K_GAME_KEYBOARD, false)

    fun saveGameKeyboard(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean(K_GAME_KEYBOARD, value).apply()
    }

    /** 整块键盘宽度（dp），0 表示用默认宽度 */
    fun loadPanelWidthDp(ctx: Context): Int = sp(ctx).getInt(K_PANEL_WIDTH, 0)

    fun savePanelWidthDp(ctx: Context, widthDp: Int) {
        sp(ctx).edit().putInt(K_PANEL_WIDTH, widthDp.coerceIn(200, 1400)).apply()
    }

    /** 拦截系统输入法：只在显示虚拟按键期间生效 */
    fun loadBlockIme(ctx: Context): Boolean = sp(ctx).getBoolean(K_BLOCK_IME, false)

    fun saveBlockIme(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean(K_BLOCK_IME, value).apply()
    }

    fun clear(ctx: Context) {
        sp(ctx).edit().remove(K_KEYS).remove(K_CURSOR).remove(K_BAR).remove(K_PANEL_WIDTH).apply()
    }
}
