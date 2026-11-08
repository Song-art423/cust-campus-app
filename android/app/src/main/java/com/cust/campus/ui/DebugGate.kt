package com.cust.campus.ui

import android.content.Context
import com.cust.campus.BuildConfig

/**
 * 调试面板的隐藏入口开关。
 *
 * 调试卡片会暴露保险库快照年龄、探活路径、通知渠道 id 这类内部实现，
 * 正式包不该让普通用户看到 —— 既困惑，又容易被误当成故障。
 *
 * 行业通用做法是「隐藏手势 + 本地开关」，Android 系统本身就是这套：
 * 设置 → 关于手机 → 连点版本号 7 次解锁开发者选项。这里照搬同一套交互。
 *
 * 默认值取 [BuildConfig.DEBUG]：
 *   - debug 包（自己测）：默认开，装上就能用，不必每次先点 7 下
 *   - release 包（发给别人）：默认关，连点版本号 7 次才出现
 *
 * 连点 7 次是**切换**而不是单向解锁 —— 同一个手势既能开也能关，
 * 用户不需要记住「怎么把它藏回去」。
 *
 * 取舍说明：没有用 `if (BuildConfig.DEBUG)` 包裹调用点，那样 R8 能在
 * release 包里把调试代码整个剔除（体积能省几十 KB），但也会让 release 包
 * 彻底失去开面板的能力。QA 恰恰要用 release 包验证混淆和性能，
 * 所以保留运行时开关，代价是几十 KB。
 */
object DebugGate {

    private const val PREF = "debug_gate"
    private const val KEY_ON = "panel_on"

    /** 解锁需要的连点次数，和 Android 系统设置保持一致 */
    const val UNLOCK_TAPS = 7

    /** 连点时间窗口：两次点击间隔超过这个值就重新计数，避免一天点 7 次被误判 */
    const val TAP_WINDOW_MS = 1500L

    fun isOn(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ON, BuildConfig.DEBUG)

    fun setOn(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ON, on).apply()
    }

    /** 首次安装时的默认值，用于在「关于」里提示用户当前处于哪种状态 */
    fun defaultOn(): Boolean = BuildConfig.DEBUG

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
