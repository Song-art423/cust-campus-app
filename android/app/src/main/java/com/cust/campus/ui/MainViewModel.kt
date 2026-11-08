package com.cust.campus.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cust.campus.data.CourseCache
import com.cust.campus.data.ScheduleStore
import com.cust.campus.data.ScheduleSync
import com.cust.campus.data.SessionKeeper
import com.cust.campus.data.api.CookieBridge
import com.cust.campus.data.api.CustApi
import com.cust.campus.data.model.AuthExpiredException
import com.cust.campus.data.model.UiState
import com.cust.campus.work.CourseAlarmScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 唯一的状态来源。
 *
 * 设计原则：**界面永远不进入「加载中」**。
 *   - 启动瞬间从磁盘读上次同步的整学期课表，直接出画面（耗时约几毫秒）
 *   - 只有完全没有缓存（首次使用）时才走一次全量同步，并给一个正经的进度页
 *   - 之后所有刷新都在后台静默进行，界面只显示一条细进度线
 *   - 切周次纯本地切换，零网络请求
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val api = CustApi()
    private val ctx: Context get() = getApplication()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        val cached = ScheduleStore.load(ctx)
        if (cached != null) {
            // 有缓存 —— 立刻渲染，不等待任何网络
            _state.value = UiState(
                data = cached,
                selectedWeek = cached.currentWeek.coerceAtLeast(cached.weeks.firstOrNull() ?: 1),
                remindAdvance = CourseCache.getAdvance(ctx),
                remindEnabled = CourseCache.isRemindEnabled(ctx)
            )
            refreshCookieStatus()
            // 每次打开让页面脚本把 jw_uf* 的计时器归零，12 小时内只跑一次。
            // 等探活失败再刷新就晚了 —— 那时服务端已经判定凭据不一致，页面也救不回来。
            SessionKeeper.refreshPageIfStale(ctx)
            if (ScheduleStore.isStale(cached)) quickRefresh()
        } else {
            fullSync()
        }
    }

    // ---------------------------------------------------------------- 同步

    /**
     * 全量同步：只在「首次使用」和「重新登录」时跑。整个学期一次拉完（并发 4 路）。
     */
    fun fullSync() {
        if (_state.value.syncing) return
        job?.cancel()
        job = viewModelScope.launch {
            _state.update {
                it.copy(syncing = true, syncProgress = 0f, syncLabel = "正在连接教务系统", error = null)
            }
            try {
                if (SessionKeeper.ensure(ctx) == SessionKeeper.Status.EXPIRED) {
                    _state.update {
                        it.copy(syncing = false, authExpired = true, error = "登录已过期")
                    }
                    refreshCookieStatus()
                    return@launch
                }

                val data = withContext(Dispatchers.IO) {
                    ScheduleSync.full(ctx, api) { progress, label ->
                        // 并发下这个回调来自多个线程，必须用 update 做 CAS，不能读改写
                        _state.update { it.copy(syncProgress = progress, syncLabel = label) }
                    }
                }

                _state.update {
                    it.copy(
                        data = data,
                        selectedWeek = data.currentWeek.coerceAtLeast(1),
                        syncing = false,
                        syncProgress = 1f,
                        syncLabel = "",
                        error = null,
                        authExpired = false,
                        remindAdvance = CourseCache.getAdvance(ctx),
                        remindEnabled = CourseCache.isRemindEnabled(ctx)
                    )
                }
                refreshCookieStatus()
            } catch (e: AuthExpiredException) {
                _state.update {
                    it.copy(syncing = false, authExpired = true, error = "登录已过期，请重新登录")
                }
                refreshCookieStatus()
            } catch (e: Exception) {
                _state.update {
                    it.copy(syncing = false, error = e.message ?: "同步失败，请检查网络")
                }
            }
        }
    }

    /** 静默补数据：只更新今天和当前周，界面不动 */
    private fun quickRefresh() {
        if (_state.value.syncing) return
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(syncing = true, syncProgress = 0.3f, syncLabel = "正在更新") }
            try {
                if (SessionKeeper.ensure(ctx) == SessionKeeper.Status.EXPIRED) {
                    _state.update { it.copy(syncing = false, authExpired = true, syncLabel = "") }
                    refreshCookieStatus()
                    return@launch
                }

                val data = withContext(Dispatchers.IO) {
                    ScheduleSync.quick(ctx, api, _state.value.data)
                }

                _state.update {
                    it.copy(
                        data = data,
                        // 用户正在看别的周时不要把他拽回来
                        selectedWeek = if (it.selectedWeek == 0) data.currentWeek else it.selectedWeek,
                        syncing = false,
                        syncProgress = 1f,
                        syncLabel = "",
                        error = null,
                        authExpired = false
                    )
                }
                refreshCookieStatus()
            } catch (e: AuthExpiredException) {
                _state.update { it.copy(syncing = false, authExpired = true, syncLabel = "") }
                refreshCookieStatus()
            } catch (e: Exception) {
                // 静默刷新失败不打扰用户，保留旧数据照样能用
                _state.update { it.copy(syncing = false, syncLabel = "") }
            }
        }
    }

    /** 手动点刷新：有数据走静默，没数据走全量 */
    fun refresh() {
        if (_state.value.data == null) fullSync() else quickRefresh()
    }

    /** 登录页返回 */
    fun onLoginReturned() {
        refreshCookieStatus()
        if (_state.value.data == null) fullSync() else quickRefresh()
    }

    // ---------------------------------------------------------------- 登录凭据

    /** 读一次 Cookie 状态给界面。要走加密存储，不能放主线程 */
    private fun refreshCookieStatus() {
        viewModelScope.launch {
            val st = withContext(Dispatchers.IO) {
                runCatching {
                    CookieBridge.ensureRestored()
                    CookieBridge.status()
                }.getOrNull()
            } ?: return@launch
            _state.update { it.copy(cookieText = st.text, cookieOk = st.hasCredential) }
        }
    }

    // ---------------------------------------------------------------- 交互

    /** 切周次。纯本地，不发请求 —— 这就是「一次查完」的意义 */
    fun selectWeek(week: Int) {
        val data = _state.value.data ?: return
        if (week !in data.weeks && data.weeks.isNotEmpty()) return
        _state.update { it.copy(selectedWeek = week) }
    }

    fun switchWeek(delta: Int) {
        val data = _state.value.data ?: return
        val target = _state.value.selectedWeek + delta
        val min = data.weeks.minOrNull() ?: 1
        val max = data.weeks.maxOrNull() ?: 20
        if (target in min..max) _state.update { it.copy(selectedWeek = target) }
    }

    fun goToday() {
        _state.value.data?.let { d -> _state.update { it.copy(selectedWeek = d.currentWeek) } }
    }

    fun setAdvance(minutes: Int) {
        CourseCache.setAdvance(ctx, minutes)
        CourseAlarmScheduler.scheduleToday(ctx)
        _state.update { it.copy(remindAdvance = CourseCache.getAdvance(ctx)) }
    }

    fun setRemindEnabled(on: Boolean) {
        CourseCache.setRemindEnabled(ctx, on)
        if (on) CourseAlarmScheduler.scheduleToday(ctx) else CourseAlarmScheduler.cancelAll(ctx)
        _state.update { it.copy(remindEnabled = on) }
    }

    /**
     * 只清课表缓存重拉，**不动登录态**。
     * 清 Cookie 是「退出登录」的语义，混在这里会让用户莫名其妙被要求重新登录 ——
     * 真要退出登录有「重新登录」入口。顺带清掉的就是 v1 掉登录的经典误伤。
     */
    fun clearAndResync() {
        ScheduleStore.clear(ctx)
        // 先把 syncing 置回 false，否则 fullSync 的并发保护会直接 return，什么都不会发生
        _state.update { it.copy(data = null, selectedWeek = 0, syncing = false, error = null) }
        fullSync()
    }
}
