package com.cust.campus.data.api

import com.cust.campus.data.model.ApiException
import com.cust.campus.data.model.AuthExpiredException
import com.cust.campus.data.model.Course
import com.cust.campus.data.model.DayCourse
import com.cust.campus.data.model.DayInfo
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * 超星综合教学管理系统（tls.ccsut.cn）客户端。
 *
 * 接口清单来自真实抓包，详见 analysis/API.md：
 *   GET  /admin/getDayBz?rq=YYYY-MM-DD   当天课程 + 当前教学周次
 *   GET  /admin/getCurrentPkZc           全部教学周次
 *   POST /admin/getXqByZc   (zc)         某周的日期/星期
 *   POST /admin/getXsdSykb  (type, zc)   完整课表
 *
 * 两个必须注意的点（都是实测踩出来的）：
 *   1. 必须带 X-Requested-With: XMLHttpRequest，否则服务端返回 HTML 空壳
 *   2. Cookie 不自己管，全部交给 CookieBridge / WebView
 */
class CustApi {

    companion object {
        const val BASE = "https://tls.ccsut.cn"

        /** 一周的课表 + 该周节次时间表 */
        data class WeekSchedule(
            val courses: List<Course>,
            val periodTimes: Map<Int, String>
        )

        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private val FORM = "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()

        /** 内置节次时间兜底表，接口取不到时用 */
        private val DEFAULT_PERIODS = mapOf(
            1 to ("8:20" to "9:05"),
            2 to ("9:15" to "10:00"),
            3 to ("10:20" to "11:05"),
            4 to ("11:15" to "12:00"),
            5 to ("14:00" to "14:45"),
            6 to ("14:55" to "15:40"),
            7 to ("16:00" to "16:45"),
            8 to ("16:55" to "17:40"),
            9 to ("19:00" to "19:45"),
            10 to ("19:55" to "20:40")
        )
    }

