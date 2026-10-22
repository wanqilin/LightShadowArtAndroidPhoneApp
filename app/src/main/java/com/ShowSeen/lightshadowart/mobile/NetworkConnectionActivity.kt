package com.ShowSeen.lightshadowart.mobile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.ShowSeen.lightshadowart.mobile.net.ConnectionManager
import com.ShowSeen.lightshadowart.mobile.net.QrPayloadParser
import com.ShowSeen.lightshadowart.mobile.scan.ScanActivity
import kotlinx.coroutines.launch

/**
 * Network Connection page (UI PRD §3.4, 2026-10-22 rework).
 *
 * One place to read the link state and to act on it: state illustration + text, the nearby networks
 * with a single ✓ for the network the frame is on, the password for the selected network, connect /
 * reconnect / disconnect and the diagnostics.
 *
 * The page never joins a Wi-Fi itself: a `WifiNetworkSpecifier` network is app-local and breaks the
 * phone's normal internet, so the user is sent to the system Wi-Fi panel and the page only proceeds
 * once the phone really is on the selected network (the device must end up on the same one).
 */
class NetworkConnectionActivity : DeviceActivity() {

    private lateinit var topBar: PageTopBarView
    private lateinit var ivIllustration: ImageView
    private lateinit var tvState: TextView
    private lateinit var tvDevice: TextView
    private lateinit var tvNote: TextView
    private lateinit var btnReconnect: MaterialButton
    private lateinit var btnRefresh: TextView
    private lateinit var tvListHint: TextView
    private lateinit var listNetworks: LinearLayout
    private lateinit var layoutPassword: TextInputLayout
    private lateinit var etPassword: TextInputEditText
    private lateinit var btnConnect: MaterialButton
    private lateinit var btnDisconnect: MaterialButton
    private lateinit var tvDiagnostics: TextView
    private lateinit var ivDiagnostics: ImageView

    /** Set once the views exist; the manager may publish before onCreate finishes */
    private var viewsReady = false

    private var diagnosticsExpanded = false

    /** Last scan result, de-duplicated by SSID and sorted by signal strength */
    private var networks: List<NearbyNetwork> = emptyList()

    private var selectedSsid: String? = null

    /** Link / SSID the device reported through `/network`; drives the single ✓ */
    private var deviceLink: String = ""
    private var deviceSsid: String = ""

    private var lastScanAtMs = 0L

    private val wifiManager: WifiManager? by lazy {
        applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    }

