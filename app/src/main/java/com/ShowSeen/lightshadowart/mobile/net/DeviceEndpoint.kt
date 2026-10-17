package com.ShowSeen.lightshadowart.mobile.net

import androidx.annotation.StringRes
import com.ShowSeen.lightshadowart.mobile.R

/**
 * Parsed device QR code: device address + auth token + interaction mode + authorized folder + role + device hotspot.
 *
 * Maps one-to-one to the device-side `ServerConfig` / `QrPayload`:
 * `http://<Device_IP>:<Port>/api/v1/auth?token=<DEVICE_TOKEN>&mode=<1|2>&role=<admin|viewer>&folder=<authorized folder>`
 * When the device runs a hotspot, `&ap_ssid=<SSID>&ap_pwd=<PASSWORD>` is appended as well.
 */
data class DeviceEndpoint(
    val host: String,
    val port: Int,
    val deviceToken: String,
    val mode: Int,
    /** Device hotspot SSID; empty means the QR code carried no hotspot info (connect manually) */
    val apSsid: String = "",
    /** Device hotspot password */
    val apPassword: String = "",
    /** Role declared by the QR code: admin / viewer; empty means the device decides (the first binder becomes admin) */
    val role: String = "",
    /** Authorized folder: ALL in personal mode, the folder chosen by the photographer in studio mode */
    val folder: String = ""
) {

    /** The QR code contains a device hotspot, so auto-connect is possible */
    val hasHotspot: Boolean get() = apSsid.isNotEmpty()

    /** Like http://192.168.1.20:39217 */
    val baseUrl: String get() = "http://$host:$port"

    /** Like http://192.168.1.20:39217/api/v1 */
    val apiBaseUrl: String get() = "$baseUrl/api/v1"

    /** Builds an API URL, e.g. urlOf("/upload") */
    fun urlOf(path: String): String = apiBaseUrl + path

    /**
     * String resource for the mode name (T-8).
     *
     * Must come from resources, never a hardcoded Chinese literal: on a real device switched to English the status
     * line still showed the Chinese family-mode text, which this property's hardcoded literal caused (found on device).
     */
    @get:StringRes
    val modeNameRes: Int
        get() = if (mode == MODE_PERSONAL) R.string.mode_personal else R.string.mode_studio

    /** Whether the QR code declares the admin role (selection users cannot configure parameters) */
    val isAdmin: Boolean get() = role.equals(ROLE_ADMIN, ignoreCase = true)

    companion object {
        const val MODE_PERSONAL = 1
        const val MODE_STUDIO = 2
        const val DEFAULT_PORT = 39217

        /** Must stay consistent with the device-side ServerConfig.ROLE_ADMIN / ROLE_VIEWER */
        const val ROLE_ADMIN = "admin"
        const val ROLE_VIEWER = "viewer"
    }
}
