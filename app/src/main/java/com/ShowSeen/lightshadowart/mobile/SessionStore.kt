package com.ShowSeen.lightshadowart.mobile

import android.content.Context
import android.content.SharedPreferences
import com.ShowSeen.lightshadowart.mobile.net.DeviceEndpoint
import com.ShowSeen.lightshadowart.mobile.net.DeviceParams
import org.json.JSONObject

/**
 * In-process session state: written after scanning/manual connect on the main screen, reused by the Wi-Fi config screen.
 *
 * Device-side sessions have a 12-hour TTL, refreshed by every heartbeat; the phone stops heartbeating once backgrounded,
 * and the device ends the session after 5 idle minutes, so tapping "Refresh connection" rebuilds an expired session.
 *
 * Also persists the SSID last pushed via Wi-Fi, the most recently connected device token, and the device hotspot credentials:
 * the first warns which Wi-Fi to join when phone and device are on different networks,
 * the second prefers the previous device when several are discovered on the LAN,
 * the third reconnects to the device hotspot (while the device is still a hotspot) when no device is found on the LAN, without rescanning.
 */
object SessionStore {

    /** Links a session can run over; kept in one place so persistence and UI agree */
    const val LINK_NONE = "none"
    const val LINK_AP = "ap"
    const val LINK_LAN = "lan"

    private const val PREFS_NAME = "light_shadow_session_store"
    private const val KEY_LAST_WIFI_SSID = "last_wifi_ssid"
    private const val KEY_CLIENT_ID = "client_id"
    private const val KEY_LAST_DEVICE_TOKEN = "last_device_token"
    private const val KEY_LAST_DEVICE_MODE = "last_device_mode"
    private const val KEY_LAST_AP_SSID = "last_ap_ssid"
    private const val KEY_LAST_AP_PASSWORD = "last_ap_password"
    private const val KEY_LAST_AP_HOST = "last_ap_host"
    private const val KEY_WIFI_PASSWORDS = "wifi_passwords"

    /** Link the session was last established over: [LINK_AP] or [LINK_LAN] */
    private const val KEY_LAST_LINK = "last_link"

    /** Network the phone pushed to the device but has not joined yet; drives the post-switch wait */
    private const val KEY_PENDING_LAN_SSID = "pending_lan_ssid"

    /** Persisted live session, so a process restart can resume without a fresh scan (PRD 16.5) */
    private const val KEY_ENDPOINT_JSON = "session_endpoint"
    private const val KEY_SESSION_ID = "session_id"
    private const val KEY_SESSION_EXPIRES = "session_expires_at"
    private const val KEY_SESSION_ROLE = "session_role"
    private const val KEY_SESSION_FOLDER = "session_folder"

    private var appContext: Context? = null

    @Volatile
    var endpoint: DeviceEndpoint? = null
        private set

    @Volatile
    var sessionId: String? = null
        private set

    @Volatile
    private var expiresAtMs: Long = 0L

    /** SSID used for the last successful Wi-Fi push; empty if never pushed */
    @Volatile
    var lastWifiSsid: String = ""
        private set

    /** SSID→password pairs this app pushed successfully; Android can't read system-saved passwords, so only this own cache can auto-fill them */
    private val wifiPasswords = mutableMapOf<String, String>()

    /** Token of the most recently connected device */
    @Volatile
    var lastDeviceToken: String = ""
        private set

    /**
     * This phone's stable identity, sent as `device_id` with `/auth`.
     *
     * The device persists it as the seat owner and refuses a silent recovery (`intent=recover`) that
     * would displace another phone's session (PRD 16.2 NW-18). A random value stored on first use is
     * enough: it only has to be stable on this phone, not globally unique forever.
     */
    @Volatile
    var clientId: String = ""
        private set

    /** Interaction mode of the most recently connected device */
    @Volatile
    var lastDeviceMode: Int = DeviceEndpoint.MODE_STUDIO
        private set

    /** Hotspot SSID of the last successful connection; empty if never connected via hotspot */
    @Volatile
    var lastApSsid: String = ""
        private set

    /** Hotspot password of the last successful connection */
    @Volatile
    var lastApPassword: String = ""
        private set

    /** Device address used for the last hotspot connection (IP on the hotspot subnet) */
    @Volatile
    var lastApHost: String = ""
        private set

    /** Link the last successful session used: [LINK_AP] / [LINK_LAN] / [LINK_NONE] */
    @Volatile
    var lastLink: String = LINK_NONE
        private set

    /**
     * Network the phone pushed to the device but has not joined yet.
     *
     * Persisted because the user may switch Wi-Fi through the system panel (or leave and come back)
     * while the app is paused: on return it has to know that a switch is still in progress and where
     * to look for the device.
     */
    @Volatile
    var pendingLanSsid: String = ""
        private set

    /** Role of this session: admin or viewer, returned by the device-side /auth */
    @Volatile
    var role: String = ""
        private set

