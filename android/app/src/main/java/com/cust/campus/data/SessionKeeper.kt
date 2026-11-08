package com.cust.campus.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.cust.campus.data.api.CookieBridge
import com.cust.campus.data.api.CustApi
import com.cust.campus.data.api.SilentWebRefresh
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * 会话保活。
 *
 * 解决用户最烦的那个问题：Cookie 过期就得重新登录填验证码。
 *
 * 实测结论（用真实 Cookie 做了子集实验，见 analysis/SESSION.md）：
 *   1. 真正维持登录态的是 jw_uf* 那组长效凭据，JSESSIONID 反而是可有可无的
 *   2. 服务端下发 Max-Age=1209600，也就是 **14 天**
 *   3. 关键：**每次请求都会把有效期重新刷新成 14 天**（滑动过期）
 *
 * 所以策略很简单：定期碰一下就行。14 天窗口内碰过一次，就永远不会掉线。
 * 这里取 6 小时，既远小于 14 天，又不会明显耗电（探活请求实测 1.4KB / 98ms）。
 *
 * 万一还是断了（比如换设备、学校清了 token），第二道防线是 silentRenew()：
 * CAS 单点登录票据还在的话，走一遍票据流程就能无感换新会话，用户连屏幕都看不到。
 */
object SessionKeeper {

    enum class Status {
        ALIVE,    // 会话正常
        RENEWED,  // 原本失效，已静默续上
        EXPIRED   // 彻底失效，需要用户重新登录一次
    }

    private const val WORK_NAME = "session_keepalive"

    /** 服务端滑动过期窗口是 14 天，6 小时刷一次有极充足余量 */
    private const val INTERVAL_HOURS = 6L

    private val api = CustApi()

    /** 后台刷新用。SupervisorJob 保证某次失败不影响后续 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 上次让页面脚本跑过的时间。页面刷新开销大，不能每次都做 */
    @Volatile
    private var lastPageRefreshAt = 0L

    /**
     * 四段式：回灌 -> 探活 -> CAS 续期 -> 页面刷新。
     *
     * 前三步都失败后仍然不放弃，是因为**接口失效不等于凭据失效**：
     * `jw_uf*` 那组 cookie 由页面 JS 维护（时间戳、校验码），
     * OkHttp 从不执行页面脚本，所以凭据会一天天变旧，直到服务端判定不一致。
     * 这时候让 WebView 真正加载一次首页，页面脚本会把它们重新算一遍。
     */
    suspend fun ensure(context: Context): Status {
        // CookieManager 被系统清空时先从加密保险库回灌，再谈探活。
        // 少了这一步，用户会莫名其妙被要求重新登录 —— v1 掉登录的主要原因之一。
        withContext(Dispatchers.IO) { CookieBridge.ensureRestored() }

        if (api.isSessionAlive()) {
            markChecked()
            return Status.ALIVE
        }

        // 轻量方案先上：CAS 票据还在的话，一次重定向就能换到新会话
        if (api.silentRenew()) {
            markChecked()
            return Status.RENEWED
        }

        // 重方案兜底：让页面脚本把 jw_uf* 重新算一遍
        if (SilentWebRefresh.refresh(context)) {
            lastPageRefreshAt = System.currentTimeMillis()
            CookieBridge.saveVault()
            if (api.isSessionAlive()) {
                markChecked()
                return Status.RENEWED
            }
        }

        return Status.EXPIRED
    }

    /**
     * 主动让页面刷新一次 cookie，**异步执行、不阻塞调用方**。
     *
     * 用来在 App 打开时悄悄把凭据的计时器归零 —— 等到探活失败再刷新就晚了，
     * 那时服务端已经判定失效，页面也救不回来。
     *
     * @param maxAgeHours 距上次刷新超过这个小时数才真正执行，默认 12 小时
     */
    fun refreshPageIfStale(context: Context, maxAgeHours: Long = 12L): Boolean {
        val age = System.currentTimeMillis() - lastPageRefreshAt
        if (lastPageRefreshAt > 0L && age < maxAgeHours * 3_600_000L) return false
        if (!CookieBridge.hasCredential()) return false   // 没登录就别折腾
        lastPageRefreshAt = System.currentTimeMillis()
        scope.launch { runCatching { SilentWebRefresh.refresh(context) } }
        return true
    }

    /** 注册后台保活任务。App 每次启动都调一次，Policy 用 KEEP 避免重置计时 */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<KeepAliveWorker>(
            INTERVAL_HOURS, TimeUnit.HOURS
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    // ---------------------------------------------------------------- 状态记录

    private var lastCheckedAt: String? = null

    private fun markChecked() {
        lastCheckedAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
    }

    fun lastCheckedText(): String? = lastCheckedAt
}

/**
 * 后台保活 Worker。
 * 注意：不要在这里发通知打扰用户，只有真的过期了才提示。
 */
class KeepAliveWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return when (SessionKeeper.ensure(applicationContext)) {
            SessionKeeper.Status.EXPIRED -> {
                SessionNotifier.notifyExpired(applicationContext)
                Result.success()
            }
            else -> Result.success()
        }
    }
}
