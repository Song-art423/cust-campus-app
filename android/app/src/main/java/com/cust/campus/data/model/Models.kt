package com.cust.campus.data.model

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 一节（或连续几节）课。
 * 对应教务接口 getXsdSykb 的 data.jcKcxx 结构，已把连续节次合并。
 */
data class Course(
    val name: String,
    val teacher: String,
    val classroom: String,
    val weekday: Int,      // 1-7，周一到周日
    val periodStart: Int,  // 起始节次 1-10
    val periodEnd: Int,
    val timeStart: String, // "8:20"
    val timeEnd: String    // "10:00"
) {
    val periodText: String get() = if (periodStart == periodEnd) "第${periodStart}节"
    else "第${periodStart}-${periodEnd}节"

    val timeText: String get() = "$timeStart-$timeEnd"

    /** 跨几节课，用于课表网格里撑高度 */
    val span: Int get() = (periodEnd - periodStart + 1).coerceAtLeast(1)
}

/** 当天的一节课，来自 getDayBz 的 bzList，教室字段更完整 */
data class DayCourse(
    val name: String,
    val teacher: String,
    val classroom: String,
    val periodStart: Int,
    val periodEnd: Int,
    val timeStart: String,
    val timeEnd: String
) {
    val periodText: String get() = if (periodStart == periodEnd) "第${periodStart}节"
    else "第${periodStart}-${periodEnd}节"

    val timeText: String get() = "$timeStart-$timeEnd"
}

/** 某一天的完整信息（getDayBz 的解析结果） */
data class DayInfo(
    val weekNo: Int,
    val term: String,
    val date: String,
    val weekday: Int,
    val weekdayCn: String,
    val courses: List<DayCourse>
)

/**
 * 整个学期的课表快照 —— 这是「一次查完、永久秒开」的核心。
 *
 * 一次全量同步把 [weeks] 里每一周的课都拉下来存本地，之后启动直接读盘渲染，
 * 不再有任何加载等待。只有跨天或数据太旧时才在后台静默补一次。
 */
data class TermData(
    val term: String = "",
    val weeks: List<Int> = emptyList(),
    val currentWeek: Int = 0,
    val todayDate: String = "",          // ISO "2026-11-06"
    val todayWeekday: Int = 0,           // 1-7
    val todayCourses: List<DayCourse> = emptyList(),
    val weekCourses: Map<Int, List<Course>> = emptyMap(),
    val periodTimes: Map<Int, String> = emptyMap(),   // 节次 -> "8:20-9:05"
    val syncedAt: Long = 0L
) {
    fun coursesOf(week: Int): List<Course> = weekCourses[week].orEmpty()

    fun periodTime(period: Int): String = periodTimes[period].orEmpty()

    /** 缓存里最大的节次编号，用于网格行数（至少 10 节） */
    val maxPeriod: Int
        get() = maxOf(10, weekCourses.values.flatten().maxOfOrNull { it.periodEnd } ?: 0)

    /** 今天是不是就对应缓存里的那一天 */
    val isToday: Boolean
        get() = todayDate == LocalDate.now().format(DateTimeFormatter.ISO_DATE)

    private val anchorDate: LocalDate
        get() = runCatching { LocalDate.parse(todayDate) }.getOrNull() ?: LocalDate.now()

    /** 第 [week] 周的周一。用「已知日期 + 已知周次」反推，不用再额外请求接口 */
    fun mondayOf(week: Int): LocalDate =
        anchorDate.minusDays((todayWeekday - 1).toLong()).plusWeeks((week - currentWeek).toLong())

    fun datesOf(week: Int): List<LocalDate> {
        val monday = mondayOf(week)
        return (0..6).map { monday.plusDays(it.toLong()) }
    }
}

/**
 * 界面状态。
 *
 * 和旧版最大的区别：[data] 一旦有值就能立刻渲染，[syncing] 只用来在角落显示一条进度，
 * 绝不会把整个界面替换成加载页。
 */
data class UiState(
    val data: TermData? = null,
    val selectedWeek: Int = 0,
    val syncing: Boolean = false,
    val syncProgress: Float = 0f,
    val syncLabel: String = "",
    val error: String? = null,
    val authExpired: Boolean = false,
    val remindAdvance: Int = 15,
    val remindEnabled: Boolean = true,
    /** 登录凭据诊断，展示在「我的」页，让用户自己能判断到底有没有掉 */
    val cookieText: String = "检测中…",
    val cookieOk: Boolean = false
) {
    /** 有数据就能画界面 */
    val hasData: Boolean get() = data != null

    /** 真正的首屏冷启动：没数据且正在同步 */
    val coldSyncing: Boolean get() = data == null && syncing
}

/** 接口异常 */
class AuthExpiredException : Exception("登录态已失效，请重新登录")

class ApiException(message: String) : Exception(message)
