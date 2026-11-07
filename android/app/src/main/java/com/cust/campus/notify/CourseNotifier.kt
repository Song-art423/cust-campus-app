package com.cust.campus.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.cust.campus.R
import com.cust.campus.data.CourseCache
import com.cust.campus.data.model.DayCourse
import com.cust.campus.ui.AlarmActivity
import com.cust.campus.ui.MainActivity
import java.time.Duration
import java.time.LocalTime

/**
 * 上课相关通知。
 *
 * ## 现状说明（重要）
 *
 * 21:06 那一版是**唯一真机验证过能弹出通知**的版本，它用的是：
 *   - 渠道 `course_remind`（HIGH，无自定义震动）
 *   - 通知只有 小图标 + 标题 + 正文 + 点击跳转
 *
 * 之后为了「超级岛 / 外卖卡片」加了一堆东西，通知就再也不弹了。
 * 把变量逐个摘除后，剩下的嫌疑只有三个，全部做了隔离：
 *
 * | 改动 | 现在的处理 |
 * |---|---|
 * | 往 extras 塞 `miui.focus.param` | 已整块删除（SystemUI 解析失败 = 整条不渲染） |
 * | 通知挂 FullScreenIntent | 默认**不挂**，面板里可手动开 |
 * | 渠道换成自定义震动的新 id | 默认走回 `course_remind`，震动是单独开关 |
 *
 * ## 为什么这次能自证
 *
 * `notify()` 成功后立刻读 [NotificationManager.getActiveNotifications]：
 * 通知在列表里 = 系统收下了（屏幕上没看到就是被折叠/勿扰/静音）；
 * 不在列表里 = 被系统直接吞了。两种失败的处理方式完全不同，不能再靠猜。
 */
object CourseNotifier {

    private const val TAG = "CourseNotifier"

    /** 21:06 验证过能弹的渠道。没坏就别动它 */
    const val CHANNEL_COURSE = "course_remind"

    /** 上一版自己造的两条实验渠道，启动时清掉，免得设置页里三条重名 */
    private const val CHANNEL_V2 = "course_remind_v2"
    private const val CHANNEL_V3 = "course_remind_v3"

    /** 需要震动时才创建的独立渠道。渠道的震动是锁死的，只能靠新 id */
    private const val CHANNEL_VIB = "course_remind_vib"

    private const val CHANNEL_CLASS_NOW = "class_ongoing"

    /** 震动模式：等 0ms → 震 400ms → 停 180ms → 震 400ms */
    private val VIBRATE_PATTERN = longArrayOf(0, 400, 180, 400)

    const val ID_PREFIX_REMIND = 2000
    const val ID_PREFIX_ONGOING = 3000

    /** 调试面板「最简通知」专用 id，面板要靠它回查投递结果 */
    const val ID_BARE_DIAG = 1999

    /**
     * 投递结果。
     *
     * 三种结果对应三种完全不同的排查方向，所以必须分开：
     *   - [Ok]        系统收下且当前在通知列表里
     *   - [Swallowed] 没抛异常，但通知不在列表里 —— 权限 / 渠道 / ROM 拦截
     *   - [Failed]    抛异常了，原因在 [Failed.reason]
     */
    sealed interface PostResult {
        data object Ok : PostResult
        data object Swallowed : PostResult
        data class Failed(val reason: String) : PostResult
    }

    // ---------------------------------------------------------------- 渠道

    /**
     * 创建 / 补齐渠道。**只增不删。**
     *
     * 这里绝不调用 `deleteNotificationChannel` —— 曾经在发送前删除旧渠道再新建，
     * 结果新渠道还没落库就 `notify()`，系统找不到渠道直接把通知丢掉，
     * 而且不报错、不打日志。删除动作一律挪到 [cleanupLegacyChannels]，
     * 只在 Application 启动时执行一次，和发送路径彻底隔开。
     */
    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        if (nm.getNotificationChannel(CHANNEL_COURSE) == null) {
            createCourseChannel(nm, CHANNEL_COURSE, "上课提醒", vibrate = false)
        }

        if (CourseCache.isVibrateEnabled(context) && nm.getNotificationChannel(CHANNEL_VIB) == null) {
            createCourseChannel(nm, CHANNEL_VIB, "上课提醒（震动）", vibrate = true)
        }

