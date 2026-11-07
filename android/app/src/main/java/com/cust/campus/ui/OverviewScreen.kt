package com.cust.campus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cust.campus.data.model.DayCourse
import com.cust.campus.data.model.TermData
import com.cust.campus.ui.components.CardHeader
import com.cust.campus.ui.components.EmptyHint
import com.cust.campus.ui.components.HairLine
import com.cust.campus.ui.components.MetaText
import com.cust.campus.ui.components.SoftCard
import com.cust.campus.ui.components.SoftTag
import com.cust.campus.ui.theme.AppColor
import com.cust.campus.ui.theme.AppType
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val WEEKDAY_SHORT = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

@Composable
fun OverviewScreen(
    data: TermData,
    onOpenWeek: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    // 每 30 秒刷新一次「当前时间」，倒计时才不会僵在打开 App 那一刻
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalTime.now()
            delay(30_000)
        }
    }

    val todayCourses = if (data.isToday) data.todayCourses else emptyList()
    val todayText = remember(data.todayDate) { prettyDate(data.todayDate) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        // ---- 大标题 ----
        Text("概览", style = AppType.pageTitle, color = AppColor.TextPrimary)
        Spacer(Modifier.height(2.dp))
        MetaText("$todayText · 第 ${data.currentWeek} 周 · ${data.term}")

        Spacer(Modifier.height(18.dp))

        // ---- 今日课程 ----
        SoftCard(Modifier.fillMaxWidth()) {
            CardHeader(
                title = "今日课程",
                badge = if (todayCourses.isEmpty()) null else "${todayCourses.size} 节",
                trailing = {
                    SoftTag("第 ${data.currentWeek} 周")
                }
            )
            Spacer(Modifier.height(14.dp))

            if (todayCourses.isEmpty()) {
                EmptyHint(if (data.isToday) "今天没课，放松一下" else "不是今天的数据，去课表页看看")
            } else {
                todayCourses.forEachIndexed { index, c ->
                    TodayCourseRow(c)
                    if (index != todayCourses.lastIndex) Spacer(Modifier.height(8.dp))
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ---- 下一节课 ----
        NextCourseCard(todayCourses, now)

        Spacer(Modifier.height(14.dp))

        // ---- 本周分布 ----
        WeekGlanceCard(
            data = data,
            onOpenWeek = onOpenWeek
        )

        Spacer(Modifier.height(96.dp))
    }
}

/** 今日课程行：淡色底 + 左侧竖色条，和参考产品一致 */
@Composable
private fun TodayCourseRow(c: DayCourse) {
    val color = AppColor.courseColor(c.name)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.10f)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧竖条：靠 IntrinsicSize.Min 撑满行高
        Box(
            Modifier
                .fillMaxHeight()
                .padding(vertical = 8.dp)
                .width(4.dp)
                .background(color, RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.padding(vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    c.name,
                    style = AppType.itemTitle,
                    color = AppColor.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(color)
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                listOf(c.classroom, c.teacher).filter { it.isNotBlank() }.joinToString(" · "),
                style = AppType.meta,
                color = AppColor.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(vertical = 12.dp)) {
            Text(c.timeStart, style = AppType.meta, color = AppColor.TextPrimary)
            Text(c.timeEnd, style = AppType.meta, color = AppColor.TextSecondary)
        }
    }
}

@Composable
private fun NextCourseCard(courses: List<DayCourse>, now: LocalTime) {
    val next = remember(courses, now) { findNext(courses, now) }

    SoftCard(Modifier.fillMaxWidth()) {
        CardHeader(title = "下一节课")
        Spacer(Modifier.height(12.dp))

        when {
            next == null -> Text(
                "今天的课已经全部结束",
                style = AppType.itemTitle,
                color = AppColor.TextSecondary
            )

            next.inProgress -> {
                Text(next.course.name, style = AppType.cardTitle, color = AppColor.TextPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("正在上课", style = AppType.statBig, color = AppColor.Success)
                    Spacer(Modifier.width(8.dp))
                    Text("还剩 ${next.minutesLeft} 分钟", style = AppType.meta,
                        color = AppColor.TextSecondary, modifier = Modifier.padding(bottom = 6.dp))
                }
            }

            else -> {
                Text(next.course.name, style = AppType.cardTitle, color = AppColor.TextPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("还有", style = AppType.meta, color = AppColor.TextSecondary,
                        modifier = Modifier.padding(bottom = 8.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(formatLeft(next.minutesLeft), style = AppType.statBig, color = AppColor.Accent)
                }
            }
        }

        if (next != null) {
            Spacer(Modifier.height(10.dp))
            HairLine()
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOf(next.course.classroom, next.course.teacher)
                        .filter { it.isNotBlank() }.joinToString(" · "),
                    style = AppType.meta,
                    color = AppColor.TextSecondary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${next.course.timeStart}-${next.course.timeEnd}",
                    style = AppType.meta,
                    color = AppColor.TextPrimary
                )
            }
        }
    }
}

@Composable
private fun WeekGlanceCard(data: TermData, onOpenWeek: (Int) -> Unit) {
    val weekCourses = data.coursesOf(data.currentWeek)
    val perDay = (1..7).map { d -> weekCourses.count { it.weekday == d } }

    SoftCard(Modifier.fillMaxWidth()) {
        CardHeader(
            title = "本周概览",
            badge = "共 ${weekCourses.size} 节",
            trailing = {
                Text(
                    "查看课表 ›",
                    style = AppType.meta.copy(fontWeight = FontWeight.SemiBold),
                    color = AppColor.Accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(AppColor.AccentSoft)
                        .clickable { onOpenWeek(data.currentWeek) }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        )
        Spacer(Modifier.height(16.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            perDay.forEachIndexed { index, count ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // 用高度表示当天课量，最直观
                    Box(
                        Modifier
                            .width(22.dp)
                            .height((8 + count * 7).coerceAtMost(56).dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(
                                if (count == 0) AppColor.SoftBg
                                else AppColor.Accent.copy(alpha = 0.18f + (count * 0.12f).coerceAtMost(0.5f))
                            )
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        WEEKDAY_SHORT[index].drop(1),
                        style = AppType.tiny,
                        color = AppColor.TextSecondary
                    )
                    Text(
                        if (count == 0) "—" else "$count",
                        style = AppType.tiny.copy(fontWeight = FontWeight.SemiBold),
                        color = if (count == 0) AppColor.TextTertiary else AppColor.TextPrimary
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 工具

private data class NextInfo(
    val course: DayCourse,
    val minutesLeft: Long,
    val inProgress: Boolean
)

private fun parseTime(raw: String): LocalTime? = runCatching {
    val parts = raw.trim().split(":")
    LocalTime.of(parts[0].toInt(), parts.getOrElse(1) { "0" }.toInt())
}.getOrNull()

/**
 * 找下一节课。
 * 优先返回「正在进行」的课；否则返回最近一节还没开始的。都没有就返回 null。
 */
private fun findNext(courses: List<DayCourse>, now: LocalTime): NextInfo? {
    val parsed = courses.mapNotNull { c ->
        val s = parseTime(c.timeStart) ?: return@mapNotNull null
        val e = parseTime(c.timeEnd) ?: return@mapNotNull null
        Triple(c, s, e)
    }.sortedBy { it.second }

    parsed.firstOrNull { now >= it.second && now <= it.third }?.let { (c, _, e) ->
        return NextInfo(c, java.time.Duration.between(now, e).toMinutes().coerceAtLeast(0), true)
    }

    parsed.firstOrNull { it.second > now }?.let { (c, s, _) ->
        return NextInfo(c, java.time.Duration.between(now, s).toMinutes(), false)
    }

    return null
}

private fun formatLeft(minutes: Long): String {
    if (minutes <= 0) return "马上开始"
    if (minutes < 60) return "$minutes 分钟"
    val h = minutes / 60
    val m = minutes % 60
    return if (m == 0L) "$h 小时" else "$h 小时 $m 分"
}

private fun prettyDate(iso: String): String = runCatching {
    val d = LocalDate.parse(iso, DateTimeFormatter.ISO_DATE)
    val idx = d.dayOfWeek.value - 1
    "${d.monthValue}月${d.dayOfMonth}日 ${WEEKDAY_SHORT[idx]}"
}.getOrDefault(iso)
