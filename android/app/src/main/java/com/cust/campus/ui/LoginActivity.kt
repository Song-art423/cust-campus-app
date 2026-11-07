package com.cust.campus.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.cust.campus.data.api.CookieBridge
import com.cust.campus.data.api.CustApi

/**
 * 内嵌登录页。
 *
 * 这是「一次登录、长期免填」的入口：
 *   - 首次使用：在 WebView 里正常登录（该填验证码就填，这是网页原生流程）
 *   - 之后：Cookie 由系统 CookieManager 落盘保管，App 重启也不丢
 *   - 会话过期：后台静默续期；即便真过期，再进来时 CAS 单点登录大概率直接放行
 *
 * 全程不接触用户密码 —— 密码只在学校自己的页面里输入，App 拿到的只是登录后的票据。
 */
class LoginActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var loadingBar: ProgressBar
    private var finished = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        CookieBridge.init(this)

        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = false
            }
            CookieBridge.attachTo(this)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (url == null || finished) return

                    loadingBar.visibility = ViewGroup.GONE

                    // 用页面里的用户名节点判断是否已进入教务首页
                    view?.evaluateJavascript(
                        "(function(){var e=document.querySelector('.admin_name');" +
                            "return e?e.innerText:'0';})()"
                    ) { raw ->
                        val name = raw?.trim('"', ' ')
                        if (!name.isNullOrBlank() && name != "0" && name != "null") {
                            onLoginSuccess()
                        }
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean = false   // 所有跳转都留在 App 内，CAS 流程才能走完
            }
        }

        loadingBar = ProgressBar(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = android.view.Gravity.CENTER }
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(
                webView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            addView(loadingBar)
        }
        setContentView(root)

        // 优先在 WebView 里后退，避免登录中途被意外踢出去
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack() && !finished) webView.goBack() else finish()
            }
        })

        // 已有 Cookie 就先试直进首页（CAS 大概率直接放行，用户看不到登录表单）。
        // 用 snapshot() 而不是只查主域 —— TGC 在 auth 子域，只看一个域会误判。
        if (CookieBridge.snapshot().isNotBlank()) {
            webView.loadUrl("${CustApi.BASE}/admin/?loginType=1")
        } else {
            webView.loadUrl("${CustApi.BASE}/admin/")
        }
    }

    private fun onLoginSuccess() {
        finished = true

        // 三件事必须按顺序做，少一件就可能「今天登录、明天失效」：
        //   1. flush    —— 把内存里的 Cookie 写进系统磁盘
        //   2. saveVault —— 再存一份加密快照，系统清 WebView 数据后还能回灌
        //   3. verify   —— 确认 jw_uf 那组凭据真的拿到了，没拿到就别假装修好了
        CookieManager.getInstance().flush()
        CookieBridge.saveVault()

        val status = CookieBridge.status()
        if (!status.hasCredential) {
            // 页面已经进了教务首页但凭据没落到 CookieManager，说明 Cookie 同步有问题。
            // 不打断用户（数据可能是好的），但留个明确的日志，别再靠猜。
            android.util.Log.w(
                "LoginActivity",
                "登录成功但未检测到 jw_uf 凭据，当前 Cookie 共 ${status.cookieCount} 条"
            )
        } else {
            android.util.Log.i(
                "LoginActivity",
                "登录成功，凭据已落盘：${status.cookieCount} 条 Cookie"
            )
        }

        runOnUiThread {
            loadingBar.visibility = ViewGroup.GONE
            setResult(RESULT_OK)
            finish()
        }
    }

    override fun onDestroy() {
        CookieManager.getInstance().flush()
        webView.destroy()
        super.onDestroy()
    }
}
