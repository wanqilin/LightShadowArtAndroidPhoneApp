package com.warke.lightshadowart.mobile.net

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 解析设备端待机页二维码。
 *
 * 载荷规范（设备端 `QrPayload.kt`）：
 * `http://<Device_IP>:<Port>/api/v1/auth?token=<DEVICE_TOKEN>&mode=<1|2>`
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
        return Result.success(DeviceEndpoint(url.host, url.port, token, mode, apSsid, apPassword))
    }

    /** 手动输入入口：`192.168.1.20` 或 `192.168.1.20:8080`，缺端口时用默认 8080 */
    fun fromManualInput(hostText: String, tokenText: String): Result<DeviceEndpoint> {
        val address = hostText.trim()
        if (address.isEmpty()) {
            return Result.failure(IllegalArgumentException("请输入设备地址"))
        }

        val host = address.substringBefore(':').trim()
        val port = address.substringAfter(':', "").trim().toIntOrNull() ?: DeviceEndpoint.DEFAULT_PORT
        if (host.isEmpty() || port !in 1..65535) {
            return Result.failure(IllegalArgumentException("设备地址格式应为 IP:端口"))
        }

        val token = tokenText.trim().ifEmpty { DEFAULT_DEVICE_TOKEN }
        return Result.success(DeviceEndpoint(host, port, token, DeviceEndpoint.MODE_STUDIO))
    }

    /** 设备端只有纯 HTTP，且扫码/手输都可能省略 scheme，这里统一补全 */
    private fun normalize(text: String): String = when {
        text.startsWith("http://", ignoreCase = true) -> text
        text.startsWith("https://", ignoreCase = true) -> "http://" + text.substring("https://".length)
        else -> "http://" + text
    }

    /** 与设备端 ServerConfig.DEVICE_TOKEN 保持一致，仅作手动输入的默认值 */
    const val DEFAULT_DEVICE_TOKEN = "lightshadowart-device-001"
}
