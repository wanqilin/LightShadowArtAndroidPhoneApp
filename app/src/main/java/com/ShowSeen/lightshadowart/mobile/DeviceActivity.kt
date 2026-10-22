package com.ShowSeen.lightshadowart.mobile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ShowSeen.lightshadowart.mobile.net.AuthResult
import com.ShowSeen.lightshadowart.mobile.net.DeviceClient
import com.ShowSeen.lightshadowart.mobile.net.DeviceEndpoint
import com.ShowSeen.lightshadowart.mobile.net.DiscoveredDevice
import com.ShowSeen.lightshadowart.mobile.net.HotspotConnector
import com.ShowSeen.lightshadowart.mobile.net.NsdDiscoverer
import com.ShowSeen.lightshadowart.mobile.net.SessionExpiredException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.Inet4Address
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Shared device-session behaviour for the pages that drive the connection: the Dashboard and the
 * Network Connection page.
 *
 * Everything here is about *reaching and keeping* a device session (discovery, hotspot hand-off,
 * authenticate, heartbeat, reconnect, disconnect, diagnostics). Pages contribute only what they
 * render through [onSessionChanged] and a few hooks, so the connection rules exist once.
 */
abstract class DeviceActivity : AppCompatActivity() {

    protected lateinit var client: DeviceClient
    protected lateinit var hotspotConnector: HotspotConnector
    protected lateinit var nsdDiscoverer: NsdDiscoverer

    /** True while "reconnect" is scanning the LAN / handing over to the device hotspot */
    protected var connecting = false

    /** True while an expired hotspot address is being migrated to the LAN address */
    protected var migratingToLan = false

    /** Rate limit for the heartbeat-driven LAN migration, so it cannot spin */
    private var lastLanMigrateAtMs = 0L

    /** Device waiting for the hotspot permissions granted in the permission callback */
    private var pendingEndpoint: DeviceEndpoint? = null

    /** Silent reconnect task (UI PRD / T-5): retry auth for a short window after a failed request */
    private var autoReconnectJob: Job? = null

    private lateinit var wifiPermissionLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SessionStore.init(this)
        client = DeviceClient(contentResolver)
        hotspotConnector = HotspotConnector.get(this)
        nsdDiscoverer = NsdDiscoverer(this)

        wifiPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { grants ->
            val endpoint = pendingEndpoint
            pendingEndpoint = null
            if (endpoint == null) return@registerForActivityResult
            if (grants.values.all { it }) {
                connectHotspot(endpoint)
            } else {
                toast(getString(R.string.toast_hotspot_permission_denied))
            }
        }
    }

    override fun onDestroy() {
        nsdDiscoverer.cancel()
        super.onDestroy()
    }

    // -----------------------------------------------------------------------
    // Hooks for the concrete page
    // -----------------------------------------------------------------------

    /** The session state (or the parameters) changed: re-render this page. */
    protected abstract fun onSessionChanged()

    /** The session is gone for good (401 / expired / user disconnect): drop page-scoped caches. */
    protected open fun onSessionCleared() = Unit

    /** A session was (re)established: refresh page content that depends on it. */
    protected open fun onSessionEstablished() = Unit

    /** Store mode: the photographer confirmed the folder, the page may navigate to the gallery. */
    protected open fun onStudioSelectionConfirmed() = Unit

    /** Whether the heartbeat may move an expired hotspot address to the LAN address. */
    protected open fun canMigrateToLan(): Boolean = true

    // -----------------------------------------------------------------------
    // Session lifetime
    // -----------------------------------------------------------------------

    /**
     * Heartbeat loop: refreshes the remaining session time and the latest parameters.
     *
     * Runs on [lifecycleScope] on purpose, not on the RESUMED state: a page opened on top (the
     * Wi-Fi config page can take close to a minute) would otherwise stop the heartbeat, and with a
     * 60s device-side disconnect timeout the device would drop the session while the user is still
     * configuring it.
     */
    protected fun startHeartbeat() {
        lifecycleScope.launch {
            while (true) {
                val endpoint = SessionStore.endpoint
                val sessionId = SessionStore.sessionId
                if (endpoint != null && sessionId != null) {
                    client.heartbeat(endpoint, sessionId)
                        .onSuccess {
                            SessionStore.touch(it.expiresInMs)
                            SessionStore.updateParams(it.params)
                        }
                        .onFailure {
                            if (it is SessionExpiredException) {
                                handleSessionExpired()
                            } else {
                                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                    migrateToLan()
                                }
                                if (!migratingToLan) scheduleAutoReconnect()
                            }
                        }
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) onSessionChanged()
                }
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    /** The device rejected our session: clear everything and let the page render the offline state. */
    protected fun handleSessionExpired() {
        SessionStore.clear()
        autoReconnectJob?.cancel()
        onSessionCleared()
        onSessionChanged()
        toast(getString(R.string.toast_session_expired))
    }

    /**
     * Silent reconnect: the session exists but the device is momentarily unreachable (reboot, Wi-Fi
     * flap, just switched networks). Retry auth for [AUTO_RECONNECT_WINDOW_MS] so the user does not
     * have to press reconnect.
     *
     * No studio token is sent: the folder for this session already exists on the device, and
     * re-sending the token would create a new empty folder on every retry.
     */
    private fun scheduleAutoReconnect() {
        if (autoReconnectJob?.isActive == true) return
        val endpoint = SessionStore.endpoint ?: return
        autoReconnectJob = lifecycleScope.launch {
            val deadline = System.currentTimeMillis() + AUTO_RECONNECT_WINDOW_MS
            Log.i(TAG, "autoReconnect: window open, target ${endpoint.baseUrl}")
            while (System.currentTimeMillis() < deadline) {
                delay(AUTO_RECONNECT_RETRY_MS)
                val result = client.auth(endpoint).getOrNull() ?: continue
                applyAuthResult(endpoint, result, announceStudioFolder = false)
                Log.i(TAG, "autoReconnect: session restored ${result.sessionId}")
                toast(getString(R.string.toast_auto_reconnected))
                return@launch
            }
            Log.i(TAG, "autoReconnect: window closed without a response, back to the user")
        }
    }

    /** Cancel any running silent reconnect (user pressed disconnect / reconnect explicitly). */
    protected fun cancelAutoReconnect() {
        autoReconnectJob?.cancel()
    }

    // -----------------------------------------------------------------------
    // Connecting
    // -----------------------------------------------------------------------

    /**
     * Connect entry point for a scanned endpoint: join the device hotspot first when the QR code
     * carries its credentials (Android 13+ needs NEARBY_WIFI_DEVICES, older versions location).
     */
    protected fun connect(endpoint: DeviceEndpoint) {
        val missing = requiredWifiPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            pendingEndpoint = endpoint
            wifiPermissionLauncher.launch(missing.toTypedArray())
            return
        }
        connectHotspot(endpoint)
    }

    private fun connectHotspot(endpoint: DeviceEndpoint) {
        if (!endpoint.hasHotspot) {
            authenticate(endpoint)
            return
        }
        // Already on the device hotspot: asking the system for a network we are already on pops a
        // system sheet and only answers after ~60s on this platform, so just use the current link.
        if (isOnWifi(endpoint.apSsid)) {
            Log.i(TAG, "already on hotspot ${endpoint.apSsid}, skip requestNetwork")
            lifecycleScope.launch {
                if (!client.probe(endpoint)) {
                    Log.w(TAG, "hotspot ${endpoint.apSsid} not reachable yet, authenticate anyway")
                }
                SessionStore.rememberWifiSsid(endpoint.apSsid)
                SessionStore.rememberApCredentials(endpoint)
                authenticate(endpoint)
            }
            return
        }
        requestHotspot(endpoint)
    }

    /** Ask the system to join the device hotspot and then exchange the session (normal path). */
    protected fun requestHotspot(endpoint: DeviceEndpoint) {
        connecting = true
        onSessionChanged()
        Log.i(TAG, "requestHotspot: ssid=${endpoint.apSsid}, device=${endpoint.baseUrl}")
        hotspotConnector.connect(endpoint.apSsid, endpoint.apPassword) { result ->
            result
                .onSuccess {
                    Log.i(TAG, "hotspot ok: ${endpoint.apSsid}")
                    SessionStore.rememberWifiSsid(endpoint.apSsid)
                    SessionStore.rememberApCredentials(endpoint)
                    authenticate(endpoint)
                }
                .onFailure { error ->
                    Log.e(TAG, "hotspot failed: ${endpoint.apSsid}", error)
                    connecting = false
                    onSessionChanged()
                    showConnectError(
                        getString(
                            R.string.toast_hotspot_failed,
                            endpoint.apSsid,
                            error.message.orEmpty()
                        ),
                        endpoint
                    )
                }
        }
    }

    /** Exchange the device token for a session, then hand the result to [applyAuthResult]. */
    protected fun authenticate(endpoint: DeviceEndpoint) {
        connecting = true
        onSessionChanged()
        lifecycleScope.launch {
            // Store mode: this selection session's own token goes up with the auth call so the
            // device creates "<yyyyMMdd><seq><token>" and returns it as the session folder.
            val studioToken =
                if (endpoint.mode == DeviceEndpoint.MODE_STUDIO) newStudioToken() else ""
            val result = client.auth(endpoint, studioToken).getOrElse { error ->
                Log.e(TAG, "auth failed: ${endpoint.baseUrl}", error)
                connecting = false
                onSessionChanged()
                showConnectError(getString(R.string.toast_connect_failed, error.message.orEmpty()), endpoint)
                return@launch
            }
            applyAuthResult(endpoint, result, announceStudioFolder = true)
        }
    }

    /**
     * Common tail of a successful auth, shared by scan / reconnect / silent reconnect.
     *
     * @param announceStudioFolder whether to show the "photographer, put the photos in this folder"
     *        dialog. True for a fresh scan or an explicit reconnect; false for the silent retry,
     *        which would otherwise nag with a folder the user has already been told about.
     */
    protected suspend fun applyAuthResult(
        endpoint: DeviceEndpoint,
        result: AuthResult,
        announceStudioFolder: Boolean
    ) {
        Log.i(TAG, "auth ok: ${endpoint.baseUrl}, session=${result.sessionId}, role=${result.role}")
        // The device owns the interaction mode (compile-time switch), so its answer wins over the
        // mode recorded from an older QR code: reconnecting then heals without a new scan.
        val effective = if (result.mode == DeviceEndpoint.MODE_PERSONAL ||
            result.mode == DeviceEndpoint.MODE_STUDIO
        ) {
            endpoint.copy(mode = result.mode)
        } else {
            endpoint
        }
        SessionStore.save(
            effective,
            result.sessionId,
            DEFAULT_SESSION_TTL_MS,
            result.role,
            result.folder,
            result.params,
            result.deviceVersion
        )
        client.heartbeat(effective, result.sessionId).onSuccess { heartbeat ->
            SessionStore.touch(heartbeat.expiresInMs)
            SessionStore.updateParams(heartbeat.params)
        }
        connecting = false
        onSessionEstablished()
        onSessionChanged()
        if (announceStudioFolder && SessionStore.inStudioMode) promptStudioFolder(result.folder)
    }

    /**
     * Reconnect: look for the device on the LAN first (it joined the Wi-Fi the phone configured),
     * otherwise fall back to the device hotspot using the remembered credentials — no new scan.
     */
    protected fun refresh() {
        if (connecting) return
        cancelAutoReconnect()

        // Still attached to the device hotspot: reuse the remembered address instead of asking the
        // system to join a network we are already on (that pops a confirmation sheet again).
        val apEndpoint = SessionStore.apEndpoint()
        if (apEndpoint != null && hotspotConnector.isConnected) {
            authenticate(apEndpoint)
            return
        }

        connecting = true
        onSessionChanged()

        // The device drops its own hotspot when it joins the target Wi-Fi, so release the binding
        // first; otherwise requests stay bound to a network that no longer exists.
        hotspotConnector.release()

        lifecycleScope.launch {
            val devices = probeLan(DISCOVER_TIMEOUT_MS)
            val device = devices.firstOrNull { it.deviceToken == SessionStore.lastDeviceToken }
                ?: devices.firstOrNull()
            if (device != null) {
                currentSsid().takeIf { it.isNotEmpty() }?.let { SessionStore.rememberWifiSsid(it) }
                authenticate(device.toEndpoint())
                return@launch
            }
            reconnectHotspot()
        }
    }

    /**
     * Move an expired hotspot address to the LAN address.
     *
     * Only when the page is in the foreground, the phone is not on the device hotspot any more and
     * a cooldown has passed. If the device is not found nothing happens: it may still be an access
     * point, and reconnecting on its own would pop a system sheet at the user for no reason.
     */
    protected fun migrateToLan() {
        if (!canMigrateToLan() || connecting || migratingToLan) return

        val endpoint = SessionStore.endpoint ?: return
        if (!endpoint.hasHotspot) return
        if (hotspotConnector.isConnected) return

        val now = System.currentTimeMillis()
        if (now - lastLanMigrateAtMs < LAN_MIGRATE_COOLDOWN_MS) return
        lastLanMigrateAtMs = now

        migratingToLan = true
        Log.i(TAG, "migrateToLan: ${endpoint.baseUrl} may be stale, looking on the LAN")
        lifecycleScope.launch {
            hotspotConnector.release()
            val devices = probeLan(DISCOVER_TIMEOUT_MS)
            migratingToLan = false

            val device = devices.firstOrNull { it.deviceToken == SessionStore.lastDeviceToken }
                ?: devices.firstOrNull()
            if (device == null) {
                Log.i(TAG, "migrateToLan: not on the LAN, keeping ${endpoint.baseUrl}")
                return@launch
            }
            Log.i(TAG, "migrateToLan: found ${device.host}:${device.port}")
            currentSsid().takeIf { it.isNotEmpty() }?.let { SessionStore.rememberWifiSsid(it) }
            authenticate(device.toEndpoint())
        }
    }

    /**
     * Nothing on the LAN: reconnect to the device hotspot using the remembered credentials (and the
     * hotspot gateway for the address — Android re-randomises the hotspot subnet on every boot).
     */
    private fun reconnectHotspot() {
        val saved = SessionStore.apEndpoint()
        val ssid = saved?.apSsid
            ?: SessionStore.lastWifiSsid.takeIf { it.startsWith(AP_SSID_PREFIX) }.orEmpty()

        if (ssid.isEmpty()) {
            connecting = false
            onSessionChanged()
            Log.i(TAG, "reconnectHotspot: no hotspot to use (lastWifiSsid=${SessionStore.lastWifiSsid})")
            toast(
                if (SessionStore.lastWifiSsid.isEmpty()) {
                    getString(R.string.toast_refresh_not_found_without_wifi)
                } else {
                    getString(R.string.toast_refresh_not_found, SessionStore.lastWifiSsid)
                }
            )
            return
        }

        // The hotspot password is per device and only known from the QR code; a pairing reset
        // rotates it, so guessing the old fixed value would just fail silently.
        val password = saved?.apPassword.orEmpty()
        if (password.isEmpty()) {
            connecting = false
            onSessionChanged()
            Log.i(TAG, "reconnectHotspot: no saved credentials for $ssid, a new scan is required")
            toast(getString(R.string.toast_hotspot_needs_rescan, ssid))
            return
        }
        val knownHost = saved?.host.orEmpty()
        Log.i(TAG, "reconnectHotspot: ssid=$ssid, knownHost=$knownHost")
        hotspotConnector.connect(ssid, password) { result ->
            result
                .onSuccess {
                    val host = hotspotConnector.boundGateway() ?: knownHost.ifEmpty { null }
                    if (host == null) {
                        Log.e(TAG, "reconnectHotspot: joined $ssid but no device address")
                        connecting = false
                        onSessionChanged()
                        showError(getString(R.string.toast_hotspot_gateway_unknown, ssid))
                        return@onSuccess
                    }
                    val endpoint = DeviceEndpoint(
                        host = host,
                        port = DeviceEndpoint.DEFAULT_PORT,
                        deviceToken = SessionStore.lastDeviceToken,
                        mode = SessionStore.lastDeviceMode,
                        apSsid = ssid,
                        apPassword = password
                    )
                    Log.i(TAG, "reconnectHotspot ok: ssid=$ssid, device=${endpoint.baseUrl}")
                    SessionStore.rememberApCredentials(endpoint)
                    SessionStore.rememberWifiSsid(ssid)
                    authenticate(endpoint)
                }
                .onFailure { error ->
                    Log.e(TAG, "reconnectHotspot failed: $ssid", error)
                    connecting = false
                    onSessionChanged()
                    val endpoint = saved ?: DeviceEndpoint(
                        host = knownHost,
                        port = DeviceEndpoint.DEFAULT_PORT,
                        deviceToken = SessionStore.lastDeviceToken,
                        mode = SessionStore.lastDeviceMode,
                        apSsid = ssid,
                        apPassword = password
                    )
                    showConnectError(
                        getString(R.string.toast_hotspot_failed, ssid, error.message.orEmpty()),
                        endpoint
                    )
                }
        }
    }

    /**
     * Leave the session: end it on the device so its standby page goes back to the QR code, then
     * clear the local state.
     *
     * @param handover administrator "sign out": also unbind the device and make it show the QR code
     *        again so somebody else can take over the administrator role.
     */
    protected fun disconnect(handover: Boolean = false) {
        val endpoint = SessionStore.endpoint
        val sessionId = SessionStore.sessionId
        connecting = false

        lifecycleScope.launch {
            val error = if (endpoint != null && sessionId != null) {
                if (handover) {
                    client.handoverSession(endpoint, sessionId).exceptionOrNull()
                } else {
                    client.endSession(endpoint, sessionId).exceptionOrNull()
                }
            } else {
                null
            }

            hotspotConnector.release()
            SessionStore.clear()
            autoReconnectJob?.cancel()
            onSessionCleared()
            onSessionChanged()

            // A 401 means the device session was already gone; that is the same outcome, not an error
            toast(
                when {
                    endpoint == null || sessionId == null -> getString(R.string.toast_disconnected_local)
                    error == null || error is SessionExpiredException ->
                        getString(if (handover) R.string.toast_logout_done else R.string.toast_disconnected)
                    else -> getString(R.string.toast_disconnect_failed, error.message.orEmpty())
                }
            )
        }
    }

    // -----------------------------------------------------------------------
    // Store-mode folder hand-off
    // -----------------------------------------------------------------------

    /** This selection session's own token: a UUID prefix matching the device-side length limit. */
    private fun newStudioToken(): String =
        UUID.randomUUID().toString().replace("-", "").take(STUDIO_TOKEN_LENGTH)

    private fun promptStudioFolder(folder: String) {
        if (folder.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_studio_folder_title)
            .setMessage(getString(R.string.dialog_studio_folder_message, folder))
            .setCancelable(false)
            .setPositiveButton(R.string.dialog_studio_folder_confirm) { _, _ -> confirmStudioSelection() }
            .show()
    }

    private fun confirmStudioSelection() {
        val endpoint = SessionStore.endpoint ?: return
        val sessionId = SessionStore.sessionId ?: return
        lifecycleScope.launch {
            client.confirmStudioSelection(endpoint, sessionId)
                .onFailure { toast(getString(R.string.toast_studio_confirm_failed, it.message.orEmpty())) }
            onStudioSelectionConfirmed()
        }
    }

    /** Store mode: create the next customer folder (administrator only). */
    protected fun createStudioFolder() {
        when {
            !SessionStore.inStudioMode -> {
                toast(getString(R.string.toast_studio_folder_personal))
                return
            }

            !SessionStore.isAdmin -> {
                toast(getString(R.string.toast_admin_only))
                return
            }
        }
        if (!requireSession()) return
        val endpoint = SessionStore.endpoint ?: return
        val sessionId = SessionStore.sessionId ?: return

        lifecycleScope.launch {
            client.createStudioFolder(endpoint, sessionId)
                .onSuccess {
                    toast(getString(R.string.toast_studio_folder_created, it))
                    onSessionChanged()
                }
                .onFailure { error ->
                    if (error is SessionExpiredException) {
                        handleSessionExpired()
                    } else {
                        toast(getString(R.string.toast_studio_folder_failed, error.message.orEmpty()))
                    }
                }
        }
    }

    // -----------------------------------------------------------------------
    // Lookups and small helpers
    // -----------------------------------------------------------------------

    /** Ask the system for the hotspot permissions this platform version needs. */
    protected fun requiredWifiPermissions(): List<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        else -> emptyList()
    }

    /** Whether there is anything left to reconnect to: hotspot link, saved hotspot, or past device. */
    protected fun hasReconnectTarget(): Boolean =
        hotspotConnector.isConnected ||
            SessionStore.apEndpoint() != null ||
            SessionStore.lastDeviceToken.isNotEmpty()

    protected fun requireSession(): Boolean {
        if (SessionStore.isActive) return true
        toast(getString(R.string.toast_need_session))
        return false
    }

    /** Current Wi-Fi name, or an empty string when not on Wi-Fi / not readable. */
    protected fun currentSsid(): String {
        val manager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val raw = runCatching { manager?.connectionInfo?.ssid }.getOrNull() ?: return ""
        return raw.trim('"').takeIf { it.isNotEmpty() && it != UNKNOWN_SSID }.orEmpty()
    }

    /**
     * Whether the phone is already on [ssid]. Asking the system for a network we are already on
     * pops a sheet and answers only after ~60s, so every connection path checks this first.
     */
    protected fun isOnWifi(ssid: String): Boolean = ssid.isNotEmpty() && currentSsid() == ssid

    /** One LAN discovery pass, bounded by [timeoutMs]; never a background scan. */
    protected suspend fun probeLan(timeoutMs: Long): List<DiscoveredDevice> =
        suspendCancellableCoroutine { continuation ->
            nsdDiscoverer.discover(timeoutMs) { devices ->
                if (continuation.isActive) continuation.resume(devices)
            }
            continuation.invokeOnCancellation { nsdDiscoverer.cancel() }
        }

    protected fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /** Remaining session time; units go through string resources so English stays English. */
    protected fun formatRemaining(ms: Long): String {
        val totalMinutes = ms / 60_000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) {
            getString(R.string.duration_hours_minutes, hours, minutes)
        } else {
            getString(R.string.duration_minutes, minutes)
        }
    }

    /**
     * Connection errors are long (OkHttp adds target, source and timeouts), so show them in a
     * scrollable, selectable dialog instead of a toast that gets clipped.
     */
    protected fun showError(message: String) {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_error_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.setOnShowListener {
            dialog.findViewById<TextView>(android.R.id.message)?.apply {
                setTextIsSelectable(true)
                movementMethod = ScrollingMovementMethod()
            }
        }
        dialog.show()
    }

    /** Failure dialog with the network snapshot appended, so one screenshot is enough to debug. */
    protected fun showConnectError(message: String, endpoint: DeviceEndpoint) {
        showError("$message\n\n${diagnostics(endpoint)}")
    }

    /** Default network, process-bound network, VPN and hotspot outcome for [endpoint]. */
    protected fun diagnostics(endpoint: DeviceEndpoint): String {
        val manager = getSystemService(ConnectivityManager::class.java)
        return buildString {
            appendLine(getString(R.string.diag_title))
            appendLine(
                getString(
                    R.string.diag_default_network,
                    describeNetwork(manager, manager?.activeNetwork, getString(R.string.diag_no_network))
                )
            )
            appendLine(
                getString(
                    R.string.diag_bound_network,
                    describeNetwork(
                        manager,
                        manager?.boundNetworkForProcess,
                        getString(R.string.diag_not_bound)
                    )
                )
            )
            appendLine(
                getString(
                    R.string.diag_vpn,
                    getString(if (hasVpn(manager)) R.string.diag_vpn_on else R.string.diag_vpn_off)
                )
            )
            appendLine(getString(R.string.diag_hotspot, describeHotspot()))
            append(getString(R.string.diag_device, "${endpoint.host}:${endpoint.port}"))
        }
    }

    private fun describeNetwork(
        manager: ConnectivityManager?,
        network: Network?,
        unavailable: String
    ): String {
        val capabilities = network?.let { manager?.getNetworkCapabilities(it) }
        if (network == null || capabilities == null) return unavailable
        val ipv4 = manager?.getLinkProperties(network)?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address }
            ?.address
            ?.hostAddress
        return (transportNames(capabilities) + listOfNotNull(ipv4)).joinToString(" / ")
    }

    private fun transportNames(capabilities: NetworkCapabilities): List<String> = buildList {
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            add(getString(R.string.diag_transport_wifi))
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            add(getString(R.string.diag_transport_cellular))
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            add(getString(R.string.diag_transport_vpn))
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            add(getString(R.string.diag_transport_ethernet))
        }
    }.ifEmpty { listOf(getString(R.string.diag_transport_unknown)) }

    private fun hasVpn(manager: ConnectivityManager?): Boolean =
        manager?.getNetworkCapabilities(manager.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

    /** "LightShadowArt-0051B8 · connected, then dropped by the system · 192.168.230.193" */
    private fun describeHotspot(): String {
        val ssid = hotspotConnector.lastAttemptSsid ?: return getString(R.string.diag_hotspot_not_attempted)
        val outcome = getString(
            when (hotspotConnector.lastOutcome) {
                HotspotConnector.Outcome.NOT_ATTEMPTED -> R.string.diag_hotspot_not_attempted
                HotspotConnector.Outcome.REQUESTING -> R.string.diag_hotspot_requesting
                HotspotConnector.Outcome.CONNECTED -> R.string.diag_hotspot_connected
                HotspotConnector.Outcome.LOST -> R.string.diag_hotspot_lost
                HotspotConnector.Outcome.UNAVAILABLE -> R.string.diag_hotspot_unavailable
                HotspotConnector.Outcome.ERROR -> R.string.diag_hotspot_error
            }
        )
        return listOfNotNull(ssid, outcome, hotspotConnector.hotspotIpv4).joinToString(" · ")
    }

    /** Open the Wi-Fi provisioning page; every entry point checks the session first. */
    protected fun openWifiConfig() {
        if (!requireSession()) return
        startActivity(Intent(this, WifiConfigActivity::class.java))
    }

    protected companion object {
        const val TAG = "DeviceActivity"
        const val HEARTBEAT_INTERVAL_MS = 5_000L
        const val DEFAULT_SESSION_TTL_MS = 12 * 60 * 60 * 1000L

        /** Reconnect discovery window, matching NsdDiscoverer's default */
        const val DISCOVER_TIMEOUT_MS = 5_000L

        /** Silent reconnect window after a failed request */
        const val AUTO_RECONNECT_WINDOW_MS = 30_000L

        /** Silent reconnect retry interval, same as the heartbeat period */
        const val AUTO_RECONNECT_RETRY_MS = 5_000L

        /** Minimum interval between heartbeat-driven LAN migrations */
        const val LAN_MIGRATE_COOLDOWN_MS = 20_000L

        /** Placeholder the system returns when the Wi-Fi name cannot be read */
        const val UNKNOWN_SSID = "<unknown ssid>"

        /** Device hotspot SSID prefix, matching ServerConfig.AP_SSID_PREFIX */
        const val AP_SSID_PREFIX = "LightShadowArt-"

        /** Store session token length, matching DeviceConfig.STUDIO_TOKEN_MAX_LENGTH */
        const val STUDIO_TOKEN_LENGTH = 6

        /** Thumbnail cache entry count */
        const val THUMB_CACHE_ITEMS = 60

        /** Media grid columns */
        const val MEDIA_SPAN_COUNT = 3

        /** Maximum number of items offered by the system photo picker at once */
        const val MAX_PICK_COUNT = 10

        // Device /control action names, matching MtkPlayerBridge
        const val CTRL_PLAY = "PLAY"
        const val CTRL_PAUSE = "PAUSE"
        const val CTRL_AUTO_PLAY = "AUTO_PLAY"
        const val CTRL_AUTO_PAUSE = "AUTO_PAUSE"
        const val CTRL_ROTATE = "ROTATE"
        const val CTRL_SWITCH_VIEW = "SWITCH_VIEW"
        const val CTRL_UP = "UP"
        const val CTRL_DOWN = "DOWN"
        const val CTRL_LEFT = "LEFT"
        const val CTRL_RIGHT = "RIGHT"
        const val CTRL_CONFIRM = "CONFIRM"
        const val CTRL_EXIT = "EXIT"

        /** Rotation step in degrees (positive = clockwise), matching MtkPlayerBridge */
        const val ROTATE_DEGREE = 90

        /** Browse view values, matching PlayStateStore.BROWSER_MODE_* */
        const val BROWSER_MODE_DATE = 0
        const val BROWSER_MODE_IMMERSIVE = 1
        const val BROWSER_MODE_FAVOURITE = 2

        // Settings sub-page indices (the settings page is hosted inside MainActivity)
        const val SETTINGS_PAGE_MENU = 0
        const val SETTINGS_PAGE_PARAMS = 1
        const val SETTINGS_PAGE_ABOUT = 2
    }
}
