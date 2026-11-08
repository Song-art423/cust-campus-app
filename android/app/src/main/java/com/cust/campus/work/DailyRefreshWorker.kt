package com.cust.campus.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cust.campus.data.CourseCache
import com.cust.campus.data.SessionKeeper
import com.cust.campus.data.api.CustApi
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * 每日刷新。
 *
 * 凌晨把「今天的课」重新拉一遍并写进缓存，顺手重排闹钟。
 * 没有它的话，前一天排的闹钟到第二天就全失效了。
 *
 * 时间点选 00:10 而不是 00:00 —— 避开系统在零点附近的集中任务，触发更稳。
 */
class DailyRefreshWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "DailyRefresh"
        private const val WORK_NAME = "daily_refresh"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<DailyRefreshWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(initialDelayMinutes(), TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        /** 距离下一个 00:10 还有多少分钟 */
        private fun initialDelayMinutes(): Long {
            val now = LocalDateTime.now()
            var next = LocalDateTime.of(now.toLocalDate(), LocalTime.of(0, 10))
            if (!next.isAfter(now)) next = next.plusDays(1)
            return Duration.between(now, next).toMinutes().coerceAtLeast(1)
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "开始每日刷新")

        // 先把登录态续上，否则拉了也是白拉
        if (SessionKeeper.ensure(applicationContext) == SessionKeeper.Status.EXPIRED) {
            Log.w(TAG, "登录态失效，跳过本次课程刷新")
            return Result.success()
        }

        return try {
            val api = CustApi()
            val day = api.getDay()

            CourseCache.save(applicationContext, day.date, day.courses)
            CourseAlarmScheduler.scheduleToday(applicationContext)

            Log.d(TAG, "刷新完成：${day.courses.size} 门课，第 ${day.weekNo} 周")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "刷新失败", e)
            // 失败不重试到底，交给后天的轮次；重试太密会打爆学校服务器
            Result.success()
        }
    }
}
