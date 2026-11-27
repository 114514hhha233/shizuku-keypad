package com.example.shizukukeypad

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvDetail: TextView
    private lateinit var tvKeys: TextView
    private lateinit var btnAuth: Button
    private lateinit var btnOverlayPerm: Button
    private lateinit var swOverlay: MaterialSwitch
    private lateinit var dotShizuku: View
    private lateinit var dotOverlay: View

    /** 程序刷新开关状态时，避免回调再次触发显示/隐藏 */
    private var syncingOverlaySwitch = false

    private val ui = Handler(Looper.getMainLooper())

    /** SAF 文件选择请求码 */
    private val REQ_EXPORT_FILE = 4101
    private val REQ_IMPORT_FILE = 4102

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener {
        ShizukuInjector.reset()
        refresh()
    }
    private val permissionResult =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            toast(getString(
                if (grantResult == PackageManager.PERMISSION_GRANTED) R.string.toast_shizuku_authorized
                else R.string.toast_shizuku_denied
            ))
            refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvDetail = findViewById(R.id.tvDetail)
        tvKeys = findViewById(R.id.tvKeys)
        btnAuth = findViewById(R.id.btnAuth)
        btnOverlayPerm = findViewById(R.id.btnOverlayPerm)
        swOverlay = findViewById(R.id.swOverlay)
        dotShizuku = findViewById(R.id.dotShizuku)
        dotOverlay = findViewById(R.id.dotOverlay)

        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)

        btnAuth.setOnClickListener { requestShizuku() }
        btnOverlayPerm.setOnClickListener { requestOverlayPermission() }
        findViewById<Button>(R.id.btnOverlaySettings).setOnClickListener { requestOverlayPermission() }
        findViewById<Button>(R.id.btnReset).setOnClickListener { resetLayout() }
        findViewById<View>(R.id.btnAbout).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        findViewById<View>(R.id.btnHelp).setOnClickListener {
            startActivity(Intent(this, HelpActivity::class.java))
        }
        // 实验模块入口（整块可摘除）
        findViewById<View>(R.id.btnMouseProbe).setOnClickListener {
            startActivity(Intent(this, MouseProbeActivity::class.java))
        }
        findViewById<TextView>(R.id.aboutLink).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        swOverlay.setOnCheckedChangeListener { _, checked ->
            if (syncingOverlaySwitch) return@setOnCheckedChangeListener
            if (checked == OverlayService.overlayShowing) return@setOnCheckedChangeListener
            toggleOverlay()
            ui.postDelayed({ refresh() }, 400)
        }
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { findViewById<TextView>(R.id.aboutLink).text = getString(R.string.about_link_with_version, "v$it") }
        setupAlphaSlider()
        setupMouseGainSlider()
        setupCloudMouseSwitch()
        setupBlockImeSwitch()
        setupPresetButtons()

        requestNotificationPermission()
        if (!OverlayService.overlayShowing) ImeGuard.recoverIfNeeded(this)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        // 控制菜单被长按隐藏后，重新打开应用就恢复显示
        if (OverlayService.overlayShowing) {
            OverlayService.send(this, OverlayService.ACTION_SHOW_BAR)
        }
        refresh()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        super.onDestroy()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    // ==================== 状态 ====================

    private fun shizukuRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    private fun shizukuGranted(): Boolean = runCatching {
        shizukuRunning() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun refresh() {
        val running = shizukuRunning()
        val granted = shizukuGranted()
        tvStatus.text = getString(
            when {
                !running -> R.string.state_not_running
                !granted -> R.string.state_not_authorized
                else -> R.string.state_authorized
            }
        )
        setDot(dotShizuku, granted)

        val overlayAllowed = Settings.canDrawOverlays(this)
        tvDetail.text = getString(
            if (overlayAllowed) R.string.state_granted else R.string.state_not_granted
        )
        setDot(dotOverlay, overlayAllowed)

        btnAuth.visibility = if (running && !granted) View.VISIBLE else View.GONE
        btnOverlayPerm.visibility = if (!overlayAllowed) View.VISIBLE else View.GONE

        val showing = OverlayService.overlayShowing
        syncingOverlaySwitch = true
        swOverlay.isChecked = showing
        syncingOverlaySwitch = false

        val saved = KeyStore.loadKeys(this).size
        tvKeys.text = if (saved == 0) getString(R.string.keys_count_default)
        else getString(R.string.keys_count_saved, saved)
    }

    private fun setDot(dot: View, ok: Boolean) {
        dot.backgroundTintList = ColorStateList.valueOf(
            Color.parseColor(if (ok) "#2FA36B" else "#E05C5C")
        )
    }

    // ==================== 权限 ====================

    private fun requestShizuku() {
        if (!shizukuRunning()) {
            toast(getString(R.string.toast_install_shizuku))
            return
        }
        if (Shizuku.isPreV11() || Shizuku.getVersion() < 11) {
            toast(getString(R.string.toast_shizuku_version_low))
            return
        }
        when {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ->
                toast(getString(R.string.toast_already_authorized))
            Shizuku.shouldShowRequestPermissionRationale() ->
                toast(getString(R.string.toast_allow_in_shizuku))
            else -> Shizuku.requestPermission(0)
        }
    }

    private fun requestOverlayPermission() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    // ==================== 悬浮层 ====================

    private fun toggleOverlay() {
        if (OverlayService.overlayShowing) {
            OverlayService.send(this, OverlayService.ACTION_HIDE)
            ui.postDelayed({ refresh() }, 300)
            return
        }
        if (!shizukuGranted()) {
            toast(getString(R.string.toast_need_shizuku_auth))
            requestShizuku()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast(getString(R.string.toast_need_overlay))
            requestOverlayPermission()
            return
        }
        OverlayService.send(this, OverlayService.ACTION_SHOW)
        toast(getString(R.string.toast_keys_shown))
        ui.postDelayed({ refresh() }, 300)
    }

    private fun resetLayout() {
        if (OverlayService.overlayShowing) {
            OverlayService.send(this, OverlayService.ACTION_RESET)
        } else {
            KeyStore.clear(this)
            toast(getString(R.string.toast_layout_cleared))
        }
        ui.postDelayed({ refresh() }, 300)
    }

    private fun setupAlphaSlider() {
        val slider = findViewById<Slider>(R.id.seekAlpha)
        val label = findViewById<TextView>(R.id.tvAlpha)
        val saved = KeyStore.loadAlpha(this).coerceIn(30, 100)
        slider.value = saved.toFloat()
        label.text = getString(R.string.alpha_label, saved)
        slider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val v = value.roundToInt()
            label.text = getString(R.string.alpha_label, v)
            KeyStore.saveAlpha(this@MainActivity, v)
            OverlayService.current?.setKeyAlpha(v)
        }
    }

    private fun setupMouseGainSlider() {
        val slider = findViewById<Slider>(R.id.seekMouseGain)
        val label = findViewById<TextView>(R.id.tvMouseGain)
        val saved = KeyStore.loadMouseGain(this).coerceIn(50, 250)
        slider.value = saved.toFloat()
        label.text = getString(R.string.mouse_gain_label, saved)
        slider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val v = value.roundToInt()
            label.text = getString(R.string.mouse_gain_label, v)
            KeyStore.saveMouseGain(this@MainActivity, v)
            OverlayService.current?.setMouseGain(v)
        }
    }

    /** 云游戏鼠标：坐标直通 + 左键长按按住。开着时灵敏度滑条不再起作用 */
    private fun setupCloudMouseSwitch() {
        val sw = findViewById<MaterialSwitch>(R.id.swCloudMouse)
        val gainSlider = findViewById<Slider>(R.id.seekMouseGain)
        val saved = KeyStore.loadCloudMouse(this)
        sw.isChecked = saved
        gainSlider.isEnabled = !saved
        sw.setOnCheckedChangeListener { _, checked ->
            KeyStore.saveCloudMouse(this, checked)
            gainSlider.isEnabled = !checked
            if (OverlayService.overlayShowing) {
                // 浮层在跑：交给它自己弹提示，切换立即生效
                OverlayService.current?.setCloudMouse(checked)
            } else {
                toast(getString(
                    if (checked) R.string.toast_cloud_mouse_on else R.string.toast_cloud_mouse_off
                ))
            }
        }
    }

    private fun setupBlockImeSwitch() {
        val sw = findViewById<MaterialSwitch>(R.id.swBlockIme)
        sw.isChecked = KeyStore.loadBlockIme(this)
        sw.setOnCheckedChangeListener { _, checked ->
            KeyStore.saveBlockIme(this, checked)
            when {
                !checked -> {
                    ImeGuard.exit(this)
                    toast(getString(R.string.toast_ime_disabled))
                }
                OverlayService.overlayShowing -> {
                    ImeGuard.enter(this)
                    toast(getString(R.string.toast_ime_enabled))
                }
                else -> toast(getString(R.string.toast_ime_enabled_later))
            }
        }
    }

    // ==================== 配置预设 ====================

    private fun setupPresetButtons() {
        findViewById<Button>(R.id.btnPresetSave).setOnClickListener { savePresetDialog() }
        findViewById<Button>(R.id.btnPresetLoad).setOnClickListener { loadPresetDialog() }
        findViewById<Button>(R.id.btnConfigExport).setOnClickListener { exportProfileFile() }
        findViewById<Button>(R.id.btnConfigImport).setOnClickListener { importProfileFile() }
    }

    private fun savePresetDialog() {
        val current = KeyStore.loadKeys(this)
        if (current.isEmpty()) {
            toast(getString(R.string.toast_no_keys))
            return
        }
        val et = EditText(this).apply {
            hint = getString(R.string.preset_name_hint)
            setSingleLine()
            setText(getString(R.string.preset_default_name, KeyStore.presetNames(this@MainActivity).size + 1))
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(et)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_save_current_keys)
            .setView(box)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val name = et.text.toString().trim()
                if (name.isEmpty()) {
                    toast(getString(R.string.toast_name_empty))
                    return@setPositiveButton
                }
                KeyStore.savePreset(this, name, current)
                toast(getString(R.string.toast_preset_saved, name))
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun loadPresetDialog() {
        val names = KeyStore.presetNames(this)
        if (names.isEmpty()) {
            toast(getString(R.string.toast_no_presets))
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_load_preset)
            .setItems(names.toTypedArray()) { _, which -> applyPreset(names[which]) }
            .setNeutralButton(R.string.dialog_delete_preset) { _, _ -> deletePresetDialog(names) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun deletePresetDialog(names: List<String>) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_delete_preset)
            .setItems(names.toTypedArray()) { _, which ->
                KeyStore.deletePreset(this, names[which])
                toast(getString(R.string.toast_preset_deleted, names[which]))
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun applyPreset(name: String) {
        val loaded = KeyStore.loadPreset(this, name)
        if (loaded.isEmpty()) {
            toast(getString(R.string.toast_preset_empty))
            return
        }
        KeyStore.saveKeys(this, loaded)
        reloadOverlay()
        toast(getString(R.string.toast_preset_loaded, name))
        refresh()
    }

    // ==================== 布局文件导入 / 导出 ====================

    private fun exportProfileFile() {
        if (KeyStore.loadKeys(this).isEmpty()) {
            toast(getString(R.string.toast_nothing_to_export))
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "hjm-keypad-layout-$stamp.json")
        }
        try {
            startActivityForResult(intent, REQ_EXPORT_FILE)
        } catch (t: Throwable) {
            toast(getString(R.string.toast_no_file_picker))
        }
    }

    private fun importProfileFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/json", "text/plain", "application/octet-stream")
            )
        }
        try {
            startActivityForResult(intent, REQ_IMPORT_FILE)
        } catch (t: Throwable) {
            toast(getString(R.string.toast_no_file_picker))
        }
    }

    @Deprecated("用 SAF 选择文件，不需要运行时存储权限")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_EXPORT_FILE -> writeProfile(uri)
            REQ_IMPORT_FILE -> readProfileForImport(uri)
        }
    }

    private fun writeProfile(uri: Uri) {
        try {
            val json = KeyStore.exportProfile(this)
            contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
                out.flush()
            } ?: throw IllegalStateException("no stream")
            val name = queryDisplayName(uri) ?: "hjm-keypad-layout.json"
            toast(getString(R.string.toast_exported_file, name))
        } catch (t: Throwable) {
            toast(getString(R.string.toast_export_failed, t.javaClass.simpleName))
        }
    }

    private fun readProfileForImport(uri: Uri) {
        val text = try {
            contentResolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(64 * 1024)
                val sb = StringBuilder()
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    total += n
                    if (total > 2 * 1024 * 1024) throw IllegalStateException("too large")
                    sb.append(String(buf, 0, n, Charsets.UTF_8))
                }
                sb.toString()
            }
        } catch (t: Throwable) {
            null
        }
        if (text.isNullOrBlank()) {
            toast(getString(R.string.toast_import_read_failed))
            return
        }
        val preview = KeyStore.previewProfile(text)
        if (preview == null) {
            toast(getString(R.string.toast_invalid_config_file))
            return
        }
        val extra = buildString {
            if (preview.hasSettings) append(getString(R.string.import_includes_settings))
            if (preview.presetCount > 0) {
                append(getString(R.string.import_includes_presets, preview.presetCount))
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_import_file)
            .setMessage(getString(R.string.dialog_import_file_message, preview.keyCount, extra))
            .setPositiveButton(R.string.action_import) { _, _ ->
                val n = KeyStore.importProfile(this, text)
                if (n > 0) {
                    reloadOverlay()
                    toast(getString(R.string.toast_config_imported_count, n))
                    refresh()
                } else {
                    toast(getString(R.string.toast_invalid_config_file))
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
        }
    }.getOrNull()

    private fun reloadOverlay() {
        if (OverlayService.overlayShowing) {
            OverlayService.send(this, OverlayService.ACTION_RELOAD)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
