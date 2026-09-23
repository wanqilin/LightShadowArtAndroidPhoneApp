package com.warke.lightshadowart.mobile.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/** 局域网内发现到的设备（NSD 服务解析结果） */
data class DiscoveredDevice(
    val serviceName: String,
    val host: String,
    val port: Int,
    val deviceToken: String,
    val mode: Int
) {

    /** 转成接口调用用的设备地址：设备已在局域网，不需要热点信息 */
    fun toEndpoint(): DeviceEndpoint = DeviceEndpoint(
        host = host,
        port = port,
        deviceToken = deviceToken,
        mode = mode
    )
}

/**
 * 局域网内发现设备：与设备端 `DeviceNsdAdvertiser` 对应，监听 `_lightshadowart._tcp.` 服务。
 *
 * 设备接入手机下发的 Wi-Fi（STA 模式）后会注册该服务，此时手机与设备同处一个局域网，
 * 用这里的结果就能拿到设备的新地址并重建会话，不必再让设备弹二维码。
 * 设备仍处于热点模式（未配网）时不会有该服务，只能靠扫码连接。
 */
class NsdDiscoverer(context: Context) {

    private val nsdManager: NsdManager? =
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * mDNS 靠组播收发，Android 的 Wi-Fi 省电策略会过滤组播包，
     * 发现期间必须持有 MulticastLock，否则经常收不到设备应答。
     */
    private val multicastLock: WifiManager.MulticastLock? =
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createMulticastLock(MULTICAST_LOCK_TAG)
            ?.apply { setReferenceCounted(false) }

    /** 已解析完成的设备，key 为服务名 */
    private val found = LinkedHashMap<String, DiscoveredDevice>()

    /** 待解析队列：resolveService 不允许并发，逐个来 */
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()

    private var resolving = false
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var onFinished: ((List<DiscoveredDevice>) -> Unit)? = null

    /**
     * 开始发现，[timeoutMs] 后在主线程回调结果（可能为空列表）。
     *
     * 同一时间只保留一次发现：重复调用会先结束上一次（其回调不会触发）。
     */
    fun discover(
        timeoutMs: Long = DISCOVER_TIMEOUT_MS,
        onResult: (List<DiscoveredDevice>) -> Unit
    ) {
        val manager = nsdManager
        if (manager == null) {
            mainHandler.post { onResult(emptyList()) }
            return
        }

        cancel()

        found.clear()
        resolveQueue.clear()
        resolving = false
        onFinished = onResult
        acquireMulticastLock()

        val listener = object : NsdManager.DiscoveryListener {

            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "discovery started: $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "service found: ${serviceInfo.serviceName}")
                resolveQueue.addLast(serviceInfo)
                resolveNext()
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                found.remove(serviceInfo.serviceName)
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "discovery stopped: $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "start discovery failed: $errorCode")
                finish()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "stop discovery failed: $errorCode")
            }
        }

        discoveryListener = listener
        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (t: Throwable) {
            Log.e(TAG, "discoverServices failed", t)
            finish()
            return
        }
        mainHandler.postDelayed(timeoutRunnable, timeoutMs)
    }

    /** 结束发现（不回调），例如页面退出时 */
    fun cancel() {
        mainHandler.removeCallbacks(timeoutRunnable)
        onFinished = null
        found.clear()
        resolveQueue.clear()
        resolving = false
        stopDiscovery()
        releaseMulticastLock()
    }

    // -----------------------------------------------------------------------
    // 内部实现
    // -----------------------------------------------------------------------

    private val timeoutRunnable = Runnable { finish() }

    /** 结束发现并回调已找到的设备；已结束则什么都不做 */
    private fun finish() {
        mainHandler.removeCallbacks(timeoutRunnable)
        val callback = onFinished ?: return
        onFinished = null
        val devices = found.values.toList()
        found.clear()
        resolveQueue.clear()
        resolving = false
        stopDiscovery()
        releaseMulticastLock()
        callback(devices)
    }

    private fun acquireMulticastLock() {
        try {
            multicastLock?.takeIf { !it.isHeld }?.acquire()
        } catch (t: Throwable) {
            Log.w(TAG, "acquire multicast lock failed", t)
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.takeIf { it.isHeld }?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "release multicast lock failed", t)
        }
    }

    private fun stopDiscovery() {
        val listener = discoveryListener ?: return
        discoveryListener = null
        try {
            nsdManager?.stopServiceDiscovery(listener)
        } catch (t: Throwable) {
            Log.e(TAG, "stopServiceDiscovery failed", t)
        }
    }

    private fun resolveNext() {
        if (resolving) return
        val manager = nsdManager ?: return
        val serviceInfo = resolveQueue.removeFirstOrNull() ?: return
        resolving = true
        try {
            @Suppress("DEPRECATION")
            manager.resolveService(serviceInfo, resolveListener)
        } catch (t: Throwable) {
            resolving = false
            Log.e(TAG, "resolveService failed", t)
        }
    }

    private val resolveListener = object : NsdManager.ResolveListener {

        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.w(TAG, "resolve failed: ${serviceInfo.serviceName} code=$errorCode")
            resolving = false
            resolveNext()
        }

        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            resolving = false
            val host = serviceInfo.host?.hostAddress.orEmpty()
            if (host.isNotEmpty() && host != UNSPECIFIED_HOST) {
                found[serviceInfo.serviceName] = DiscoveredDevice(
                    serviceName = serviceInfo.serviceName,
                    host = host,
                    port = serviceInfo.port,
                    deviceToken = attributeOf(serviceInfo, ATTR_TOKEN),
                    mode = attributeOf(serviceInfo, ATTR_MODE).toIntOrNull()
                        ?: DeviceEndpoint.MODE_STUDIO
                )
            }
            resolveNext()
        }
    }

    private fun attributeOf(serviceInfo: NsdServiceInfo, name: String): String =
        serviceInfo.attributes?.get(name)?.toString(Charsets.UTF_8).orEmpty()

    private companion object {
        const val TAG = "NsdDiscoverer"

        /** 与设备端 `ServerConfig.NSD_SERVICE_TYPE` 保持一致 */
        const val SERVICE_TYPE = "_lightshadowart._tcp."

        /** 发现窗口：局域网内 mDNS 通常 1 秒内出结果，留 5 秒兜底 */
        const val DISCOVER_TIMEOUT_MS = 5_000L

        const val ATTR_TOKEN = "token"
        const val ATTR_MODE = "mode"

        const val MULTICAST_LOCK_TAG = "light_shadow_nsd"

        /** 解析未拿到地址时的占位值 */
        const val UNSPECIFIED_HOST = "0.0.0.0"
    }
}
