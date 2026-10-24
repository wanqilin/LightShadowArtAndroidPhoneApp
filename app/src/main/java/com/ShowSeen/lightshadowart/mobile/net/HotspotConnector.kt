package com.ShowSeen.lightshadowart.mobile.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.Inet4Address

/**
 * Join the hotspot hosted by the device itself.
 *
 * The device joins no LAN of its own, so the phone can reach its HTTP service only
 * while connected to that hotspot; the QR code carries the hotspot SSID / password,
 * and after scanning this class asks the system to join it.
 *
 * Joining goes through [WifiNetworkSpecifier]: ordinary apps cannot connect
 * silently, so the system shows a one-time confirmation dialog. Once connected, the
 * process network is bound to the hotspot — the hotspot has no internet access, and
 * without binding the requests still go over mobile data and never reach the device.
 */
class HotspotConnector private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val connectivityManager = appContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val pending = mutableListOf<(Result<Unit>) -> Unit>()

    private var callback: ConnectivityManager.NetworkCallback? = null
    private var boundNetwork: Network? = null

    /** Whether the process is currently bound to the device hotspot */
    val isConnected: Boolean get() = boundNetwork != null

    /**
     * Read-only description of the network the process is bound to, like
     * `net=102 iface=wlan0 ipv4=192.168.8.126`; null when not bound.
     *
     * Diagnostics only (failure logs must show which network the process is pinned
     * to). `ConnectivityManager.boundNetworkForProcess` is not used here: since API
     * 23 it requires a signature permission beyond `ACCESS_NETWORK_STATE`, so ordinary
     * apps always read null, whereas [boundNetwork] here is exactly the network this
     * class binds and unbinds, and is therefore the reliable source.
     */
    fun describeBinding(): String? {
        val network = boundNetwork ?: return null
        val manager = connectivityManager
        val properties = runCatching { manager?.getLinkProperties(network) }.getOrNull()
        val ipv4 = properties?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address }
            ?.address
            ?.hostAddress
        return listOfNotNull(
            "net=$network",
            properties?.interfaceName?.let { "iface=$it" },
            ipv4?.let { "ipv4=$it" }
        ).joinToString(" ")
    }

    /** SSID of the most recent attempt; null if never attempted (or already [release]d) */
    var lastAttemptSsid: String? = null
        private set

    /** Outcome of the most recent attempt */
    var lastOutcome: Outcome = Outcome.NOT_ATTEMPTED
        private set

    /**
     * The network this process is bound to, or null when not bound.
     *
     * `NsdManager` searches the system's default network unless it is told otherwise, and a
     * `WifiNetworkSpecifier` network is local-only, so it never becomes the default. Discovery must be
     * pointed at this network explicitly, otherwise it searches the wrong link and reports nothing.
     */
    val boundNetworkOrNull: Network? get() = boundNetwork

    /** IPv4 address assigned on the hotspot network; null if not obtained */
    var hotspotIpv4: String? = null
        private set

    /** Connection outcome; failure messages use it to tell the user which step failed */
    enum class Outcome { NOT_ATTEMPTED, REQUESTING, CONNECTED, LOST, UNAVAILABLE, ERROR }

    /**
     * Ask the system to join the device hotspot and bind this process to it; the
     * result is delivered on the main thread.
     *
     * Below Android 10 there is no [WifiNetworkSpecifier], so this fails right away
     * and the caller prompts the user to connect manually.
     */
    /**
     * Join the hotspot, preferring a Wi-Fi suggestion.
     *
     * The system confirmation dialog is unavoidable while the phone has another network with
     * internet (a hotspot without internet is never chosen automatically). The page therefore tells
     * the user to confirm it instead of waiting silently.
     */
    fun join(ssid: String, password: String, onResult: (Result<Unit>) -> Unit) {
        if (isOnWifi(ssid)) {
            notify(onResult, Result.success(Unit))
            return
        }
        // Right after a pairing reset the hotspot has just restarted: a specifier request made before
        // its SSID is in the scan cache fails at once ("out of range"), which is why the first scan
        // after a strong pull failed while the second worked. Wait until it is visible, then ask.
        Thread {
            val visible = awaitSsidVisible(ssid, SSID_VISIBLE_TIMEOUT_MS)
            Log.i(TAG, "join $ssid: visible=$visible")
            mainHandler.post { connect(ssid, password, onResult) }
        }.start()
    }

    /** Poll the scan results until [ssid] shows up; true when seen (or when it cannot be checked) */
    private fun awaitSsidVisible(ssid: String, timeoutMs: Long): Boolean {
        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return true
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val results = runCatching { wifiManager.scanResults }.getOrNull()
            if (results?.any { it.SSID?.trim('"') == ssid } == true) return true
            runCatching { wifiManager.startScan() }
            runCatching { Thread.sleep(600) }
        }
        return false
    }

    /** Withdraw the suggestion again so the phone does not auto-join the frame later */
    fun removeSuggestion(ssid: String, password: String) {
        if (ssid.isEmpty()) return
        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        runCatching {
            val builder = WifiNetworkSuggestion.Builder().setSsid(ssid)
            if (password.isNotEmpty()) builder.setWpa2Passphrase(password)
            val status = wifiManager.removeNetworkSuggestions(listOf(builder.build()))
            Log.i(TAG, "removeNetworkSuggestions($ssid) status=$status")
        }.onFailure { Log.w(TAG, "removeNetworkSuggestions failed: ${it.message}") }
    }

    private fun addSuggestion(ssid: String, password: String): Boolean {
        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return false
        return runCatching {
            val builder = WifiNetworkSuggestion.Builder().setSsid(ssid)
            if (password.isNotEmpty()) builder.setWpa2Passphrase(password)
            val status = wifiManager.addNetworkSuggestions(listOf(builder.build()))
            Log.i(TAG, "addNetworkSuggestions($ssid) status=$status")
            status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS
        }.getOrDefault(false)
    }

    private fun isOnWifi(ssid: String): Boolean {
        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return false
        val info = runCatching { wifiManager.connectionInfo }.getOrNull() ?: return false
        val current = info.ssid?.trim('"').orEmpty()
        return current == ssid && info.supplicantState == SupplicantState.COMPLETED
    }

    fun connect(ssid: String, password: String, onResult: (Result<Unit>) -> Unit, retried: Boolean = false) {
        val manager = connectivityManager
        if (manager == null) {
            notify(onResult, Result.failure(IllegalStateException("系统不支持 Wi-Fi 连接")))
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            notify(onResult, Result.failure(IllegalStateException("系统版本过低，请手动连接设备热点")))
            return
        }

        // One request per hotspot: a second join for the same SSID would release() the pending
        // system request and the phone would report "the app cancelled the request"
        synchronized(pending) {
            if (lastOutcome == Outcome.REQUESTING && lastAttemptSsid == ssid) {
                pending += onResult
                Log.i(TAG, "join already in flight for $ssid, sharing its result")
                return
            }
            pending.clear()
            pending += onResult
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
            // The device hotspot has no internet, so the INTERNET capability must be
            // removed; otherwise the system never deems this network usable
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                manager.bindProcessToNetwork(network)
                boundNetwork = network
                lastOutcome = Outcome.CONNECTED
                Log.i(TAG, "hotspot connected: $ssid, link=$network")
                finish(Result.success(Unit))
            }

            /** Diagnostics for connection failures: shows whether the hotspot got an IPv4 address */
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                hotspotIpv4 = linkProperties.linkAddresses
                    .firstOrNull { it.address is Inet4Address }
                    ?.address
                    ?.hostAddress
                Log.i(TAG, "hotspot link: $linkProperties")
            }

            override fun onUnavailable() {
                // The request completed (not cancelled), so one retry is safe and covers a hotspot
                // that was still settling when the first attempt ran
                if (!retried) {
                    Log.i(TAG, "hotspot unavailable, retrying once: $ssid")
                    mainHandler.postDelayed(
                        { connect(ssid, password, onResult, retried = true) },
                        HOTSPOT_RETRY_DELAY_MS
                    )
                    return
                }
                lastOutcome = Outcome.UNAVAILABLE
                Log.w(TAG, "hotspot unavailable: $ssid")
                finish(Result.failure(IllegalStateException("未连接到设备热点")))
            }

            override fun onLost(network: Network) {
                // Do not short-circuit on "network == bound network": the system may
                // deliver onLost with no further callback, leaving boundNetwork stale;
                // the UI then thinks it is still on the hotspot and the "refresh
                // connection" button stays disabled. Always unbind and clear the state.
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
     * Device address inside the bound hotspot network, i.e. that network's IPv4
     * gateway — the device itself is the hotspot gateway.
     *
     * Fallback for reconnecting saved records that keep only the hotspot name and no
     * device address (hotspot subnets vary by model, so the IP cannot be hardcoded).
     * Returns null when not bound or when it cannot be read.
     */
    fun boundGateway(): String? {
        val manager = connectivityManager ?: return null
        val network = boundNetwork ?: return null
        val properties = runCatching { manager.getLinkProperties(network) }.getOrNull() ?: return null

        properties.routes.firstOrNull { it.isDefaultRoute }?.gateway?.let { gateway ->
            if (gateway is Inet4Address) return gateway.hostAddress
        }

        // On a few models the default route has no gateway: the device on the hotspot
        // subnet is always our own address with the last octet changed to 1
        val own = properties.linkAddresses
            .firstOrNull { it.address is Inet4Address }
            ?.address
            ?.hostAddress
            ?: return null
        val lastDot = own.lastIndexOf('.')
        return if (lastDot > 0) own.substring(0, lastDot + 1) + HOST_SUFFIX else null
    }

    /**
     * Release the hotspot binding unless a new join is in flight.
     *
     * Releasing a pending [WifiNetworkSpecifier] request makes the system report "the app cancelled
     * the request"; delayed callers (sign-out finishing after the user scanned again) must not do
     * that to a request they did not start.
     */
    fun releaseIfIdle() {
        if (lastOutcome == Outcome.REQUESTING) {
            Log.i(TAG, "release skipped: a hotspot request is in flight")
            return
        }
        release()
    }

    /** Leave the hotspot, restore the default process network (call before refreshing) */
    fun release() {
        val manager = connectivityManager
        callback?.let { runCatching { manager?.unregisterNetworkCallback(it) } }
        callback = null

        if (boundNetwork != null) {
            runCatching { manager?.bindProcessToNetwork(null) }
            boundNetwork = null
        }

        // This attempt is void; stale diagnostics must not mislead later failure messages
        lastAttemptSsid = null
        lastOutcome = Outcome.NOT_ATTEMPTED
        hotspotIpv4 = null
    }

    private fun notify(onResult: (Result<Unit>) -> Unit, result: Result<Unit>) {
        mainHandler.post { onResult(result) }
    }

    /** Answer every caller that is waiting for the single in-flight request */
    private fun finish(result: Result<Unit>) {
        val waiting = synchronized(pending) { pending.toList().also { pending.clear() } }
        waiting.forEach { notify(it, result) }
    }

    companion object {
        private const val TAG = "HotspotConnector"

        /** System connection timeout, including time the user spends on the confirmation dialog */
        private const val REQUEST_TIMEOUT_MS = 30_000

        /** Give the hotspot time to appear in the scan results after a pairing reset */
        private const val SSID_VISIBLE_TIMEOUT_MS = 8_000L

        /** Delay before retrying a completed-but-unavailable hotspot request */
        private const val HOTSPOT_RETRY_DELAY_MS = 2_000L

        /** Fallback: turn the phone's hotspot-subnet address into the device address (last octet is always 1) */
        private const val HOST_SUFFIX = "1"

        @Volatile
        private var instance: HotspotConnector? = null

        /**
         * Process-wide singleton.
         *
         * The hotspot network request and the process binding are per-process state; if
         * each Activity held its own connector, release() on the config screen would
         * only clear its own copy and not the request the home screen just started —
         * the phone would stay pinned to the device hotspot, never reach the LAN, and
         * the home info box could not show the LAN address and network name. Everything
         * shares one callback / boundNetwork / isConnected.
         */
        fun get(context: Context): HotspotConnector =
            instance ?: synchronized(this) {
                instance ?: HotspotConnector(context.applicationContext).also { instance = it }
            }
    }
}
