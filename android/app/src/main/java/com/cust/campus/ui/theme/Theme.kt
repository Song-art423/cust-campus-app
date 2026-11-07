package com.cust.campus.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 设计令牌。
 *
 * 参考的是长理星球那套视觉语言：纯白底 + 白色卡片 + 极淡投影 + 紫色作唯一强调色，
 * 课表用高饱和纯色块配白字。刻意不用 Material You 动态取色，
 * 否则每台手机上颜色都不一样，视觉控制不住。
 */
object AppColor {
    val Bg = Color(0xFFFFFFFF)
    val Card = Color(0xFFFFFFFF)
    val SoftBg = Color(0xFFF6F6F9)

    val TextPrimary = Color(0xFF111114)
    val TextSecondary = Color(0xFF8E8E93)
    val TextTertiary = Color(0xFFB4B4BA)

    val Accent = Color(0xFF5B5BD6)
    val AccentSoft = Color(0xFFEFEFFC)

    val Divider = Color(0xFFF0F0F3)

    val Danger = Color(0xFFFF3B30)
    val DangerSoft = Color(0xFFFDECEA)
    val Warning = Color(0xFFFF9500)
    val Success = Color(0xFF34C759)
    val SuccessSoft = Color(0xFFE9F7EE)

    /** 课表色板。同一个课名永远拿到同一个颜色，靠 hashCode 取模 */
    val Course = listOf(
        Color(0xFF2E9BFF), // 蓝
        Color(0xFFF2A115), // 琥珀
        Color(0xFF00C2D1), // 青
        Color(0xFFFF7A1A), // 橙
        Color(0xFF34C759), // 绿
        Color(0xFFFF3B30), // 红
        Color(0xFF00BFA6), // 蓝绿
        Color(0xFF9B51E0)  // 紫
    )

    fun courseColor(name: String): Color =
        Course[((name.hashCode() % Course.size) + Course.size) % Course.size]
}

/** 字号体系。中文界面下 11sp 以下的辅助文字基本看不清，所以下限设在 10sp */
object AppType {
    val pageTitle = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp)
    val pageSubtitle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal)
    val cardTitle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold)
    val itemTitle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal)
    val meta = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal)
    val tiny = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Normal)
    val statBig = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold)
}

private val LightColors = lightColorScheme(
    primary = AppColor.Accent,
    onPrimary = Color.White,
    primaryContainer = AppColor.AccentSoft,
    onPrimaryContainer = AppColor.Accent,
    background = AppColor.Bg,
    onBackground = AppColor.TextPrimary,
    surface = AppColor.Card,
    onSurface = AppColor.TextPrimary,
    surfaceVariant = AppColor.SoftBg,
    onSurfaceVariant = AppColor.TextSecondary,
    outline = AppColor.Divider,
    error = AppColor.Danger
)

private val AppTypography = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Normal)
)

@Composable
fun CustCampusTheme(content: @Composable () -> Unit) {
    // 参考产品是纯亮色设计。暗色模式各家做法差异大，这里先固定亮色，
    // 强行跟随系统反而会出现「白卡片配黑底」的割裂感。
    MaterialTheme(
        colorScheme = LightColors,
        typography = AppTypography,
        content = content
    )
}
