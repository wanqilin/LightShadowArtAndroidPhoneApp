package com.warke.lightshadowart.mobile

import android.content.Context
import android.content.SharedPreferences
import com.warke.lightshadowart.mobile.net.DeviceEndpoint

/**
 * 进程内会话状态：主界面扫码/手动连接后写入，Wi-Fi 配置页直接复用。
 *
 * 设备端会话 TTL 为 12 小时，每次心跳都会刷新剩余时间；手机端退到后台不再心跳，
 * 设备端会在空闲 5 分钟后自动结束会话，因此本地会话失效后点击「刷新连接」即可重建。
 *
 * 另外持久化「上次下发 Wi-Fi 时用的 SSID」与「最近连接的设备标识」：
 * 前者用于手机与设备不在同一网络时提醒用户该连哪个 Wi-Fi，
 * 后者用于局域网内发现到多台设备时优先选中上次那台。
 */
object SessionStore {

    private const val PREFS_NAME = "light_shadow_session_store"
    private const val KEY_LAST_WIFI_SSID = "last_wifi_ssid"
    private const val KEY_LAST_DEVICE_TOKEN = "last_device_token"
    private const val KEY_LAST_DEVICE_MODE = "last_device_mode"

    private var appContext: Context? = null

    @Volatile
    var endpoint: DeviceEndpoint? = null
        private set

    @Volatile
    var sessionId: String? = null
        private set

    @Volatile
    private var expiresAtMs: Long = 0L

    /** 上一次成功下发 Wi-Fi 时使用的 SSID；为空表示还没下发过 */
    @Volatile
    var lastWifiSsid: String = ""
        private set

    /** 最近一次连接过的设备 Token */
    @Volatile
    var lastDeviceToken: String = ""
        private set

    /** 最近一次连接过的设备交互模式 */
    @Volatile
    var lastDeviceMode: Int = DeviceEndpoint.MODE_STUDIO
        private set

    val isActive: Boolean
        get() = endpoint != null && !sessionId.isNullOrEmpty()

    val remainingMs: Long
        get() = (expiresAtMs - System.currentTimeMillis()).coerceAtLeast(0L)

    /** 读取历史记录，App 启动时调用一次即可 */
    fun init(context: Context) {
        if (appContext != null) return
        val appCtx = context.applicationContext
        appContext = appCtx

        val store = prefs(appCtx)
        lastWifiSsid = store.getString(KEY_LAST_WIFI_SSID, "").orEmpty()
        lastDeviceToken = store.getString(KEY_LAST_DEVICE_TOKEN, "").orEmpty()
        lastDeviceMode = store.getInt(KEY_LAST_DEVICE_MODE, DeviceEndpoint.MODE_STUDIO)
    }

    fun save(endpoint: DeviceEndpoint, sessionId: String, ttlMs: Long) {
        this.endpoint = endpoint
        this.sessionId = sessionId
        this.expiresAtMs = System.currentTimeMillis() + ttlMs

        lastDeviceToken = endpoint.deviceToken
        lastDeviceMode = endpoint.mode
        prefs()?.edit()
            ?.putString(KEY_LAST_DEVICE_TOKEN, lastDeviceToken)
            ?.putInt(KEY_LAST_DEVICE_MODE, lastDeviceMode)
            ?.apply()
    }

    /** 心跳返回 expires_in_ms 后刷新本地剩余时间 */
    fun touch(remaining: Long) {
        expiresAtMs = System.currentTimeMillis() + remaining
    }

    /**
     * 设备换网后地址会变（热点网段 → 路由器网段），但会话存在设备端、不受换网影响，
     * 所以只把本地记的地址换成局域网里的新地址，会话本身保持不变。
     */
    fun updateEndpoint(endpoint: DeviceEndpoint) {
        this.endpoint = endpoint
    }

    /** 记录一次成功的 Wi-Fi 下发：设备接下来会接入这个网络 */
    fun rememberWifiSsid(ssid: String) {
        lastWifiSsid = ssid
        prefs()?.edit()?.putString(KEY_LAST_WIFI_SSID, ssid)?.apply()
    }

    /** 清空当前会话；历史记录（上次 Wi-Fi、最近设备）保留 */
    fun clear() {
        endpoint = null
        sessionId = null
        expiresAtMs = 0L
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun prefs(): SharedPreferences? =
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
