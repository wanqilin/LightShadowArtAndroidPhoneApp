package com.ShowSeen.lightshadowart.mobile

import android.content.Context
import android.content.SharedPreferences
import com.ShowSeen.lightshadowart.mobile.net.DeviceEndpoint
import com.ShowSeen.lightshadowart.mobile.net.DeviceParams
import org.json.JSONObject

/**
 * 进程内会话状态：主界面扫码/手动连接后写入，Wi-Fi 配置页直接复用。
 *
 * 设备端会话 TTL 为 12 小时，每次心跳都会刷新剩余时间；手机端退到后台不再心跳，
 * 设备端会在空闲 5 分钟后自动结束会话，因此本地会话失效后点击「刷新连接」即可重建。
 *
 * 另外持久化「上次下发 Wi-Fi 时用的 SSID」、「最近连接的设备标识」与「设备热点凭据」：
 * 前者用于手机与设备不在同一网络时提醒用户该连哪个 Wi-Fi，
 * 中者用于局域网内发现到多台设备时优先选中上次那台，
 * 后者用于局域网内找不到设备时（设备仍在热点形态）回连设备热点，无需重新扫码。
 */
object SessionStore {

    private const val PREFS_NAME = "light_shadow_session_store"
    private const val KEY_LAST_WIFI_SSID = "last_wifi_ssid"
    private const val KEY_LAST_DEVICE_TOKEN = "last_device_token"
    private const val KEY_LAST_DEVICE_MODE = "last_device_mode"
    private const val KEY_LAST_AP_SSID = "last_ap_ssid"
    private const val KEY_LAST_AP_PASSWORD = "last_ap_password"
    private const val KEY_LAST_AP_HOST = "last_ap_host"
    private const val KEY_WIFI_PASSWORDS = "wifi_passwords"

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

    /** 本 App 曾成功下发过的 SSID→密码；安卓读不到系统保存的密码，只能用这份自有缓存自动带入 */
    private val wifiPasswords = mutableMapOf<String, String>()

    /** 最近一次连接过的设备 Token */
    @Volatile
    var lastDeviceToken: String = ""
        private set

    /** 最近一次连接过的设备交互模式 */
    @Volatile
    var lastDeviceMode: Int = DeviceEndpoint.MODE_STUDIO
        private set

    /** 上次成功接入的设备热点名；为空表示还没通过热点连接过 */
    @Volatile
    var lastApSsid: String = ""
        private set

    /** 上次成功接入的设备热点密码 */
    @Volatile
    var lastApPassword: String = ""
        private set

    /** 上次通过设备热点连接时用的设备地址（热点网段里的 IP） */
    @Volatile
    var lastApHost: String = ""
        private set

    /** 本次会话角色：admin（管理员）/ viewer（选片用户），由设备端 /auth 返回 */
    @Volatile
    var role: String = ""
        private set

    /** 本次会话授权文件夹：个人模式 ALL，门店模式为摄影师指定文件夹 */
    @Volatile
    var folder: String = ""
        private set

    /** 设备端当前运行参数（心跳 / 会话超时 / 无操作倒计时 / 自动播放间隔） */
    @Volatile
    var params: DeviceParams = DeviceParams.DEFAULT
        private set

    /** 管理员可配置参数、可移交控制权；选片用户只读 */
    val isAdmin: Boolean
        get() = role.equals(DeviceEndpoint.ROLE_ADMIN, ignoreCase = true)

    /** 当前是否处于门店选片模式 */
    val inStudioMode: Boolean
        get() = (endpoint?.mode ?: lastDeviceMode) == DeviceEndpoint.MODE_STUDIO

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
        lastApSsid = store.getString(KEY_LAST_AP_SSID, "").orEmpty()
        lastApPassword = store.getString(KEY_LAST_AP_PASSWORD, "").orEmpty()
        lastApHost = store.getString(KEY_LAST_AP_HOST, "").orEmpty()
        wifiPasswords.clear()
        wifiPasswords.putAll(parseWifiPasswords(store.getString(KEY_WIFI_PASSWORDS, "")))
    }

    fun save(
        endpoint: DeviceEndpoint,
        sessionId: String,
        ttlMs: Long,
        role: String,
        folder: String,
        params: DeviceParams
    ) {
        this.endpoint = endpoint
        this.sessionId = sessionId
        this.expiresAtMs = System.currentTimeMillis() + ttlMs
        this.role = role
        this.folder = folder
        this.params = params

        lastDeviceToken = endpoint.deviceToken
        lastDeviceMode = endpoint.mode
        prefs()?.edit()
            ?.putString(KEY_LAST_DEVICE_TOKEN, lastDeviceToken)
            ?.putInt(KEY_LAST_DEVICE_MODE, lastDeviceMode)
            ?.apply()
    }

    /** 心跳 / 读取配置返回最新参数后刷新本地缓存 */
    fun updateParams(params: DeviceParams) {
        this.params = params
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

    /** 本 App 曾为 [ssid] 成功下发过的密码；没记录过返回空串 */
    fun wifiPasswordFor(ssid: String): String = wifiPasswords[ssid].orEmpty()

    /** 记录一次成功下发的 Wi-Fi 密码，下次选到同一 SSID 时自动带入 */
    fun rememberWifiPassword(ssid: String, password: String) {
        if (ssid.isEmpty() || password.isEmpty()) return
        if (wifiPasswords[ssid] == password) return
        wifiPasswords[ssid] = password
        prefs()?.edit()?.putString(KEY_WIFI_PASSWORDS, encodeWifiPasswords())?.apply()
    }

    private fun encodeWifiPasswords(): String {
        val json = JSONObject()
        wifiPasswords.forEach { (ssid, password) -> json.put(ssid, password) }
        return json.toString()
    }

    private fun parseWifiPasswords(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            val result = mutableMapOf<String, String>()
            json.keys().forEach { key -> result[key] = json.optString(key) }
            result
        }.getOrDefault(emptyMap())
    }

    /**
     * 记录一次成功的设备热点接入：设备之后若又回到热点形态（没接入 Wi-Fi 或重开热点），
     * 「刷新连接」用这份凭据回连，用户不必重新扫码。
     */
    fun rememberApCredentials(endpoint: DeviceEndpoint) {
        if (!endpoint.hasHotspot) return
        lastApSsid = endpoint.apSsid
        lastApPassword = endpoint.apPassword
        lastApHost = endpoint.host
        prefs()?.edit()
            ?.putString(KEY_LAST_AP_SSID, lastApSsid)
            ?.putString(KEY_LAST_AP_PASSWORD, lastApPassword)
            ?.putString(KEY_LAST_AP_HOST, lastApHost)
            ?.apply()
    }

    /** 上次通过设备热点连接时用的设备端点；没记录过热点信息时返回 null */
    fun apEndpoint(): DeviceEndpoint? {
        if (lastApSsid.isEmpty() || lastApHost.isEmpty()) return null
        return DeviceEndpoint(
            host = lastApHost,
            port = DeviceEndpoint.DEFAULT_PORT,
            deviceToken = lastDeviceToken,
            mode = lastDeviceMode,
            apSsid = lastApSsid,
            apPassword = lastApPassword
        )
    }

    /** 清空当前会话；历史记录（上次 Wi-Fi、最近设备、设备热点凭据）保留 */
    fun clear() {
        endpoint = null
        sessionId = null
        expiresAtMs = 0L
        role = ""
        folder = ""
        params = DeviceParams.DEFAULT
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun prefs(): SharedPreferences? =
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
