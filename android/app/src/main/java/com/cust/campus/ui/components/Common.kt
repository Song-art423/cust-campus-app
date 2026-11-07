package com.cust.campus.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cust.campus.ui.theme.AppColor
import com.cust.campus.ui.theme.AppType

/** 白色圆角卡片，配一层几乎看不见的投影 —— 整个 App 的基础容器 */
@Composable
fun SoftCard(
    modifier: Modifier = Modifier,
    radius: Dp = 22.dp,
    padding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .shadow(
                elevation = 10.dp,
                shape = RoundedCornerShape(radius),
                clip = false,
                ambientColor = Color(0x0D000000),
                spotColor = Color(0x14000000)
            )
            .clip(RoundedCornerShape(radius))
            .background(AppColor.Card)
            .padding(padding),
        content = content
    )
}

/** 卡片标题行：左边标题，右边可选尾巴（比如「更新于 xx」或一个图标按钮） */
@Composable
fun CardHeader(
    title: String,
    modifier: Modifier = Modifier,
    badge: String? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = AppType.cardTitle, color = AppColor.TextPrimary)
        if (!badge.isNullOrBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(
                badge,
                style = AppType.meta,
                color = AppColor.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** 灰色小字，用于「更新于 xx」这类辅助信息 */
@Composable
fun MetaText(text: String, modifier: Modifier = Modifier, color: Color = AppColor.TextSecondary) {
    Text(text, style = AppType.meta, color = color, modifier = modifier)
}

@Composable
fun HairLine(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(AppColor.Divider)
    )
}

/** 圆形的小图标按钮，参考产品顶部那一排白色圆钮 */
@Composable
fun CircleIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = AppColor.TextPrimary,
    contentDesc: String = ""
) {
    Box(
        modifier = modifier
            .size(38.dp)
            .shadow(
                elevation = 6.dp,
                shape = CircleShape,
                clip = false,
                ambientColor = Color(0x0D000000),
                spotColor = Color(0x14000000)
            )
            .clip(CircleShape)
            .background(AppColor.Card)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDesc, tint = tint, modifier = Modifier.size(19.dp))
    }
}

/** 淡色小标签，比如「必修」「季」 */
@Composable
fun SoftTag(text: String, color: Color = AppColor.Accent, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = AppType.tiny.copy(fontWeight = FontWeight.SemiBold),
        color = color,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/** 空状态提示 */
@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(vertical = 22.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = AppType.body, color = AppColor.TextTertiary)
    }
}

/**
 * 顶部那条极细的同步进度。
 *
 * 这是「不再有加载页」的关键：刷新时只在标题下面滑一条 2dp 的进度线，
 * 内容和滚动位置完全不动，用户没有等待感。
 */
@Composable
fun SyncLine(progress: Float, label: String, modifier: Modifier = Modifier) {
    val p by animateFloatAsState(targetValue = progress.coerceIn(0f, 1f), label = "sync")
    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(AppColor.AccentSoft)
        ) {
            LinearProgressIndicator(
                progress = { if (p <= 0f) 0.05f else p },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = AppColor.Accent,
                trackColor = Color.Transparent
            )
        }
        if (label.isNotBlank()) {
            Text(
                label,
                style = AppType.tiny,
                color = AppColor.TextTertiary,
                modifier = Modifier.padding(start = 20.dp, top = 6.dp)
            )
        }
    }
}

/** 底部导航的标签 */
enum class Tab(val label: String, val icon: ImageVector) {
    Overview("概览", Icons.Filled.Home),
    Timetable("课表", Icons.Filled.DateRange),
    Settings("我的", Icons.Filled.Person)
}

/** 悬浮胶囊底部导航 */
@Composable
fun FloatingBottomBar(
    current: Tab,
    onSelect: (Tab) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .shadow(
                elevation = 18.dp,
                shape = CircleShape,
                clip = false,
                ambientColor = Color(0x14000000),
                spotColor = Color(0x1F000000)
            )
            .clip(CircleShape)
            .background(AppColor.Card)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Tab.entries.forEach { tab ->
            val active = tab == current
            val bg by animateColorAsState(
                if (active) AppColor.AccentSoft else Color.Transparent,
                label = "navBg"
            )
            val fg by animateColorAsState(
                if (active) AppColor.Accent else AppColor.TextSecondary,
                label = "navFg"
            )
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(bg)
                    .clickable { onSelect(tab) }
                    .padding(horizontal = if (active) 16.dp else 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(tab.icon, contentDescription = tab.label, tint = fg, modifier = Modifier.size(19.dp))
                if (active) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        tab.label,
                        style = AppType.meta.copy(fontWeight = FontWeight.SemiBold),
                        color = fg
                    )
                }
            }
        }
    }
}
