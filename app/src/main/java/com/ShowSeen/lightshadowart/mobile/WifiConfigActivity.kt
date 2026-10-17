package com.ShowSeen.lightshadowart.mobile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.ShowSeen.lightshadowart.mobile.net.DeviceClient
import com.ShowSeen.lightshadowart.mobile.net.DeviceEndpoint
import com.ShowSeen.lightshadowart.mobile.net.DiscoveredDevice
import com.ShowSeen.lightshadowart.mobile.net.HotspotConnector
import com.ShowSeen.lightshadowart.mobile.net.NsdDiscoverer
import com.ShowSeen.lightshadowart.mobile.net.SessionExpiredException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import kotlin.coroutines.resume

/**
 * Wi-Fi config page: sends an SSID and password to the device so it joins the same LAN.
 * Reuses the session already established by the main screen ([SessionStore]).
 *
 * To avoid typing the SSID by hand, entering this page pre-fills the phone's current Wi-Fi name;
 * when that cannot be read, the user can pick one from the Wi-Fi picker list.
 */
class WifiConfigActivity : AppCompatActivity() {

    private lateinit var tvTarget: TextView
    private lateinit var layoutSsid: TextInputLayout
    private lateinit var etSsid: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var btnPickWifi: MaterialButton
    private lateinit var btnSubmit: MaterialButton
    private lateinit var progress: LinearProgressIndicator
    private lateinit var tvResult: TextView

    private lateinit var client: DeviceClient
    private lateinit var hotspotConnector: HotspotConnector
    private lateinit var nsdDiscoverer: NsdDiscoverer

    /** Already waiting for the device to join the LAN and auto-return; prevents parallel waits */
    private var waitingForLan = false

    private val wifiManager: WifiManager? by lazy {
        applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    }

    /** Set when the user taps "Pick Wi-Fi" without location permission; the picker resumes once granted */
    private var pickAfterPermission = false

    /**
     * Runtime permissions required to read the Wi-Fi name and scan list.
     *
     * Location permission is mandatory: the official docs state that getScanResults() still requires
     * ACCESS_FINE_LOCATION on targetSdk 33+ (coarse location alone is not enough); NEARBY_WIFI_DEVICES
     * only covers reading the current SSID here, not the scan list.
     */
    private val wifiPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        prefillSsid()
        if (pickAfterPermission) {
            pickAfterPermission = false
            if (hasLocationPermission()) showWifiPicker() else showWifiPickUnavailable()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wifi_config)

        tvTarget = findViewById(R.id.tv_target)
        layoutSsid = findViewById(R.id.layout_ssid)
        etSsid = findViewById(R.id.et_ssid)
        etPassword = findViewById(R.id.et_password)
        btnPickWifi = findViewById(R.id.btn_pick_wifi)
        btnSubmit = findViewById(R.id.btn_submit)
        progress = findViewById(R.id.progress)
        tvResult = findViewById(R.id.tv_result)

        findViewById<PageTopBarView>(R.id.wifi_top_bar).apply {
            setTitle(getString(R.string.wifi_title))
            setOnBackClickListener { finish() }
        }
        // Activate Frame has no device endpoint yet; the switch stays disabled and says so
        findViewById<MaterialSwitch>(R.id.switch_activate_frame).isEnabled = false

        client = DeviceClient(contentResolver)
        hotspotConnector = HotspotConnector.get(this)
        nsdDiscoverer = NsdDiscoverer(this)
        SessionStore.init(this)

        val endpoint = SessionStore.endpoint
        if (endpoint == null || !SessionStore.isActive) {
            toast(getString(R.string.toast_need_session))
            finish()
            return
        }

        tvTarget.text = getString(
            R.string.state_connected,
            endpoint.baseUrl,
            getString(endpoint.modeNameRes)
        )
        btnSubmit.setOnClickListener { submit() }
        btnPickWifi.setOnClickListener { showWifiPicker() }

