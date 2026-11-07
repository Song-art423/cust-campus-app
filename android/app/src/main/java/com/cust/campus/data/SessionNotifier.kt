package com.cust.campus.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.cust.campus.R
import com.cust.campus.ui.LoginActivity

/**
 * 登录态相关通知。
 * 只在「真的需要用户动手」时才发，绝不为了刷存在感打扰人。
 */
object SessionNotifier {

    private const val CHANNEL_SESSION = "session"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_SESSION) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_SESSION,
                    "登录状态",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = "登录过期提醒" }
            )
        }
    }

    /**
     * 提示用户需要重新登录。
     *
     * 实际体验并不糟：点开就是个 WebView，如果学校 CAS 的票据还在，会直接自动登录成功，
     * 用户什么都不用填。真到这一步才需要动手，而保活跑着的话这情况很罕见。
     */
    fun notifyExpired(context: Context) {
        ensureChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        val intent = Intent(context, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pi = PendingIntent.getActivity(
            context, 1001, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val n = NotificationCompat.Builder(context, CHANNEL_SESSION)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("登录需要恢复")
            .setContentText("点一下，通常会自动登录，无需输入密码")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        nm.notify(1001, n)
    }
}
