package com.ShowSeen.lightshadowart.mobile.net

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Parses the QR code shown on the device idle screen.
 *
 * Payload spec (device side `QrPayload.kt`):
 * `http://<Device_IP>:<Port>/api/v1/auth?token=<DEVICE_TOKEN>&mode=<1|2>&role=<admin|viewer>&folder=<authorized folder>`
 * When the device hosts a hotspot it also carries `&ap_ssid=<SSID>&ap_pwd=<PASSWORD>`, which the phone uses to join it automatically.
 */
object QrPayloadParser {

    fun parse(raw: String?): Result<DeviceEndpoint> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) {
            return Result.failure(IllegalArgumentException("二维码内容为空"))
        }

        val url = normalize(text).toHttpUrlOrNull()
            ?: return Result.failure(IllegalArgumentException("不是有效的设备二维码"))

        val token = url.queryParameter("token").orEmpty()
        if (token.isEmpty()) {
            return Result.failure(IllegalArgumentException("二维码缺少 token 参数"))
        }

        val mode = url.queryParameter("mode")?.toIntOrNull() ?: DeviceEndpoint.MODE_STUDIO
        val apSsid = url.queryParameter("ap_ssid").orEmpty()
        val apPassword = url.queryParameter("ap_pwd").orEmpty()
        // Lets the phone identify "admin / viewer" and the authorized folder (requirements 4.2.2 / 6.2)
        val role = url.queryParameter("role").orEmpty()
        val folder = url.queryParameter("folder").orEmpty()
        return Result.success(
            DeviceEndpoint(url.host, url.port, token, mode, apSsid, apPassword, role, folder)
        )
    }

    /** The device serves plain HTTP only and scanned payloads may omit the scheme, so it is added here */
    private fun normalize(text: String): String = when {
        text.startsWith("http://", ignoreCase = true) -> text
        text.startsWith("https://", ignoreCase = true) -> "http://" + text.substring("https://".length)
        else -> "http://" + text
    }
}
