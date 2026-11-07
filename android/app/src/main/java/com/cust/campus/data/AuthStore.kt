package com.cust.campus.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 登录凭据存储。
 *
 * Cookie 等价于账号密码，必须加密落盘。优先用 EncryptedSharedPreferences（AES256-GCM，
 * 密钥由系统 Keystore 托管）；个别机型 Keystore 异常时降级到普通存储，保证功能可用。
 */
class AuthStore(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "auth_encrypted",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.e(TAG, "加密存储不可用，降级为普通存储", e)
        context.getSharedPreferences("auth_plain", Context.MODE_PRIVATE)
    }

    var cookie: String?
        get() = prefs.getString(KEY_COOKIE, null)
        set(value) {
            // 用 commit 而非 apply：这是登录凭据的唯一副本，
            // 写盘必须同步完成，不能赌进程在异步落盘前不会被杀
            prefs.edit().putString(KEY_COOKIE, value).commit()
        }

    /**
     * Cookie 快照的写入时间。
     *
     * 用途：Cookie 本身不带「还剩多久」，而服务端给的是 14 天滑动窗口。
     * 记下快照时刻，回灌时就能算出还该给多久有效期，也能在界面上显示剩余天数。
     */
    var cookieSavedAt: Long
        get() = prefs.getLong(KEY_COOKIE_AT, 0L)
        set(value) {
            prefs.edit().putLong(KEY_COOKIE_AT, value).commit()
        }

    val isLoggedIn: Boolean
        get() = !cookie.isNullOrBlank()

    /** 缓存的身份信息，用于界面展示 */
    var displayName: String?
        get() = prefs.getString(KEY_NAME, null)
        set(value) = prefs.edit().putString(KEY_NAME, value).apply()

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val TAG = "AuthStore"
        private const val KEY_COOKIE = "cookie"
        private const val KEY_COOKIE_AT = "cookie_saved_at"
        private const val KEY_NAME = "display_name"
    }
}
