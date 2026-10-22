package com.ShowSeen.lightshadowart.mobile

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ShowSeen.lightshadowart.mobile.net.ConnectionManager
import com.ShowSeen.lightshadowart.mobile.net.DeviceClient
import com.ShowSeen.lightshadowart.mobile.net.DeviceEndpoint
import com.ShowSeen.lightshadowart.mobile.net.HotspotConnector
import com.ShowSeen.lightshadowart.mobile.net.SessionExpiredException
import kotlinx.coroutines.launch
import java.net.Inet4Address

/**
 * Shared device-session behaviour for the screens that drive the connection (dashboard and network
 * page).
 *
 * Ownership moved into [ConnectionManager]: the state machine, heartbeat, reconnect and the
 * hotspot binding all live there, so two screens always see the same state (PRD 16.4). This class
 * only translates user actions into manager intents, subscribes to the state, and keeps the pieces
 * that are genuinely UI: permission requests, dialogs and diagnostics.
 */
abstract class DeviceActivity : AppCompatActivity(), ConnectionManager.Listener {

    protected val connection: ConnectionManager get() = ConnectionManager.get(this)
    protected val client: DeviceClient get() = connection.client
    protected val hotspotConnector: HotspotConnector get() = connection.hotspotConnector

    /** True while a connect / reconnect / switch is in flight */
    protected val connecting: Boolean get() = connection.snapshot.isBusy

    /** Device waiting for the hotspot permissions granted in the permission callback */
    private var pendingEndpoint: DeviceEndpoint? = null

    private lateinit var wifiPermissionLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SessionStore.init(this)
        connection.addListener(this)
        wifiPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { grants ->
            val endpoint = pendingEndpoint
            pendingEndpoint = null
            if (endpoint == null) return@registerForActivityResult
            if (grants.values.all { it }) {
                connection.connect(endpoint)
            } else {
                toast(getString(R.string.toast_hotspot_permission_denied))
            }
        }
    }

    override fun onDestroy() {
        connection.removeListener(this)
        super.onDestroy()
    }

    // -----------------------------------------------------------------------
    // Hooks for the concrete page
    // -----------------------------------------------------------------------

    /** The session state (or the parameters) changed: re-render this page. */
    protected abstract fun onSessionChanged()

    /** The session is gone for good (expired / rejected token / user disconnect): drop page caches. */
    protected open fun onSessionCleared() = Unit

    /** A session was (re)established: refresh page content that depends on it. */
    protected open fun onSessionEstablished() = Unit

    /** Store mode: the photographer confirmed the folder, the page may navigate to the gallery. */
    protected open fun onStudioSelectionConfirmed() = Unit

    // -----------------------------------------------------------------------
    // ConnectionManager.Listener (always delivered on the main thread)
    // -----------------------------------------------------------------------

    override fun onConnectionChanged(snapshot: ConnectionManager.Snapshot) {
        onSessionChanged()
        when (snapshot.state) {
            ConnectionManager.State.NEED_BIND -> {
                onSessionCleared()
                toast(getString(R.string.toast_need_rescan))
            }

            ConnectionManager.State.FAILED -> {
                // Only explicit failures carry a message; silent retries stay quiet
                val reason = snapshot.error
                if (!reason.isNullOrEmpty()) {
                    val endpoint = SessionStore.endpoint ?: SessionStore.apEndpoint()
                    if (endpoint != null) showConnectError(reason, endpoint) else showError(reason)
                }
            }

            else -> Unit
        }
    }

    override fun onSessionReady() {
        onSessionEstablished()
        onSessionChanged()
    }

    override fun onSessionGone() {
        onSessionCleared()
        onSessionChanged()
    }

    override fun onStudioFolderReady(folder: String) {
        promptStudioFolder(folder)
    }

    override fun onDisconnectFinished(handover: Boolean, error: Throwable?) {
        // A 401 means the device session was already gone; that is the same outcome, not an error
        toast(
            when {
                error == null || error is SessionExpiredException ->
                    getString(if (handover) R.string.toast_logout_done else R.string.toast_disconnected)
                else -> getString(R.string.toast_disconnect_failed, error.message.orEmpty())
            }
        )
    }

    // -----------------------------------------------------------------------
    // Connecting
    // -----------------------------------------------------------------------

    /**
     * Connect entry point for a scanned endpoint: the hotspot permission is requested here (UI
     * concern), the join + auth themselves are the manager's job.
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
        connection.connect(endpoint)
    }

    /** Reconnect: the manager retries the link the last session ran over (no cross-link fallback) */
    protected fun refresh() {
        if (connecting) return
        connection.reconnect()
    }

    /** Leave the session; [handover] also unbinds the device and makes it show a fresh QR code */
    protected fun disconnect(handover: Boolean = false) {
        connection.disconnect(handover)
    }

    /** The device rejected a request (401): try one silent re-auth before asking for a new scan */
    protected fun handleSessionExpired() {
        connection.onUnauthorized()
    }

    // -----------------------------------------------------------------------
    // Store-mode folder hand-off
    // -----------------------------------------------------------------------

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

    /** Current SSID, the process binding and hotspot outcome for [endpoint]. */
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

    protected companion object {
        const val TAG = "DeviceActivity"

        /** Device hotspot SSID prefix, matching ServerConfig.AP_SSID_PREFIX */
        const val AP_SSID_PREFIX = "LightShadowArt-"

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
