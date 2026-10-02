package com.ShowSeen.lightshadowart.mobile.net

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 解析设备端待机页二维码。
 *
 * 载荷规范（设备端 `QrPayload.kt`）：
 * `http://<Device_IP>:<Port>/api/v1/auth?token=<DEVICE_TOKEN>&mode=<1|2>&role=<admin|viewer>&folder=<授权文件夹>`
 * 设备开热点时再带 `&ap_ssid=<SSID>&ap_pwd=<PASSWORD>`，手机据此自动接入热点。
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
        // 二维码据此让移动端识别「管理员 / 选片用户」以及授权文件夹（需求 4.2.2 / 6.2）
        val role = url.queryParameter("role").orEmpty()
        val folder = url.queryParameter("folder").orEmpty()
        return Result.success(
            DeviceEndpoint(url.host, url.port, token, mode, apSsid, apPassword, role, folder)
        )
    }

    /** 设备端只有纯 HTTP，且扫码载荷可能省略 scheme，这里统一补全 */
    private fun normalize(text: String): String = when {
        text.startsWith("http://", ignoreCase = true) -> text
        text.startsWith("https://", ignoreCase = true) -> "http://" + text.substring("https://".length)
        else -> "http://" + text
    }
}
