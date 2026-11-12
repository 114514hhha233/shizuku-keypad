package com.example.shizukukeypad

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * 前台服务承载悬浮层。
 * 放 Activity 里的话，切到游戏后 Activity 被回收按键就消失了；
 * 前台服务能保证整个游戏过程中按键都在。
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_SHOW = "com.example.shizukukeypad.SHOW"
        const val ACTION_HIDE = "com.example.shizukukeypad.HIDE"
        const val ACTION_RESET = "com.example.shizukukeypad.RESET"
        const val ACTION_SHOW_BAR = "com.example.shizukukeypad.SHOW_BAR"
        const val ACTION_RELOAD = "com.example.shizukukeypad.RELOAD"
        private const val CHANNEL_ID = "overlay"

        @Volatile
        var overlayShowing: Boolean = false
            private set

        /** 正在运行的悬浮层，供主界面实时调整透明度等外观设置 */
        @Volatile
        var current: KeypadOverlay? = null
            private set

        fun send(ctx: Context, action: String) {
            val intent = Intent(ctx, OverlayService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }
    }

    private lateinit var overlay: KeypadOverlay
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        overlay = KeypadOverlay(this)
        current = overlay
        startForeground(1, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> {
                overlay.show()
                overlayShowing = true
            }
            ACTION_RESET -> {
                if (overlayShowing) overlay.resetLayout()
            }
            ACTION_SHOW_BAR -> {
                if (overlayShowing) overlay.showToolbar()
            }
            ACTION_RELOAD -> {
                if (overlayShowing) overlay.reloadLayout()
            }
            ACTION_HIDE -> {
                overlay.hide()
                overlayShowing = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 旋转后尺寸刷新有延迟，等系统稳定再按新尺寸重排
        handler.postDelayed({ if (overlayShowing) overlay.relayout() }, 350)
    }

    override fun onDestroy() {
        overlay.hide()
        current = null
        overlayShowing = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_MIN
            ).apply { setShowBadge(false) }
            nm.createNotificationChannel(channel)
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.notification_running_title))
            .setContentText(getString(R.string.notification_running_text))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }
}
