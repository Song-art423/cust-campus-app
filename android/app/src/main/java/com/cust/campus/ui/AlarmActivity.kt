package com.cust.campus.ui

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.cust.campus.data.model.DayCourse
import com.cust.campus.notify.CourseNotifier
import com.cust.campus.work.CourseAlarmScheduler
import kotlinx.coroutines.delay
import java.time.LocalDateTime

/** 无人操作时的自动关闭时长：够看清内容，又不会一直亮着 */
private const val AUTO_DISMISS_MS = 90_000L

/**
 * 熄屏提醒页。
 *
 * 由 `Notification.setFullScreenIntent` 拉起 —— 这是 Android 10 之后**唯一**合法且可靠的
 * 「后台把自己的界面盖到锁屏上」的通道，普通 startActivity 会被后台启动限制拦掉。
 *
 * 行为对齐闹钟类应用：
 *   - 点亮屏幕、盖住锁屏（不需要解锁就能看到内容）
 *   - 保持屏幕常亮，直到用户明确关闭
 *   - 90 秒没操作自动退出，避免一整晚占着屏幕
 *
 * 没有全屏 Intent 权限时会退化成普通横幅通知（系统行为，本类根本不会被拉起），
 * 权限状态在「我的 → 提醒调试」里能查。
 */
class AlarmActivity : ComponentActivity() {

    companion object {
        const val EXTRA_NAME = "alarm_name"
        const val EXTRA_TEACHER = "alarm_teacher"
        const val EXTRA_CLASSROOM = "alarm_classroom"
        const val EXTRA_PERIOD_START = "alarm_period_start"
        const val EXTRA_PERIOD_END = "alarm_period_end"
        const val EXTRA_TIME_START = "alarm_time_start"
        const val EXTRA_TIME_END = "alarm_time_end"
        const val EXTRA_MINUTES_LEFT = "alarm_minutes_left"

        fun intent(context: Context, course: DayCourse, minutesLeft: Int): Intent =
            Intent(context, AlarmActivity::class.java).apply {
                putExtra(EXTRA_NAME, course.name)
                putExtra(EXTRA_TEACHER, course.teacher)
                putExtra(EXTRA_CLASSROOM, course.classroom)
                putExtra(EXTRA_PERIOD_START, course.periodStart)
                putExtra(EXTRA_PERIOD_END, course.periodEnd)
                putExtra(EXTRA_TIME_START, course.timeStart)
                putExtra(EXTRA_TIME_END, course.timeEnd)
                putExtra(EXTRA_MINUTES_LEFT, minutesLeft)
                // 从后台被拉起，不指定这个标志在某些 ROM 上会被判成非法启动
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ---- 锁屏穿透 + 点亮屏幕 ----
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        // 亮起来就别自己灭掉，否则用户刚看到屏幕就黑了
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView)
            .isAppearanceLightStatusBars = false

        val course = restoreCourse()
        val minutesLeft = intent.getIntExtra(EXTRA_MINUTES_LEFT, 0)

        setContent {
            AlarmContent(
                course = course,
                minutesLeft = minutesLeft,
                onDismiss = { dismiss(notifyAgain = false) },
                onSnooze = { dismiss(notifyAgain = true) }
            )
        }
    }

    /** 从 extras 还原课程。任一关键字段缺失就当作「未知课程」，界面降级显示 */
    private fun restoreCourse(): DayCourse? {
        val name = intent.getStringExtra(EXTRA_NAME)?.takeIf { it.isNotBlank() } ?: return null
        return DayCourse(
            name = name,
            teacher = intent.getStringExtra(EXTRA_TEACHER).orEmpty(),
            classroom = intent.getStringExtra(EXTRA_CLASSROOM).orEmpty(),
            periodStart = intent.getIntExtra(EXTRA_PERIOD_START, 1),
            periodEnd = intent.getIntExtra(EXTRA_PERIOD_END, 1),
            timeStart = intent.getStringExtra(EXTRA_TIME_START).orEmpty(),
            timeEnd = intent.getStringExtra(EXTRA_TIME_END).orEmpty()
        )
    }

    private fun dismiss(notifyAgain: Boolean) {
        val course = restoreCourse()
        if (course != null) {
            // 通知本体要一起收掉，否则用户关掉全屏页后通知栏还挂着一条
            runCatching {
                getSystemService(NotificationManager::class.java)
                    ?.cancel(CourseNotifier.ID_PREFIX_REMIND + course.periodStart)
            }
            if (notifyAgain) {
                runCatching {
                    CourseAlarmScheduler.setAlarm(
                        applicationContext, course, LocalDateTime.now().plusMinutes(5)
                    )
                }
            }
        }
        finish()
    }
}

@Composable
private fun AlarmContent(
    course: DayCourse?,
    minutesLeft: Int,
    onDismiss: () -> Unit,
    onSnooze: () -> Unit
) {
    // 无人操作时自动退出，避免占屏一整晚
    LaunchedEffect(Unit) {
        delay(AUTO_DISMISS_MS)
        onDismiss()
    }

    val bg = Brush.verticalGradient(listOf(Color(0xFF5B5BD6), Color(0xFF3B3BA8)))

    Box(
        Modifier
            .fillMaxSize()
            .background(bg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 28.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "上课提醒",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xCCFFFFFF),
                letterSpacing = 2.sp
            )

            Spacer(Modifier.height(14.dp))

            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    if (minutesLeft <= 1) "即将开始" else "$minutesLeft",
                    fontSize = if (minutesLeft <= 1) 40.sp else 56.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )
                if (minutesLeft > 1) {
                    Text(
                        "分钟后上课",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xCCFFFFFF),
                        modifier = Modifier.padding(bottom = 10.dp, start = 8.dp)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Text(
                course?.name ?: "下一节课",
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                lineHeight = 38.sp
            )

            Spacer(Modifier.height(18.dp))

            if (course != null) {
                val detail = buildList {
                    course.classroom.takeIf { it.isNotBlank() }?.let { add(it) }
                    add(course.periodText)
                }.joinToString(" · ")

                Text(
                    detail,
                    fontSize = 15.sp,
                    color = Color(0xD9FFFFFF),
                    lineHeight = 22.sp
                )
                if (course.timeStart.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        course.timeText,
                        fontSize = 15.sp,
                        color = Color(0xD9FFFFFF)
                    )
                }
                if (course.teacher.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        course.teacher,
                        fontSize = 13.sp,
                        color = Color(0x99FFFFFF)
                    )
                }
            }

            Spacer(Modifier.height(44.dp))

            // 主操作：白色实心
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .background(Color.White, RoundedCornerShape(16.dp))
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "知道了",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF3B3BA8),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(12.dp))

            // 次操作：描边
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .background(Color(0x26FFFFFF), RoundedCornerShape(16.dp))
                    .clickable(onClick = onSnooze),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "5 分钟后再提醒",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
            }
        }
    }
}
