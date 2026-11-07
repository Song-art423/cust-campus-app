package com.cust.campus.data

import android.content.Context
import android.util.Log
import com.cust.campus.data.api.CookieBridge
import com.cust.campus.data.api.CustApi
import com.cust.campus.data.model.ApiException
import com.cust.campus.data.model.AuthExpiredException
import com.cust.campus.data.model.Course
import com.cust.campus.data.model.TermData
import com.cust.campus.work.CourseAlarmScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 同步编排。
 *
 * 两种同步，用途完全不同：
 *
 *   [full]  —— 首次使用 / 重新登录后跑一次，把整个学期所有周的课表全部拉下来。
 *              只跑这一次，之后永远走缓存。界面上给一个正经的进度页。
 *
 *   [quick] —— 日常启动时若缓存跨天或过期，只补今天 + 当前周。全程静默，
 *              界面上只显示一条细进度，不打断任何操作。
 */
object ScheduleSync {

    private const val TAG = "ScheduleSync"

    /**
     * 全量同步的并发度。
     *
     * 20 周串行，每轮再限流 120ms，实测要 6 秒以上；4 路并发压到 2 秒上下。
     * 不设更高是因为再往上纯属打学校服务器 —— 4 路已经够快，也不至于招人。
     */
    private const val MAX_CONCURRENCY = 4

    suspend fun full(
        context: Context,
        api: CustApi,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): TermData = coroutineScope {
        onProgress(0.02f, "正在获取学期信息")
        val day = api.getDay()
        val weeks = api.getAllWeeks().ifEmpty { (1..20).toList() }.sorted()
        val total = weeks.size

        val done = AtomicInteger(0)
        val sem = Semaphore(MAX_CONCURRENCY)

        // 单周失败不该拖垮整次同步：学校的网经常抽，容忍个别周缺失
        val results = weeks.map { week ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    val ws = try {
                        api.getWeekScheduleFull(week)
                    } catch (e: AuthExpiredException) {
                        throw e                    // 登录态问题必须立刻中止，重试没意义
                    } catch (e: Exception) {
                        Log.w(TAG, "第 $week 周同步失败，跳过", e)
                        null
                    }
                    val n = done.incrementAndGet()
                    onProgress(
                        0.02f + 0.96f * n / total.toFloat(),
                        "正在同步 $n/$total 周"
                    )
                    week to ws
                }
            }
        }.awaitAll()

        val ok = results.filter { it.second != null }
        if (ok.isEmpty()) throw ApiException("所有周次都同步失败，请检查网络或重新登录")

        val courses = linkedMapOf<Int, List<Course>>()
        val times = linkedMapOf<Int, String>()
        // 按周次顺序合并，保证同一个节次时间以最后一次出现为准
        ok.sortedBy { it.first }.forEach { (week, ws) ->
            courses[week] = ws!!.courses
            // 有些节次只在特定周出现，单看一周会漏
            times.putAll(ws.periodTimes)
        }

        val data = TermData(
            term = day.term,
            weeks = weeks,
            currentWeek = day.weekNo,
            todayDate = day.date,
            todayWeekday = day.weekday,
            todayCourses = day.courses,
            weekCourses = courses,
            periodTimes = times,
            syncedAt = System.currentTimeMillis()
        )

        onProgress(1f, "同步完成")
        persist(context, data)
        data
    }

    /**
     * 静默补数据。只碰「今天」和「当前周」，把结果并进已有缓存。
     * [cached] 为空时退化成 full，避免状态不完整。
     */
    suspend fun quick(context: Context, api: CustApi, cached: TermData?): TermData {
        if (cached == null) return full(context, api)

        val day = api.getDay()
        val ws = api.getWeekScheduleFull(day.weekNo)

        val merged = cached.copy(
            term = day.term.ifEmpty { cached.term },
            currentWeek = day.weekNo,
            todayDate = day.date,
            todayWeekday = day.weekday,
            todayCourses = day.courses,
            weekCourses = cached.weekCourses + (day.weekNo to ws.courses),
            periodTimes = cached.periodTimes + ws.periodTimes,
            weeks = if (cached.weeks.contains(day.weekNo)) cached.weeks
            else (cached.weeks + day.weekNo).sorted(),
            syncedAt = System.currentTimeMillis()
        )

        persist(context, merged)
        return merged
    }

    /** 落盘 + 重排上课提醒 + 更新 Cookie 保险库。三件事总是绑在一起做，避免漏掉 */
    private fun persist(context: Context, data: TermData) {
        ScheduleStore.save(context, data)
        CourseCache.save(context, data.todayDate, data.todayCourses)
        CourseAlarmScheduler.scheduleToday(context)
        // 这一轮请求里服务端刚把 14 天有效期刷新过，顺手让保险库追上最新状态
        CookieBridge.saveVault()
    }
}
