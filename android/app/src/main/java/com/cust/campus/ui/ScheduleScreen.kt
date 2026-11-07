package com.cust.campus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cust.campus.data.model.Course
import com.cust.campus.data.model.TermData
import com.cust.campus.ui.components.CircleIconButton
import com.cust.campus.ui.components.MetaText
import com.cust.campus.ui.components.SoftTag
import com.cust.campus.ui.theme.AppColor
import com.cust.campus.ui.theme.AppType
import java.time.LocalDate

private val WEEK_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

/** 左侧时间轴宽度 / 每节课的行高 */
private val AXIS_WIDTH = 34.dp
private val SLOT_HEIGHT = 58.dp
private val GAP = 5.dp

/**
 * 课表周视图。
 *
 * 布局用「固定行高的 Column + 绝对偏移」实现合并节次：
 * 整张表按 maxPeriod 行铺底色格子，再把课程块按 (periodStart-1)*行高 偏移盖上去，
 * 跨节次的课自然就撑满了那一段，不需要手算网格跨度。
 */
@Composable
fun ScheduleScreen(
    data: TermData,
    week: Int,
    onPrevWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onBackToday: () -> Unit,
    onPickWeek: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var now by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(Unit) { now = LocalDate.now() }

    val courses = data.coursesOf(week)
    val dates = remember(data.todayDate, week, data.currentWeek) { data.datesOf(week) }
    val todayIndex = if (week == data.currentWeek && data.isToday) data.todayWeekday - 1 else -1

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        // ---- 标题 + 周切换 ----
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("课表", style = AppType.pageTitle, color = AppColor.TextPrimary)
                Spacer(Modifier.height(2.dp))
                MetaText(monthRange(dates))
            }
            Spacer(Modifier.weight(1f))
            CircleIconButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, onPrevWeek, contentDesc = "上一周")
            Spacer(Modifier.width(8.dp))
            CircleIconButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, onNextWeek, contentDesc = "下一周")
        }

        Spacer(Modifier.height(14.dp))

        // ---- 周次胶囊条 ----
        WeekStrip(
            data = data,
            selected = week,
            onPickWeek = onPickWeek,
            onBackToday = onBackToday
        )

        Spacer(Modifier.height(14.dp))

        // ---- 星期表头 ----
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(AXIS_WIDTH))
            WEEK_LABELS.forEachIndexed { i, label ->
                val isToday = i == todayIndex
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        label,
                        style = AppType.meta.copy(
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal
                        ),
                        color = if (isToday) AppColor.Accent else AppColor.TextTertiary
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        dates.getOrNull(i)?.dayOfMonth?.toString() ?: "",
                        style = AppType.tiny.copy(
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal
                        ),
                        color = if (isToday) AppColor.Accent else AppColor.TextSecondary
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ---- 网格 ----
        GridBody(
            data = data,
            courses = courses
        )

        Spacer(Modifier.height(100.dp))
    }
}

/** 一排横向滚动的周次小胶囊，比「左右箭头点点点」快得多 */
@Composable
private fun WeekStrip(
    data: TermData,
    selected: Int,
    onPickWeek: (Int) -> Unit,
    onBackToday: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(data.weeks.size) { idx ->
                val w = data.weeks[idx]
                val active = w == selected
                val isCurrent = w == data.currentWeek
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(
                            when {
                                active -> AppColor.Accent
                                isCurrent -> AppColor.AccentSoft
                                else -> AppColor.SoftBg
                            }
                        )
                        .clickable { onPickWeek(w) }
                        .padding(horizontal = 13.dp, vertical = 7.dp)
                ) {
                    Text(
                        "$w",
                        style = AppType.meta.copy(
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                        ),
                        color = when {
                            active -> Color.White
                            isCurrent -> AppColor.Accent
                            else -> AppColor.TextSecondary
                        }
                    )
                }
            }
        }
        if (selected != data.currentWeek) {
            Spacer(Modifier.width(8.dp))
            Text(
                "回本周",
                style = AppType.meta.copy(fontWeight = FontWeight.SemiBold),
                color = AppColor.Accent,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { onBackToday() }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}

/**
 * 网格主体。
 *
 * 分成两层：底层是按节次铺的浅色格 + 左侧时间轴；上层按 weekday 定位到列，
 * 按 periodStart 定位到行，把课程块画上去。
 */
@Composable
private fun GridBody(data: TermData, courses: List<Course>) {
    val rows = data.maxPeriod
    val gridHeight = SLOT_HEIGHT * rows + GAP * (rows - 1)

    Row(Modifier.fillMaxWidth()) {
        // 左侧时间轴：每节课的起始时间 + 节次号
        Column(Modifier.width(AXIS_WIDTH)) {
            repeat(rows) { i ->
                val period = i + 1
                val start = data.periodTime(period).substringBefore("-")
                Column(
                    Modifier
                        .height(SLOT_HEIGHT)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "$period",
                        style = AppType.tiny.copy(fontWeight = FontWeight.SemiBold),
                        color = AppColor.TextSecondary
                    )
                    Text(
                        start,
                        style = AppType.tiny.copy(fontSize = 9.sp),
                        color = AppColor.TextTertiary,
                        maxLines = 1
                    )
                }
                if (i != rows - 1) Spacer(Modifier.height(GAP))
            }
        }

        Spacer(Modifier.width(2.dp))

        // 7 列
        Row(Modifier.fillMaxWidth()) {
            repeat(7) { dayIdx ->
                val dayCourses = courses.filter { it.weekday == dayIdx + 1 }
                Box(
                    Modifier
                        .weight(1f)
                        .height(gridHeight)
                ) {
                    // 底色格子
                    Column(Modifier.fillMaxHeight()) {
                        repeat(rows) { i ->
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(SLOT_HEIGHT)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(AppColor.SoftBg.copy(alpha = 0.6f))
                            )
                            if (i != rows - 1) Spacer(Modifier.height(GAP))
                        }
                    }

                    // 课程块
                    dayCourses.forEach { c ->
                        val top = SLOT_HEIGHT * (c.periodStart - 1) + GAP * (c.periodStart - 1)
                        val h = SLOT_HEIGHT * c.span + GAP * (c.span - 1)
                        CourseBlock(
                            course = c,
                            modifier = Modifier
                                .padding(horizontal = 1.dp)
                                .padding(top = top)
                                .fillMaxWidth()
                                .height(h)
                        )
                    }
                }
                if (dayIdx != 6) Spacer(Modifier.width(3.dp))
            }
        }
    }
}

/** 一个课程块：高饱和纯色 + 白字，文字按空间自适应 */
@Composable
private fun CourseBlock(course: Course, modifier: Modifier = Modifier) {
    val color = AppColor.courseColor(course.name)
    val compact = course.span <= 1

    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color)
            .padding(horizontal = 3.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            course.name,
            style = AppType.tiny.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = if (compact) 9.sp else 10.sp,
                lineHeight = if (compact) 11.sp else 12.sp
            ),
            color = Color.White,
            maxLines = if (compact) 3 else 5,
            overflow = TextOverflow.Ellipsis
        )
        if (!compact && course.classroom.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                course.classroom,
                style = AppType.tiny.copy(fontSize = 8.sp, lineHeight = 10.sp),
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ---------------------------------------------------------------- 辅助

/** 这一周的日期跨度，比如「11.3 - 11.9」 */
private fun monthRange(dates: List<LocalDate>): String {
    val first = dates.firstOrNull() ?: return ""
    val last = dates.lastOrNull() ?: return ""
    return "${first.monthValue}.${first.dayOfMonth} - ${last.monthValue}.${last.dayOfMonth}"
}
