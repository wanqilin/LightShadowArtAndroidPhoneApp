package com.ShowSeen.lightshadowart.mobile.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Device discovered on the LAN (result of NSD service resolution) */
data class DiscoveredDevice(
    val serviceName: String,
    val host: String,
    val port: Int,
    val deviceToken: String,
    val mode: Int
) {

    /** Convert to a device address for API calls: the device is on the LAN, so no hotspot info */
    fun toEndpoint(): DeviceEndpoint = DeviceEndpoint(
        host = host,
        port = port,
        deviceToken = deviceToken,
        mode = mode
    )
}

/**
 * Discovers devices on the LAN: mirrors the device-side `DeviceNsdAdvertiser`, listening for the
 * `_lightshadowart._tcp.` service.
 *
 * A device joining the Wi-Fi handed out by the phone (STA mode) registers that service; phone and
 * device then share one LAN, so these results give the device's new address and let the session be
 * rebuilt without making the device show a QR code again. While the device is still in hotspot mode
 * (not yet provisioned) the service does not exist, so scanning the code is the only way in.
 */
class NsdDiscoverer(context: Context) {

    private val nsdManager: NsdManager? =
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * mDNS uses multicast, which Android's Wi-Fi power saving filters out, so a MulticastLock must
     * be held while discovering or device replies are often missed.
     */
    private val multicastLock: WifiManager.MulticastLock? =
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createMulticastLock(MULTICAST_LOCK_TAG)
            ?.apply { setReferenceCounted(false) }

    /** Resolved devices, keyed by service name */
    private val found = LinkedHashMap<String, DiscoveredDevice>()

    /**
     * Number of `onServiceFound` callbacks in this round (including ones that never resolved to an
     * address).
     *
     * Differs from [found], which only records entries once a valid address is resolved: diagnosing
     * "no device found" means telling "no reply at all" apart from "replied but resolution failed".
     * The latter means multicast works and only resolution is missing; the former means the phone
     * may never have sent anything out (e.g. not connected to the target network). Failure logs
     * rely on this count.
     */
    var foundCount: Int = 0
        private set

    /** Pending resolve queue: resolveService does not allow concurrency, so go one by one */
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()

    private var resolving = false
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var onFinished: ((List<DiscoveredDevice>) -> Unit)? = null

    /**
     * Starts discovery and reports the result on the main thread after [timeoutMs] (may be empty).
     *
     * Only one discovery is kept at a time: a repeated call first ends the previous one, whose
     * callback will not fire.
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
        foundCount = 0
        onFinished = onResult
        acquireMulticastLock()

        val listener = object : NsdManager.DiscoveryListener {

            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "discovery started: $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                foundCount++
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

    /** Ends discovery without callback, e.g. when leaving the screen */
    fun cancel() {
        mainHandler.removeCallbacks(timeoutRunnable)
        onFinished = null
        found.clear()
        resolveQueue.clear()
        resolving = false
        // Void this round's count: after cancel, [foundCount] is stale and would mislead failure logs
        foundCount = 0
        stopDiscovery()
        releaseMulticastLock()
    }

    // -----------------------------------------------------------------------
    // Internal implementation
    // -----------------------------------------------------------------------

    private val timeoutRunnable = Runnable { finish() }

    /** Ends discovery and reports the devices found; does nothing if already finished */
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

        /** Keep in sync with device-side `ServerConfig.NSD_SERVICE_TYPE` */
        const val SERVICE_TYPE = "_lightshadowart._tcp."

        /** Discovery window: LAN mDNS usually answers within 1s; keep 5s as a fallback */
        const val DISCOVER_TIMEOUT_MS = 5_000L

        const val ATTR_TOKEN = "token"
        const val ATTR_MODE = "mode"

        const val MULTICAST_LOCK_TAG = "light_shadow_nsd"

        /** Placeholder value when resolution yields no address */
        const val UNSPECIFIED_HOST = "0.0.0.0"
    }
}