    private val wifiPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshNetworks(force = true)
    }

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents.isNullOrBlank()) return@registerForActivityResult
        QrPayloadParser.parse(contents)
            .onSuccess { endpoint ->
                // Same entry point as a first-time bind: join the hotspot (if any) and authenticate
                connect(endpoint)
            }
            .onFailure { toast(getString(R.string.scan_invalid_qr)) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_network)

        topBar = findViewById(R.id.network_top_bar)
        ivIllustration = findViewById(R.id.iv_network_illustration)
        tvState = findViewById(R.id.tv_network_state)
        tvDevice = findViewById(R.id.tv_network_device)
        tvNote = findViewById(R.id.tv_network_note)
        btnReconnect = findViewById(R.id.btn_network_reconnect)
        btnRefresh = findViewById(R.id.btn_network_refresh)
        tvListHint = findViewById(R.id.tv_network_list_hint)
        listNetworks = findViewById(R.id.list_networks)
        layoutPassword = findViewById(R.id.layout_network_password)
        etPassword = findViewById(R.id.et_network_password)
        btnConnect = findViewById(R.id.btn_network_connect)
        btnDisconnect = findViewById(R.id.btn_network_disconnect)
        tvDiagnostics = findViewById(R.id.tv_network_diagnostics)
        ivDiagnostics = findViewById(R.id.iv_network_diagnostics)

        topBar.setTitle(getString(R.string.network_title))
        topBar.setOnBackClickListener { finish() }
        topBar.showTrailingAction(
            R.drawable.ic_scan,
            getString(R.string.network_scan_action)
        ) { openScanner() }

        btnReconnect.setOnClickListener { if (!connecting) refresh() }
        btnRefresh.setOnClickListener { refreshNetworks(force = true) }
        btnConnect.setOnClickListener { connectSelected() }
        btnDisconnect.setOnClickListener { confirmDisconnect() }
        findViewById<View>(R.id.row_network_diagnostics).setOnClickListener { toggleDiagnostics() }

        viewsReady = true
        render()
        ensurePermissionAndScan()
    }

    override fun onResume() {
        super.onResume()
        if (!viewsReady) return
        render()
    }

    override fun onSessionChanged() {
        if (!viewsReady) return
        render()
        refreshDeviceStatus()
    }

    // -----------------------------------------------------------------------
    // Rendering
    // -----------------------------------------------------------------------

    private fun render() {
        val snapshot = connection.snapshot
        val active = SessionStore.isActive && snapshot.state != ConnectionManager.State.DISCONNECTING
        val busy = snapshot.isBusy

        val stateText = when {
            busy -> getString(R.string.network_state_connecting)
            active && snapshot.link == ConnectionManager.Link.AP ->
                getString(R.string.network_state_connected_ap)
            active && snapshot.link == ConnectionManager.Link.LAN ->
                getString(R.string.network_state_connected_lan)
            active -> getString(R.string.network_state_connected)
            else -> getString(R.string.network_state_disconnected)
        }
        tvState.text = stateText
        ivIllustration.setImageResource(
            if (active || busy) R.drawable.ic_network_link else R.drawable.ic_network_link_off
        )
        ivIllustration.alpha = if (busy) 0.6f else 1f

        if (active) {
            val label = when {
                snapshot.link == ConnectionManager.Link.AP ->
                    SessionStore.lastApSsid.ifEmpty { getString(R.string.network_value_unknown) }
                deviceSsid.isNotEmpty() -> deviceSsid
                currentSsid().isNotEmpty() -> currentSsid()
                else -> getString(R.string.network_value_unknown)
            }
            tvDevice.text = getString(
                R.string.network_device_line,
                label,
                formatRemaining(SessionStore.remainingMs)
            )
        }
        tvDevice.isVisible = active

        val note = when {
            busy -> getString(R.string.network_note_connecting)
            active -> ""
            snapshot.state == ConnectionManager.State.FAILED -> failureNote(snapshot.failure)
            hasReconnectTarget() -> getString(R.string.network_note_can_reconnect)
            else -> getString(R.string.network_note_no_target)
        }
        tvNote.text = note
        tvNote.isVisible = note.isNotEmpty()

        btnReconnect.isVisible = !active
        when {
            busy -> {
                btnReconnect.setText(R.string.action_refresh_connecting)
                btnReconnect.isEnabled = false
            }

            else -> {
                btnReconnect.setText(R.string.action_refresh)
                btnReconnect.isEnabled = hasReconnectTarget()
            }
        }
        // Nothing to disconnect when no session exists and the phone is not bound to the hotspot
        btnDisconnect.isVisible = active || hotspotConnector.isConnected
        btnDisconnect.isEnabled = active || hotspotConnector.isConnected

        renderNetworks()
        renderPassword()
        renderDiagnostics()
    }

    /** Why the last attempt failed, in words the user can act on */
    private fun failureNote(failure: ConnectionManager.Failure): String = when (failure) {
        ConnectionManager.Failure.LAN_SWITCH_TIMEOUT -> getString(R.string.network_switch_timeout_note)
        ConnectionManager.Failure.LAN_NOT_FOUND -> getString(R.string.network_lan_not_found_note)
        ConnectionManager.Failure.AP_NOT_FOUND, ConnectionManager.Failure.AP_PERMISSION ->
            getString(R.string.network_note_can_reconnect)
        ConnectionManager.Failure.TOKEN_REJECTED -> getString(R.string.toast_need_rescan)
        ConnectionManager.Failure.NO_TARGET -> getString(R.string.network_note_no_target)
        else -> getString(R.string.network_note_can_reconnect)
    }

    /** The single ✓ marks the network the device is on, never the phone's own network */
    private fun isDeviceCurrentNetwork(ssid: String): Boolean {
        if (deviceLink == DEVICE_LINK_LAN && deviceSsid.isNotEmpty()) return deviceSsid == ssid
        return SessionStore.lastLink == SessionStore.LINK_LAN && SessionStore.lastWifiSsid == ssid
    }

    private fun renderNetworks() {
        listNetworks.removeAllViews()
        if (networks.isEmpty()) {
            tvListHint.isVisible = true
            tvListHint.setText(
                when {
                    !hasLocationPermission() -> R.string.network_list_need_permission
                    !isLocationOn() -> R.string.network_list_location_off
                    else -> R.string.network_list_empty
                }
            )
            return
        }
        tvListHint.isVisible = false
        val inflater = layoutInflater
        networks.forEach { network ->
            val row = inflater.inflate(R.layout.item_network_wifi, listNetworks, false)
            row.findViewById<TextView>(R.id.tv_wifi_ssid).text = network.ssid
            row.findViewById<View>(R.id.iv_wifi_lock).isVisible = network.secured
            row.findViewById<View>(R.id.iv_wifi_check).isVisible = isDeviceCurrentNetwork(network.ssid)
            row.alpha = if (selectedSsid == null || selectedSsid == network.ssid) 1f else 0.6f
            row.setOnClickListener { selectNetwork(network.ssid) }
            listNetworks.addView(row)
        }
    }

    private fun selectNetwork(ssid: String) {
        selectedSsid = ssid
        val cached = SessionStore.wifiPasswordFor(ssid)
        etPassword.setText(cached)
        renderNetworks()
        renderPassword()
    }

    private fun renderPassword() {
        val ssid = selectedSsid
        layoutPassword.isVisible = ssid != null
        if (ssid != null && etPassword.text.isNullOrEmpty()) {
            val cached = SessionStore.wifiPasswordFor(ssid)
            if (cached.isNotEmpty()) etPassword.setText(cached)
        }
        btnConnect.isEnabled = ssid != null && SessionStore.isActive && !connecting
        btnConnect.setText(
            if (connection.snapshot.state == ConnectionManager.State.SWITCHING) {
                R.string.network_waiting_device
            } else {
                R.string.network_connect_action
            }
        )
    }

    private fun renderDiagnostics() {
        val endpoint = SessionStore.endpoint
        tvDiagnostics.text = if (endpoint == null) {
            getString(R.string.network_note_no_target)
        } else {
            diagnostics(endpoint)
        }
    }

    // -----------------------------------------------------------------------
    // Nearby networks
    // -----------------------------------------------------------------------

    private fun ensurePermissionAndScan() {
        val missing = locationPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            refreshNetworks(force = true)
        } else {
            wifiPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    /**
     * Read the system scan results. The system rate-limits scans (about four in 30 s), so a manual
     * refresh inside that window reuses the last result instead of failing.
     */
    private fun refreshNetworks(force: Boolean) {
        if (!hasLocationPermission()) {
            networks = emptyList()
            renderNetworks()
            return
        }
        val now = System.currentTimeMillis()
        if (!force && now - lastScanAtMs < SCAN_MIN_INTERVAL_MS && networks.isNotEmpty()) return
        lastScanAtMs = now

        val best = LinkedHashMap<String, NearbyNetwork>()
        runCatching { wifiManager?.scanResults }.getOrNull().orEmpty()
            .filter { cleanSsid(it.SSID) != null }
            .forEach { result ->
                val ssid = cleanSsid(result.SSID) ?: return@forEach
                if (ssid.startsWith(AP_SSID_PREFIX)) return@forEach
                val secured = result.capabilities.orEmpty().contains("WPA") ||
                    result.capabilities.orEmpty().contains("WEP")
                val current = best[ssid]
                if (current == null || result.level > current.level) {
                    best[ssid] = NearbyNetwork(ssid, result.level, secured)
                }
            }
        networks = best.values.sortedByDescending { it.level }.take(MAX_LISTED_NETWORKS)

        // Keep the old selection when it is still around, otherwise start from the phone's network
        val keep = selectedSsid?.takeIf { ssid -> networks.any { it.ssid == ssid } }
        selectedSsid = keep ?: currentSsid().takeIf { ssid -> networks.any { it.ssid == ssid } }
        etPassword.setText(selectedSsid?.let { SessionStore.wifiPasswordFor(it) }.orEmpty())
        render()
    }

    // -----------------------------------------------------------------------
    // Actions
    // -----------------------------------------------------------------------

    /**
     * Push the selected network to the frame.
     *
     * Two cases:
     * - the frame is already on the LAN → the phone must be on the target first, otherwise the two
     *   would end up on different networks (PRD NW-4);
     * - the frame is in AP mode → the push has to go over the hotspot, and only then does the phone
     *   move to the target (the page waits for that in [onResume]).
     */
    private fun connectSelected() {
        val ssid = selectedSsid ?: return
        if (!SessionStore.isActive) {
            requireSession()
            return
        }
        val phoneSsid = currentSsid()
        val password = etPassword.text?.toString().orEmpty()
        if (password.isEmpty() && networks.firstOrNull { it.ssid == ssid }?.secured == true) {
            toast(getString(R.string.network_password_required))
            return
        }
        SessionStore.rememberWifiPassword(ssid, password)

        val deviceOnLan = SessionStore.lastLink == SessionStore.LINK_LAN
        if (deviceOnLan && phoneSsid != ssid) {
            toast(getString(R.string.network_join_first, ssid))
            openWifiPanel()
            return
        }

        connection.pushWifiConfig(ssid, password) { accepted ->
            if (!accepted) return@pushWifiConfig
            if (currentSsid() == ssid) {
                connection.awaitDeviceOnLan()
            } else {
                toast(getString(R.string.network_pushed_join, ssid))
                openWifiPanel()
                // Keep polling for the phone to join: the panel may close without the page resuming,
                // or the user may come back much later; a single onResume check is not reliable
                connection.awaitPhoneOnTarget(ssid)
            }
        }
    }

    /** Open the system Wi-Fi panel: the app never joins a normal network by itself */
    private fun openWifiPanel() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_WIFI)
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS)
        }
        runCatching { startActivity(intent) }
    }

    private fun openScanner() {
        scanLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setCaptureActivity(ScanActivity::class.java)
                .setPrompt(getString(R.string.scan_prompt))
                .setBeepEnabled(false)
                .setOrientationLocked(true)
        )
    }

    private fun confirmDisconnect() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.network_disconnect_confirm_title)
            .setMessage(R.string.network_disconnect_confirm_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.network_disconnect) { _, _ -> disconnect() }
            .show()
    }

    private fun toggleDiagnostics() {
        diagnosticsExpanded = !diagnosticsExpanded
        tvDiagnostics.isVisible = diagnosticsExpanded
        ivDiagnostics.rotation = if (diagnosticsExpanded) 90f else 0f
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Ask the device which link / network it is on, so the ✓ reflects the device, not the phone */
    private fun refreshDeviceStatus() {
        val endpoint = SessionStore.endpoint ?: return
        val sessionId = SessionStore.sessionId ?: return
        lifecycleScope.launch {
            client.networkStatus(endpoint, sessionId).onSuccess {
                deviceLink = it.link
                deviceSsid = it.ssid
                renderNetworks()
            }
        }
    }

    private fun currentSsid(): String {
        val raw = runCatching { wifiManager?.connectionInfo?.ssid }.getOrNull() ?: return ""
        return cleanSsid(raw).orEmpty()
    }

    private fun cleanSsid(raw: String?): String? =
        raw?.trim('"')?.takeIf { it.isNotEmpty() && it != UNKNOWN_SSID }

    private fun locationPermissions(): List<String> =
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun isLocationOn(): Boolean =
        getSystemService(LocationManager::class.java)?.isLocationEnabled == true

    private data class NearbyNetwork(val ssid: String, val level: Int, val secured: Boolean)

    private companion object {
        const val UNKNOWN_SSID = "<unknown ssid>"
        const val AP_SSID_PREFIX = "LightShadowArt-"
        const val DEVICE_LINK_LAN = "lan"
        const val SCAN_MIN_INTERVAL_MS = 30_000L

        /**
         * How many nearby networks are listed. A dense neighbourhood can return dozens, and a long
         * wall of SSIDs pushes the password field and the connect button far below the fold.
         */
        const val MAX_LISTED_NETWORKS = 8
    }
}
