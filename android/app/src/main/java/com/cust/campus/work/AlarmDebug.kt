package com.cust.campus.work

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.cust.campus.data.CourseCache
import com.cust.campus.data.model.DayCourse
import com.cust.campus.notify.CourseNotifier
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 提醒调试工具。
 *
 * 起因很实际：课前弹窗只在上课前触发，想验证一次就得等到那节课。
 * 这个模块让任何时刻都能在 10 秒内跑通完整链路，且能直接看出
 * 「不响」到底是权限、渠道、还是算法问题。
 *
 * 三层验证，越往下越接近真实：
 *   1. [health]        体检 —— 四个高频静默失败点逐个查
 *   2. [previewNow]    预演 —— 直接发通知，验证文案/图标/点击/灵动岛
 *   3. [testFullChain] 真链路 —— 注入临时课表 + AlarmManager 真实触发
 */
object AlarmDebug {

    private const val TAG = "AlarmDebug"

    private const val PREF = "alarm_debug"
    private const val KEY_BACKUP_DATE = "backup_date"
    private const val KEY_BACKUP_COURSES = "backup_courses"
    private const val KEY_HAS_BACKUP = "has_backup"

    private val main = Handler(Looper.getMainLooper())

    // ---------------------------------------------------------------- 体检

    data class Health(
        /** Android 13+ 的运行时权限 */
        val notifyPermission: Boolean,
        /**
         * 应用级通知总开关（`areNotificationsEnabled`）。
         *
         * 和运行时权限是**两码事**：用户在系统设置里关掉「允许通知」后，
         * 权限依然是 granted，只查权限会得到「一切正常但一条都不弹」的假象。
         * 这是排查通知静默失败时最容易漏掉的一环。
         */
        val appNotificationsEnabled: Boolean,
        val channelBlocked: Boolean,
        /** 渠道当前真实等级。低于 HIGH 就不会弹横幅，用户看到的只是通知栏里静静躺一条 */
        val channelImportance: Int,
        /** 渠道是否允许震动。false 时通知照样出，但一声不响 —— 最容易被当成「没生效」 */
        val channelVibrate: Boolean,
        /** 面板里的开关：开启后走独立的震动渠道 */
        val vibrateEnabled: Boolean,
        /** 系统是否允许「通知拉起全屏界面」（熄屏盖锁屏），Android 14+ 才有单独开关 */
        val fullScreenSystemAllow: Boolean,
        /** 面板里的开关：用户可以主动关掉全屏 Intent 来排查问题 */
        val fullScreenEnabled: Boolean,
        val exactAlarm: Boolean,
        val batteryUnrestricted: Boolean,
        val hasTodayCache: Boolean,
        val todayCourseCount: Int,
        val plannedCount: Int,
        val nextInSeconds: Long?
    ) {
        /** 当前实际会不会挂全屏 Intent（= 用户开关 && 系统允许） */
        val fullScreenActive: Boolean
            get() = fullScreenEnabled && fullScreenSystemAllow

        /** 所有会阻断提醒的问题，为空表示环境就绪 */
        val blockers: List<String>
            get() = buildList {
                if (!notifyPermission) add("未授予通知权限")
                if (!appNotificationsEnabled) add("系统设置里把通知总开关关了")
                if (channelBlocked) add("通知渠道被关闭")
                if (!exactAlarm) add("未允许精确闹钟")
                if (!hasTodayCache) add("今天没有课表缓存")
            }

        /** 能弹出横幅 = 等级 HIGH 以上且渠道没被关 */
        val canHeadsUp: Boolean
            get() = channelImportance >= NotificationManager.IMPORTANCE_HIGH

        val importanceLabel: String
            get() = when {
                channelImportance >= NotificationManager.IMPORTANCE_HIGH -> "高 · 会弹横幅"
                channelImportance == NotificationManager.IMPORTANCE_DEFAULT -> "默认 · 有声无横幅"
                channelImportance == NotificationManager.IMPORTANCE_LOW -> "低 · 静默不弹"
                channelImportance == NotificationManager.IMPORTANCE_MIN -> "最低 · 折叠"
                else -> "已关闭"
            }
    }

