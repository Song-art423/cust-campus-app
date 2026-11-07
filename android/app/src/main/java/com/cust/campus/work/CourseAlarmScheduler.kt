package com.cust.campus.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.cust.campus.data.CourseCache
import com.cust.campus.data.model.DayCourse
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 上课提醒调度。
 *
 * 用 AlarmManager 精确闹钟而不是 WorkManager —— 后者最小间隔 15 分钟且会被
 * Doze 模式推迟，上课提醒晚 5 分钟就完全没意义了。
 *
 * 每次调度只覆盖「今天剩下的课」。跨天由 DailyRefreshWorker 在凌晨兜住。
 */
object CourseAlarmScheduler {

    private const val TAG = "CourseAlarm"

    /** 节次上限。requestCode 用节次编号，固定 1..MAX_PERIOD 才能不依赖缓存就取消干净 */
    private const val MAX_PERIOD = 12

    /** 一条待触发的提醒 */
    data class Planned(
        val course: DayCourse,
        val triggerAt: LocalDateTime
    )

    /** 解析 "8:20" 这种时间串，容错优先 */
    fun parseTime(s: String): LocalTime? = runCatching {
        val parts = s.trim().split(":")
        LocalTime.of(parts[0].toInt(), parts.getOrNull(1)?.toInt() ?: 0)
    }.getOrNull()

    // ---------------------------------------------------------------- 纯逻辑

    /**
     * 算出「今天还该响哪些闹钟」。
     *
     * 抽成不碰 Context、不读系统时间的纯函数，好处有三个：
     *   1. 能单测（注入任意 now，秒验跨午夜、提前量超课间这些边界）
     *   2. 调试面板能拿它做「快进 N 分钟」预览
     *   3. 排程和实际 setAlarm 解耦，出问题一眼能定位是「没算出来」还是「没设进去」
     */
    fun plan(
        courses: List<DayCourse>,
        advanceMinutes: Int,
        now: LocalDateTime
    ): List<Planned> {
        val today = now.toLocalDate()
        return courses.mapNotNull { course ->
            val start = parseTime(course.timeStart) ?: return@mapNotNull null
            val triggerAt = LocalDateTime.of(today, start).minusMinutes(advanceMinutes.toLong())

            // 已经过了提醒时间就跳过（比如中午才打开 App，上午的课不该再响）
            if (!triggerAt.isAfter(now)) return@mapNotNull null

            Planned(course, triggerAt)
        }.sortedBy { it.triggerAt }
    }

    // ---------------------------------------------------------------- 排程

    /**
     * 按当前缓存重新排一遍今天的闹钟。
     * 可以随时重复调用，内部会先取消旧的再排新的，不会重复提醒。
     */
    fun scheduleToday(context: Context) {
        cancelAll(context)

        if (!CourseCache.isRemindEnabled(context)) return

        val snapshot = CourseCache.load(context) ?: return
        if (!snapshot.isToday) return   // 缓存是旧日期的，等 DailyRefresh 更新

        val advance = CourseCache.getAdvance(context)
        val planned = plan(snapshot.courses, advance, LocalDateTime.now())

        planned.forEach { setAlarm(context, it.course, it.triggerAt) }

        if (planned.isEmpty() && snapshot.courses.isNotEmpty()) {
            Log.d(TAG, "今天 ${snapshot.courses.size} 节课的提醒时间都已过，不再排程")
        }
    }

    /** 设一个精确闹钟到指定时刻。调试面板也走这条路径，保证测的就是真实链路 */
    fun setAlarm(context: Context, course: DayCourse, at: LocalDateTime) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return

        val intent = Intent(context, CourseAlarmReceiver::class.java).apply {
            action = CourseAlarmReceiver.ACTION_REMIND
            putExtra(CourseAlarmReceiver.EXTRA_PERIOD_START, course.periodStart)
        }

        val pi = PendingIntent.getBroadcast(
            context,
            // requestCode 用节次区分，同一节课只会有一个待触发闹钟
            course.periodStart,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 用 withNano(0) 抹掉毫秒以下的抖动，日志和时间戳都干净
        val triggerAt = at.withNano(0)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

        try {
            if (canExact(am)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                // 用户没给精确闹钟权限时退化成非精确，可能会晚几分钟，但总比不响强
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                Log.w(TAG, "无精确闹钟权限，退化为非精确调度（可能延迟）")
            }
            Log.d(TAG, "已排程: ${course.name} @ $at")
        } catch (e: SecurityException) {
            Log.e(TAG, "排程失败，缺少闹钟权限", e)
        }
    }

    fun canExact(am: AlarmManager): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.canScheduleExactAlarms() else true

    /**
     * 取消今天所有提醒。
     *
     * 早先的实现按「缓存里的课程」逐个取消 —— 一旦课表变了或者缓存不是今天，
     * 旧闹钟的 requestCode 对不上，就会永远残留，到点又响一遍。
     * 现在固定扫 1..MAX_PERIOD，和缓存彻底脱钩。
     */
    fun cancelAll(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, CourseAlarmReceiver::class.java).apply {
            action = CourseAlarmReceiver.ACTION_REMIND
        }
        (1..MAX_PERIOD).forEach { period ->
            val pi = PendingIntent.getBroadcast(
                context, period, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            runCatching { am.cancel(pi) }
        }
    }

    /** 调试用：把今天的排程打印出来，便于排查「为什么没提醒」 */
    fun dumpSchedule(context: Context, offsetMinutes: Long = 0): String {
        val snapshot = CourseCache.load(context) ?: return "无缓存"
        val advance = CourseCache.getAdvance(context)
        val fmt = DateTimeFormatter.ofPattern("HH:mm")
        val now = LocalDateTime.now().plusMinutes(offsetMinutes)
        val planned = plan(snapshot.courses, advance, now)

        return buildString {
            appendLine("缓存日期 ${snapshot.date}（今天 ${LocalDate.now()}）")
            appendLine("提前量 $advance 分钟${if (offsetMinutes != 0L) "，时间快进 $offsetMinutes 分钟" else ""}")
            if (snapshot.courses.isEmpty()) {
                append("今天无课")
                return@buildString
            }
            snapshot.courses.forEach { c ->
                val hit = planned.firstOrNull { it.course.periodStart == c.periodStart }
                val at = hit?.triggerAt?.format(fmt) ?: "已过"
                appendLine("${at.padEnd(6)} ${c.timeStart} ${c.name}  ${c.classroom}")
            }
        }
    }

    /** 距上节课还有多少秒，给调试面板做倒计时提示用 */
    fun secondsUntilNext(context: Context): Long? {
        val snapshot = CourseCache.load(context) ?: return null
        val advance = CourseCache.getAdvance(context)
        val now = LocalDateTime.now()
        val next = plan(snapshot.courses, advance, now).firstOrNull() ?: return null
        return ChronoUnit.SECONDS.between(now, next.triggerAt)
    }
}
