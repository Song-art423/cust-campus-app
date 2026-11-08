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
 *   - WebView 里用户登录时由系统 CookieManager 落盘（权威来源，JS 写的 cookie 也只在这里）
 *   - OkHttp 的每个请求都要带上它，且服务端会下发新的 Set-Cookie 做滑动续期
 *
 * ## 历次踩过的坑（改这个文件前先读完）
 *
 * 1. **应用层拦截器看不到重定向中途的 Set-Cookie。**
 *    `addInterceptor` 只看得到最后一跳，而 CAS 的续期恰恰下发在中间 302 上。
 *    → 用 `CookieJar`（按跳取）+ `addNetworkInterceptor`（按跳收）。
 *
 * 2. **没有本地副本，CookieManager 一空就彻底没救。**
 *    → 每次 Cookie 变动加密快照到 [AuthStore]，缺失时回灌。
 *
 * 3. **`getCookie(url)` 只返回 path 匹配的 cookie。**
 *    只查 `https://tls.ccsut.cn/` 会漏掉挂在 `/admin` 下的凭据 ——
 *    这类 cookie 由页面 JS 用 `document.cookie` 写入，path 由 JS 决定，
 *    未必是 `/`。漏采的后果是：保险库里从来没有真正的凭据快照。
 *    → 按「根 + 实际业务路径」多点采样，见 [PROBES]。
 *
 * 4. **回灌时硬编码 `Path=/` 会改变 cookie 的归属。**
 *    path 变宽还好，但 host-only 与 domain cookie 的差别、以及服务端可能做
 *    的 path 绑定校验都会出问题。
 *    → 快照按「采集时的 URL」分组存，回灌时用同一个 URL 写回，
 *      让 CookieManager 自己按 URL 决定 path。
 *
 * 5. **用「全局是否为空」判断要不要回灌是错的。**
 *    MIUI 清 WebView 数据时常常残留几条无关 cookie（`defaultMsg` / `uuid`），
 *    此时全局非空但关键凭据已经没了，旧逻辑会认为「不需要恢复」。
 *    → 改为判断**关键凭据是否存在**。
 *
 * 6. **快照与时间戳分开落盘会造成致命错位。**
 *    → [AuthStore.saveSnapshot] 一次 commit 写完。
 */
object CookieBridge {

    private const val TAG = "CookieBridge"

    /**
     * Cookie 采集探针。
     *
     * `CookieManager` 没有「列出全部 cookie」的 API，只能按 URL 反查，
     * 而 `getCookie(url)` 只返回 path 匹配该 URL 的 cookie。
     * 所以每个域都按「根路径 + 实际业务路径」各采一次。
     *
     * 新增接口路径时记得同步这里，否则那条路径下的 cookie 采不到。
     */
    private val PROBES = listOf(
        "https://tls.ccsut.cn/",
        "https://tls.ccsut.cn/admin/",
        "https://tls.ccsut.cn/admin/?loginType=1",
        "https://auth.ccsut.cn/",
        "https://auth.ccsut.cn/backstage/cas/"
    )

    /** 真正的登录凭据前缀（见 analysis/SESSION.md：jw_uf* 才是关键，JSESSIONID 可有可无） */
    private const val CREDENTIAL_PREFIX = "jw_uf"

    /** CAS 单点登录票据，静默续期全靠它 */
    private const val TGC_NAME = "CASTGC"

    /** 服务端滑动过期窗口 14 天 */
    private const val CREDENTIAL_TTL_MS = 14L * 24 * 60 * 60 * 1000

    /** 回灌失败后的重试间隔，避免每次启动都去读加密存储 */
    private const val RESTORE_RETRY_MS = 30_000L

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var recoveredFromVault = false

    @Volatile
    private var lastRestoreAttemptAt = 0L

