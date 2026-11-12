package com.example.shizukukeypad

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvDetail: TextView
    private lateinit var tvKeys: TextView
    private lateinit var btnAuth: Button
    private lateinit var btnOverlayPerm: Button
    private lateinit var btnToggle: Button

    private val ui = Handler(Looper.getMainLooper())

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
        btnToggle = findViewById(R.id.btnToggle)

        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)

        btnAuth.setOnClickListener { requestShizuku() }
        btnOverlayPerm.setOnClickListener { requestOverlayPermission() }
        btnToggle.setOnClickListener { toggleOverlay() }
        findViewById<Button>(R.id.btnReset).setOnClickListener { resetLayout() }
        findViewById<TextView>(R.id.aboutLink).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { findViewById<TextView>(R.id.aboutLink).text = getString(R.string.about_link_with_version, "v$it") }
        setupAlphaSlider()
        setupMouseGainSlider()
        setupFullScreenSwitch()
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
        val granted = shizukuGranted()
        tvStatus.text = when {
            !shizukuRunning() -> getString(R.string.shizuku_not_running)
            !granted -> getString(R.string.shizuku_running_unauthorized)
            else -> getString(R.string.shizuku_authorized_status)
        }

        val overlayAllowed = Settings.canDrawOverlays(this)
        val showing = OverlayService.overlayShowing
        tvDetail.text = getString(
            R.string.status_detail,
            getString(if (granted) R.string.status_ready else R.string.status_not_ready),
            getString(
                if (overlayAllowed) R.string.status_overlay_granted
                else R.string.status_overlay_not_granted
            ),
            getString(if (showing) R.string.status_keys_showing else R.string.status_keys_not_showing)
        )

        btnAuth.visibility = if (shizukuRunning() && !granted) View.VISIBLE else View.GONE
        btnOverlayPerm.visibility = if (!overlayAllowed) View.VISIBLE else View.GONE
        btnToggle.text = getString(
            if (showing) R.string.btn_hide_virtual_keys else R.string.btn_show_virtual_keys
        )

        val saved = KeyStore.loadKeys(this).size
        tvKeys.text = if (saved == 0) getString(R.string.keys_count_default)
        else getString(R.string.keys_count_saved, saved)
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
        val seek = findViewById<SeekBar>(R.id.seekAlpha)
        val label = findViewById<TextView>(R.id.tvAlpha)
        val saved = KeyStore.loadAlpha(this)
        seek.progress = saved - 30
        label.text = getString(R.string.alpha_label, saved)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val value = progress + 30
                label.text = getString(R.string.alpha_label, value)
                KeyStore.saveAlpha(this@MainActivity, value)
                OverlayService.current?.setKeyAlpha(value)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun setupMouseGainSlider() {
        val seek = findViewById<SeekBar>(R.id.seekMouseGain)
        val label = findViewById<TextView>(R.id.tvMouseGain)
        val saved = KeyStore.loadMouseGain(this)
        seek.progress = saved - 50
        label.text = getString(R.string.mouse_gain_label, saved)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val value = progress + 50
                label.text = getString(R.string.mouse_gain_label, value)
                KeyStore.saveMouseGain(this@MainActivity, value)
                OverlayService.current?.setMouseGain(value)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun setupFullScreenSwitch() {
        val sw = findViewById<Switch>(R.id.swFullScreenMouse)
        sw.isChecked = KeyStore.loadFullScreenMouse(this)
        sw.setOnCheckedChangeListener { _, checked ->
            KeyStore.saveFullScreenMouse(this, checked)
            OverlayService.current?.setFullScreenMode(checked)
        }
    }

    private fun setupBlockImeSwitch() {
        val sw = findViewById<Switch>(R.id.swBlockIme)
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
        findViewById<Button>(R.id.btnConfigExport).setOnClickListener { exportConfig() }
        findViewById<Button>(R.id.btnConfigImport).setOnClickListener { confirmImport() }
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
        AlertDialog.Builder(this)
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
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_load_preset)
            .setItems(names.toTypedArray()) { _, which -> applyPreset(names[which]) }
            .setNeutralButton(R.string.dialog_delete_preset) { _, _ -> deletePresetDialog(names) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun deletePresetDialog(names: List<String>) {
        AlertDialog.Builder(this)
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

    private fun exportConfig() {
        val json = KeyStore.exportKeys(this)
        if (KeyStore.loadKeys(this).isEmpty()) {
            toast(getString(R.string.toast_nothing_to_export))
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.clip_label_config), json))
        toast(getString(R.string.toast_config_copied))
    }

    private fun confirmImport() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) {
            toast(getString(R.string.toast_clipboard_empty))
            return
        }
        if (KeyStore.importableCount(text) == 0) {
            toast(getString(R.string.toast_invalid_config))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_import_config)
            .setMessage(R.string.dialog_import_message)
            .setPositiveButton(R.string.action_import) { _, _ ->
                if (KeyStore.importKeys(this, text) > 0) {
                    reloadOverlay()
                    toast(getString(R.string.toast_config_imported))
                    refresh()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun reloadOverlay() {
        if (OverlayService.overlayShowing) {
            OverlayService.send(this, OverlayService.ACTION_RELOAD)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