    fun health(context: Context): Health {
        val nm = context.getSystemService(NotificationManager::class.java)

        val notifyPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true

        val appNotificationsEnabled = runCatching {
            nm?.areNotificationsEnabled() == true
        }.getOrDefault(false)

        // 渠道存在但 importance 被用户调成 NONE，日志里一切正常、屏幕上什么都不出
        val channel = runCatching {
            nm?.getNotificationChannel(CourseNotifier.CHANNEL_COURSE)
        }.getOrNull()
        val channelImportance = channel?.importance ?: NotificationManager.IMPORTANCE_NONE
        val channelBlocked = channelImportance == NotificationManager.IMPORTANCE_NONE
        val channelVibrate = channel?.shouldVibrate() ?: false

        // 仅 Android 14+ 有这个限制；13 及以下声明即授予。
        // 侧载安装的包不会被 Play 商店撤销，通常直接就是 true。
        val fullScreenSystemAllow = CourseNotifier.fullScreenSystemAllowed(context)
        val fullScreenEnabled = CourseCache.isFullScreenEnabled(context)
        val vibrateEnabled = CourseCache.isVibrateEnabled(context)

        val am = context.getSystemService(AlarmManager::class.java)
        val exactAlarm = am != null && CourseAlarmScheduler.canExact(am)

        val pm = context.getSystemService(PowerManager::class.java)
        val batteryUnrestricted = runCatching {
            pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        }.getOrDefault(false)

        val snapshot = CourseCache.load(context)
        val hasCache = snapshot?.isToday == true
        val courses = if (hasCache) snapshot!!.courses else emptyList()
        val planned = if (hasCache) {
            CourseAlarmScheduler.plan(
                courses, CourseCache.getAdvance(context), LocalDateTime.now()
            )
        } else emptyList()

        return Health(
            notifyPermission = notifyPermission,
            appNotificationsEnabled = appNotificationsEnabled,
            channelBlocked = channelBlocked,
            channelImportance = channelImportance,
            channelVibrate = channelVibrate,
            vibrateEnabled = vibrateEnabled,
            fullScreenSystemAllow = fullScreenSystemAllow,
            fullScreenEnabled = fullScreenEnabled,
            exactAlarm = exactAlarm,
            batteryUnrestricted = batteryUnrestricted,
            hasTodayCache = hasCache,
            todayCourseCount = courses.size,
            plannedCount = planned.size,
            nextInSeconds = planned.firstOrNull()?.let {
                java.time.Duration.between(LocalDateTime.now(), it.triggerAt).seconds
            }
        )
    }

    // ---------------------------------------------------------------- 预演

    /**
     * 延迟几秒后直接发一条提醒通知，完全不经过 AlarmManager。
     *
     * 用来验证「通知本身」有没有问题：文案、图标、渠道、横幅、点击跳转、灵动岛。
     * 如果这个不响，就是权限/渠道问题；如果这个响而真闹钟不响，才是调度问题。
     */
    fun previewNow(
        context: Context,
        delaySeconds: Long = 3,
        onResult: (CourseNotifier.PostResult) -> Unit = {}
    ): String {
        blockReason(context)?.let { return it }

        val app = context.applicationContext
        val course = sampleCourse(app, leadMinutes = 15)

        main.postDelayed({
            onResult(postUpcoming(app, course, "预演失败"))
        }, delaySeconds * 1000)

        return "${delaySeconds} 秒后弹通知（模拟「15 分钟后上${course.name}」）"
    }

    /**
     * 最简通知测试：不带大图标、不带样式、不带全屏 Intent，只有图标加两行字。
     *
     * 专门用来做二分：这条能弹而「3 秒预演」不弹，问题就在某个装饰上；
     * 这条也不弹，就是权限 / 渠道层面，别再去调样式了。
     */
    fun previewBare(
        context: Context,
        delaySeconds: Long = 2,
        onResult: (CourseNotifier.PostResult) -> Unit = {}
    ): String {
        blockReason(context)?.let { return it }

        val app = context.applicationContext
        main.postDelayed({
            val result = runCatching {
                CourseNotifier.postBare(app, "最简通知测试", "看到这条说明通知通道是通的")
            }.getOrElse {
                Log.e(TAG, "最简通知失败", it)
                CourseNotifier.PostResult.Failed("${it.javaClass.simpleName}: ${it.message}")
            }

            if (result is CourseNotifier.PostResult.Ok) {
                // 延迟回查：刚 notify 完同一次调用里列表可能还没写进去，立即读会假阴性
                main.postDelayed({
                    onResult(
                        if (CourseNotifier.isActive(app, CourseNotifier.ID_BARE_DIAG)) result
                        else CourseNotifier.PostResult.Swallowed
                    )
                }, 700)
            } else {
                onResult(result)
            }
        }, delaySeconds * 1000)

        return "${delaySeconds} 秒后发一条最简通知（不带头像 / 样式 / 全屏）"
    }

