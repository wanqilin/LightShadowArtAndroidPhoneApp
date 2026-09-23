package com.warke.lightshadowart.mobile.net

/**
 * 设备端二维码解析结果：设备地址 + 鉴权 Token + 交互模式 + 设备热点。
 *
 * 与设备端 `ServerConfig` / `QrPayload` 一一对应：
 * `http://<Device_IP>:<Port>/api/v1/auth?token=<DEVICE_TOKEN>&mode=<1|2>`
 * 设备开热点时再带 `&ap_ssid=<SSID>&ap_pwd=<PASSWORD>`。
 */
data class DeviceEndpoint(
    val host: String,
    val port: Int,
    val deviceToken: String,
    val mode: Int,
    /** 设备热点 SSID；为空表示二维码里没带热点信息（需手动连接） */
    val apSsid: String = "",
    /** 设备热点密码 */
    val apPassword: String = ""
) {

    /** 二维码里带了设备热点，可直接自动接入 */
    val hasHotspot: Boolean get() = apSsid.isNotEmpty()

    /** 形如 http://192.168.1.20:8080 */
    val baseUrl: String get() = "http://$host:$port"

    /** 形如 http://192.168.1.20:8080/api/v1 */
    val apiBaseUrl: String get() = "$baseUrl/api/v1"

    /** 拼接接口地址，例如 urlOf("/upload") */
    fun urlOf(path: String): String = apiBaseUrl + path

    /** 设备端交互模式：1 = 个人模式，2 = 门店选片模式 */
    val modeName: String
        get() = if (mode == MODE_PERSONAL) "个人模式" else "门店选片模式"

    companion object {
        const val MODE_PERSONAL = 1
        const val MODE_STUDIO = 2
        const val DEFAULT_PORT = 8080
    }
}
