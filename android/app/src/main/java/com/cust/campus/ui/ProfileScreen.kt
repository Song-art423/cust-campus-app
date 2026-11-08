package com.cust.campus.ui

import android.os.SystemClock
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cust.campus.BuildConfig
import com.cust.campus.data.model.TermData
import com.cust.campus.ui.components.CardHeader
import com.cust.campus.ui.components.HairLine
import com.cust.campus.ui.components.MetaText
import com.cust.campus.ui.components.SoftCard
import com.cust.campus.ui.components.SoftTag
import com.cust.campus.ui.theme.AppColor
import com.cust.campus.ui.theme.AppType

/**
 * 「我的」页：提醒设置、数据管理、关于。
 * 全部是本地设置，不需要网络。
 */
@Composable
fun ProfileScreen(
    data: TermData?,
    syncing: Boolean,
    syncLabel: String,
    remindEnabled: Boolean,
    remindAdvance: Int,
    cookieText: String,
    cookieOk: Boolean,
    onSetRemindEnabled: (Boolean) -> Unit,
    onSetAdvance: (Int) -> Unit,
    onRefresh: () -> Unit,
    onClearAndResync: () -> Unit,
    onLogin: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current

    // 调试面板是否可见：debug 包默认开、release 包默认关，
    // 连点版本号 7 次可随时切换（同一个手势收起来）。
    var debugOn by remember { mutableStateOf(DebugGate.isOn(ctx)) }
    var taps by remember { mutableIntStateOf(0) }
    var lastTapAt by remember { mutableLongStateOf(0L) }

    /**
     * 连点计数。间隔超过窗口就重新数，避免「一天里零散点 7 次」被误判成解锁，
     * 这也是 Android 系统设置里连点版本号的做法。
     */
    fun onVersionTap() {
        val now = SystemClock.elapsedRealtime()
        taps = if (lastTapAt != 0L && now - lastTapAt <= DebugGate.TAP_WINDOW_MS) taps + 1 else 1
        lastTapAt = now
        if (taps >= DebugGate.UNLOCK_TAPS) {
            taps = 0
            lastTapAt = 0L
            val next = !debugOn
            DebugGate.setOn(ctx, next)
            debugOn = next
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Text("我的", style = AppType.pageTitle, color = AppColor.TextPrimary)
        Spacer(Modifier.height(2.dp))
        MetaText(if (data != null) "${data.term} · 已缓存 ${data.weeks.size} 周" else "尚未同步数据")

        Spacer(Modifier.height(18.dp))

        // ---- 登录凭据 ----
        // 放在第一位：这是「免重复登录」到底有没有生效的唯一可信依据，
        // 别让用户再靠「明天还能不能用」去赌。
        SoftCard(Modifier.fillMaxWidth()) {
            CardHeader(
                title = "登录凭据",
                trailing = {
                    SoftTag(
                        if (cookieOk) "有效" else "未就绪",
                        color = if (cookieOk) AppColor.Success else AppColor.Warning
                    )
                }
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (cookieOk) AppColor.Success else AppColor.TextTertiary)
                )
                Spacer(Modifier.width(8.dp))
                Text(cookieText, style = AppType.itemTitle, color = AppColor.TextPrimary)
            }
            Spacer(Modifier.height(6.dp))
            MetaText(
                if (cookieOk) "已加密保存到本机，服务端滑动续期，14 天内打开过一次就不会掉"
                else "点下面「重新登录」补一次，之后就不用再管了",
                color = AppColor.TextTertiary
            )
        }

        Spacer(Modifier.height(14.dp))

        // ---- 上课提醒 ----
        SoftCard(Modifier.fillMaxWidth()) {
            CardHeader(
                title = "上课提醒",
                trailing = {
                    Switch(
                        checked = remindEnabled,
                        onCheckedChange = onSetRemindEnabled,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = AppColor.Accent,
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = AppColor.TextTertiary.copy(alpha = 0.4f)
                        )
                    )
                }
            )
            Spacer(Modifier.height(4.dp))
            MetaText(if (remindEnabled) "课前 ${remindAdvance} 分钟提醒" else "已关闭")

            if (remindEnabled) {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 15, 20, 30).forEach { m ->
                        val active = m == remindAdvance
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(11.dp))
                                .background(if (active) AppColor.Accent else AppColor.SoftBg)
                                .clickable { onSetAdvance(m) }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "$m",
                                style = AppType.meta.copy(
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                                ),
                                color = if (active) Color.White
                                else AppColor.TextSecondary
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ---- 调试区 ----
        // 默认不渲染。debug 包首次安装可见，release 包要连点版本号 7 次才出现。
        // 这两张卡会露出保险库快照、探活路径、渠道 id，不适合给普通用户看到。
        if (debugOn) {
            Spacer(Modifier.height(14.dp))

            // 登录态诊断：掉 Cookie 在手机端完全不可见，
            // 这里把采集 / 保险库 / 探活 / 续期四环摊开
            CookieDebugCard()

            Spacer(Modifier.height(14.dp))

            // 提醒调试：课前弹窗没法靠「等」来测，给一套秒级验证手段
            RemindDebugCard()
        }

        Spacer(Modifier.height(14.dp))

        // ---- 数据 ----
        SoftCard(Modifier.fillMaxWidth()) {
            CardHeader(title = "数据", badge = if (syncing) syncLabel.ifBlank { "同步中" } else null)
            Spacer(Modifier.height(10.dp))

            SettingRow(
                title = "立即同步",
                subtitle = if (syncing) "正在进行…" else "后台更新最近数据，不打断浏览",
                enabled = !syncing,
                onClick = onRefresh
            )
            HairLine()
            SettingRow(
                title = "清除缓存并重新同步",
                subtitle = "数据错乱时用这个，会重拉整个学期",
                enabled = !syncing,
                danger = true,
                onClick = onClearAndResync
            )
            HairLine()
            SettingRow(
                title = "重新登录",
                subtitle = "登录态失效时用",
                onClick = onLogin
            )
        }

        Spacer(Modifier.height(14.dp))

        // ---- 关于 ----
        SoftCard(Modifier.fillMaxWidth()) {
            CardHeader(title = "关于")
            Spacer(Modifier.height(10.dp))
            // 版本号取自 BuildConfig，不再硬编码 —— 它是解锁调试面板的入口，
            // 显示错了就没人能对着说明找到该点哪一行
            AboutRow("客户端版本", BuildConfig.VERSION_NAME, onClick = { onVersionTap() })
            HairLine()
            AboutRow("数据来源", "tls.ccsut.cn")
            HairLine()
            Box(Modifier.padding(vertical = 12.dp)) {
                MetaText(
                    if (debugOn) {
                        "调试面板已开放 · 再连点版本号 ${DebugGate.UNLOCK_TAPS} 次可收起"
                    } else {
                        "课表数据为教务系统原始数据，仅供个人查询参考。"
                    },
                    color = if (debugOn) AppColor.Accent else AppColor.TextTertiary
                )
            }
        }

        Spacer(Modifier.height(100.dp))
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = AppType.itemTitle,
                color = when {
                    !enabled -> AppColor.TextTertiary
                    danger -> AppColor.Danger
                    else -> AppColor.TextPrimary
                }
            )
            Spacer(Modifier.height(2.dp))
            MetaText(subtitle, color = AppColor.TextTertiary)
        }
        SoftTag(if (enabled) "›" else "…", color = AppColor.TextTertiary)
    }
}

/** 关于页的一行。传入 onClick 时整行可点（用于版本号连点解锁调试面板） */
@Composable
private fun AboutRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = AppType.body, color = AppColor.TextPrimary)
        Spacer(Modifier.weight(1f))
        MetaText(value)
    }
}
