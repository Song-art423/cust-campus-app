package com.cust.campus

import android.app.Application
import com.cust.campus.data.SessionKeeper
import com.cust.campus.data.SessionNotifier
import com.cust.campus.data.api.CookieBridge
import com.cust.campus.notify.CourseNotifier
import com.cust.campus.work.DailyRefreshWorker

class CampusApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // CookieManager 是懒加载的，提前初始化一次，后续所有请求才拿得到落盘的 Cookie
        CookieBridge.init(this)
        SessionNotifier.ensureChannel(this)
        CourseNotifier.ensureChannels(this)

        // 后台保活：每 6 小时轻碰一次教务系统
        // 服务端是 14 天滑动续期，这个频率下登录态基本不会掉
        SessionKeeper.schedule(this)

        // 每天凌晨刷新当天课表并重排闹钟。
        // 没有它，前一天排的闹钟第二天就全废了
        DailyRefreshWorker.schedule(this)
    }
}
