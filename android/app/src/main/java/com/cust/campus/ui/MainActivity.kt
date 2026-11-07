package com.cust.campus.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.cust.campus.data.model.UiState
import com.cust.campus.notify.CourseNotifier
import com.cust.campus.ui.components.FloatingBottomBar
import com.cust.campus.ui.components.SoftCard
import com.cust.campus.ui.components.SyncLine
import com.cust.campus.ui.components.Tab
import com.cust.campus.ui.theme.AppColor
import com.cust.campus.ui.theme.AppType
import com.cust.campus.ui.theme.CustCampusTheme

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        vm.onLoginReturned()
    }

    /**
     * Android 13+ 通知权限是运行时权限，不主动请求就永远是「拒绝」。
     * 拒绝后 NotificationManager.notify() 不抛异常、不打日志，通知直接消失 ——
     * 表现就是「代码看起来全对，但就是不弹」。
     */
    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 结果由「我的 → 提醒调试」实时反映，这里不需要额外处理 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()

        // 每次打开都按当前时间对齐「正在上课」卡片。
        // 没有这一步，下课后卡片会一直挂到下次闹钟触发为止。
        runCatching { CourseNotifier.syncOngoing(this) }

        setContent {
            CustCampusTheme {
                val state by vm.state.collectAsState()

                // 首次启动没有会话时自动拉起登录页，避免用户先看到一张错误卡片。
                // 只自动弹一次：用户主动返回后不再骚扰。
                var autoLoginDone by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(state) {
                    val s = state
                    if (!autoLoginDone && s.data == null && s.authExpired) {
                        autoLoginDone = true
                        loginLauncher.launch(Intent(this@MainActivity, LoginActivity::class.java))
                    }
                }

                AppRoot(
                    state = state,
                    onRefresh = { vm.refresh() },
                    onPrevWeek = { vm.switchWeek(-1) },
                    onNextWeek = { vm.switchWeek(1) },
                    onBackToday = { vm.goToday() },
                    onPickWeek = { vm.selectWeek(it) },
                    onSetAdvance = { vm.setAdvance(it) },
                    onSetRemindEnabled = { vm.setRemindEnabled(it) },
                    onClearAndResync = { vm.clearAndResync() },
                    onLogin = {
                        loginLauncher.launch(Intent(this@MainActivity, LoginActivity::class.java))
                    }
                )
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
fun AppRoot(
    state: UiState,
    onRefresh: () -> Unit,
    onPrevWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onBackToday: () -> Unit,
    onPickWeek: (Int) -> Unit,
    onSetAdvance: (Int) -> Unit,
    onSetRemindEnabled: (Boolean) -> Unit,
    onClearAndResync: () -> Unit,
    onLogin: () -> Unit
) {
    var tab by rememberSaveable { mutableStateOf(Tab.Overview) }

    Box(
        Modifier
            .fillMaxSize()
            .background(AppColor.Bg)
    ) {
        val data = state.data

        // 完全没有数据时只能走冷启动页 / 错误页；一旦有数据，永远直接出画面
        if (data == null) {
            when {
                state.coldSyncing -> ColdStartScreen(state.syncProgress, state.syncLabel)
                state.authExpired -> WelcomeScreen(onLogin = onLogin)
                state.error != null -> ErrorScreen(
                    message = state.error,
                    onRetry = onRefresh,
                    onLogin = onLogin
                )
                else -> WelcomeScreen(onLogin = onLogin)
            }
        } else {
            Box(Modifier.fillMaxSize()) {
                val scroll = rememberScrollState()

                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll)
                ) {
                    // 静默同步时只在顶部滑一条线，内容完全不动
                    if (state.syncing) {
                        SyncLine(state.syncProgress, state.syncLabel)
                    }

                    Crossfade(targetState = tab, label = "tab") { current ->
                        when (current) {
                            Tab.Overview -> OverviewScreen(
                                data = data,
                                onOpenWeek = {
                                    onPickWeek(it)
                                    tab = Tab.Timetable
                                }
                            )

                            Tab.Timetable -> ScheduleScreen(
                                data = data,
                                week = state.selectedWeek,
                                onPrevWeek = onPrevWeek,
                                onNextWeek = onNextWeek,
                                onBackToday = onBackToday,
                                onPickWeek = onPickWeek
                            )

                            Tab.Settings -> ProfileScreen(
                                data = data,
                                syncing = state.syncing,
                                syncLabel = state.syncLabel,
                                remindEnabled = state.remindEnabled,
                                remindAdvance = state.remindAdvance,
                                cookieText = state.cookieText,
                                cookieOk = state.cookieOk,
                                onSetRemindEnabled = onSetRemindEnabled,
                                onSetAdvance = onSetAdvance,
                                onRefresh = onRefresh,
                                onClearAndResync = onClearAndResync,
                                onLogin = onLogin
                            )
                        }
                    }
                }
            }
        }

        // 悬浮胶囊导航贴底居中
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 18.dp)
        ) {
            if (data != null) {
                FloatingBottomBar(current = tab, onSelect = { tab = it })
            }
        }
    }
}

