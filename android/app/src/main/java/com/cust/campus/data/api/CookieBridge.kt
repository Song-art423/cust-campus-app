package com.cust.campus.data.api

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import com.cust.campus.data.AuthStore
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor

/**
 * OkHttp 与 WebView 的 Cookie 双向桥。
 *
 * ## 为什么需要这一层
 *
 * 教务系统的「免登录」完全建立在 Cookie 上，而 Cookie 有两个来源：
 *   - WebView 里用户登录时由系统 CookieManager 落盘（权威来源）
 *   - OkHttp 的每个请求都要带上它，且服务端会下发新的 Set-Cookie 做滑动续期
 *
 * ## 旧实现的两个致命缺陷（v1 掉登录的原因）
 *
 * 1. **用应用层拦截器手动塞 `Cookie` 头，丢掉了重定向中途的 `Set-Cookie`。**
 *    OkHttp 的 `chain.proceed()` 内部会自己跟随 302，应用层拦截器只看得到最后一跳的响应。
 *    而 CAS 登录/续期流程里，新的会话 Cookie 恰恰下发在中间那几跳 302 上 ——
 *    于是服务端「每次请求把 14 天有效期刷新一遍」这件事，对本地完全无效，
 *    本地记录的过期时间永远停在登录那一刻。
 *    → 现在改用 `CookieJar`（按跳取 Cookie）+ 网络层拦截器（按跳捕获 Set-Cookie）。
 *
 * 2. **没有任何本地副本，`CookieManager` 一空就彻底没救。**
 *    系统清理 WebView 数据、WebView 自身升级、部分 ROM 的省电策略都会让 CookieManager 变空，
 *    而此时用户什么都不知道，只看到要重新登录。
 *    → 现在每次 Cookie 变动都同步一份加密快照到 [AuthStore]，启动时若发现为空就回灌。
 */
object CookieBridge {

    private const val TAG = "CookieBridge"

    /** 教务主域 + CAS 认证域，两个域都要同步 */
    private val SYNC_URLS = listOf(
        "https://tls.ccsut.cn/",
        "https://auth.ccsut.cn/"
    )

    /** 真正的登录凭据前缀（见 analysis/SESSION.md：jw_uf* 才是关键，JSESSIONID 可有可无） */
    private const val CREDENTIAL_PREFIX = "jw_uf"

    /** 服务端滑动过期窗口 14 天 */
    private const val CREDENTIAL_TTL_MS = 14L * 24 * 60 * 60 * 1000

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var restored = false

    @Volatile
    private var recoveredFromVault = false

    /** 懒初始化 —— 加密存储要走 Keystore，别在 Application.onCreate 里付这个开销 */
    private val store: AuthStore? by lazy {
        appContext?.let { ctx -> runCatching { AuthStore(ctx) }.getOrNull() }
    }

    // ---------------------------------------------------------------- 生命周期

    fun init(context: Context) {
        appContext = context.applicationContext
        runCatching {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cm.flush()
        }.onFailure { Log.e(TAG, "CookieManager 初始化失败", it) }
    }

    /** WebView 必须开启三方 Cookie，否则 CAS 跨子域登录拿不到 TGC */
    fun attachTo(webView: WebView) {
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
    }

    /**
     * CookieManager 为空时从加密保险库恢复。**幂等，且必须在 IO 线程调用。**
     *
     * 这是「一次登录长期有效」的最后一道保险：哪怕系统把 WebView 数据清了，
     * 只要本地快照还在有效期内，用户依然无感。
     */
    fun ensureRestored() {
        if (restored) return
        synchronized(this) {
            if (restored) return
            restored = true
            if (rawCookies().isBlank()) restoreFromVault()
        }
    }

    // ---------------------------------------------------------------- OkHttp 接入

    /**
     * 给 OkHttp 的 CookieJar。
     *
     * 只负责「取」——每次请求按目标 URL 实时从 CookieManager 读，
     * 所以跨域重定向时不会把 tls 的 Cookie 泄漏到 auth 域，
     * 也不会用上一跳的旧 Cookie 发下一跳。
     */
    fun cookieJar(): CookieJar = object : CookieJar {

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val raw = runCatching {
                CookieManager.getInstance().getCookie(url.toString())
            }.getOrNull()
            if (raw.isNullOrBlank()) return emptyList()

            return raw.split(';').mapNotNull { seg ->
                val s = seg.trim()
                val eq = s.indexOf('=')
                if (eq <= 0) return@mapNotNull null
                val name = s.substring(0, eq).trim()
                val value = s.substring(eq + 1).trim()
                runCatching {
                    Cookie.Builder()
                        .name(name)
                        .value(value)
                        .hostOnlyDomain(url.host)
                        .path("/")
                        .build()
                }.getOrNull()
            }
        }

