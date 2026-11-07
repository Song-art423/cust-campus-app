package com.cust.campus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.cust.campus.notify.CourseNotifier
import com.cust.campus.ui.components.CardHeader
import com.cust.campus.ui.components.HairLine
import com.cust.campus.ui.components.MetaText
import com.cust.campus.ui.components.SoftCard
import com.cust.campus.ui.components.SoftTag
import com.cust.campus.ui.theme.AppColor
import com.cust.campus.ui.theme.AppType
import com.cust.campus.work.AlarmDebug
import kotlinx.coroutines.delay

/**
 * 提醒调试面板。
 *
 * 存在的唯一理由：课前弹窗只在上课前才会触发，想验证一次就得等到那节课。
 * 这里把它压缩成 10 秒 —— 并且能直接告诉你「不响」到底是权限、渠道还是算法问题。
 *
 * 四件套递进关系：
 *   体检 → 最简通知（排除装饰干扰）→ 预演（验证完整通知）→ 真链路（验证闹钟调度）
 */
@Composable
fun RemindDebugCard(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current

    var health by remember { mutableStateOf(AlarmDebug.health(ctx)) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var dump by remember { mutableStateOf<String?>(null) }
    var hasBackup by remember { mutableStateOf(AlarmDebug.hasBackup(ctx)) }

    // 从系统设置页授权完回来，状态要自己更新，否则用户以为没生效
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                health = AlarmDebug.health(ctx)
                hasBackup = AlarmDebug.hasBackup(ctx)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    // 反馈文本自己消失，不用手动清
    LaunchedEffect(feedback) {
        if (feedback != null) {
            delay(8000)
            feedback = null
        }
    }

    // 投递结果统一在面板上回显：三种结果对应三种完全不同的排查方向
    val report: (CourseNotifier.PostResult) -> Unit = { r ->
        feedback = when (r) {
            is CourseNotifier.PostResult.Ok ->
                "成功：系统已收下，去通知栏 / 锁屏看一眼"
            is CourseNotifier.PostResult.Swallowed ->
                "系统收下但没落进通知列表 —— 权限 / 渠道 / ROM 拦截，不是代码问题"
            is CourseNotifier.PostResult.Failed -> "投递失败：${r.reason}"
        }
    }

    SoftCard(modifier.fillMaxWidth()) {
        CardHeader(
            title = "提醒调试",
            trailing = {
                val ok = health.blockers.isEmpty()
                SoftTag(
                    if (ok) "环境就绪" else "${health.blockers.size} 项待修",
                    color = if (ok) AppColor.Success else AppColor.Warning
                )
            }
        )
        Spacer(Modifier.height(6.dp))
        MetaText("不用等上课时间，最快 2 秒验证一次", color = AppColor.TextTertiary)

        Spacer(Modifier.height(12.dp))

        // ---- 体检 ----
        HealthRow("通知权限", health.notifyPermission, "去授予") {
            AlarmDebug.openNotificationSettings(ctx)
        }
        HairLine()
        // 权限给了不代表能发：系统设置里的应用级总开关是独立的第二道闸
        HealthRow(
            label = "通知总开关",
            ok = health.appNotificationsEnabled,
            action = "去打开",
            okText = "已开启"
        ) {
            AlarmDebug.openNotificationSettings(ctx)
        }
        HairLine()
        // 单看「渠道没被关」不够 —— 关着和「开着但等级低」都表现为「不弹」
        HealthRow(
            label = "通知渠道",
            ok = health.canHeadsUp,
            action = "去调高",
            okText = health.importanceLabel
        ) {
            AlarmDebug.openChannelSettings(ctx)
        }
        HairLine()
        // 震动单独开关：开了走独立渠道（渠道的震动属性创建后锁死，只能靠新 id）
        HealthRow(
            label = "震动提醒",
            ok = health.vibrateEnabled,
            action = "去开启",
            okText = "已开启"
        ) {
            AlarmDebug.setVibrateEnabled(ctx, !health.vibrateEnabled)
            health = AlarmDebug.health(ctx)
        }
        HairLine()
        HealthRow("精确闹钟", health.exactAlarm, "去允许") {
            AlarmDebug.openExactAlarmSettings(ctx)
        }
        HairLine()
        HealthRow("电池不受限", health.batteryUnrestricted, "去设置") {
            AlarmDebug.openBatterySettings(ctx)
        }
        HairLine()
        // 这项决定「黑屏时是点亮屏幕弹页面，还是只在锁屏上躺一条横幅」
        HealthRow(
            label = "全屏通知（系统权限）",
            ok = health.fullScreenSystemAllow,
            action = "去允许",
            okText = "已允许"
        ) {
            AlarmDebug.openFullScreenIntentSettings(ctx)
        }
        HairLine()
        // 本 App 自己的开关，用于隔离「挂了全屏 Intent 通知就消失」的机型。默认关
        HealthRow(
            label = "熄屏全屏提醒（可选）",
            ok = health.fullScreenEnabled,
            action = "去开启",
            okText = if (health.fullScreenSystemAllow) "已开启" else "已开 · 系统未授权"
        ) {
            AlarmDebug.setFullScreenEnabled(ctx, !health.fullScreenEnabled)
            health = AlarmDebug.health(ctx)
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                MetaText(
                    "小米/红米还需在「其他权限 → 锁屏显示」里允许，否则页面会被挡在锁屏后面",
                    color = AppColor.TextTertiary
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                "去开启",
                style = AppType.meta.copy(fontWeight = FontWeight.SemiBold),
                color = AppColor.Accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(AppColor.AccentSoft)
                    .clickable { AlarmDebug.openOemLockScreenPermission(ctx) }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }

        Spacer(Modifier.height(12.dp))

        // ---- 今日排程概况 ----
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(AppColor.SoftBg)
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Column {
                Text(
                    if (health.hasTodayCache) {
                        "今天 ${health.todayCourseCount} 节课 · 待触发 ${health.plannedCount} 个提醒"
                    } else {
                        "今天没有课表缓存 —— 排程无法进行，先用下面「真链路」注入测试课"
                    },
                    style = AppType.meta,
                    color = if (health.hasTodayCache) AppColor.TextPrimary else AppColor.Warning
                )
                health.nextInSeconds?.let { s ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "下一个提醒在 ${formatLeft(s)} 后",
                        style = AppType.meta,
                        color = AppColor.TextSecondary
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ---- 操作 ----
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DebugButton("最简通知 2s", primary = false, modifier = Modifier.weight(1f)) {
                feedback = AlarmDebug.previewBare(ctx, onResult = report)
            }
            DebugButton("完整通知 3s", primary = false, modifier = Modifier.weight(1f)) {
                feedback = AlarmDebug.previewNow(ctx, onResult = report)
            }
        }

        Spacer(Modifier.height(8.dp))

        DebugButton("10 秒真链路", primary = true, modifier = Modifier.fillMaxWidth()) {
            feedback = AlarmDebug.testFullChain(ctx)
            health = AlarmDebug.health(ctx)
            hasBackup = AlarmDebug.hasBackup(ctx)
        }

        Spacer(Modifier.height(8.dp))

        DebugButton("熄屏预演 · 8 秒后按电源键锁屏看效果", primary = true, modifier = Modifier.fillMaxWidth()) {
            feedback = AlarmDebug.previewLockScreen(ctx, onResult = report)
        }

        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DebugButton("快进 60 分钟看排程", primary = false, modifier = Modifier.weight(1f)) {
                dump = AlarmDebug.dumpSchedule(ctx, 60)
            }
            if (hasBackup) {
                DebugButton("恢复真实课表", primary = false, modifier = Modifier.weight(1f)) {
                    feedback = AlarmDebug.restoreRealCache(ctx)
                    health = AlarmDebug.health(ctx)
                    hasBackup = false
                    dump = null
                }
            }
        }

        if (feedback != null) {
            Spacer(Modifier.height(10.dp))
            Text(feedback!!, style = AppType.meta, color = AppColor.Accent)
        }

        if (dump != null) {
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(AppColor.SoftBg)
                    .padding(12.dp)
            ) {
                Text(
                    dump!!,
                    style = AppType.meta.copy(fontFamily = FontFamily.Monospace),
                    color = AppColor.TextPrimary
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        MetaText(
            "「最简通知」只带图标和文字，用来判断是权限/渠道问题还是样式问题：" +
                "它不弹就说明跟界面无关；它弹而完整通知不弹，就关掉「熄屏全屏提醒」再试一次。",
            color = AppColor.TextTertiary
        )
    }
}

@Composable
private fun HealthRow(
    label: String,
    ok: Boolean,
    action: String,
    okText: String = "正常",
    onFix: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (ok) AppColor.Success else AppColor.Danger)
        )
        Spacer(Modifier.width(9.dp))
        Text(label, style = AppType.body, color = AppColor.TextPrimary)

        Spacer(Modifier.weight(1f))

        if (ok) {
            MetaText(okText, color = AppColor.Success)
        } else {
            Text(
                action,
                style = AppType.meta.copy(fontWeight = FontWeight.SemiBold),
                color = AppColor.Accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(AppColor.AccentSoft)
                    .clickable(onClick = onFix)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }
    }
}

@Composable
private fun DebugButton(
    text: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (primary) AppColor.Accent else AppColor.SoftBg)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = AppType.meta.copy(fontWeight = FontWeight.SemiBold),
            color = if (primary) Color.White else AppColor.TextPrimary
        )
    }
}

private fun formatLeft(seconds: Long): String {
    if (seconds <= 0) return "不到 1 分钟"
    val m = seconds / 60
    return if (m >= 60) "${m / 60} 小时 ${m % 60} 分钟" else "$m 分钟"
}