        if (nm.getNotificationChannel(CHANNEL_CLASS_NOW) == null) {
            runCatching {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_CLASS_NOW, "正在上课", NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = "上课期间显示当前课程"
                        setShowBadge(false)
                    }
                )
            }.onFailure { Log.e(TAG, "创建常驻渠道失败", it) }
        }
    }

    /**
     * 清理历史遗留的实验渠道。**只在 Application 启动时调一次。**
     *
     * v2 / v3 是排查通知问题时换 id 换出来的，留着会让系统设置页出现
     * 三条重名「上课提醒」，用户根本分不清哪个生效。
     */
    fun cleanupLegacyChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        listOf(CHANNEL_V2, CHANNEL_V3).forEach { old ->
            runCatching { nm.deleteNotificationChannel(old) }
        }
    }

    private fun createCourseChannel(
        nm: NotificationManager,
        id: String,
        name: String,
        vibrate: Boolean
    ) {
        runCatching {
            nm.createNotificationChannel(
                NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "上课前提醒你"
                    setShowBadge(true)
                    if (vibrate) {
                        enableVibration(true)
                        vibrationPattern = VIBRATE_PATTERN
                    }
                    // 不震动时不调 enableVibration —— 与 21:06 那版保持一致，
                    // 那时渠道能正常弹通知，多调一个没必要的方法只会增加变量
                }
            )
        }.onFailure { Log.e(TAG, "创建渠道 $id 失败", it) }
    }

    // ---------------------------------------------------------------- 环境检查

    /**
     * 能不能真的发出去。两道关缺一不可：
     *   - 运行时权限 POST_NOTIFICATIONS（Android 13+）
     *   - 应用级总开关 `areNotificationsEnabled()`
     *
     * 第二道最阴：用户在系统设置里关掉「允许通知」后，运行时权限**依然是 granted**，
     * 只查权限会得到「一切正常但一条都不弹」的假象。
     */
    fun notificationsAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.areNotificationsEnabled() == true
        }.getOrDefault(false)
    }

    /** 系统层面是否允许「通知拉起全屏界面」（熄屏盖到锁屏上） */
    fun fullScreenSystemAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        return runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.canUseFullScreenIntent() == true
        }.getOrDefault(false)
    }

    /** 最终是否挂全屏 Intent：默认关，只有用户手动开了才算 */
    fun fullScreenAllowed(context: Context): Boolean =
        CourseCache.isFullScreenEnabled(context) && fullScreenSystemAllowed(context)

    // ---------------------------------------------------------------- 课前提醒

    /** 课前提醒。文案 + 点击跳转，其余一律不带 —— 与 21:06 那版保持一致 */
    fun notifyUpcoming(context: Context, course: DayCourse, minutesLeft: Int): PostResult {
        ensureChannels(context)

        val title = if (minutesLeft <= 1) "马上上课：${course.name}"
        else "${minutesLeft} 分钟后上课"
        val where = course.classroom.ifBlank { "教室待定" }
        val content = "${course.name} · $where · ${course.timeText}"

        val notification = try {
            buildReminder(context, course, minutesLeft, title, content)
        } catch (t: Throwable) {
            Log.e(TAG, "通知构造失败，回退最简形态", t)
            bareReminder(context, title, content)
        }

        return post(context, ID_PREFIX_REMIND + course.periodStart, notification)
    }

    private fun buildReminder(
        context: Context,
        course: DayCourse,
        minutesLeft: Int,
        title: String,
        content: String
    ): Notification {
        val vibrate = CourseCache.isVibrateEnabled(context)
        val builder = NotificationCompat.Builder(
            context, if (vibrate) CHANNEL_VIB else CHANNEL_COURSE
        )
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context))

        if (vibrate) builder.setVibrate(VIBRATE_PATTERN)

        // 全屏 Intent 是唯一会改变通知「形态」的挂载，默认关掉。
        // 排查「整条通知不显示」时它就是第一嫌疑，所以做成显式开关。
        if (fullScreenAllowed(context)) {
            runCatching {
                builder.setFullScreenIntent(fullScreenIntent(context, course, minutesLeft), true)
            }.onFailure { Log.w(TAG, "全屏 Intent 挂载失败，退化为横幅", it) }
        }

        return builder.build()
    }

    /** 最简通知：只有图标、标题、正文。兜底 + 面板二分之一用 */
    fun bareReminder(context: Context, title: String, content: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_COURSE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context))
            .build()

    /** 调试面板专用：发一条最简通知并回传投递结果 */
    fun postBare(context: Context, title: String, content: String): PostResult {
        ensureChannels(context)
        return post(context, ID_BARE_DIAG, bareReminder(context, title, content))
    }

    // ---------------------------------------------------------------- 常驻卡片

    /** 上课中的常驻卡片：带进度条，不能划掉 */
    fun notifyOngoing(
        context: Context,
        course: DayCourse,
        elapsedMinutes: Int,
        totalMinutes: Int
    ): PostResult {
        ensureChannels(context)

        val total = totalMinutes.coerceAtLeast(1)
        val elapsed = elapsedMinutes.coerceIn(0, total)
        val left = total - elapsed
        val where = course.classroom.ifBlank { "教室待定" }
        val content = "$where · 至 ${course.timeEnd} · 还剩 $left 分钟"

        val notification = try {
            NotificationCompat.Builder(context, CHANNEL_CLASS_NOW)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("正在上课：${course.name}")
                .setContentText(content)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setShowWhen(false)
                .setProgress(total, elapsed, false)
                .setSubText("第 ${course.periodStart}-${course.periodEnd} 节")
                .setContentIntent(contentIntent(context))
                .build()
        } catch (t: Throwable) {
            Log.e(TAG, "常驻卡片构造失败", t)
            return PostResult.Failed("构造失败：${t.javaClass.simpleName}")
        }

        return post(context, ID_PREFIX_ONGOING + course.periodStart, notification)
    }

    fun cancelOngoing(context: Context, periodStart: Int) {
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.cancel(ID_PREFIX_ONGOING + periodStart)
        }
    }

    /**
     * 按当前时间同步「正在上课」卡片：在上课就显示，不在就清掉。
     * 可反复调用，App 启动 / 闹钟触发 / 每日刷新后各调一次。
     */
    fun syncOngoing(context: Context) {
        val snapshot = CourseCache.load(context) ?: return
        if (!snapshot.isToday) return

        val now = LocalTime.now()
        val current = snapshot.courses.firstOrNull { c ->
            val start = parse(c.timeStart) ?: return@firstOrNull false
            val end = parse(c.timeEnd) ?: return@firstOrNull false
            !now.isBefore(start) && now.isBefore(end)
        }

        snapshot.courses.forEach { c ->
            if (c.periodStart != current?.periodStart) cancelOngoing(context, c.periodStart)
        }

        if (current == null) return

        val start = parse(current.timeStart) ?: return
        val end = parse(current.timeEnd) ?: return
        val elapsed = Duration.between(start, now).toMinutes().toInt().coerceAtLeast(0)
        val total = Duration.between(start, end).toMinutes().toInt()

        notifyOngoing(context, current, elapsed, total)
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 唯一出口。所有 notify 都走这里，异常一律转成人话。
     *
     * 注意：这里**不做** `getActiveNotifications()` 回查。刚 notify 完同一次调用里
     * 系统可能还没把通知写进列表，立即读会得到假阴性。回查放在 [isActive] 里，
     * 由调试面板延迟 700ms 调一次。
     */
    private fun post(context: Context, id: Int, notification: Notification): PostResult {
        if (!notificationsAllowed(context)) {
            return PostResult.Failed("应用通知总开关是关的 —— 系统会把通知直接丢掉")
        }
        val nm = context.getSystemService(NotificationManager::class.java)
            ?: return PostResult.Failed("取不到系统通知服务")

        return try {
            nm.notify(id, notification)
            Log.d(TAG, "通知已投递 id=$id")
            PostResult.Ok
        } catch (t: Throwable) {
            Log.e(TAG, "notify() 抛出异常 id=$id", t)
            PostResult.Failed("${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * 通知是否真的落在系统的活动通知列表里。
     *
     * 这是「系统收下了没」的唯一硬证据：在列表里说明投递成功（屏幕上没看到
     * 就是被折叠 / 勿扰 / 静音），不在列表里说明被系统或 ROM 拦掉了。
     */
    fun isActive(context: Context, id: Int): Boolean = runCatching {
        context.getSystemService(NotificationManager::class.java)
            ?.activeNotifications?.any { it.id == id } == true
    }.getOrDefault(false)

    /** 全屏提醒的 PendingIntent，requestCode 用节次区分，避免多节课互相覆盖 */
    private fun fullScreenIntent(
        context: Context,
        course: DayCourse,
        minutesLeft: Int
    ): PendingIntent = PendingIntent.getActivity(
        context,
        ID_PREFIX_ONGOING + course.periodStart,
        AlarmActivity.intent(context, course, minutesLeft),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun contentIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun parse(s: String): LocalTime? = runCatching {
        val p = s.trim().split(":")
        LocalTime.of(p[0].toInt(), p.getOrNull(1)?.toInt() ?: 0)
    }.getOrNull()
}