// ---------------------------------------------------------------- 冷启动 / 空态

/**
 * 冷启动只发生在首次安装或清缓存之后。
 * 这里给一个像样的进度，并且明确告诉用户在等什么 —— 一次拉完整学期，
 * 之后永远不会再看到这个页面。
 */
@Composable
private fun ColdStartScreen(progress: Float, label: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 40.dp)
        ) {
            Text("正在拉取整个学期课表", style = AppType.cardTitle, color = AppColor.TextPrimary)
            Spacer(Modifier.height(6.dp))
            Text(
                label.ifBlank { "只需这一次，之后秒开" },
                style = AppType.meta,
                color = AppColor.TextSecondary
            )
            Spacer(Modifier.height(20.dp))
            SyncLine(progress, "")
            Spacer(Modifier.height(10.dp))
            Text(
                "${(progress * 100).toInt()}%",
                style = AppType.meta.copy(fontWeight = FontWeight.SemiBold),
                color = AppColor.Accent
            )
        }
    }
}

/** 没有会话时的欢迎页，比一张报错卡片体面得多 */
@Composable
private fun WelcomeScreen(onLogin: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center
    ) {
        SoftCard(Modifier.fillMaxWidth()) {
            Text("长理课表", style = AppType.pageTitle, color = AppColor.TextPrimary)
            Spacer(Modifier.height(6.dp))
            Text(
                "登录一次，整个学期课表全部下载到本地。之后打开就是瞬间出现，切周次也不用等。",
                style = AppType.body,
                color = AppColor.TextSecondary
            )
            Spacer(Modifier.height(22.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(AppColor.Accent, RoundedCornerShape(14.dp))
                    .clickable(onClick = onLogin)
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("去登录", style = AppType.itemTitle, color = Color.White)
            }
        }
    }
}

@Composable
private fun ErrorScreen(message: String, onRetry: () -> Unit, onLogin: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center
    ) {
        SoftCard(Modifier.fillMaxWidth()) {
            Text("同步没成功", style = AppType.cardTitle, color = AppColor.TextPrimary)
            Spacer(Modifier.height(6.dp))
            Text(message, style = AppType.body, color = AppColor.TextSecondary)
            Spacer(Modifier.height(20.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PillButton("重试", primary = true, modifier = Modifier.weight(1f), onClick = onRetry)
                PillButton("重新登录", primary = false, modifier = Modifier.weight(1f), onClick = onLogin)
            }
        }
    }
}

@Composable
private fun PillButton(
    text: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier
            .background(
                if (primary) AppColor.Accent else AppColor.SoftBg,
                RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = AppType.itemTitle,
            color = if (primary) Color.White else AppColor.TextPrimary
        )
    }
}