        /**
         * 这里**故意什么都不做**。
         *
         * `Set-Cookie` 带着 Max-Age / Expires 等属性，解析成 okhttp 的 Cookie 再序列化会丢掉有效期，
         * 那就会把 14 天的长效凭据写成会话级 Cookie —— 一重启就没了。
         * 所以写入统一交给 [capture]，用服务端下发的原始行。
         */
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
    }

    /**
     * 注册为**网络层拦截器**（`addNetworkInterceptor`）。
     *
     * 关键差别：网络层拦截器对每一次实际发出的请求都会执行，
     * 包括 OkHttp 内部跟随的每一个 302 跳转，因此能捕获到中间跳上的 Set-Cookie。
     * 应用层拦截器做不到这一点。
     */
    fun capture(): Interceptor = Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)

        val setCookies = response.headers("Set-Cookie")
        if (setCookies.isNotEmpty()) {
            val url = request.url.toString()
            val cm = CookieManager.getInstance()
            var written = 0
            setCookies.forEach { line ->
                if (runCatching { cm.setCookie(url, line) }.isSuccess) written++
            }
            if (written > 0) {
                // 立刻落盘。服务端是滑动续期，这个刷新不能只停在内存里
                runCatching { cm.flush() }
                saveVault()
            }
        }
        response
    }

    // ---------------------------------------------------------------- 对外查询

    /** 当前所有域下的 Cookie 合并串 */
    fun snapshot(): String = rawCookies()

    data class Status(
        val hasCredential: Boolean,
        val cookieCount: Int,
        val remainingDays: Int,
        val recovered: Boolean
    ) {
        val text: String
            get() = when {
                !hasCredential -> "未登录"
                remainingDays <= 0 -> "凭据可能已过期"
                else -> "凭据有效 · 约剩 $remainingDays 天"
            }
    }

    /** 供界面展示的诊断信息。**需要在 IO 线程调用**（会读加密存储） */
    fun status(): Status {
        val pairs = parsePairs(rawCookies())
        val savedAt = runCatching { store?.cookieSavedAt ?: 0L }.getOrDefault(0L)
        val remaining = if (savedAt <= 0L) 0 else {
            val left = CREDENTIAL_TTL_MS - (System.currentTimeMillis() - savedAt)
            (left / 86_400_000L).toInt().coerceIn(0, 14)
        }
        return Status(
            hasCredential = pairs.keys.any { it.startsWith(CREDENTIAL_PREFIX) },
            cookieCount = pairs.size,
            remainingDays = remaining,
            recovered = recoveredFromVault
        )
    }

    // ---------------------------------------------------------------- 保险库

    /** 把当前 Cookie 加密快照落盘。由 [capture] 和登录成功后调用 */
    fun saveVault() {
        val s = store ?: return
        val json = JsonObject()
        SYNC_URLS.forEach { url ->
            val raw = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
            if (!raw.isNullOrBlank()) json.addProperty(url, raw)
        }
        if (json.size() == 0) return

        runCatching {
            s.cookie = json.toString()
            s.cookieSavedAt = System.currentTimeMillis()
        }.onFailure { Log.w(TAG, "保险库写入失败", it) }
    }

    private fun restoreFromVault() {
        val s = store ?: return
        val raw = runCatching { s.cookie }.getOrNull()
        if (raw.isNullOrBlank()) return

        val savedAt = runCatching { s.cookieSavedAt }.getOrDefault(0L)
        val age = System.currentTimeMillis() - savedAt
        if (savedAt <= 0L || age > CREDENTIAL_TTL_MS) {
            // 过期快照没有任何价值，清掉免得以后误判
            runCatching { s.clear() }
            return
        }

        val json = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return
        val remainSec = ((CREDENTIAL_TTL_MS - age) / 1000).coerceAtLeast(60)

        var n = 0
        json.entrySet().forEach { (host, v) ->
            v.asString.split(';')
                .map { it.trim() }
                .filter { it.contains('=') }
                .forEach { pair ->
                    val line = "$pair; Path=/; Max-Age=$remainSec"
                    if (runCatching { CookieManager.getInstance().setCookie(host, line) }.isSuccess) n++
                }
        }
        runCatching { CookieManager.getInstance().flush() }

        recoveredFromVault = n > 0
        Log.i(TAG, "从本地保险库恢复 $n 条 Cookie（快照年龄 ${age / 3600_000L} 小时）")
    }

    // ---------------------------------------------------------------- 清理

    fun clear() {
        runCatching {
            val cm = CookieManager.getInstance()
            SYNC_URLS.forEach { cm.setCookie(it, "___del=1; Max-Age=0; Path=/") }
            cm.removeAllCookies(null)
            cm.flush()
        }
        runCatching { store?.clear() }
        recoveredFromVault = false
    }

    // ---------------------------------------------------------------- 内部工具

    private fun rawCookies(): String = SYNC_URLS
        .mapNotNull { url ->
            runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
                ?.takeIf { it.isNotBlank() }
        }
        .joinToString("; ")

    private fun parsePairs(raw: String): Map<String, String> =
        raw.split(';').mapNotNull { seg ->
            val s = seg.trim()
            val eq = s.indexOf('=')
            if (eq <= 0) null else s.substring(0, eq).trim() to s.substring(eq + 1).trim()
        }.toMap()
}