        val missing = locationPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            prefillSsid()
        } else {
            wifiPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    override fun onDestroy() {
        nsdDiscoverer.cancel()
        super.onDestroy()
    }

    // -----------------------------------------------------------------------
    // Pre-filling and picking the Wi-Fi name
    // -----------------------------------------------------------------------

    /** Pre-fill the Wi-Fi the phone is currently on, and explain the source under the input field */
    private fun prefillSsid() {
        val current = currentSsid()
        if (etSsid.text.isNullOrBlank()) {
            // Fall back to the last connected network when the current one cannot be read
            // (not on Wi-Fi / no permission) — usually the same router
            val fallback = SessionStore.lastWifiSsid.takeIf { it.isNotEmpty() }
            (current ?: fallback)?.let { ssid ->
                etSsid.setText(ssid)
                applySavedPassword(ssid, fromPicker = false)
            }
        }
        layoutSsid.helperText = current?.let { getString(R.string.wifi_current_hint, it) }
            ?: getString(R.string.wifi_current_unknown)
    }

    /**
     * When the picked Wi-Fi is one this app has already pushed to the device, pre-fill the saved password.
     *
     * Android does not let apps read the system's saved Wi-Fi passwords (the sensitive fields of
     * getConfiguredNetworks are wiped by the system), so the only usable source is the cache this app
     * built from successful pushes; networks never pushed have no record, and in that case the password
     * field is left untouched so whatever the user typed is not cleared.
     *
     * @param fromPicker true = the user just picked the SSID in the list (overwrite with that network's saved
     *                   password if there is one); false = initial page fill (only fill when the field is empty)
     */
    private fun applySavedPassword(ssid: String, fromPicker: Boolean) {
        val saved = SessionStore.wifiPasswordFor(ssid)
        if (saved.isEmpty()) return
        if (fromPicker || etPassword.text.isNullOrEmpty()) {
            etPassword.setText(saved)
            etPassword.setSelection(saved.length)
        }
    }

    /** List the current Wi-Fi and the scanned ones; picking one fills the input field */
    private fun showWifiPicker() {
        if (!hasLocationPermission()) {
            // Without location permission getScanResults() only returns an empty list, so request it first;
            // if the user already permanently denied it (no more dialogs), go straight to settings so the tap does something
            if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
                pickAfterPermission = true
                wifiPermissionLauncher.launch(locationPermissions().toTypedArray())
            } else {
                showWifiPickUnavailable()
            }
            return
        }

        val current = currentSsid()
        val ssids = (listOfNotNull(current) + scannedSsids()).distinct()
        if (ssids.isEmpty()) {
            showWifiPickUnavailable()
            return
        }

        val labels = ssids.map {
            if (it == current) getString(R.string.wifi_pick_current, it) else it
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.wifi_pick_title)
            .setItems(labels.toTypedArray()) { _, which ->
                val ssid = ssids[which]
                etSsid.setText(ssid)
                // Fill in the password too when the picked network was pushed by this app, saving retyping
                applySavedPassword(ssid, fromPicker = true)
            }
            .setNegativeButton(R.string.manual_dialog_cancel, null)
            .show()
    }

    /** When the list is unavailable, say which step failed (permission / location switch / nothing scanned) instead of a bare "cannot read" */
    private fun showWifiPickUnavailable() {
        if (hasLocationPermission()) {
            toast(getString(if (isLocationOn()) R.string.wifi_pick_empty else R.string.wifi_pick_need_location_service))
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.wifi_pick_title)
            .setMessage(R.string.wifi_pick_need_location)
            .setPositiveButton(R.string.wifi_open_settings) { _, _ -> openAppSettings() }
            .setNegativeButton(R.string.manual_dialog_cancel, null)
            .show()
    }

    /** Wi-Fi name the phone is currently on; null when not on Wi-Fi or unreadable (no permission) */
    private fun currentSsid(): String? =
        cleanSsid(runCatching { wifiManager?.connectionInfo?.ssid }.getOrNull())

    /** Scanned Wi-Fi names, strongest signal first */
    private fun scannedSsids(): List<String> =
        runCatching { wifiManager?.scanResults.orEmpty() }
            .getOrDefault(emptyList())
            .sortedByDescending { it.level }
            .mapNotNull { cleanSsid(it.SSID) }
            .distinct()

    /** The system returns the SSID quoted, and returns "<unknown ssid>" without permission */
    private fun cleanSsid(raw: String?): String? =
        raw?.trim('"')?.takeIf { it.isNotEmpty() && it != UNKNOWN_SSID }

    private fun locationPermissions(): List<String> =
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    /** Whether "precise location" is granted — with only approximate location the scan list is empty too */
    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** With the system location master switch off, no network is scanned even with permission granted */
    private fun isLocationOn(): Boolean =
        getSystemService(LocationManager::class.java)?.isLocationEnabled == true

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            )
        )
    }

    // -----------------------------------------------------------------------
    // Push
    // -----------------------------------------------------------------------

    private fun submit() {
        val endpoint = SessionStore.endpoint ?: return
        val sessionId = SessionStore.sessionId ?: return

        val ssid = etSsid.text?.toString()?.trim().orEmpty()
        if (ssid.isEmpty()) {
            toast(getString(R.string.toast_wifi_ssid_required))
            return
        }
        val password = etPassword.text?.toString().orEmpty()

        progress.isVisible = true
        btnSubmit.isEnabled = false
        tvResult.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        tvResult.text = getString(R.string.wifi_sending)

        lifecycleScope.launch {
            // The push request can only go out over the device hotspot link; check the link first,
            // otherwise it just waits out the full 10s timeout
            val linkError = ensureDeviceReachable(endpoint)
            val result = if (linkError == null) {
                client.wifiConfig(endpoint, sessionId, ssid, password)
            } else {
                Result.failure(IllegalStateException(linkError))
            }

            progress.isVisible = false
            btnSubmit.isEnabled = true

            result.onSuccess {
                // This only means "the device received the config", not that it has joined that network,
                // so the device's network is not recorded here — it is recorded once the phone really
                // discovers the device on the LAN (see waitForLanAndReturn).
                // The password goes into this app's own cache so picking the same SSID fills it in later
                // (Android cannot read the system's saved passwords).
                SessionStore.rememberWifiPassword(ssid, password)
                tvResult.setTextColor(ContextCompat.getColor(this@WifiConfigActivity, R.color.success))
                tvResult.text = getString(R.string.wifi_success, ssid)
                // Pass the password to the wait flow: once the device joins, the phone must join the same Wi-Fi with the same credentials
                waitForLanAndReturn(ssid, password)
            }.onFailure { error ->
                tvResult.setTextColor(ContextCompat.getColor(this@WifiConfigActivity, R.color.error))
                val reason = if (error is SessionExpiredException) {
                    SessionStore.clear()
                    getString(R.string.toast_session_expired)
                } else {
                    error.message.orEmpty()
                }
                tvResult.text = getString(R.string.wifi_failed, reason)
            }
        }
    }

    /**
     * After a successful push, wait for the device to appear on the LAN and auto-return home once it does.
     *
     * The device has to stop its hotspot, join the target Wi-Fi, get an address and register NSD, which takes
     * seconds to tens of seconds; the user has nothing to do here meanwhile, and making them go home and tap
     * "refresh connection" is a pointless extra step — so the device is waited out here: discovering it means it
     * has joined the LAN, so the session address is switched to the LAN address (the hotspot one is dead) and
     * [finish] returns home.
     *
     * If it never shows up, stay on this page and explain — the phone-side cause when the phone has not joined
     * that Wi-Fi, the device-side situation when the phone is already on it.
     *
     * @param password the Wi-Fi password entered on this page, used to join the phone to the same network
     */
    private fun waitForLanAndReturn(ssid: String, password: String) {
        if (waitingForLan) return
        waitingForLan = true

        lifecycleScope.launch {
            // The device hotspot network is useless now (the device is leaving it / has left), so the process
            // must not stay bound to it: while bound, this process's requests and mDNS use that network and the
            // device is not discovered even after the phone has joined the target Wi-Fi.
            hotspotConnector.release()

            val joinError = joinTargetWifi(ssid, password)
            if (joinError != null) {
                // The system may already have moved the phone to the target network yet never report success for
                // our request: on Android 16, issuing a WifiNetworkSpecifier request for a network the phone has
                // **saved / already joined** calls back onUnavailable directly, while the phone is in fact on that
                // network (the default network is already WIFI/VALIDATED). So after a failure, re-check the actual
                // network: if the phone really is on the target, continue discovery instead of misreporting the
                // user's network state as a failure; only report an error when it truly did not switch.
                if (currentSsid() == ssid) {
                    Log.w(
                        TAG,
                        "system refused the request but phone is on the target wifi, continue: $ssid, ${diagSummary()}"
                    )
                } else {
                    waitingForLan = false
                    Log.w(TAG, "phone failed to join wifi: ssid=$ssid, ${diagSummary()}")
                    tvResult.setTextColor(ContextCompat.getColor(this@WifiConfigActivity, R.color.error))
                    tvResult.text = joinError
                    return@launch
                }
            }

            val device = awaitDeviceOnLan()
            waitingForLan = false
            if (device == null) {
                // Failing only now means everything on the phone side was done (it is on that Wi-Fi), so log a
                // snapshot: if it fails again, the log alone shows whether the phone's network or the device is
                // the blocker, with no guessing
                Log.w(TAG, "device not found on lan after wifi-config: $ssid, ${diagSummary()}")
                tvResult.setTextColor(ContextCompat.getColor(this@WifiConfigActivity, R.color.text_secondary))
                tvResult.text = getString(R.string.wifi_switch_timeout, ssid)
                return@launch
            }

            Log.i(TAG, "device joined lan: ${device.host}:${device.port}")
            currentSsid()?.let { SessionStore.rememberWifiSsid(it) }
            SessionStore.updateEndpoint(device.toEndpoint())
            finish()
        }
    }

    /**
     * Join the phone to the target Wi-Fi as well (the prerequisite for LAN discovery in [waitForLanAndReturn]).
     *
     * Why the app must join actively: the device hotspot is a tethering setup, and the device does **not** turn off
     * its hotspot when it switches Wi-Fi, so the phone may still be associated with the device hotspot; unbinding
     * the process network does not mean the phone switched to the target network. The process then has no LAN path,
     * mDNS discovery cannot start, and the round just times out with "device not found on the LAN".
     * Measured in the field: once the phone really is on the same Wi-Fi, discovery succeeds immediately — so this
     * step is the deciding one.
     *
     * @return null if the phone is already on that Wi-Fi (LAN discovery can start); otherwise the failure reason for the user
     */
    private suspend fun joinTargetWifi(ssid: String, password: String): String? {
        // No need to ask the system to connect again when already on the target network: this skips one system
        // confirmation dialog and avoids some devices only calling back after the full timeout when asked to join
        // a network they are already on, which would drag down a scenario that could have succeeded
        if (currentSsid() == ssid) {
            Log.i(TAG, "phone already on target wifi: $ssid")
            return null
        }

        tvResult.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        tvResult.text = getString(R.string.wifi_joining, ssid)

        val joined = withTimeoutOrNull(JOIN_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                hotspotConnector.connect(ssid, password) { result ->
                    if (continuation.isActive) continuation.resume(result.isSuccess)
                }
            }
        } ?: false

        if (joined) {
            Log.i(TAG, "phone joined wifi: $ssid, ${hotspotConnector.describeBinding()}")
            return null
        }

        return getString(joinFailureMessageRes(hotspotConnector.lastOutcome), ssid)
    }

    /** Message for a failed phone join: distinguishes "system could not confirm", "system cannot connect", "request error" */
    private fun joinFailureMessageRes(outcome: HotspotConnector.Outcome): Int =
        when (outcome) {
            HotspotConnector.Outcome.UNAVAILABLE -> R.string.wifi_join_unavailable
            HotspotConnector.Outcome.LOST -> R.string.wifi_join_lost
            HotspotConnector.Outcome.ERROR -> R.string.wifi_join_error
            HotspotConnector.Outcome.NOT_ATTEMPTED -> R.string.wifi_join_unsupported
            // REQUESTING: still "requesting" when the timeout hits, usually because the system dialog was never confirmed
            HotspotConnector.Outcome.REQUESTING -> R.string.wifi_join_dialog
            HotspotConnector.Outcome.CONNECTED -> R.string.wifi_join_error
        }

    /**
     * Network snapshot at the moment of failure, for log triage (B).
     *
     * Four things are recorded: the phone's current SSID, the system default network and its capabilities, the
     * process-bound network, and whether this round's NSD found any device. The last two together separate the
     * causes — "bound to the old hotspot" = did not switch, "no default network" = the phone is not online at all,
     * "NSD found 0 with a healthy network" = the device side never came up or multicast is blocked.
     */
    private fun diagSummary(): String {
        val manager = getSystemService(ConnectivityManager::class.java)
        val active = manager?.activeNetwork
        return listOf(
            "ssid=${currentSsid() ?: "none"}",
            "default=${describeNetwork(manager, active)}",
            "bound=${hotspotConnector.describeBinding() ?: "none"}",
            "nsdFound=${nsdDiscoverer.foundCount}"
        ).joinToString(", ")
    }

    /** "WIFI / INTERNET / VALIDATED / 192.168.8.126"; falls back to the "no network" string when the network is unavailable */
    private fun describeNetwork(
        manager: ConnectivityManager?,
        network: Network?
    ): String {
        val capabilities = network?.let { manager?.getNetworkCapabilities(it) }
        if (network == null || capabilities == null) return getString(R.string.diag_no_network)

        val ipv4 = manager?.getLinkProperties(network)?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address }
            ?.address
            ?.hostAddress
        val transports = buildList {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("WIFI")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("CELLULAR")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ETHERNET")
        }.ifEmpty { listOf("UNKNOWN") }
        val flags = listOfNotNull(
            "INTERNET".takeIf { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) },
            "VALIDATED".takeIf { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
        )
        return (transports + flags + listOfNotNull(ipv4)).joinToString(" / ")
    }

    /** Look for the device on the LAN repeatedly, returning it as soon as it is found; null after [LAN_WAIT_ATTEMPTS] rounds */
    private suspend fun awaitDeviceOnLan(): DiscoveredDevice? {
        repeat(LAN_WAIT_ATTEMPTS) { attempt ->
            val devices = probeLan()
            val device = devices.firstOrNull { it.deviceToken == SessionStore.lastDeviceToken }
                ?: devices.firstOrNull()
            if (device != null) return device
            if (attempt < LAN_WAIT_ATTEMPTS - 1) delay(LAN_WAIT_INTERVAL_MS)
        }
        return null
    }

    /** Run one LAN discovery pass (waits at most [LAN_PROBE_TIMEOUT_MS]) */
    private suspend fun probeLan(): List<DiscoveredDevice> =
        suspendCancellableCoroutine { continuation ->
            nsdDiscoverer.discover(LAN_PROBE_TIMEOUT_MS) { devices ->
                if (continuation.isActive) continuation.resume(devices)
            }
            continuation.invokeOnCancellation { nsdDiscoverer.cancel() }
        }

    /**
     * Confirm the phone really is on the device hotspot link before pushing.
     *
     * The device hotspot changes subnet on every restart, and the system may also switch the phone back to another
     * Wi-Fi; the request would then just wait out the 10s connect timeout and the user sees an inscrutable error.
     * So a short-timeout TCP probe decides first; if broken, reconnect automatically with the credentials from the
     * QR code, and if that still fails give clear guidance.
     *
     * @return null if the link is usable; otherwise the failure reason shown to the user
     */
    private suspend fun ensureDeviceReachable(endpoint: DeviceEndpoint): String? {
        if (client.probe(endpoint)) return null

        if (!endpoint.hasHotspot) {
            return getString(R.string.wifi_link_unreachable, "${endpoint.host}:${endpoint.port}")
        }

        tvResult.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        tvResult.text = getString(R.string.wifi_relinking, endpoint.apSsid)

        val connected = withTimeoutOrNull(RELINK_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                hotspotConnector.connect(endpoint.apSsid, endpoint.apPassword) { result ->
                    if (continuation.isActive) continuation.resume(result.isSuccess)
                }
            }
        } ?: false

        // The system may call onAvailable before an IPv4 address is assigned, so retry the probe a few times if unreachable
        if (connected) {
            repeat(PROBE_ATTEMPTS) { attempt ->
                if (client.probe(endpoint)) return null
                if (attempt < PROBE_ATTEMPTS - 1) delay(PROBE_RETRY_INTERVAL_MS)
            }
        }

        return getString(R.string.wifi_link_lost, endpoint.host, endpoint.apSsid)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val TAG = "WifiConfigActivity"

        /** Placeholder returned by the system when the Wi-Fi name cannot be read for lack of permission */
        const val UNKNOWN_SSID = "<unknown ssid>"

        /** Time for the system to join the device hotspot and bind the process network; a timeout means the hotspot is unreachable */
        const val RELINK_TIMEOUT_MS = 15_000L

        /**
         * Time allowed for the system to join the phone to the target Wi-Fi.
         *
         * Must exceed HotspotConnector's request timeout (60s) — that timeout includes the user's time on the
         * system confirmation dialog; if this one fired first, the user would still be looking at the dialog
         * while the attempt is already declared failed, wasting the wait.
         */
        const val JOIN_TIMEOUT_MS = 65_000L

        /** Number and interval of the reachability re-probes after a successful reconnect */
        const val PROBE_ATTEMPTS = 5
        const val PROBE_RETRY_INTERVAL_MS = 1_000L

        /** Post-push polling for the device on the LAN: per-round discovery window, round interval and round count (about 48s total) */
        const val LAN_PROBE_TIMEOUT_MS = 3_000L
        const val LAN_WAIT_INTERVAL_MS = 1_000L
        const val LAN_WAIT_ATTEMPTS = 12
    }
}