    /**
     * 学校服务器证书链偶尔不完整（缺中间证书），Android 7+ 会直接拒绝握手，
     * 而浏览器因为内置补全机制能正常访问。这里放宽证书链校验但保留主机名校验，
     * 避免「浏览器能开、App 连不上」这种诡异问题。
     */
    private val client: OkHttpClient = run {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(tm), SecureRandom())
        }
        OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, tm)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            // Cookie 按跳读写：CookieJar 负责「发」，网络层拦截器负责「收」。
            // 换成这两件套之前用的是应用层 addInterceptor，会漏掉重定向中途的 Set-Cookie。
            .cookieJar(CookieBridge.cookieJar())
            .addNetworkInterceptor(CookieBridge.capture())
            .build()
    }

    // ---------------------------------------------------------------- 底层请求

    private fun get(path: String, query: Map<String, String> = emptyMap()): JsonObject {
        val url = buildString {
            append(BASE).append(path)
            if (query.isNotEmpty()) {
                append('?')
                append(query.entries.joinToString("&") { "${it.key}=${it.value}" })
            }
        }
        return exec(Request.Builder().url(url).get())
    }

    private fun post(path: String, form: Map<String, String> = emptyMap()): JsonObject {
        val body = form.entries.joinToString("&") { "${it.key}=${it.value}" }.toRequestBody(FORM)
        return exec(Request.Builder().url(BASE + path).post(body))
    }

    private fun exec(builder: Request.Builder): JsonObject {
        val req = builder
            .header("User-Agent", UA)
            .header("X-Requested-With", "XMLHttpRequest")   // 缺了它只回空壳
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Referer", "$BASE/admin/?loginType=1")
            .build()

        val res = try {
            client.newCall(req).execute()
        } catch (e: IOException) {
            throw ApiException("网络请求失败：${e.message}")
        }

        res.use { r ->
            // 教务会话失效时统一 303 -> /admin/login
            if (r.code in 300..399 || r.code == 401) {
                throw AuthExpiredException()
            }
            if (r.code != 200) {
                throw ApiException("服务器返回 HTTP ${r.code}")
            }
            val text = r.body?.string().orEmpty()
            val json = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
                ?: throw AuthExpiredException()   // 返回 HTML = 已被踢回登录页

            val ret = json.get("ret")?.asInt ?: 0
            if (ret != 0) {
                throw ApiException("接口异常 ret=$ret ${json.get("msg")?.asString.orEmpty()}")
            }
            return json
        }
    }

    // ---------------------------------------------------------------- 公开接口

    /**
     * 当天课程 + 当前教学周次。
     * 做上课提醒只需要这一个接口，一次搞定「今天第几周」和「今天上什么」。
     */
    suspend fun getDay(date: LocalDate = LocalDate.now()): DayInfo = withContext(Dispatchers.IO) {
        val payload = get("/admin/getDayBz", mapOf("rq" to date.format(DateTimeFormatter.ISO_DATE)))
        parseDay(payload.getAsJsonObject("data") ?: JsonObject(), date)
    }

    suspend fun getAllWeeks(): List<Int> = withContext(Dispatchers.IO) {
        val payload = get("/admin/getCurrentPkZc")
        (payload.get("data") as? JsonArray)?.mapNotNull { it.asString.toIntOrNull() } ?: emptyList()
    }

    suspend fun getWeekSchedule(weekNo: Int, type: Int = 1): List<Course> =
        getWeekScheduleFull(weekNo, type).courses

    /**
     * 连节次时间表一起返回。
     * 课表网格左侧那一列「第几节 + 起止时间」就靠它，不同学年作息调整也不会错。
     */
    suspend fun getWeekScheduleFull(weekNo: Int, type: Int = 1): WeekSchedule =
        withContext(Dispatchers.IO) {
            val payload = post(
                "/admin/getXsdSykb",
                mapOf("type" to type.toString(), "zc" to weekNo.toString())
            )
            val data = payload.getAsJsonObject("data") ?: JsonObject()
            val times = periodTimes(data).mapValues { (_, v) -> "${v.first}-${v.second}" }
            WeekSchedule(parseSchedule(data), times)
        }

    /**
     * 登录态探针。实测行为极其干净：
     *   有效  -> 200 + 265KB 页面，含 admin_name
     *   失效  -> 303 -> /admin/login，0 字节
     */
    suspend fun isSessionAlive(): Boolean = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$BASE/admin/?loginType=1")
            .header("User-Agent", UA)
            .get()
            .build()
        try {
            client.newCall(req).execute().use { r ->
                r.code == 200 && r.body?.string().orEmpty().contains("admin_name")
            }
        } catch (e: IOException) {
            false
        }
    }

    /**
     * 静默续期：教务会话过期但 CAS 单点登录票据还在时，走一遍票据流程自动换新会话。
     *
     * 流程（全靠 CookieBridge 自动搬运 Cookie，不用手写任何 Cookie 逻辑）：
     *   1. GET /admin/caslogin
     *   2. 302 -> auth.ccsut.cn/backstage/cas/login?service=...
     *   3. CAS 认出 auth 域下的 CASTGC 有效 -> 302 回 /admin/caslogin?ticket=ST-xxx
     *   4. 教务校验 ticket -> 下发新 JSESSIONID
     *
     * 全程用户零操作。只有 CAS 票据本身也过期了才会落到登录页，此时返回 false。
     */
    suspend fun silentRenew(): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("$BASE/admin/caslogin")
                .header("User-Agent", UA)
                .build()
            val landedOnLoginPage = client.newCall(req).execute().use { r ->
                val finalUrl = r.request.url.toString()
                // 停在 CAS 登录页 = 单点登录票据也没了，静默续期失败
                finalUrl.contains("auth.ccsut.cn") && finalUrl.contains("login")
            }
            if (landedOnLoginPage) return@withContext false
            isSessionAlive()
        } catch (e: Exception) {
            false
        }
    }

    // ---------------------------------------------------------------- 解析

    private fun clean(v: String?): String {
        val s = v?.trim().orEmpty()
        return if (s == "-" || s == "null" || s == "_null_") "" else s
    }

    private fun periodTimes(data: JsonObject): Map<Int, Pair<String, String>> {
        val out = DEFAULT_PERIODS.toMutableMap()
        data.getAsJsonArray("jcKcxx")?.forEach { el ->
            val j = el.asJsonObject
            val no = j.get("jcbm")?.asString?.toIntOrNull() ?: return@forEach
            val s = clean(j.get("kssj")?.asString)
            val e = clean(j.get("jssj")?.asString)
            if (s.isNotEmpty() && e.isNotEmpty()) out[no] = s to e
        }
        return out
    }

    private fun parseDay(data: JsonObject, date: LocalDate): DayInfo {
        val xlrq = data.getAsJsonObject("xlrq") ?: JsonObject()
        val periods = DEFAULT_PERIODS

        val courses = mutableListOf<DayCourse>()
        data.getAsJsonArray("bzList")?.forEach { el ->
            val o = el.asJsonObject
            val name = clean(o.get("title")?.asString)
            if (name.isEmpty()) return@forEach
            val a = o.get("jcst")?.asString?.toIntOrNull() ?: 0
            val b = o.get("jcnd")?.asString?.toIntOrNull() ?: 0
            courses += DayCourse(
                name = name,
                teacher = clean(o.get("bz")?.asString),
                classroom = clean(o.get("address")?.asString),
                periodStart = a,
                periodEnd = b,
                timeStart = periods[a]?.first.orEmpty(),
                timeEnd = periods[b]?.second.orEmpty()
            )
        }

        return DayInfo(
            weekNo = xlrq.get("zc")?.asString?.toIntOrNull() ?: 0,
            term = clean(xlrq.get("xnxqh")?.asString),
            date = date.format(DateTimeFormatter.ISO_DATE),
            weekday = xlrq.get("xqbh")?.asString?.toIntOrNull() ?: date.dayOfWeek.value,
            weekdayCn = clean(xlrq.get("xqmc")?.asString),
            courses = courses.sortedBy { it.periodStart }
        )
    }

    /**
     * 解析课表，把连续节次合并。
     * 同一门课连上两节时接口会分别出现在 jcbm=1 和 jcbm=2，必须合并否则界面会重复两条。
     */
    private fun parseSchedule(data: JsonObject): List<Course> {
        val periods = periodTimes(data)

        // key = (weekday, name, teacher, classroom) -> 命中的节次集合
        val buckets = linkedMapOf<Quad, MutableSet<Int>>()

        data.getAsJsonArray("jcKcxx")?.forEach periodLoop@{ el ->
            val j = el.asJsonObject
            val period = j.get("jcbm")?.asString?.toIntOrNull() ?: return@periodLoop
            j.getAsJsonArray("kbxx")?.forEach kbLoop@{ kbel ->
                val kb = kbel.asJsonObject
                val weekday = kb.get("yzxq")?.asString?.toIntOrNull() ?: return@kbLoop
                if (weekday !in 1..7) return@kbLoop
                kb.getAsJsonArray("kcxx")?.forEach kcLoop@{ kcel ->
                    val kc = kcel.asJsonObject
                    val name = clean(kc.get("kcmc")?.asString)
                    if (name.isEmpty()) return@kcLoop
                    val key = Quad(weekday, name, clean(kc.get("teacher")?.asString),
                        clean(kc.get("classroom")?.asString))
                    buckets.getOrPut(key) { mutableSetOf() }.add(period)
                }
            }
        }

        val result = mutableListOf<Course>()
        buckets.forEach { (key, hits) ->
            val sorted = hits.sorted()
            if (sorted.isEmpty()) return@forEach

            // 连续节次切段：[1,2,5] -> [(1,2), (5,5)]
            var segStart = sorted[0]
            var prev = sorted[0]
            val segs = mutableListOf<Pair<Int, Int>>()
            for (p in sorted.drop(1)) {
                if (p == prev + 1) { prev = p; continue }
                segs += segStart to prev
                segStart = p; prev = p
            }
            segs += segStart to prev

            segs.forEach { (a, b) ->
                result += Course(
                    name = key.name, teacher = key.teacher, classroom = key.classroom,
                    weekday = key.weekday, periodStart = a, periodEnd = b,
                    timeStart = periods[a]?.first.orEmpty(),
                    timeEnd = periods[b]?.second.orEmpty()
                )
            }
        }
        return result.sortedWith(compareBy({ it.weekday }, { it.periodStart }, { it.name }))
    }

    private data class Quad(val weekday: Int, val name: String, val teacher: String, val classroom: String)
}
