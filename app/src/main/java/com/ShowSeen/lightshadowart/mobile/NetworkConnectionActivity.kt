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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
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
 * Network Connection page (UI PRD §3.4, 2026-10-22 second pass).
 *
 * Layout: a fixed state card, a nearby-network list that scrolls inside its own card (the page
 * itself never scrolls) and a diagnostics bar pinned at the bottom.
 *
 * Interaction: tapping a network is the action. A secured network asks for its password in a dialog,
 * then the push and the switch start automatically — there is no "connect" button and no inline
 * password field. Disconnecting a frame is a session action (Settings → Sign out), not a network-page
 * action, so the page has no "disconnect" button.
 */
class NetworkConnectionActivity : DeviceActivity() {

    private lateinit var topBar: PageTopBarView
    private lateinit var ivIllustration: ImageView
    private lateinit var tvState: TextView
    private lateinit var tvDevice: TextView
    private lateinit var tvNote: TextView
    private lateinit var btnRefresh: TextView
    private lateinit var tvListHint: TextView
    private lateinit var listNetworks: LinearLayout

    /** Set once the views exist; the manager may publish before onCreate finishes */
    private var viewsReady = false

    /** Last scan result, de-duplicated by SSID and sorted by signal strength */
    private var networks: List<NearbyNetwork> = emptyList()

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
        btnRefresh = findViewById(R.id.btn_network_refresh)
        tvListHint = findViewById(R.id.tv_network_list_hint)
        listNetworks = findViewById(R.id.list_networks)

        topBar.setTitle(getString(R.string.network_title))
        topBar.setOnBackClickListener { finish() }
        topBar.showTrailingAction(
            R.drawable.ic_scan,
            getString(R.string.network_scan_action)
        ) { openScanner() }

        btnRefresh.setOnClickListener { refreshNetworks(force = true) }
        findViewById<View>(R.id.row_network_diagnostics).setOnClickListener { showDiagnostics() }

