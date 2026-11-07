package com.cust.campus.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.cust.campus.data.api.CookieBridge
import com.cust.campus.data.api.CustApi
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

    /**
     * 三段式：先探活 -> 再静默续期 -> 最后才判定失效。
     * 任何时候拿到 RENEWED 就说明用户完全无感。
     */
    suspend fun ensure(): Status {
        // CookieManager 被系统清空时先从加密保险库回灌，再谈探活。
        // 少了这一步，用户会莫名其妙被要求重新登录 —— v1 掉登录的主要原因之一。
        withContext(Dispatchers.IO) { CookieBridge.ensureRestored() }

        if (api.isSessionAlive()) {
            markChecked()
            return Status.ALIVE
        }
        if (api.silentRenew()) {
            markChecked()
            return Status.RENEWED
        }
        return Status.EXPIRED
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
        return when (SessionKeeper.ensure()) {
            SessionKeeper.Status.EXPIRED -> {
                SessionNotifier.notifyExpired(applicationContext)
                Result.success()
            }
            else -> Result.success()
        }
    }
}
