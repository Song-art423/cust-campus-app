package com.cust.campus.data

import android.content.Context
import android.content.SharedPreferences
import com.cust.campus.data.model.DayCourse
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 课程本地缓存。
 *
 * 闹钟触发时 App 进程大概率已经被系统回收了，没法现拉接口，
 * 所以必须把「今天的课」提前落到磁盘，闹钟响起时直接读。
 *
 * 顺带存了用户的提醒偏好。
 */
object CourseCache {

    private const val PREF = "course_cache"
    private const val KEY_DATE = "cached_date"
    private const val KEY_COURSES = "courses_json"
    private const val KEY_ADVANCE = "advance_minutes"
    private const val KEY_ENABLED = "remind_enabled"
    private const val KEY_FULLSCREEN = "fullscreen_enabled"
    private const val KEY_VIBRATE = "vibrate_enabled"

    /** 默认提前 15 分钟，和大多数教务 App 一致 */
    private const val DEFAULT_ADVANCE = 15

    private val gson = Gson()

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    data class Snapshot(val date: String, val courses: List<DayCourse>) {
        val isToday: Boolean
            get() = date == LocalDate.now().format(DateTimeFormatter.ISO_DATE)
    }

    fun save(context: Context, date: String, courses: List<DayCourse>) {
        prefs(context).edit()
            .putString(KEY_DATE, date)
            .putString(KEY_COURSES, gson.toJson(courses))
            .apply()
    }

    /** 原始 JSON，调试时用来原样备份/恢复，不经过一次序列化往返 */
    fun rawJson(context: Context): String? =
        prefs(context).getString(KEY_COURSES, null)

    /** 原样写回，配合 [rawJson] 做调试后的还原 */
    fun restoreRaw(context: Context, date: String, json: String) {
        prefs(context).edit()
            .putString(KEY_DATE, date)
            .putString(KEY_COURSES, json)
            .apply()
    }

    fun load(context: Context): Snapshot? {
        val p = prefs(context)
        val date = p.getString(KEY_DATE, null) ?: return null
        val json = p.getString(KEY_COURSES, null) ?: return null
        val courses = runCatching {
            gson.fromJson<List<DayCourse>>(
                json,
                object : TypeToken<List<DayCourse>>() {}.type
            )
        }.getOrNull() ?: return null
        return Snapshot(date, courses)
    }

    // ---------------------------------------------------------------- 偏好

    fun getAdvance(context: Context): Int =
        prefs(context).getInt(KEY_ADVANCE, DEFAULT_ADVANCE)

    fun setAdvance(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_ADVANCE, minutes.coerceIn(0, 60)).apply()
    }

    fun isRemindEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setRemindEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    /**
     * 熄屏时是否用「全屏 Intent」把提醒页盖到锁屏上。
     *
     * **默认关**。这是唯一一处会改变通知形态的挂载，也是排查
     * 「通知整条不显示」时的第一嫌疑，需要用户主动开启。
     */
    fun isFullScreenEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FULLSCREEN, false)

    fun setFullScreenEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_FULLSCREEN, on).apply()
    }

    /**
     * 是否震动。
     *
     * **默认关**：21:06 那版能弹出通知的状态就是「不震动」，
     * 而渠道的震动属性创建后永久锁死，想震必须换一条新渠道 —— 这正是
     * 上一次通知彻底消失的最大嫌疑。所以震动做成独立开关 + 独立渠道，
     * 出问题关掉就能立刻回到可用状态。
     */
    fun isVibrateEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VIBRATE, false)

    fun setVibrateEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_VIBRATE, on).apply()
    }
}
