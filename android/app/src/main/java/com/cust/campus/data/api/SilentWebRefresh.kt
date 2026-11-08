package com.cust.campus.data.api

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 静默 WebView 刷新。
 *
 * ## 为什么必须用 WebView，而不是再发一个 HTTP 请求
 *
 * 教务的登录凭据里有一组 `jw_uf*`（`jw_uf` / `jw_uf_d` / `jw_uf_r` / `jw_uf_u` / `jw_uf_vc`），
 * 它们的形状说明是**页面 JavaScript 用 `document.cookie` 写进去的**，不是服务端 `Set-Cookie` 下发的：
 *
 * ```
 * jw_uf_d=1793953473926     登录时刻的毫秒时间戳
 * jw_uf_u=csgyxy            学院代码
 * jw_uf_r=xs                角色（学生）
 * jw_uf_vc=43f29dc9...      32 位校验码
 * ```
 *
 * OkHttp 只发 AJAX 接口，**不会执行页面脚本**。所以：
 *   - 服务端下发的那部分 cookie，`capture` 拦截器能收到
 *   - 页面 JS 维护的那部分（尤其是 `jw_uf_d` 时间戳和 `jw_uf_vc` 校验码），
 *     OkHttp 这条路径永远刷不到
 *
 * 结果就是：接口看着能用，但页面侧凭据一天天变旧，某天服务端一对校验就判定失效 ——
 * 表现为「莫名其妙掉登录，重新进网页又好了」。
 *
 * 所以必须让页面真正跑一遍。这里用一个不挂到界面的 WebView 加载教务首页，
 * 等 `onPageFinished` 后 flush + 存快照，然后销毁。
 *
 * ## 成本与限制
 *
 * - 只在「需要时」调用（见 [SessionKeeper]），常规探活仍然走轻量 HTTP
 * - WebView 必须在主线程创建，因此这里切到 `Dispatchers.Main`
 * - 后台被 ROM 冻结时 WebView 可能加载失败，所以它只是兜底，不是唯一手段
 */
object SilentWebRefresh {

    private const val TAG = "SilentWebRefresh"

    /** 加载教务首页：只有 HTML 页面才会执行脚本，AJAX 接口不算 */
    private const val PROBE_URL = "https://tls.ccsut.cn/admin/?loginType=1"

    private const val TIMEOUT_MS = 20_000L

    /** 同一时刻只允许一个 WebView 在跑，避免连续调用时重复创建 */
    @Volatile
    private var running = false

    /**
     * 加载一次教务首页，让页面脚本刷新 cookie。
     *
     * @return true 表示页面成功加载（不代表凭据一定有效，需要调用方再探活确认）
     */
    suspend fun refresh(context: Context): Boolean {
        if (running) {
            Log.d(TAG, "已有刷新在进行，跳过")
            return false
        }
        running = true
        return try {
            withContext(Dispatchers.Main) {
                withTimeoutOrNull(TIMEOUT_MS) { loadOnce(context.applicationContext) } ?: run {
                    Log.w(TAG, "静默刷新超时（${TIMEOUT_MS}ms）")
                    false
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "静默刷新异常", t)
            false
        } finally {
            running = false
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun loadOnce(ctx: Context): Boolean =
        suspendCancellableCoroutine { cont ->
            val web = try {
                WebView(ctx)
            } catch (t: Throwable) {
                Log.e(TAG, "创建 WebView 失败", t)
                cont.resume(false)
                return@suspendCancellableCoroutine
            }

            // resume 只能发生一次：onPageFinished 可能被调用多次
            var settled = false
            fun settle(ok: Boolean) {
                if (settled) return
                settled = true
                runCatching { web.stopLoading() }
                runCatching { web.destroy() }
                if (cont.isActive) cont.resume(ok)
            }

            cont.invokeOnCancellation {
                runCatching { web.stopLoading() }
                runCatching { web.destroy() }
            }

            try {
                web.settings.apply {
                    javaScriptEnabled = true      // 没有它，页面脚本不会跑
                    domStorageEnabled = true
                    databaseEnabled = true
                    userAgentString = USER_AGENT
                    // 不加载图片，省流量也更快
                    loadsImagesAutomatically = false
                    blockNetworkImage = true
                }
                CookieBridge.attachTo(web)

                web.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        // flush 把 JS 刚写的 cookie 落到系统存储，再存一份加密快照
                        runCatching { CookieManager.getInstance().flush() }
                        runCatching { CookieBridge.saveVault() }
                        Log.i(TAG, "页面加载完成，已刷新并快照 cookie：$url")
                        settle(true)
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: android.webkit.WebResourceRequest?,
                        error: android.webkit.WebResourceError?
                    ) {
                        super.onReceivedError(view, request, error)
                        // 主文档失败才算失败；子资源（图片等）失败忽略
                        if (request?.isForMainFrame == true) {
                            Log.w(TAG, "主文档加载失败：${error?.description}")
                            settle(false)
                        }
                    }
                }

                web.loadUrl(PROBE_URL)
            } catch (t: Throwable) {
                Log.e(TAG, "WebView 加载异常", t)
                settle(false)
            }
        }

    /** 用桌面 UA：教务移动端适配页有时不下发完整的 cookie 集合 */
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
}
