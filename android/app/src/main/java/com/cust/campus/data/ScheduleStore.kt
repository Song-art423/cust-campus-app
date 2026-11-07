package com.cust.campus.data

import android.content.Context
import com.cust.campus.data.model.TermData
import com.google.gson.Gson
import java.io.File
import java.time.LocalDate

/**
 * 学期课表的本地落盘。
 *
 * 存在 filesDir/schedule.json 而不是 SharedPreferences —— 整个学期 20 周的课表
 * 序列化后约百来 KB，SharedPreferences 会把它整个读进内存且不适合大对象。
 *
 * 写入走「临时文件 + 重命名」，避免写到一半被系统杀掉留下半个损坏的 JSON。
 */
object ScheduleStore {

    private const val FILE_NAME = "schedule.json"
    private const val TMP_NAME = "schedule.json.tmp"

    /** 数据超过这个时长就在后台静默补一次 */
    private const val STALE_MILLIS = 6L * 60 * 60 * 1000

    private val gson = Gson()

    fun load(context: Context): TermData? {
        val f = File(context.filesDir, FILE_NAME)
        if (!f.exists() || f.length() == 0L) return null
        return runCatching {
            gson.fromJson(f.readText(Charsets.UTF_8), TermData::class.java)
        }.getOrNull()?.takeIf { it.weekCourses.isNotEmpty() }
    }

    fun save(context: Context, data: TermData) {
        runCatching {
            val tmp = File(context.filesDir, TMP_NAME)
            tmp.writeText(gson.toJson(data), Charsets.UTF_8)
            val real = File(context.filesDir, FILE_NAME)
            if (real.exists()) real.delete()
            tmp.renameTo(real)
        }
    }

    fun clear(context: Context) {
        File(context.filesDir, FILE_NAME).delete()
        File(context.filesDir, TMP_NAME).delete()
    }

    /**
     * 是否需要补数据。两种情况：
     *   1. 跨天了 —— 今天的课、当前周次都可能变了
     *   2. 数据超过 6 小时 —— 学校偶尔会调课
     * 注意：这只触发后台静默刷新，界面上不会出现加载页。
     */
    fun isStale(data: TermData): Boolean {
        val today = LocalDate.now().toString()
        return data.todayDate != today ||
                System.currentTimeMillis() - data.syncedAt > STALE_MILLIS
    }
}
