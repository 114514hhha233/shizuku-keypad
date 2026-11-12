package com.example.shizukukeypad

import android.content.Context
import android.os.ParcelFileDescriptor
import rikka.shizuku.Shizuku
import moe.shizuku.server.IShizukuService
import java.util.concurrent.Executors

/**
 * 用 Shizuku 的 shell 权限临时把默认输入法换成内置空输入法，避免系统输入法弹出遮挡。
 *
 * - 只在“显示虚拟按键”期间生效：show() 进入，hide()/服务销毁时恢复。
 * - 进入前记住原输入法，退出时换回并禁用空输入法。
 * - 应用被强杀导致没来得及恢复时，下次打开应用会自动还原。
 */
object ImeGuard {

    private const val NOOP_IME = "com.example.shizukukeypad/.NoopImeService"
    private const val PREF = "ime_guard"
    private const val K_PREV = "prev_ime"
    private const val K_ACTIVE = "active"

    /** 串行执行，避免快速开关时 enter/exit 交叉 */
    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ime-guard").apply { isDaemon = true }
    }

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun shellGranted(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** 显示虚拟按键时调用 */
    fun enter(ctx: Context) {
        val app = ctx.applicationContext
        exec.execute {
            val p = sp(app)
            if (p.getBoolean(K_ACTIVE, false)) return@execute
            if (!shellGranted()) return@execute
            val prev = runCatching { sh("settings get secure default_input_method") }
                .getOrNull()?.second?.trim().orEmpty()
            if (prev.isEmpty() || prev == "null") return@execute
            // 上次异常退出残留：不要把自己的空输入法记成“原输入法”
            val restoreTo = if (prev.startsWith(app.packageName)) null else prev
            p.edit().putString(K_PREV, restoreTo).putBoolean(K_ACTIVE, true).apply()
            sh("ime enable $NOOP_IME")
            sh("ime set $NOOP_IME")
        }
    }

    /** 隐藏虚拟按键、关闭开关或服务销毁时调用 */
    fun exit(ctx: Context) {
        val app = ctx.applicationContext
        exec.execute {
            val p = sp(app)
            if (!p.getBoolean(K_ACTIVE, false)) return@execute
            val prev = p.getString(K_PREV, null)
            if (!prev.isNullOrBlank()) {
                val r = runCatching { sh("ime set $prev") }.getOrNull()
                if (r == null || r.first != 0) runCatching { sh("ime reset") }
            }
            sh("ime disable $NOOP_IME")
            p.edit().clear().apply()
        }
    }

    /** 上次没来得及恢复（例如应用被强杀）时，启动后补一次 */
    fun recoverIfNeeded(ctx: Context) {
        if (sp(ctx).getBoolean(K_ACTIVE, false)) exit(ctx)
    }

    /** 返回退出码和输出；Shizuku 不可用时返回 null */
    private fun sh(cmd: String): Pair<Int, String>? {
        if (!shellGranted()) return null
        val binder = Shizuku.getBinder() ?: return null
        val svc = IShizukuService.Stub.asInterface(binder)
        val proc = svc.newProcess(arrayOf("sh", "-c", "$cmd 2>&1"), null, null)
        val out = ParcelFileDescriptor.AutoCloseInputStream(proc.inputStream)
            .bufferedReader()
            .use { it.readText() }
        val code = proc.waitFor()
        runCatching { proc.destroy() }
        return code to out
    }
}
