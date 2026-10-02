package com.ShowSeen.lightshadowart.mobile.net

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
class HotspotConnector private constructor(context: Context) {

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
                // 不能按「network 是否等于当前绑定网络」短路：系统可能先回调 onLost 再没有
                // 任何后续回调，短路会让 boundNetwork 残留，界面据此误判「还挂在热点上」，
                // 「刷新连接」按钮就一直置灰。这里一律解绑并清状态。
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

    /**
     * 已绑定热点网络里的设备地址，即该网络的 IPv4 网关——设备自己就是热点网关。
     *
     * 用于只记得热点名、不记得设备地址的旧记录回连兜底（不同机型热点网段不同，不能写死 IP）。
     * 未绑定或取不到时返回 null。
     */
    fun boundGateway(): String? {
        val manager = connectivityManager ?: return null
        val network = boundNetwork ?: return null
        val properties = runCatching { manager.getLinkProperties(network) }.getOrNull() ?: return null

        properties.routes.firstOrNull { it.isDefaultRoute }?.gateway?.let { gateway ->
            if (gateway is Inet4Address) return gateway.hostAddress
        }

        // 少数机型默认路由不带网关：热点网段里设备固定是本机地址的最后一段改成 1
        val own = properties.linkAddresses
            .firstOrNull { it.address is Inet4Address }
            ?.address
            ?.hostAddress
            ?: return null
        val lastDot = own.lastIndexOf('.')
        return if (lastDot > 0) own.substring(0, lastDot + 1) + HOST_SUFFIX else null
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

    companion object {
        private const val TAG = "HotspotConnector"

        /** 系统连接超时，含用户在确认框上停留的时间 */
        private const val REQUEST_TIMEOUT_MS = 60_000

        /** 兜底把手机在热点网段里的地址换成设备地址（最后一段固定为 1） */
        private const val HOST_SUFFIX = "1"

        @Volatile
        private var instance: HotspotConnector? = null

        /**
         * 进程内单例。
         *
         * 热点接入的网络请求与进程绑定是「进程」维度的状态，若各 Activity 各持一份连接器，
         * 配网页 release() 只能解掉自己那份，解不掉主页刚发起的那次请求——手机仍被系统
         * 钉在设备热点上，进不到局域网，主页信息框就刷不出局域网地址与网络名。
         * 统一共用同一份 callback / boundNetwork / isConnected。
         */
        fun get(context: Context): HotspotConnector =
            instance ?: synchronized(this) {
                instance ?: HotspotConnector(context.applicationContext).also { instance = it }
            }
    }
}