        viewsReady = true
        render()
        // The device may already be connected when the page opens: its link is what decides the ✓
        refreshDeviceStatus()
        ensurePermissionAndScan()
    }

    override fun onResume() {
        super.onResume()
        if (!viewsReady) return
        if (!SessionStore.isActive) clearDeviceNetwork()
        render()
        refreshDeviceStatus()
    }

    override fun onSessionChanged() {
        if (!viewsReady) return
        if (!SessionStore.isActive) clearDeviceNetwork()
        render()
        refreshDeviceStatus()
    }

    /** The device's link is unknown once the session ends; keep no stale check mark around */
    private fun clearDeviceNetwork() {
        deviceLink = ""
        deviceSsid = ""
    }

    // -----------------------------------------------------------------------
    // Rendering
    // -----------------------------------------------------------------------

    private fun render() {
        val snapshot = connection.snapshot
        // Only a healthy link counts as connected: a persisted session whose heartbeat is failing
        // (DEGRADED) or that already failed must not show the green "已连接" card.
        val active = SessionStore.isActive && snapshot.state == ConnectionManager.State.CONNECTED
        val degraded = SessionStore.isActive && snapshot.state == ConnectionManager.State.DEGRADED
        val busy = snapshot.isBusy

        // The device's own report wins: a LAN endpoint can still be reachable through a stale
        // address while the frame itself is serving its hotspot, and the card must not claim "LAN"
        // with the hotspot's name next to it.
        val lanLink = if (deviceLink.isNotEmpty()) deviceLink == DEVICE_LINK_LAN
        else snapshot.link == ConnectionManager.Link.LAN
        val apLink = if (deviceLink.isNotEmpty()) !lanLink
        else snapshot.link == ConnectionManager.Link.AP

        val stateText = when {
            busy -> getString(R.string.network_state_connecting)
            active && apLink -> getString(R.string.network_state_connected_ap)
            active && lanLink -> getString(R.string.network_state_connected_lan)
            active -> getString(R.string.network_state_connected)
            degraded -> getString(R.string.dashboard_degraded)
            else -> getString(R.string.network_state_disconnected)
        }
        tvState.text = stateText
        ivIllustration.setImageResource(
            if (active || busy) R.drawable.ic_network_link else R.drawable.ic_network_link_off
        )
        ivIllustration.alpha = if (busy) 0.6f else 1f

        if (active) {
            val label = when {
                apLink && deviceSsid.isNotEmpty() -> deviceSsid
                apLink -> SessionStore.lastApSsid.ifEmpty { getString(R.string.network_value_unknown) }
                deviceSsid.isNotEmpty() -> deviceSsid
                // The device's own answer may still be in flight; the network the phone is on is the
                // same one the device was just switched to, so it is a truthful interim label
                currentSsid().isNotEmpty() -> currentSsid()
                SessionStore.lastWifiSsid.isNotEmpty() -> SessionStore.lastWifiSsid
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

        renderNetworks()
    }

    /** Why the last attempt failed, in words the user can act on */
    private fun failureNote(failure: ConnectionManager.Failure): String = when (failure) {
        ConnectionManager.Failure.LAN_SWITCH_TIMEOUT -> getString(R.string.network_switch_timeout_note)
        ConnectionManager.Failure.LAN_NOT_FOUND -> getString(R.string.network_lan_not_found_note)
        ConnectionManager.Failure.AP_NOT_FOUND, ConnectionManager.Failure.AP_PERMISSION ->
            getString(R.string.network_qr_stale_note)
        ConnectionManager.Failure.TOKEN_REJECTED -> getString(R.string.toast_need_rescan)
        ConnectionManager.Failure.NO_TARGET -> getString(R.string.network_note_no_target)
        else -> getString(R.string.network_note_can_reconnect)
    }

    /**
     * The single ✓ marks the network the device is on.
     *
     * Only the device's own answer (`GET /network`) counts: a remembered link is not evidence, and
     * after signing out the previous session's LAN must not keep its check mark.
     */
    private fun isDeviceCurrentNetwork(ssid: String): Boolean =
        deviceLink == DEVICE_LINK_LAN && deviceSsid.isNotEmpty() && deviceSsid == ssid

    private fun renderNetworks() {
        listNetworks.removeAllViews()
        if (networks.isEmpty()) {
            tvListHint.isVisible = true
            tvListHint.setText(
                when {
                    !hasScanPermission() -> R.string.network_list_need_permission
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
            row.setOnClickListener { onNetworkTapped(network) }
            listNetworks.addView(row)
        }
    }

    // -----------------------------------------------------------------------
    // Connecting
    // -----------------------------------------------------------------------

    /**
     * Tapping a network is the whole action: the password (if any) is asked in a dialog and the
     * push starts right after it, so there is no separate "connect" button to find.
     */
    private fun onNetworkTapped(network: NearbyNetwork) {
        if (!SessionStore.isActive) {
            requireSession()
            return
        }
        if (network.secured) {
            askPassword(network.ssid)
        } else {
            connectTo(network.ssid, "")
        }
    }

    private fun askPassword(ssid: String) {
        val view = layoutInflater.inflate(R.layout.dialog_wifi_password, null)
        val input = view.findViewById<TextInputEditText>(R.id.dialog_password_input)
        val layout = view.findViewById<TextInputLayout>(R.id.dialog_password_layout)
        val cached = SessionStore.wifiPasswordFor(ssid)
        if (cached.isNotEmpty()) input.setText(cached)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(ssid)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.network_connect_confirm, null)
            .create()

        dialog.setOnShowListener {
            // Validate before dismissing, so a typo does not close the dialog
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val password = input.text?.toString().orEmpty()
                if (password.isEmpty()) {
                    layout.error = getString(R.string.network_password_required)
                    return@setOnClickListener
                }
                layout.error = null
                dialog.dismiss()
                connectTo(ssid, password)
            }
            input.requestFocus()
        }
        dialog.show()
    }

    /**
     * Push the selected network to the frame and wait for it on the LAN.
     *
     * The push always goes over the link that works right now (hotspot or the current LAN), and only
     * then does the phone move to the target: the device cannot be reached once the phone leaves,
     * so "join first, push later" is impossible for a network change.
     */
    private fun connectTo(ssid: String, password: String) {
        if (!SessionStore.isActive) {
            requireSession()
            return
        }
        SessionStore.rememberWifiPassword(ssid, password)

        if (currentSsid() == ssid) {
            // Already on that network: nothing to push, just find the device again
            connection.awaitDeviceOnLan()
            return
        }

        connection.pushWifiConfig(ssid, password) { accepted ->
            if (!accepted) return@pushWifiConfig
            toast(getString(R.string.network_pushed_join, ssid))
            openWifiPanel()
            // Keep looking for the device: it re-appears on the LAN once both sides have switched,
            // and the user may take a while to pick the network in the system panel
            connection.awaitDeviceOnLan()
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

    // -----------------------------------------------------------------------
    // Diagnostics
    // -----------------------------------------------------------------------

    /** Pinned bottom bar: opens the report in a dialog instead of pushing the list off screen */
    private fun showDiagnostics() {
        val endpoint = SessionStore.endpoint
        val text = if (endpoint == null) {
            getString(R.string.network_note_no_target)
        } else {
            diagnostics(endpoint)
        }

        val density = resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val report = TextView(this).apply {
            setText(text)
            setTextIsSelectable(true)
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(padding, padding / 2, padding, padding / 2)
        }
        val scroll = ScrollView(this).apply { addView(report) }
        val container = FrameLayout(this).apply { addView(scroll) }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.network_diagnostics_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // -----------------------------------------------------------------------
    // Nearby networks
    // -----------------------------------------------------------------------

    /** Ask the device which link / network it is on, so the ✓ reflects the device, not the phone */
    private fun refreshDeviceStatus() {
        val endpoint = SessionStore.endpoint ?: return
        val sessionId = SessionStore.sessionId ?: return
        lifecycleScope.launch {
            client.networkStatus(endpoint, sessionId).onSuccess {
                deviceLink = it.link
                deviceSsid = it.ssid
                // The card shows the device's network name too; refresh it, not just the list
                render()
            }
        }
    }

    private fun ensurePermissionAndScan() {
        val missing = requiredPermissions().filter {
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
        if (!hasScanPermission()) {
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
        render()
    }

    private fun currentSsid(): String {
        val raw = runCatching { wifiManager?.connectionInfo?.ssid }.getOrNull() ?: return ""
        return cleanSsid(raw).orEmpty()
    }

    private fun cleanSsid(raw: String?): String? =
        raw?.trim('"')?.takeIf { it.isNotEmpty() && it != UNKNOWN_SSID }

    /**
     * Permissions the network list needs.
     *
     * Android 13 split Wi-Fi scanning out of the location group: an app targeting 33+ must hold
     * NEARBY_WIFI_DEVICES or `getScanResults` comes back empty — the location permission alone is no
     * longer enough (it is still needed to read the current SSID).
     */
    private fun requiredPermissions(): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }

    private fun hasScanPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun isLocationOn(): Boolean =
        getSystemService(LocationManager::class.java)?.isLocationEnabled == true

    private data class NearbyNetwork(val ssid: String, val level: Int, val secured: Boolean)

    private companion object {
        const val UNKNOWN_SSID = "<unknown ssid>"
        const val AP_SSID_PREFIX = "LightShadowArt-"
        const val DEVICE_LINK_LAN = "lan"
        const val SCAN_MIN_INTERVAL_MS = 30_000L

        /**
         * How many nearby networks are listed. The list scrolls inside its card, so the cap is only
         * about keeping the strongest candidates on top rather than limiting reachability.
         */
        const val MAX_LISTED_NETWORKS = 12
    }
}