    /**
     * 熄屏预演：延时发一条带全屏 Intent 的提醒，让用户自己锁屏看效果。
     *
     * 为什么不直接 startActivity：Android 10 起后台启动 Activity 会被拦，
     * 只有「通知 + 全屏 Intent」这一条路是系统认可的。所以必须走通知。
     */
    fun previewLockScreen(
        context: Context,
        delaySeconds: Long = 8,
        onResult: (CourseNotifier.PostResult) -> Unit = {}
    ): String {
        blockReason(context)?.let { return it }

        val app = context.applicationContext
        val course = sampleCourse(app, leadMinutes = 15)

        main.postDelayed({
            onResult(postUpcoming(app, course, "熄屏预演失败"))
        }, delaySeconds * 1000)

        return when {
            !CourseCache.isFullScreenEnabled(app) ->
                "全屏提醒被你在面板里关掉了，这次只会弹横幅"
            !CourseNotifier.fullScreenSystemAllowed(app) ->
                "${delaySeconds} 秒后触发，但系统没给「全屏通知」权限 —— 只会在锁屏上显示横幅"
            else ->
                "点完立刻按电源键锁屏，${delaySeconds} 秒后会点亮屏幕并弹出提醒页"
        }
    }

    /** 发一条课前提醒并把异常转成结果对象，预演三段共用 */
    private fun postUpcoming(
        app: Context,
        course: DayCourse,
        tag: String
    ): CourseNotifier.PostResult = runCatching {
        CourseNotifier.notifyUpcoming(app, course, 15)
    }.getOrElse {
        Log.e(TAG, tag, it)
        CourseNotifier.PostResult.Failed("${it.javaClass.simpleName}: ${it.message}")
    }

    /** 环境不允许发通知时返回一句人话，允许则返回 null */
    private fun blockReason(context: Context): String? {
        val h = health(context)
        if (!h.notifyPermission) return "没有通知权限，通知会被系统静默丢弃 —— 先去授予"
        if (!h.appNotificationsEnabled) return "系统设置里本应用的通知总开关是关的 —— 先去打开"
        return null
    }

    // ---------------------------------------------------------------- 真链路

    /**
     * 走完整真实链路：注入临时课表 → AlarmManager 精确闹钟 → 广播接收器 → 通知。
     *
     * 只有这条路径能验证：精确闹钟权限、Doze 下能否唤醒、进程被杀后 Receiver 能否起来、
     * 以及 Receiver 里「从缓存捞课」的逻辑。
     */
    fun testFullChain(context: Context, delaySeconds: Long = 10): String {
        blockReason(context)?.let { return it }
        val h = health(context)

        val app = context.applicationContext
        val now = LocalDateTime.now()
        val trigger = now.plusSeconds(delaySeconds)

        // 伪造一节「delaySeconds 秒后开始」的课，提前量设 0 让它立刻响
        val fake = DayCourse(
            name = "调试用课程",
            teacher = "Debug",
            classroom = "测试教室 A101",
            periodStart = 1,
            periodEnd = 2,
            timeStart = trigger.toLocalTime().format(DateTimeFormatter.ofPattern("H:mm")),
            timeEnd = trigger.plusMinutes(45).toLocalTime()
                .format(DateTimeFormatter.ofPattern("H:mm"))
        )

        backupAndInject(app, listOf(fake))
        CourseCache.setAdvance(app, 0)

        // 先清干净 1..12 的旧闹钟，避免和测试闹钟抢同一个 requestCode
        CourseAlarmScheduler.cancelAll(app)
        CourseAlarmScheduler.setAlarm(app, fake, trigger)

        Log.d(TAG, "真链路测试闹钟已设: $trigger")

        return if (h.exactAlarm) {
            "${delaySeconds} 秒后触发（精确闹钟，应准时）"
        } else {
            "${delaySeconds} 秒后触发，但无精确闹钟权限 —— 可能延迟几分钟"
        }
    }

    /** 恢复真实课表。测试完记得点，否则今天一直显示「调试用课程」 */
    fun restoreRealCache(context: Context): String {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (!p.getBoolean(KEY_HAS_BACKUP, false)) return "没有备份，无需恢复"

        val date = p.getString(KEY_BACKUP_DATE, null)
        val json = p.getString(KEY_BACKUP_COURSES, null)
        // 测试时把提前量改成了 0，恢复必须一起还原，否则用户真实提醒全变成准点响
        val advance = p.getInt("backup_advance", 15)

        CourseAlarmScheduler.cancelAll(context)

        if (date != null && json != null) {
            CourseCache.restoreRaw(context, date, json)
        }
        CourseCache.setAdvance(context, advance)

        p.edit().clear().apply()
        CourseAlarmScheduler.scheduleToday(context)
        return "已恢复真实课表并重排闹钟"
    }

