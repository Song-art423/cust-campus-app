package com.cust.campus.work

import com.cust.campus.data.model.DayCourse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 上课提醒排程逻辑测试。
 *
 * 这些边界只有单测能覆盖 —— 靠真机等上课时间验证，一天最多测一次，
 * 而「提前量大于距离开课时间」「时间格式带前导零」这类问题一年都碰不到几次。
 *
 * 方法名用英文：Gradle 控制台是 GBK，中文方法名在失败报告里会变成 `?????`，
 * 出错时根本认不出是哪条。
 */
class CourseAlarmSchedulerTest {

    private val day: LocalDate = LocalDate.of(2026, 11, 9) // 周一

    private fun course(
        name: String = "高等数学",
        start: String = "8:20",
        end: String = "10:00",
        period: Int = 1
    ) = DayCourse(
        name = name,
        teacher = "张老师",
        classroom = "B203",
        periodStart = period,
        periodEnd = period + 1,
        timeStart = start,
        timeEnd = end
    )

    private fun at(hour: Int, minute: Int): LocalDateTime =
        LocalDateTime.of(day, LocalTime.of(hour, minute))

    /** 提前 15 分钟，8:20 的课 → 8:05 响 */
    @Test
    fun schedulesSkippedWhenAdvanceApplied() {
        val result = CourseAlarmScheduler.plan(
            listOf(course(start = "8:20")),
            advanceMinutes = 15,
            now = at(7, 0)
        )

        assertEquals(1, result.size)
        assertEquals(at(8, 5), result[0].triggerAt)
    }

    /** 8:10 打开 App，8:20 的课提醒点 8:05 已过 → 不再响 */
    @Test
    fun skipsWhenRemindTimePassed() {
        val result = CourseAlarmScheduler.plan(
            listOf(course(start = "8:20")),
            advanceMinutes = 15,
            now = at(8, 10)
        )

        assertTrue(result.isEmpty())
    }

    /** 边界：提醒时刻正好等于当前，不算将来，避免立刻触发 */
    @Test
    fun skipsWhenRemindTimeEqualsNow() {
        val result = CourseAlarmScheduler.plan(
            listOf(course(start = "8:20")),
            advanceMinutes = 15,
            now = at(8, 5)
        )

        assertTrue(result.isEmpty())
    }

    /** 提前量为 0 → 准点提醒 */
    @Test
    fun zeroAdvanceTriggersAtClassStart() {
        val result = CourseAlarmScheduler.plan(
            listOf(course(start = "8:20")),
            advanceMinutes = 0,
            now = at(7, 0)
        )

        assertEquals(at(8, 20), result[0].triggerAt)
    }

    /** 用户设 30 分钟提前量，但 8:10 才打开 App，8:20 的课只剩 10 分钟 → 跳过 */
    @Test
    fun skipsWhenAdvanceLargerThanTimeLeft() {
        val result = CourseAlarmScheduler.plan(
            listOf(course(start = "8:20")),
            advanceMinutes = 30,
            now = at(8, 10)
        )

        assertTrue(result.isEmpty())
    }

    /** 多节课：过滤已过，并按触发时间升序 */
    @Test
    fun sortsByTriggerTimeAndFiltersPast() {
        val courses = listOf(
            course(name = "下午课", start = "14:00", period = 5),
            course(name = "上午课", start = "8:20", period = 1),
            course(name = "上午第二节", start = "10:20", period = 3)
        )

        val result = CourseAlarmScheduler.plan(courses, advanceMinutes = 15, now = at(9, 0))

        assertEquals(2, result.size)
        assertEquals("上午第二节", result[0].course.name)
        assertEquals("下午课", result[1].course.name)
        assertTrue(result[0].triggerAt.isBefore(result[1].triggerAt))
    }

    /** "08:20" 这种带前导零的格式能解析 */
    @Test
    fun parsesTimeWithLeadingZero() {
        val result = CourseAlarmScheduler.plan(
            listOf(course(start = "08:20")),
            advanceMinutes = 15,
            now = at(7, 0)
        )

        assertEquals(at(8, 5), result[0].triggerAt)
    }

    /** 时间串非法时跳过这一节，不能整批崩掉 */
    @Test
    fun ignoresMalformedTimeInsteadOfCrashing() {
        val bad = course().copy(timeStart = "未知")
        val good = course(name = "语文", start = "8:20", period = 2)

        val result = CourseAlarmScheduler.plan(
            listOf(bad, good),
            advanceMinutes = 15,
            now = at(7, 0)
        )

        assertEquals(1, result.size)
        assertEquals("语文", result[0].course.name)
    }

    @Test
    fun emptyCourseListReturnsEmpty() {
        assertTrue(CourseAlarmScheduler.plan(emptyList(), 15, at(7, 0)).isEmpty())
    }

    /**
     * 已知限制（不是 bug，但值得记着）：
     * 00:10 的课 + 提前 15 分钟 → 提醒点落在前一天 23:55。
     * 而 plan 用 now 的日期拼装具体时刻，当天调用时 23:55 已成过去，会被过滤。
     *
     * 也就是说「凌晨 0 点前的课」永远排不上提醒。
     * 学校不存在这个时间段的课，所以不修；将来真有深夜课程，
     * 得把 plan 改成按「时间差」回溯，而不是按「日期拼装」。
     */
    @Test
    fun midnightCourseIsDroppedKnownLimitation() {
        val result = CourseAlarmScheduler.plan(
            listOf(course(start = "0:10")),
            advanceMinutes = 15,
            now = at(23, 50)
        )

        assertTrue(result.isEmpty())
    }
}
