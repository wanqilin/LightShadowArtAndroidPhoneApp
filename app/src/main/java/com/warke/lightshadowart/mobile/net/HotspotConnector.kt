package com.warke.lightshadowart.mobile.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.Inet4Address

/**
 * 接入设备自建热点。
 *
 * 设备本身不接入任何局域网，手机只有连上它的热点才能访问设备上的 HTTP 服务；
 * 二维码里带了热点的 SSID / 密码，扫码后由本类请求系统连接该热点。
 *
 * 连接走 [WifiNetworkSpecifier]：普通应用无法静默连网，系统会弹一次确认框。
 * 连上后把本进程的网络绑定到热点——热点没有外网，不绑定的话请求仍会走移动数据，
 * 从而访问不到设备。
 */
class HotspotConnector(context: Context) {

    private val appContext = context.applicationContext
    private val connectivityManager = appContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var callback: ConnectivityManager.NetworkCallback? = null
    private var boundNetwork: Network? = null

    /** 是否已绑定到设备热点 */
    val isConnected: Boolean get() = boundNetwork != null

    /** 最近一次接入尝试的热点名；未尝试（或已 [release]）为 null */
    var lastAttemptSsid: String? = null
        private set

    /** 最近一次接入尝试的结论 */
    var lastOutcome: Outcome = Outcome.NOT_ATTEMPTED
        private set

    /** 热点网络分到的 IPv4 地址；未拿到为 null */
    var hotspotIpv4: String? = null
        private set

    /** 接入结论：失败提示要据此告诉用户卡在哪一步 */
    enum class Outcome { NOT_ATTEMPTED, REQUESTING, CONNECTED, LOST, UNAVAILABLE, ERROR }

    /**
     * 请系统连接设备热点，并把本进程的网络绑定到该热点；结果回调在主线程。
     *
     * Android 10 以下没有 [WifiNetworkSpecifier]，直接返回失败，由调用方提示手动连接。
     */
    fun connect(ssid: String, password: String, onResult: (Result<Unit>) -> Unit) {
        val manager = connectivityManager
        if (manager == null) {
            notify(onResult, Result.failure(IllegalStateException("系统不支持 Wi-Fi 连接")))
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            notify(onResult, Result.failure(IllegalStateException("系统版本过低，请手动连接设备热点")))
            return
        }

        release()
        lastAttemptSsid = ssid
        lastOutcome = Outcome.REQUESTING

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .apply { if (password.isNotEmpty()) setWpa2Passphrase(password) }
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            // 设备热点没有外网，必须去掉 INTERNET 能力，否则系统不认为该网络可用
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                manager.bindProcessToNetwork(network)
                boundNetwork = network
                lastOutcome = Outcome.CONNECTED
                Log.i(TAG, "hotspot connected: $ssid, link=$network")
                notify(onResult, Result.success(Unit))
            }

            /** 排查连不上时用：能看出热点是否分到了 IPv4 地址 */
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                hotspotIpv4 = linkProperties.linkAddresses
                    .firstOrNull { it.address is Inet4Address }
                    ?.address
                    ?.hostAddress
                Log.i(TAG, "hotspot link: $linkProperties")
            }

            override fun onUnavailable() {
                lastOutcome = Outcome.UNAVAILABLE
                Log.w(TAG, "hotspot unavailable: $ssid")
                notify(onResult, Result.failure(IllegalStateException("未连接到设备热点")))
            }

            override fun onLost(network: Network) {
                if (boundNetwork != network) return
                // 热点消失（例如设备收到 Wi-Fi 配置后关热点切 STA）时必须解绑，
                // 否则进程网络仍指向已不存在的热点，之后连上目标 Wi-Fi 也发不出请求
                runCatching { manager.bindProcessToNetwork(null) }
                boundNetwork = null
                lastOutcome = Outcome.LOST
                Log.i(TAG, "hotspot lost: $ssid")
            }
        }
        callback = networkCallback

        Log.i(TAG, "requestNetwork: ssid=$ssid, timeout=${REQUEST_TIMEOUT_MS}ms")
        try {
            manager.requestNetwork(request, networkCallback, REQUEST_TIMEOUT_MS)
        } catch (t: Throwable) {
            callback = null
            lastOutcome = Outcome.ERROR
            Log.e(TAG, "requestNetwork failed: $ssid", t)
            notify(onResult, Result.failure(t))
        }
    }

    /** 断开热点并把进程网络恢复为系统默认（刷新连接前调用） */
    fun release() {
        val manager = connectivityManager
        callback?.let { runCatching { manager?.unregisterNetworkCallback(it) } }
        callback = null

        if (boundNetwork != null) {
            runCatching { manager?.bindProcessToNetwork(null) }
            boundNetwork = null
        }

        // 本次接入已作废，诊断信息不能留旧值误导后续失败提示
        lastAttemptSsid = null
        lastOutcome = Outcome.NOT_ATTEMPTED
        hotspotIpv4 = null
    }

    private fun notify(onResult: (Result<Unit>) -> Unit, result: Result<Unit>) {
        mainHandler.post { onResult(result) }
    }

    private companion object {
        const val TAG = "HotspotConnector"

        /** 系统连接超时，含用户在确认框上停留的时间 */
        const val REQUEST_TIMEOUT_MS = 60_000
    }
}