    /** Folder authorized for this session: ALL in personal mode, the photographer's folder in studio mode */
    @Volatile
    var folder: String = ""
        private set

    /** Current device-side parameters (heartbeat / session timeout / idle countdown / autoplay interval) */
    @Volatile
    var params: DeviceParams = DeviceParams.DEFAULT
        private set

    /** Device app version, returned by the device-side /auth; shown on the About screen */
    @Volatile
    var deviceVersion: String = ""
        private set

    /** Admins can configure parameters and hand over control; viewers are read-only */
    val isAdmin: Boolean
        get() = role.equals(DeviceEndpoint.ROLE_ADMIN, ignoreCase = true)

    /** Whether the studio selection mode is currently active */
    val inStudioMode: Boolean
        get() = (endpoint?.mode ?: lastDeviceMode) == DeviceEndpoint.MODE_STUDIO

    val isActive: Boolean
        get() = endpoint != null && !sessionId.isNullOrEmpty() &&
            expiresAtMs > System.currentTimeMillis()

    /**
     * Whether this phone still has a pairing identity to recover with.
     *
     * Kept separate from [isActive]: the session dies with a device reboot or a 12 h TTL, while the
     * pairing survives both — that is exactly the case a foreground reconnect has to cover.
     */
    val hasTarget: Boolean
        get() = lastDeviceToken.isNotEmpty()

    val remainingMs: Long
        get() = (expiresAtMs - System.currentTimeMillis()).coerceAtLeast(0L)

    /** Loads persisted history; call once at app startup */
    fun init(context: Context) {
        if (appContext != null) return
        val appCtx = context.applicationContext
        appContext = appCtx

        val store = prefs(appCtx)
        // Stable per-phone identity for the seat check; generated once and kept forever
        clientId = store.getString(KEY_CLIENT_ID, "").orEmpty()
        if (clientId.isEmpty()) {
            clientId = java.util.UUID.randomUUID().toString()
            store.edit().putString(KEY_CLIENT_ID, clientId).apply()
        }
        lastWifiSsid = store.getString(KEY_LAST_WIFI_SSID, "").orEmpty()
        lastDeviceToken = store.getString(KEY_LAST_DEVICE_TOKEN, "").orEmpty()
        lastDeviceMode = store.getInt(KEY_LAST_DEVICE_MODE, DeviceEndpoint.MODE_STUDIO)
        lastApSsid = store.getString(KEY_LAST_AP_SSID, "").orEmpty()
        lastApPassword = store.getString(KEY_LAST_AP_PASSWORD, "").orEmpty()
        lastApHost = store.getString(KEY_LAST_AP_HOST, "").orEmpty()
        wifiPasswords.clear()
        wifiPasswords.putAll(parseWifiPasswords(store.getString(KEY_WIFI_PASSWORDS, "")))
        lastLink = store.getString(KEY_LAST_LINK, LINK_NONE).orEmpty()
        pendingLanSsid = store.getString(KEY_PENDING_LAN_SSID, "").orEmpty()

        // Resume the session that was live when the process died, if it has not expired yet
        val expires = store.getLong(KEY_SESSION_EXPIRES, 0L)
        val savedEndpoint = parseEndpoint(store.getString(KEY_ENDPOINT_JSON, ""))
        val savedSession = store.getString(KEY_SESSION_ID, "").orEmpty()
        if (savedEndpoint != null && savedSession.isNotEmpty() && expires > System.currentTimeMillis()) {
            endpoint = savedEndpoint
            sessionId = savedSession
            expiresAtMs = expires
            role = store.getString(KEY_SESSION_ROLE, "").orEmpty()
            folder = store.getString(KEY_SESSION_FOLDER, "").orEmpty()
        }
    }

    fun save(
        endpoint: DeviceEndpoint,
        sessionId: String,
        ttlMs: Long,
        role: String,
        folder: String,
        params: DeviceParams,
        deviceVersion: String
    ) {
        this.endpoint = endpoint
        this.sessionId = sessionId
        this.expiresAtMs = System.currentTimeMillis() + ttlMs
        this.role = role
        this.folder = folder
        this.params = params
        this.deviceVersion = deviceVersion

        lastDeviceToken = endpoint.deviceToken
        lastDeviceMode = endpoint.mode
        prefs()?.edit()
            ?.putString(KEY_LAST_DEVICE_TOKEN, lastDeviceToken)
            ?.putInt(KEY_LAST_DEVICE_MODE, lastDeviceMode)
            ?.putString(KEY_ENDPOINT_JSON, encodeEndpoint(endpoint))
            ?.putString(KEY_SESSION_ID, sessionId)
            ?.putLong(KEY_SESSION_EXPIRES, expiresAtMs)
            ?.putString(KEY_SESSION_ROLE, role)
            ?.putString(KEY_SESSION_FOLDER, folder)
            ?.apply()
    }