    fun hasBackup(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean(KEY_HAS_BACKUP, false)

    /**
     * 熄屏全屏提醒开关。
     *
     * 关掉之后通知里不再挂 FullScreenIntent —— 这是唯一一处会改变通知形态的挂载，
     * 排查「通知整条不显示」时先把它关掉，一次只动一个变量。
     */
    fun isFullScreenEnabled(context: Context): Boolean = CourseCache.isFullScreenEnabled(context)

    fun setFullScreenEnabled(context: Context, on: Boolean) =
        CourseCache.setFullScreenEnabled(context, on)

    /** 震动开关。开了才走独立的震动渠道，关了立刻回到 21:06 那版的可用状态 */
    fun isVibrateEnabled(context: Context): Boolean = CourseCache.isVibrateEnabled(context)

    fun setVibrateEnabled(context: Context, on: Boolean) =
        CourseCache.setVibrateEnabled(context, on)

    /**
     * 把「快进 N 分钟之后」会排哪些闹钟列出来。
     *
     * 这一步纯算不碰系统，所以能把 8:20 的课在一秒钟内「走」到 7:50 看看提醒到底排没排。
     * 排程算法的边界问题（提前量大于距离开课时间、跨午夜、连堂课）全靠它暴露。
     */
    fun dumpSchedule(context: Context, offsetMinutes: Long = 0): String =
        CourseAlarmScheduler.dumpSchedule(context, offsetMinutes)

    // ---------------------------------------------------------------- 内部

    private fun backupAndInject(context: Context, courses: List<DayCourse>) {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val current = CourseCache.load(context)

        // 只在第一次注入时备份，避免连点两次把假课表当成真实数据备份下来
        if (!p.getBoolean(KEY_HAS_BACKUP, false) && current != null) {
            p.edit()
                .putString(KEY_BACKUP_DATE, current.date)
                .putString(KEY_BACKUP_COURSES, CourseCache.rawJson(context))
                .putBoolean(KEY_HAS_BACKUP, true)
                .putInt("backup_advance", CourseCache.getAdvance(context))
                .apply()
        }

        val today = java.time.LocalDate.now().toString()
        CourseCache.save(context, today, courses)
    }

    /** 造一条用于预演的样课。有真实课就用真实的，没有就编一节 */
    private fun sampleCourse(context: Context, leadMinutes: Int): DayCourse {
        val snapshot = CourseCache.load(context)
        if (snapshot != null && snapshot.isToday) {
            snapshot.courses.firstOrNull()?.let { return it }
        }
        val t = LocalTime.now().plusMinutes(leadMinutes.toLong())
        return DayCourse(
            name = "高等数学",
            teacher = "张老师",
            classroom = "教学楼 B203",
            periodStart = 1,
            periodEnd = 2,
            timeStart = t.format(DateTimeFormatter.ofPattern("H:mm")),
            timeEnd = t.plusMinutes(45).format(DateTimeFormatter.ofPattern("H:mm"))
        )
    }

    // ---------------------------------------------------------------- 跳设置

    /** Android 版本差异很大，能跳就跳，跳不过去就落到应用详情页 */
    fun openNotificationSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            appDetails(context)
        }
        launch(context, intent)
    }

    fun openChannelSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            openNotificationSettings(context)
            return
        }
        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, CourseNotifier.CHANNEL_COURSE)
        launch(context, intent)
    }

    fun openExactAlarmSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData(Uri.parse("package:${context.packageName}"))
        } else {
            appDetails(context)
        }
        launch(context, intent)
    }

    fun openBatterySettings(context: Context) {
        launch(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    /** 「全屏通知」特殊权限。Android 14+ 才单独成页，13 及以下跟着通知权限走 */
    fun openFullScreenIntentSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            launch(
                context,
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                    .setData(Uri.parse("package:${context.packageName}"))
            )
        } else {
            openNotificationSettings(context)
        }
    }

    /**
     * MIUI / HyperOS 的「锁屏显示」开关。
     *
     * 这个开关不在标准 Android 权限体系里，没有任何公开 API 能查能申请，
     * 只能靠 MIUI 私有的权限编辑页跳过去让用户自己开。
     * 不开的话：全屏 Intent 有权限也没用，界面会被挡在锁屏后面。
     */
    fun openOemLockScreenPermission(context: Context) {
        val opened = runCatching {
            context.startActivity(
                Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                    putExtra("extra_pkgname", context.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }.isSuccess
        if (!opened) launch(context, appDetails(context))
    }

    private fun appDetails(context: Context) = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}")
    )

    private fun launch(context: Context, intent: Intent) {
        runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }.onFailure { Log.w(TAG, "打不开设置页", it) }
    }
}
