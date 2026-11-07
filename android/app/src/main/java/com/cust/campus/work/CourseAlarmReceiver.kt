package com.cust.campus.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.cust.campus.data.CourseCache
import com.cust.campus.notify.CourseNotifier
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 上课闹钟接收器。
 *
 * 只做一件事：从本地缓存捞出这门课，发通知。
 * 不联网、不查接口 —— 广播接收器有 10 秒执行上限，而且此时大概率没登录态可用。
 */
class CourseAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_REMIND = "com.cust.campus.action.COURSE_REMIND"
        const val EXTRA_PERIOD_START = "period_start"
        private const val TAG = "CourseAlarm"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REMIND) return

        val periodStart = intent.getIntExtra(EXTRA_PERIOD_START, -1)
        val snapshot = CourseCache.load(context)
        if (snapshot == null || !snapshot.isToday) {
            Log.w(TAG, "闹钟触发但缓存缺失或已过期，跳过")
            return
        }

        val course = snapshot.courses.firstOrNull { it.periodStart == periodStart }
        if (course == null) {
            Log.w(TAG, "闹钟触发但找不到节次 $periodStart 的课程")
            return
        }

        val minutesLeft = remainingMinutes(course.timeStart)
        when (val result = CourseNotifier.notifyUpcoming(context, course, minutesLeft)) {
            is CourseNotifier.PostResult.Ok ->
                Log.d(TAG, "已提醒: ${course.name}（还有 $minutesLeft 分钟）")
            is CourseNotifier.PostResult.Swallowed ->
                Log.w(TAG, "通知被系统吞了: ${course.name} —— 查通知权限/渠道")
            is CourseNotifier.PostResult.Failed ->
                Log.e(TAG, "提醒投递失败: ${result.reason}")
        }

        // 顺手同步「正在上课」卡片：这次闹钟可能正好撞在上课时间点，
        // 也可能用户上课中恰好手动触发了一次调试预演
        CourseNotifier.syncOngoing(context)
    }

    /** 距离上课还有几分钟，算不出来就按用户设的提前量兜底 */
    private fun remainingMinutes(timeStart: String): Int {
        val parsed = runCatching {
            val p = timeStart.trim().split(":")
            LocalTime.of(p[0].toInt(), p.getOrNull(1)?.toInt() ?: 0)
        }.getOrNull() ?: return 15

        val mins = java.time.Duration.between(
            LocalDateTime.now(),
            LocalDateTime.of(LocalDate.now(), parsed)
        ).toMinutes().toInt()

        return mins.coerceAtLeast(0)
    }
}