    /** Record which link the current session runs over; a reconnect retries only this link (PRD NW-5) */
    fun setLastLink(link: String) {
        if (lastLink == link) return
        lastLink = link
        prefs()?.edit()?.putString(KEY_LAST_LINK, link)?.apply()
    }

    /**
     * Forget the paired device after a sign-out.
     *
     * A sign-out makes the device rotate its token and hotspot password, so the stored token can only
     * ever produce a 401: keeping it would offer a "reconnect" that is guaranteed to fail instead of
     * telling the user to scan the new QR code.
     */
    fun forgetDeviceToken() {
        lastDeviceToken = ""
        lastApSsid = ""
        lastApPassword = ""
        lastApHost = ""
        prefs()?.edit()
            ?.remove(KEY_LAST_DEVICE_TOKEN)
            ?.remove(KEY_LAST_AP_SSID)
            ?.remove(KEY_LAST_AP_PASSWORD)
            ?.remove(KEY_LAST_AP_HOST)
            ?.apply()
    }

    /** Remember (or clear, with an empty value) the network the device was switched to */
    fun setPendingLanSsid(ssid: String) {
        if (pendingLanSsid == ssid) return
        pendingLanSsid = ssid
        prefs()?.edit()?.putString(KEY_PENDING_LAN_SSID, ssid)?.apply()
    }

    private fun encodeEndpoint(endpoint: DeviceEndpoint): String = JSONObject()
        .put("host", endpoint.host)
        .put("port", endpoint.port)
        .put("token", endpoint.deviceToken)
        .put("mode", endpoint.mode)
        .put("apSsid", endpoint.apSsid)
        .put("apPassword", endpoint.apPassword)
        .put("role", endpoint.role)
        .put("folder", endpoint.folder)
        .toString()

    private fun parseEndpoint(raw: String?): DeviceEndpoint? {
        if (raw.isNullOrEmpty()) return null
        return runCatching {
            val json = JSONObject(raw)
            val host = json.optString("host")
            if (host.isEmpty()) return null
            DeviceEndpoint(
                host = host,
                port = json.optInt("port", DeviceEndpoint.DEFAULT_PORT),
                deviceToken = json.optString("token"),
                mode = json.optInt("mode", DeviceEndpoint.MODE_STUDIO),
                apSsid = json.optString("apSsid"),
                apPassword = json.optString("apPassword"),
                role = json.optString("role"),
                folder = json.optString("folder")
            )
        }.getOrNull()
    }

    /** Refreshes the local cache after a heartbeat or config read returns the latest parameters */
    fun updateParams(params: DeviceParams) {
        this.params = params
    }

    /** Refreshes the local remaining time after a heartbeat returns expires_in_ms */
    fun touch(remaining: Long) {
        expiresAtMs = System.currentTimeMillis() + remaining
    }

    /**
     * The device address changes after a network switch (hotspot subnet → router subnet), but the session lives on
     * the device side and survives the switch, so only the local address is replaced with the new LAN one.
     */
    fun updateEndpoint(endpoint: DeviceEndpoint) {
        this.endpoint = endpoint
    }

    /** Records a successful Wi-Fi push: the device will join this network next */
    fun rememberWifiSsid(ssid: String) {
        lastWifiSsid = ssid
        prefs()?.edit()?.putString(KEY_LAST_WIFI_SSID, ssid)?.apply()
    }

    /** Password this app once pushed successfully for [ssid]; empty string if never recorded */
    fun wifiPasswordFor(ssid: String): String = wifiPasswords[ssid].orEmpty()

    /** Records a successfully pushed Wi-Fi password so it auto-fills when the same SSID is picked again */
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
     * Records a successful device hotspot connection: if the device returns to hotspot mode later (no Wi-Fi joined
     * or hotspot reopened), "Refresh connection" reconnects with these credentials instead of rescanning the code.
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

    /** Device endpoint used for the last hotspot connection; null if no hotspot info was recorded */
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

    /** Clears the current session; history (last Wi-Fi, recent device, hotspot credentials) is kept */
    fun clear() {
        endpoint = null
        sessionId = null
        expiresAtMs = 0L
        role = ""
        folder = ""
        params = DeviceParams.DEFAULT
        deviceVersion = ""
        pendingLanSsid = ""
        // No session means no last link: a reconnect must not keep targeting the old one
        lastLink = LINK_NONE
        prefs()?.edit()
            ?.remove(KEY_ENDPOINT_JSON)
            ?.remove(KEY_SESSION_ID)
            ?.remove(KEY_SESSION_EXPIRES)
            ?.remove(KEY_SESSION_ROLE)
            ?.remove(KEY_SESSION_FOLDER)
            ?.remove(KEY_PENDING_LAN_SSID)
            ?.putString(KEY_LAST_LINK, LINK_NONE)
            ?.apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun prefs(): SharedPreferences? =
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