    @Volatile
    private var lastRestoreResult = ""

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
     * 关键凭据不存在时从加密保险库回灌。**必须在 IO 线程调用。**
     *
     * 这是「一次登录长期有效」的最后一道保险：哪怕系统把 WebView 数据清了，
     * 只要本地快照还在有效期内，用户依然无感。
     *
     * 判断依据是**关键凭据是否存在**，不是 CookieManager 是否为空 ——
     * 残留一条无关 cookie 就放弃恢复，是之前掉登录的直接原因之一。
     */
    fun ensureRestored() {
        if (hasCredential()) return

        val now = System.currentTimeMillis()
        if (now - lastRestoreAttemptAt < RESTORE_RETRY_MS) return

        synchronized(this) {
            if (hasCredential()) return
            if (System.currentTimeMillis() - lastRestoreAttemptAt < RESTORE_RETRY_MS) return
            lastRestoreAttemptAt = System.currentTimeMillis()
            restoreFromVault()
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

    /** 当前所有探针下的 Cookie 合并串，仅用于展示 */
    fun snapshot(): String = probeCookies().values.joinToString("; ")

    /** 关键凭据（jw_uf*）是否存在于 CookieManager */
    fun hasCredential(): Boolean = mergedPairs().keys.any { it.startsWith(CREDENTIAL_PREFIX) }

    /** CAS 票据是否还在（静默续期的前提） */
    fun hasTicket(): Boolean = mergedPairs().containsKey(TGC_NAME)

    data class Status(
        val hasCredential: Boolean,
        val hasTicket: Boolean,
        val cookieCount: Int,
        val remainingDays: Int,
        val recovered: Boolean
    ) {
        val text: String
            get() = when {
                hasCredential && remainingDays > 0 -> "凭据有效 · 约剩 $remainingDays 天"
                hasCredential -> "凭据在，但快照时间偏旧"
                hasTicket -> "只剩单点登录票据，可静默续期"
                else -> "未登录"
            }
    }

    /** 供界面展示的诊断信息。**需要在 IO 线程调用**（会读加密存储） */
    fun status(): Status {
        val pairs = mergedPairs()
        val savedAt = runCatching { store?.cookieSavedAt ?: 0L }.getOrDefault(0L)
        val remaining = if (savedAt <= 0L) 0 else {
            val left = CREDENTIAL_TTL_MS - (System.currentTimeMillis() - savedAt)
            (left / 86_400_000L).toInt().coerceIn(0, 14)
        }
        return Status(
            hasCredential = pairs.keys.any { it.startsWith(CREDENTIAL_PREFIX) },
            hasTicket = pairs.containsKey(TGC_NAME),
            cookieCount = pairs.size,
            remainingDays = remaining,
            recovered = recoveredFromVault
        )
    }

    // ---------------------------------------------------------------- 保险库

    /**
     * 把当前 Cookie 加密快照落盘。由 [capture]、登录成功后、同步结束时调用。
     *
     * **不含关键凭据的快照不会覆盖含凭据的旧快照** —— 否则某次请求后
     * CookieManager 恰好只剩无关 cookie，就会把唯一一份好快照冲掉。
     */
    fun saveVault() {
        val s = store ?: return
        val jar = probeCookies()
        if (jar.isEmpty()) return

        val newHasCredential = jar.values.any {
            it.contains(CREDENTIAL_PREFIX) || it.contains(TGC_NAME)
        }
        if (!newHasCredential && vaultHasCredential(s)) {
            Log.w(TAG, "当前 Cookie 不含关键凭据，保留保险库里的旧快照不覆盖")
            return
        }

        val payload = JsonObject()
        jar.forEach { (url, raw) -> payload.addProperty(url, raw) }

        val root = JsonObject().apply {
            addProperty("savedAt", System.currentTimeMillis())
            add("jar", payload)
        }

        runCatching { s.saveSnapshot(root.toString(), System.currentTimeMillis()) }
            .onFailure { Log.w(TAG, "保险库写入失败", it) }
    }

    /** 强制从保险库回灌，忽略 30 秒节流。诊断面板用 */
    fun forceRestore(): String = synchronized(this) {
        lastRestoreAttemptAt = 0L
        restoreFromVault()
        lastRestoreResult
    }

    private fun restoreFromVault(): String {
        val s = store ?: return "无法打开加密存储".also { lastRestoreResult = it }
        val raw = runCatching { s.cookie }.getOrNull()
        if (raw.isNullOrBlank()) {
            return "保险库为空".also { lastRestoreResult = it }
        }

        val savedAt = runCatching { s.cookieSavedAt }.getOrDefault(0L)
        val age = System.currentTimeMillis() - savedAt
        if (savedAt <= 0L || age > CREDENTIAL_TTL_MS) {
            // 过期快照没有任何价值，清掉免得以后误判
            runCatching { s.clearCookie() }
            return "快照已超 14 天（${age / 86_400_000L} 天），不值得回灌".also {
                lastRestoreResult = it
            }
        }

        val jar = runCatching {
            JsonParser.parseString(raw).asJsonObject.getAsJsonObject("jar")
        }.getOrNull()

        // 兼容旧格式：v1 存的是 {"https://tls.ccsut.cn/": "a=1; b=2"}，没有 jar 包装
        val entries: Map<String, String> = if (jar != null) {
            jar.entrySet().associate { it.key to it.value.asString }
        } else {
            runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
                ?.entrySet()?.associate { it.key to it.value.asString }
                ?: return "保险库格式无法识别".also { lastRestoreResult = it }
        }

        val remainSec = ((CREDENTIAL_TTL_MS - age) / 1000).coerceAtLeast(60)
        val cm = CookieManager.getInstance()
        var n = 0

        entries.forEach { (url, rawCookies) ->
            rawCookies.split(';')
                .map { it.trim() }
                .filter { it.contains('=') }
                // 不写 Path：让 CookieManager 按 url 自己定，还原原始的归属
                .forEach { pair ->
                    val line = "$pair; Max-Age=$remainSec"
                    if (runCatching { cm.setCookie(url, line) }.isSuccess) n++
                }
        }
        runCatching { cm.flush() }

        recoveredFromVault = n > 0
        val msg = "从保险库回灌 $n 条 Cookie（快照年龄 ${age / 3600_000L} 小时）"
        Log.i(TAG, msg)
        lastRestoreResult = msg
        return msg
    }

    /** 只读判断，避免为了「要不要覆盖」去解析整份快照 */
    private fun vaultHasCredential(s: AuthStore): Boolean {
        val raw = runCatching { s.cookie }.getOrNull() ?: return false
        return raw.contains(CREDENTIAL_PREFIX) || raw.contains(TGC_NAME)
    }

    // ---------------------------------------------------------------- 诊断

    data class ProbeLine(val url: String, val raw: String) {
        val names: List<String>
            get() = raw.split(';').mapNotNull { seg ->
                val s = seg.trim()
                val eq = s.indexOf('=')
                if (eq <= 0) null else s.substring(0, eq).trim()
            }
    }

    data class Diagnostics(
        val probes: List<ProbeLine>,
        val hasCredential: Boolean,
        val hasTicket: Boolean,
        val vaultSavedAt: Long,
        val vaultAgeHours: Long,
        val vaultHasCredential: Boolean,
        val lastRestore: String,
        val recovered: Boolean
    ) {
        val verdict: String
            get() = when {
                hasCredential && hasTicket -> "凭据与票据都在，链路健康"
                hasCredential -> "凭据在，但 CAS 票据丢了 —— 续期会失败"
                hasTicket -> "票据在但凭据丢了，下次静默续期能救回来"
                vaultHasCredential -> "浏览器里没凭据，保险库里有 —— 可以回灌"
                else -> "两边都没有，只能重新登录"
            }
    }

    fun diagnostics(): Diagnostics {
        val probes = PROBES.mapNotNull { url ->
            runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { ProbeLine(url, it) }
        }
        val pairs = mergedPairs()
        val s = store
        val savedAt = runCatching { s?.cookieSavedAt ?: 0L }.getOrDefault(0L)
        return Diagnostics(
            probes = probes,
            hasCredential = pairs.keys.any { it.startsWith(CREDENTIAL_PREFIX) },
            hasTicket = pairs.containsKey(TGC_NAME),
            vaultSavedAt = savedAt,
            vaultAgeHours = if (savedAt <= 0L) -1 else
                (System.currentTimeMillis() - savedAt) / 3_600_000L,
            vaultHasCredential = s != null && vaultHasCredential(s),
            lastRestore = lastRestoreResult,
            recovered = recoveredFromVault
        )
    }

    // ---------------------------------------------------------------- 清理

    fun clear() {
        runCatching {
            val cm = CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()
        }
        runCatching { store?.clearCookie() }
        recoveredFromVault = false
        lastRestoreResult = ""
        lastRestoreAttemptAt = 0L
    }

    // ---------------------------------------------------------------- 内部工具

    /** 按探针采集，返回 url -> 原始 cookie 串。同名 cookie 在不同 path 下是不同条目，不合并 */
    private fun probeCookies(): Map<String, String> {
        val cm = CookieManager.getInstance()
        val out = LinkedHashMap<String, String>()
        PROBES.forEach { url ->
            val raw = runCatching { cm.getCookie(url) }.getOrNull()
            if (!raw.isNullOrBlank()) out[url] = raw
        }
        return out
    }

    /** 所有探针的 name=value 合并（同名后者覆盖前者），用于「有没有某个凭据」这类判断 */
    private fun mergedPairs(): Map<String, String> =
        probeCookies().values
            .flatMap { raw -> raw.split(';') }
            .mapNotNull { seg ->
                val s = seg.trim()
                val eq = s.indexOf('=')
                if (eq <= 0) null else s.substring(0, eq).trim() to s.substring(eq + 1).trim()
            }
            .toMap()
}
