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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.cust.campus.data.SessionKeeper
import com.cust.campus.data.api.CookieBridge
import com.cust.campus.data.api.CustApi
import com.cust.campus.data.api.SilentWebRefresh
import com.cust.campus.ui.components.CardHeader
import com.cust.campus.ui.components.HairLine
import com.cust.campus.ui.components.MetaText
import com.cust.campus.ui.components.SoftCard
import com.cust.campus.ui.components.SoftTag
import com.cust.campus.ui.theme.AppColor
import com.cust.campus.ui.theme.AppType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 登录态诊断。
 *
 * 存在理由：掉 Cookie 这件事在手机端完全不可见 —— 用户只能看到「又要重新登录」，
 * 而真正断掉的环节可能是采集、保险库、探活、续期中的任意一个。
 * 这里把每一环的真实数据摊开，并给出手动触发的入口，一次就能定位断在哪。
 */
@Composable
fun CookieDebugCard(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var diag by remember { mutableStateOf<CookieBridge.Diagnostics?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            diag = withContext(Dispatchers.IO) {
                runCatching { CookieBridge.diagnostics() }.getOrNull()
            }
        }
    }

    LaunchedEffect(Unit) { reload() }

    // 从系统设置 / 登录页回来要自己刷新，否则看到的还是旧数据
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reload()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(feedback) {
        if (feedback != null) {
            delay(6000)
            feedback = null
        }
    }

    SoftCard(modifier.fillMaxWidth()) {
        CardHeader(
            title = "登录态诊断",
            trailing = {
                val d = diag
                val ok = d != null && d.hasCredential
                SoftTag(
                    when {
                        d == null -> "读取中"
                        ok -> "凭据在"
                        d.hasTicket -> "仅票据"
                        else -> "无凭据"
                    },
                    color = when {
                        d == null -> AppColor.TextTertiary
                        ok -> AppColor.Success
                        d.hasTicket -> AppColor.Warning
                        else -> AppColor.Danger
                    }
                )
            }
        )

        Spacer(Modifier.height(10.dp))

        val d = diag
        if (d == null) {
            MetaText("正在读取…", color = AppColor.TextTertiary)
            return@SoftCard
        }

        // ---- 结论 ----
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (d.hasCredential) AppColor.SuccessSoft else AppColor.DangerSoft
                )
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(d.verdict, style = AppType.meta, color = AppColor.TextPrimary)
        }

        Spacer(Modifier.height(10.dp))

        // ---- 四个关键项 ----
        DiagRow("登录凭据 jw_uf*", d.hasCredential)
        HairLine()
        DiagRow("CAS 票据 CASTGC", d.hasTicket, soft = true)
        HairLine()
        DiagRow(
            "保险库快照",
            ok = d.vaultHasCredential,
            okText = if (d.vaultAgeHours < 0) "含凭据 · 时间未知"
            else "含凭据 · ${d.vaultAgeHours} 小时前",
            failText = "空或不含凭据"
        )
        HairLine()
        DiagRow(
            "本次会话回灌过",
            ok = d.recovered,
            okText = "是",
            failText = "否",
            soft = true
        )

        if (d.lastRestore.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            MetaText(d.lastRestore, color = AppColor.TextTertiary)
        }

        // ---- 探针明细 ----
        Spacer(Modifier.height(10.dp))
        Text(
            if (expanded) "收起 Cookie 明细" else "展开 Cookie 明细（${d.probes.size} 个路径）",
            style = AppType.meta.copy(fontWeight = FontWeight.SemiBold),
            color = AppColor.Accent,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp)
        )

        if (expanded) {
            Spacer(Modifier.height(6.dp))
            if (d.probes.isEmpty()) {
                MetaText("所有探针都取不到 cookie —— CookieManager 是空的", color = AppColor.Danger)
            } else {
                d.probes.forEach { line ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(
                            line.url,
                            style = AppType.meta.copy(fontFamily = FontFamily.Monospace),
                            color = AppColor.TextSecondary
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            line.names.joinToString(", ").ifBlank { "（空）" },
                            style = AppType.meta.copy(fontFamily = FontFamily.Monospace),
                            color = if (line.names.any { it.startsWith("jw_uf") })
                                AppColor.Success else AppColor.TextPrimary
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- 手动触发 ----
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DiagButton("强制回灌", busy, Modifier.weight(1f)) {
                busy = true
                scope.launch {
                    feedback = withContext(Dispatchers.IO) { CookieBridge.forceRestore() }
                    busy = false
                    reload()
                }
            }
            DiagButton("探活", busy, Modifier.weight(1f)) {
                busy = true
                scope.launch {
                    val alive = withContext(Dispatchers.IO) { CustApi().isSessionAlive() }
                    feedback = if (alive) "会话有效" else "会话已失效 —— 下一步试「页面刷新」或「静默续期」"
                    busy = false
                    reload()
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DiagButton("页面刷新（跑 JS）", busy, Modifier.weight(1f)) {
                busy = true
                scope.launch {
                    val ok = SilentWebRefresh.refresh(ctx)
                    val alive = if (ok) withContext(Dispatchers.IO) { CustApi().isSessionAlive() } else false
                    feedback = when {
                        !ok -> "页面加载失败 —— 后台可能被系统限制了"
                        alive -> "页面刷新成功，凭据已重新计算"
                        else -> "页面加载了但仍未登录 —— 服务端已判定失效，只能重新登录"
                    }
                    busy = false
                    reload()
                }
            }
            DiagButton("静默续期", busy, Modifier.weight(1f)) {
                busy = true
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { CustApi().silentRenew() }
                    feedback = if (ok) "CAS 续期成功，已换到新会话" else "CAS 票据也失效了"
                    busy = false
                    reload()
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        DiagButton("按四段式完整跑一遍 ensure()", busy, Modifier.fillMaxWidth(), primary = true) {
            busy = true
            scope.launch {
                val st = SessionKeeper.ensure(ctx)
                feedback = when (st) {
                    SessionKeeper.Status.ALIVE -> "会话正常"
                    SessionKeeper.Status.RENEWED -> "原本失效，已自动救回"
                    SessionKeeper.Status.EXPIRED -> "彻底失效，需要重新登录"
                }
                busy = false
                reload()
            }
        }

        if (feedback != null) {
            Spacer(Modifier.height(10.dp))
            Text(feedback!!, style = AppType.meta, color = AppColor.Accent)
        }

        Spacer(Modifier.height(10.dp))
        MetaText(
            "凭据由页面 JS 维护，OkHttp 的接口请求不会刷新它们。" +
                "所以「页面刷新」这一步是唯一能救回旧凭据的手段，" +
                "App 每次打开时也会自动跑一次（12 小时内只跑一次）。",
            color = AppColor.TextTertiary
        )
    }
}

@Composable
private fun DiagRow(
    label: String,
    ok: Boolean,
    okText: String = "存在",
    failText: String = "缺失",
    soft: Boolean = false
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = AppType.meta, color = AppColor.TextSecondary)
        Spacer(Modifier.weight(1f))
        Text(
            if (ok) okText else failText,
            style = AppType.meta,
            color = when {
                ok -> if (soft) AppColor.TextSecondary else AppColor.Success
                else -> if (soft) AppColor.TextTertiary else AppColor.Danger
            }
        )
    }
}

@Composable
private fun DiagButton(
    text: String,
    busy: Boolean,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier
            .clip(RoundedCornerShape(11.dp))
            .background(if (primary) AppColor.Accent else AppColor.SoftBg)
            .clickable(enabled = !busy) { onClick() }
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = AppType.meta.copy(fontWeight = FontWeight.SemiBold),
            color = if (primary) androidx.compose.ui.graphics.Color.White
            else if (busy) AppColor.TextTertiary else AppColor.TextPrimary
        )
    }
}
