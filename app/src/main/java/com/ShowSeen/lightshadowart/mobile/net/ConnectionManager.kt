package com.ShowSeen.lightshadowart.mobile.net

import android.content.Context
import android.os.SystemClock
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ShowSeen.lightshadowart.mobile.AppConfig
import com.ShowSeen.lightshadowart.mobile.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Single owner of "are we connected to the frame, and over which link" (PRD 16.4).
 *
 * The previous design spread this over two Activity instances with a handful of booleans
 * (`connecting`, `migratingToLan`, `autoReconnectJob`, ...). Here one state machine decides, every
 * intent bumps a generation so late callbacks from an abandoned attempt are dropped, and screens
 * only render [snapshot].
 *
 * Link policy (PRD NW-5 / NW-6): a reconnect retries the link the session was last established over
 * and never silently falls back to the other one — an access point that is gone must not make the
 * app pop a system sheet behind the user's back.
 */
class ConnectionManager private constructor(context: Context) {

    enum class State {
        IDLE,
        SEARCHING,
        JOINING_AP,
        AUTHENTICATING,
        CONNECTED,
        SWITCHING,
        DEGRADED,
        DISCONNECTING,
        FAILED,

        /**
         * Another phone is driving the frame: a silent recovery was refused (409 `occupied`) and must
         * not be retried, otherwise two paired phones kick each other out on every foreground
         * (PRD 16.2 NW-18). Only a scan takes the seat back.
         */
        OCCUPIED,
        NEED_BIND
    }

    enum class Failure {
        NONE,
        NO_TARGET,
        AP_NOT_FOUND,
        AP_PERMISSION,
        LAN_NOT_FOUND,
        LAN_SWITCH_TIMEOUT,
        AUTH_FAILED,
        TOKEN_REJECTED,
        UNREACHABLE,
        OCCUPIED
    }

    enum class Link { NONE, AP, LAN }

    data class Snapshot(
        val state: State = State.IDLE,
        val failure: Failure = Failure.NONE,
        val link: Link = Link.NONE,
        /** Raw reason shown in the diagnostics dialog; null unless [state] is FAILED / NEED_BIND */
        val error: String? = null
    ) {
        /** True while an attempt is in flight: screens disable their actions meanwhile */
        val isBusy: Boolean
            get() = state == State.SEARCHING || state == State.JOINING_AP ||
                state == State.AUTHENTICATING || state == State.SWITCHING ||
                state == State.DISCONNECTING
    }

    /** Screens subscribe; callbacks are always delivered on the main thread */
    interface Listener {
        fun onConnectionChanged(snapshot: Snapshot)

        /** A session was established or refreshed */
        fun onSessionReady()

        /** The session is gone (expired, rejected token, user disconnect) */
        fun onSessionGone()

        /** Studio mode: the phone has to tell the photographer which folder to fill */
        fun onStudioFolderReady(folder: String)

        /** A disconnect finished; [error] is null on success or when the session was already gone */
        fun onDisconnectFinished(handover: Boolean, error: Throwable?)
    }

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** A Wi-Fi push is in flight: the link is expected to drop, do not turn that into "unstable" */
    @Volatile
    private var switchInFlight = false

    /**
     * A pairing (scan) is driving the connection. Foreground restore must not start its own hotspot
     * join meanwhile: that second request cancels the one the scan just started, and the system tells
     * the user "the app cancelled the request".
     */
    @Volatile
    private var pairingInProgress = false

    /**
     * When the current link was adopted (monotonic). A freshly adopted link gets [LINK_SETTLE_MS]
     * before a failed heartbeat may declare it unstable: during a hotspot → LAN hand-over the first
     * heartbeats can still hit the network transition, and flashing "unstable" there is wrong.
     */
    @Volatile
    private var linkSettledAtMs = 0L
    private val listeners = CopyOnWriteArrayList<Listener>()

    /** HTTP client shared with the screens (they still call media / control endpoints themselves) */
    val client = DeviceClient(app.contentResolver)

    /** Access point binder; only ever used for the device's own hotspot */
    val hotspotConnector = HotspotConnector.get(app)

    private val nsdDiscoverer = NsdDiscoverer(app)
    private val locator = DeviceLocator(nsdDiscoverer)

    @Volatile
    var snapshot: Snapshot = Snapshot()
        private set

    /** Bumped by every intent; async work compares against it and drops stale results */
    private var generation = 0

    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var backgroundJob: Job? = null

    /** The single post-switch discovery window (see [awaitDeviceOnLan]) */
    private var waitTargetJob: Job? = null

    /** Monotonic deadline of the running window; 0 when no window is active */
    private var switchDeadlineMs = 0L

    /** The whole app is in the foreground: [establish] and the recheck only run then */
    @Volatile
    private var foreground = false

    /** A hotspot join was already requested during this foreground visit (PRD 16.2 NW-16) */
    private var joinAttempted = false

    /**
     * A hotspot join is being prepared or is in flight.
     *
     * Nothing may start a second one or release the binding meanwhile: the system reports the first
     * one as cancelled by the app ("该应用已取消选择设备的请求") and the pairing fails.
     */
    @Volatile
    private var joinInFlight = false

    /** Last successful heartbeat / auth (monotonic): a "connected" older than this is not trusted */
    @Volatile
    private var lastOkAtMs = 0L

    /** Throttle for repeated establish attempts (network callbacks can arrive in bursts) */
    private var lastEstablishAtMs = 0L

    private var recheckJob: Job? = null

    /** Guards against several silent re-auths racing on repeated 401s */
    private var reauthenticating = false

    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    // ------------------------------------------------------------------
    // Intents
    // ------------------------------------------------------------------

    /** Scan result: join the device hotspot if needed, then exchange the token for a session */
    fun connect(endpoint: DeviceEndpoint) {
        val gen = ++generation
        pairingInProgress = true
        cancelReconnect()

        // A different token means a new pairing took over (the frame was strongly reset and shows a
        // fresh code). The old link and session are void even when the phone still reports the same
        // hotspot as connected — scanning the new code must replace the whole connection, not
        // authenticate over the dead one.
        val takeover = endpoint.deviceToken.isNotEmpty() &&
            SessionStore.lastDeviceToken.isNotEmpty() &&
            endpoint.deviceToken != SessionStore.lastDeviceToken
        if (takeover) {
            Log.i(TAG, "new pairing takes over: resetting the old link and session")
            stopHeartbeat()
            hotspotConnector.release()
            SessionStore.clear()
        }

        if (endpoint.hasHotspot && (takeover || !isOnWifi(endpoint.apSsid))) {
            publish(Snapshot(State.JOINING_AP, link = Link.AP))
            hotspotConnector.join(endpoint.apSsid, endpoint.apPassword) { result ->
                if (gen == generation) {
                    pairingInProgress = false
                    result.onSuccess {
                        SessionStore.rememberWifiSsid(endpoint.apSsid)
                        SessionStore.rememberApCredentials(endpoint)
                        authenticate(endpoint, gen, sendStudioToken = true)
                    }.onFailure { error ->
                        publish(
                            Snapshot(
                                State.FAILED,
                                failure = if (error is UnsupportedOperationException) Failure.AP_PERMISSION else Failure.AP_NOT_FOUND,
                                link = Link.NONE,
                                error = error.message
                            )
                        )
                    }
                }
            }
        } else {
            pairingInProgress = false
            if (endpoint.hasHotspot) {
                SessionStore.rememberWifiSsid(endpoint.apSsid)
                SessionStore.rememberApCredentials(endpoint)
            }
            authenticate(endpoint, gen, sendStudioToken = true)
        }
    }

    /**
     * User pressed "reconnect"-equivalent intent: rebuild the connection now.
     *
     * Kept as a thin alias of [establish] so no screen has to know about discovery order.
     */
    fun reconnect() = establish()

    /**
     * Push a target Wi-Fi to the device (PRD NW-4).
     *
     * Split from [awaitDeviceOnLan] on purpose: when the device is in AP mode the phone has to send
     * this **over the hotspot** and only then move to the target network, so the page must be able
     * to wait for the phone side before asking the manager to look on the LAN.
     *
     * @param onResult true when the device accepted the credentials
     */
    fun pushWifiConfig(ssid: String, password: String, onResult: (Boolean) -> Unit) {
        val endpoint = SessionStore.endpoint
        val sessionId = SessionStore.sessionId
        if (endpoint == null || sessionId == null) {
            publish(Snapshot(State.FAILED, Failure.NO_TARGET))
            onResult(false)
            return
        }
        val gen = ++generation
        cancelReconnect()
        switchInFlight = true
        publish(Snapshot(State.SWITCHING, link = snapshot.link))
        scope.launch {
            val result = client.wifiConfig(endpoint, sessionId, ssid, password)
            // Keep switchInFlight until the switch resolves: the device stops its hotspot only after
            // the response, so clearing it here let the first heartbeat failure look like an unstable
            // link and the card flashed "连接不稳定".
            if (gen != generation) {
                switchInFlight = false
                onResult(false)
                return@launch
            }
            result.onFailure { error ->
                switchInFlight = false
                if (error is SessionExpiredException) {
                    onUnauthorized()
                } else {
                    publish(Snapshot(State.FAILED, Failure.AUTH_FAILED, snapshot.link, error.message))
                }
            }
            if (result.isSuccess) {
                SessionStore.rememberWifiSsid(ssid)
                // The device may switch any moment now; remember where we expect to find it
                SessionStore.setPendingLanSsid(ssid)
            }
            onResult(result.isSuccess)
        }
    }

    /**
     * Look for the device on the LAN, retrying until it shows up.
     *
     * A Wi-Fi switch is asynchronous on both sides: the device leaves the hotspot and joins the LAN
     * while the phone is still on the hotspot (or moving), so one discovery round races the switch.
     * Each round returns as soon as the device answers; when it does not, retry within a bounded
     * window and then report the failure.
     *
     * The phone's SSID is deliberately not consulted: Android reports `unknown` without location
     * permission, and what matters is whether the device is reachable — not which network we are on.
     */
    fun awaitDeviceOnLan() {
        // Keep one authoritative window: a second call while it is running (a failed heartbeat, a
        // foreground bounce) must not restart the deadline, otherwise the timeout never fires.
        val running = waitTargetJob?.isActive == true &&
            System.currentTimeMillis() < switchDeadlineMs
        if (running) return

        val gen = ++generation
        cancelReconnect()
        publish(Snapshot(State.SWITCHING, link = Link.LAN))
        switchDeadlineMs = System.currentTimeMillis() + POST_SWITCH_WINDOW_MS
        waitTargetJob = scope.launch {
            // While bound to the hotspot, discovery and HTTP would keep using a network the device left
            hotspotConnector.release()
            var attempt = 0
            while (System.currentTimeMillis() < switchDeadlineMs && isActive) {
                attempt++
                val started = System.currentTimeMillis()
                val found = locator.findLanEndpoint(SessionStore.lastDeviceToken, DISCOVER_TIMEOUT_MS)
                if (gen != generation) return@launch
                Log.i(
                    TAG,
                    "lan discovery #$attempt took ${System.currentTimeMillis() - started}ms, found=${found != null}"
                )
                if (found != null) {
                    // Only a device that reports itself on the LAN is a finished switch: the frame
                    // can answer over a transient address while its own link is still coming up,
                    // which made the card flash connected → disconnected → connected.
                    if (authenticateForSwitch(found, gen)) return@launch
                }
                delay(POST_SWITCH_RETRY_MS)
            }
            if (gen == generation) {
                // Give up on this switch; a later foreground uses the normal restore path instead
                switchInFlight = false
                SessionStore.setPendingLanSsid("")
                publish(Snapshot(State.FAILED, Failure.LAN_SWITCH_TIMEOUT))
            }
        }
    }

    /** End the session; [handover] also makes the device unbind and show a fresh QR code */
    fun disconnect(handover: Boolean) {
        val endpoint = SessionStore.endpoint
        val sessionId = SessionStore.sessionId
        val gen = ++generation
        cancelReconnect()

        // Reset the local state first: signing out must show "not connected" at once, on AP and on
        // LAN alike. The device side (handover / QR) is confirmed afterwards and only feeds the
        // completion callback — waiting for it here kept the page on "connecting" for many seconds.
        stopHeartbeat()
        // Stop auto-joining the frame hotspot once this session is over
        hotspotConnector.removeSuggestion(SessionStore.lastApSsid, SessionStore.lastApPassword)
        SessionStore.clear()
        // Signing out rotates the device credentials: the stored token is dead, so drop it and let
        // the page ask for the new QR code instead of offering a reconnect that cannot work
        if (handover) SessionStore.forgetDeviceToken()
        publish(Snapshot(State.IDLE))
        listeners.forEach { it.onSessionGone() }

        scope.launch {
            val error = if (endpoint != null && sessionId != null) {
                if (handover) {
                    client.handoverSession(endpoint, sessionId).exceptionOrNull()
                } else {
                    client.endSession(endpoint, sessionId).exceptionOrNull()
                }
            } else {
                null
            }
            // Only now may the process leave the hotspot; skip it when the user already started a
            // new hotspot request (releasing would cancel it)
            hotspotConnector.releaseIfIdle()
            if (gen != generation) return@launch
            listeners.forEach { it.onDisconnectFinished(handover, error) }
        }
    }

    /** The device rejected our session (401 from a feature call): try one silent re-auth first */
    fun onUnauthorized() {
        if (reauthenticating) return
        val endpoint = SessionStore.endpoint
        if (endpoint == null) {
            clearSession(Failure.NO_TARGET)
            return
        }
        reauthenticating = true
        val gen = generation
        val link = snapshot.link
        scope.launch {
            val result = client.auth(
                endpoint,
                "",
                AppConfig.AUTH_INTENT_RECOVER,
                SessionStore.clientId
            )
            reauthenticating = false
            if (gen != generation) return@launch
            result.onSuccess {
                applyAuth(endpoint, it, announceStudioFolder = false, link = link)
            }.onFailure { recoverAfterUnreachable(it) }
        }
    }

    /**
     * The saved endpoint could not be reached.
     *
     * A 401 really means the pairing was reset (the device rotates its token), so the session is
     * dropped. 409 means another phone owns the seat and must not be displaced. Any other failure is a
     * network problem: the device may simply have been switched to the network we pushed, so if the
     * phone is on that network look for it on the LAN before giving up — and never wipe the token
     * because of a timeout.
     */
    private fun recoverAfterUnreachable(error: Throwable) {
        if (error is SessionExpiredException) {
            clearSession(Failure.TOKEN_REJECTED)
            return
        }
        if (error is DeviceOccupiedException) {
            publish(Snapshot(State.OCCUPIED, Failure.OCCUPIED, snapshot.link))
            return
        }
        if (SessionStore.pendingLanSsid.isNotEmpty()) {
            awaitDeviceOnLan()
        } else {
            publish(Snapshot(State.FAILED, Failure.UNREACHABLE, snapshot.link))
        }
    }

    // ------------------------------------------------------------------
    // Foreground / background (PRD NW-5)
    // ------------------------------------------------------------------

    fun onForeground() {
        backgroundJob?.cancel()
        backgroundJob = null
        foreground = true
        joinAttempted = false
        establish()
        startHeartbeat()
        startRecheck()
    }

    fun onBackground() {
        foreground = false
        stopRecheck()
        if (backgroundJob?.isActive == true) return
        backgroundJob = scope.launch {
            // Grace covers the frames the user does not perceive as "leaving" (system pickers and
            // dialogs are covered by AppVisibility.keepAlive instead; this is the real background)
            delay(BACKGROUND_GRACE_MS)
            stopHeartbeat()
        }
    }

    /** A network became usable again (Wi-Fi rejoined, mobile data back): rebuild right away */
    fun onNetworkAvailable() {
        if (!foreground) return
        // A new network is a genuinely new opportunity: the one hotspot join per foreground may run
        joinAttempted = false
        establish()
    }

    /**
     * Rebuild the connection without user action (PRD 16.2 NW-15 / NW-16, architecture 14.2).
     *
     * Ordered by how much it disturbs the user, because the frame is reachable in three different
     * ways and only the last one needs a system dialog:
     * 1. the hotspot we are already on (bind the process, use its gateway);
     * 2. LAN discovery — a frame that rebooted may hold a different DHCP address, so the saved
     *    endpoint is worthless and the pairing token is the only identity that matters;
     * 3. one system-confirmed hotspot join per foreground visit.
     *
     * @param quiet used by the periodic recheck: it must not flash "connecting" on an idle screen
     */
    fun establish(quiet: Boolean = false) {
        if (pairingInProgress) return
        if (snapshot.isBusy) return
        // A join is being prepared: a second attempt (or a release) would make the system reject it
        if (joinInFlight) return
        // Another phone is driving the frame; only a scan may take the seat back (NW-18)
        if (snapshot.state == State.OCCUPIED) return
        // The pairing token was rejected: retrying silently can only fail again, the user has to scan
        if (snapshot.state == State.NEED_BIND) return
        if (!SessionStore.hasTarget) {
            // Signed out / pairing reset: there is no identity left to recover with
            if (snapshot.state != State.IDLE) publish(Snapshot(State.IDLE))
            return
        }
        // A switch is still pending: the device has left the hotspot, so keep looking on the LAN
        if (SessionStore.pendingLanSsid.isNotEmpty()) {
            awaitDeviceOnLan()
            return
        }
        // A link younger than one heartbeat round trip is trusted; an older one is re-verified
        if (snapshot.state == State.CONNECTED &&
            SystemClock.elapsedRealtime() - lastOkAtMs < LINK_STALE_MS
        ) {
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastEstablishAtMs < ESTABLISH_THROTTLE_MS) return
        lastEstablishAtMs = now

        val gen = ++generation
        cancelReconnect()
        if (!quiet) publish(Snapshot(State.SEARCHING, link = Link.LAN))
        scope.launch {
            val ap = SessionStore.apEndpoint()
            // 1. Still on the frame hotspot: cheapest and completely silent
            if (ap != null && SessionStore.lastLink == SessionStore.LINK_AP && isOnWifi(ap.apSsid)) {
                val host = hotspotConnector.bindToCurrentWifi(ap.apSsid) ?: ap.host
                authenticate(ap.copy(host = host), gen, false, AppConfig.AUTH_INTENT_RECOVER, Link.AP)
                return@launch
            }
            // Discovery must search the LAN, so drop a hotspot binding that points at another network
            if (hotspotConnector.isConnected) hotspotConnector.release()
            // 2. LAN discovery (mDNS, then the local /24): no dialog, survives an address change
            val found = locator.findLanEndpoint(SessionStore.lastDeviceToken, DISCOVER_TIMEOUT_MS)
            if (gen != generation) return@launch
            if (found != null) {
                authenticate(found, gen, false, AppConfig.AUTH_INTENT_RECOVER, Link.LAN)
                return@launch
            }
            if (ap == null) {
                if (!quiet) publish(Snapshot(State.FAILED, Failure.NO_TARGET))
                return@launch
            }
            // 3. On the hotspot SSID but not bound to it: bind, still silent
            if (isOnWifi(ap.apSsid)) {
                val host = hotspotConnector.bindToCurrentWifi(ap.apSsid) ?: ap.host
                authenticate(ap.copy(host = host), gen, false, AppConfig.AUTH_INTENT_RECOVER, Link.AP)
                return@launch
            }
            // 4. Ask the system to join, at most once per foreground visit. Only when the hotspot is
            // actually broadcasting: a LAN-only frame has none, and the dialog would be pure noise.
            val visible = hotspotConnector.isSsidVisible(ap.apSsid)
            if (joinAttempted || !visible) {
                if (!visible) Log.i(TAG, "hotspot ${ap.apSsid} not in the scan results, skip the join")
                if (!quiet) publish(Snapshot(State.FAILED, Failure.AP_NOT_FOUND))
                return@launch
            }
            joinAttempted = true
            joinAp(ap, gen, AppConfig.AUTH_INTENT_RECOVER)
        }
    }

    /**
     * Slow recheck while the app stays in the foreground.
     *
     * Discovery is free to retry — it never shows a system dialog — so a frame that comes back a
     * minute after the phone (reboot, router rejoin) is picked up without the user doing anything.
     */
    private fun startRecheck() {
        if (recheckJob?.isActive == true) return
        recheckJob = scope.launch {
            while (isActive) {
                delay(RECHECK_INTERVAL_MS)
                if (!foreground) continue
                when (snapshot.state) {
                    State.FAILED, State.IDLE, State.DEGRADED -> establish(quiet = true)
                    else -> Unit
                }
            }
        }
    }

    private fun stopRecheck() {
        recheckJob?.cancel()
        recheckJob = null
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun joinAp(
        endpoint: DeviceEndpoint,
        gen: Int,
        intent: String = AppConfig.AUTH_INTENT_BIND
    ) {
        joinInFlight = true
        publish(Snapshot(State.JOINING_AP, link = Link.AP))
        connectHotspot(endpoint, gen, intent)
    }

    /** Join the hotspot; one request only, so the system dialog is never cancelled by a retry */
    private fun connectHotspot(endpoint: DeviceEndpoint, gen: Int, intent: String) {
        hotspotConnector.join(endpoint.apSsid, endpoint.apPassword) { result ->
            joinInFlight = false
            if (gen != generation) return@join
            result.onSuccess {
                val host = hotspotConnector.boundGateway() ?: endpoint.host
                val joined = endpoint.copy(host = host)
                SessionStore.rememberApCredentials(joined)
                authenticate(joined, gen, sendStudioToken = false, intent = intent, link = Link.AP)
            }.onFailure { error ->
                publish(
                    Snapshot(
                        State.FAILED,
                        failure = if (error is UnsupportedOperationException) Failure.AP_PERMISSION else Failure.AP_NOT_FOUND,
                        link = Link.NONE,
                        error = error.message
                    )
                )
            }
        }
    }

    /**
     * Authenticate the device found during a switch, but only accept it once the device itself says
     * it is on the LAN.
     *
     * @return true when the session was adopted (caller can stop looking); false means the device was
     *         reachable but not settled yet, so the caller keeps scanning
     */
    private suspend fun authenticateForSwitch(endpoint: DeviceEndpoint, gen: Int): Boolean {
        val result = client.auth(endpoint, "").getOrNull() ?: return false
        if (gen != generation) return false
        val status = client.networkStatus(endpoint, result.sessionId).getOrNull()
        if (status == null || status.link != "lan") {
            Log.i(TAG, "switch: device reachable but not on the LAN yet (link=${status?.link})")
            return false
        }
        applyAuth(endpoint, result, announceStudioFolder = false, link = Link.LAN)
        return true
    }

    /**
     * Exchange the pairing token for a session.
     *
     * @param intent [AppConfig.AUTH_INTENT_BIND] only when the user scanned the code; a silent
     *               recovery must never displace another phone (PRD 16.2 NW-18)
     * @param link   the transport this attempt actually uses; the device cannot tell us, and deriving
     *               it from the endpoint's `apSsid` is what used to mislabel a LAN session as AP
     */
    private fun authenticate(
        endpoint: DeviceEndpoint,
        gen: Int,
        sendStudioToken: Boolean,
        intent: String = AppConfig.AUTH_INTENT_BIND,
        link: Link = linkOf(endpoint)
    ) {
        publish(Snapshot(State.AUTHENTICATING, link = link))
        scope.launch {
            val result = client.auth(
                endpoint,
                if (sendStudioToken) studioTokenFor(endpoint) else "",
                intent,
                SessionStore.clientId
            )
            if (gen != generation) return@launch
            result.onSuccess {
                applyAuth(endpoint, it, announceStudioFolder = intent == AppConfig.AUTH_INTENT_BIND, link = link)
            }.onFailure { error ->
                when (error) {
                    is DeviceOccupiedException ->
                        publish(Snapshot(State.OCCUPIED, Failure.OCCUPIED, link))
                    is SessionExpiredException -> clearSession(Failure.TOKEN_REJECTED)
                    else -> publish(Snapshot(State.FAILED, Failure.AUTH_FAILED, link, error.message))
                }
            }
        }
    }

    private suspend fun applyAuth(
        endpoint: DeviceEndpoint,
        result: AuthResult,
        announceStudioFolder: Boolean,
        link: Link
    ) {
        // The device owns the interaction mode, so its answer wins over the mode recorded from an
        // older QR code: reconnecting then heals without a new scan
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
            if (result.expiresInMs > 0) result.expiresInMs else DEFAULT_SESSION_TTL_MS,
            result.role,
            result.folder,
            result.params,
            result.deviceVersion
        )
        // The link is what this attempt actually used, not what the endpoint happens to carry: a LAN
        // discovered endpoint keeps the QR's apSsid when a session was injected or restored, and reading
        // that as "AP" sent later recoveries down the hotspot path (the 2026-10-24 dead end).
        SessionStore.setLastLink(
            if (link == Link.AP) SessionStore.LINK_AP else SessionStore.LINK_LAN
        )
        if (SessionStore.lastLink == SessionStore.LINK_LAN) {
            // The switch completed: nothing is pending any more
            SessionStore.setPendingLanSsid("")
            switchInFlight = false
        }
        Log.i(
            TAG,
            "auth ok: ${effective.baseUrl}, session=${result.sessionId}, role=${result.role}, " +
                "mode=${result.mode}, link=${SessionStore.lastLink}"
        )
        client.heartbeat(effective, result.sessionId).onSuccess { heartbeat ->
            SessionStore.touch(heartbeat.expiresInMs)
            SessionStore.updateParams(heartbeat.params)
        }
        publish(Snapshot(State.CONNECTED, link = link))
        linkSettledAtMs = SystemClock.elapsedRealtime()
        lastOkAtMs = SystemClock.elapsedRealtime()
        startHeartbeat()
        listeners.forEach { it.onSessionReady() }
        if (announceStudioFolder && SessionStore.inStudioMode) {
            listeners.forEach { it.onStudioFolderReady(result.folder) }
        }
    }

    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope.launch {
            while (isActive) {
                val endpoint = SessionStore.endpoint
                val sessionId = SessionStore.sessionId
                if (endpoint != null && sessionId != null) {
                    val result = client.heartbeat(endpoint, sessionId)
                    result.onSuccess {
                        SessionStore.touch(it.expiresInMs)
                        SessionStore.updateParams(it.params)
                        lastOkAtMs = SystemClock.elapsedRealtime()
                    }.onFailure { error ->
                        if (error is SessionExpiredException) {
                            onUnauthorized()
                        } else if (snapshot.state == State.CONNECTED) {
                            val settling = SystemClock.elapsedRealtime() - linkSettledAtMs < LINK_SETTLE_MS
                            if (settling) {
                                Log.i(TAG, "heartbeat failed while the new link is settling, retrying")
                            } else {
                                handleUnreachable()
                            }
                        }
                    }
                }
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    /** A request failed while the session is alive: retry the same link for a short window */
    /**
     * A request failed while a session is still believed to exist.
     *
     * The switch case is handled first: a successful `/wifi-config` means the device is moving to the
     * network the phone pushed, so the hotspot link is expected to die. Everything else degrades and
     * is then handed back to [establish], which can discover the device on a new address — retrying
     * the saved endpoint alone could never recover from a device reboot (DHCP change).
     */
    private fun handleUnreachable() {
        if (switchInFlight) return
        if (SessionStore.pendingLanSsid.isNotEmpty()) {
            awaitDeviceOnLan()
            return
        }
        markDegraded()
    }

    private fun markDegraded() {
        publish(Snapshot(State.DEGRADED, Failure.UNREACHABLE, snapshot.link))
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            val deadline = System.currentTimeMillis() + AUTO_RECONNECT_WINDOW_MS
            while (System.currentTimeMillis() < deadline && isActive) {
                delay(AUTO_RECONNECT_RETRY_MS)
                val endpoint = SessionStore.endpoint ?: break
                val sessionId = SessionStore.sessionId ?: break
                val result = client.heartbeat(endpoint, sessionId)
                if (result.isSuccess) {
                    result.getOrNull()?.let {
                        SessionStore.touch(it.expiresInMs)
                        SessionStore.updateParams(it.params)
                    }
                    lastOkAtMs = SystemClock.elapsedRealtime()
                    publish(Snapshot(State.CONNECTED, link = snapshot.link))
                    return@launch
                }
            }
            if (snapshot.state == State.DEGRADED) {
                publish(Snapshot(State.FAILED, Failure.UNREACHABLE, snapshot.link))
            }
        }
    }

    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        waitTargetJob?.cancel()
        waitTargetJob = null
        joinInFlight = false
    }

    private fun clearSession(failure: Failure) {
        stopHeartbeat()
        joinInFlight = false
        hotspotConnector.release()
        SessionStore.clear()
        publish(
            Snapshot(
                if (failure == Failure.TOKEN_REJECTED) State.NEED_BIND else State.IDLE,
                failure
            )
        )
        listeners.forEach { it.onSessionGone() }
    }

    private fun publish(next: Snapshot) {
        val previous = snapshot
        snapshot = next
        if (previous.state != next.state || previous.failure != next.failure) {
            Log.i(
                TAG,
                "state: ${previous.state} -> ${next.state} (failure=${next.failure}, link=${next.link})"
            )
        }
        mainHandler.post { listeners.forEach { it.onConnectionChanged(next) } }
    }

    private fun studioTokenFor(endpoint: DeviceEndpoint): String =
        if (endpoint.mode == DeviceEndpoint.MODE_STUDIO) newStudioToken() else ""

    /** This selection session's own token: a UUID prefix matching the device-side length limit */
    private fun newStudioToken(): String =
        UUID.randomUUID().toString().replace("-", "").take(STUDIO_TOKEN_LENGTH)

    private fun linkOf(endpoint: DeviceEndpoint): Link =
        if (endpoint.hasHotspot) Link.AP else Link.LAN

    /** Whether the phone is already on [ssid]; asking the system for a network we are on pops a sheet */
    fun isOnWifi(ssid: String): Boolean = ssid.isNotEmpty() && currentSsid() == ssid

    /** Current Wi-Fi name, or an empty string when not on Wi-Fi / not readable */
    fun currentSsid(): String {
        val manager = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val raw = runCatching { manager?.connectionInfo?.ssid }.getOrNull() ?: return ""
        return raw.trim('"').takeIf { it.isNotEmpty() && it != UNKNOWN_SSID }.orEmpty()
    }

    companion object {
        private const val TAG = "ConnectionManager"

        /** Heartbeat period; the device ends the session after 60 s without one */
        const val HEARTBEAT_INTERVAL_MS = 5_000L

        /** How long a failed request keeps retrying before the screen reports the device unreachable */
        const val AUTO_RECONNECT_WINDOW_MS = 30_000L
        const val AUTO_RECONNECT_RETRY_MS = 5_000L

        /** One LAN discovery pass */
        const val DISCOVER_TIMEOUT_MS = 5_000L

        /** How long the phone waits for the device to appear on the LAN after a Wi-Fi push */
        const val LAN_SWITCH_TIMEOUT_MS = 48_000L

        /** Total window for the post-switch discovery retries (device and phone switch separately) */
        private const val POST_SWITCH_WINDOW_MS = 120_000L

        /** A newly adopted link is not declared unstable for this long */
        private const val LINK_SETTLE_MS = 15_000L

        /**
         * A "connected" state older than this is re-verified before it is trusted again: one heartbeat
         * interval plus its whole-call timeout, so a link that stopped working in the background is
         * never shown as healthy on the next foreground.
         */
        private const val LINK_STALE_MS = HEARTBEAT_INTERVAL_MS + 8_000L

        /** Minimum gap between two [establish] runs (network callbacks arrive in bursts) */
        private const val ESTABLISH_THROTTLE_MS = 3_000L

        /** Slow foreground recheck: discovery only, never a system dialog (PRD 16.2 NW-16) */
        private const val RECHECK_INTERVAL_MS = 30_000L

        /** Delay before retrying a failed hotspot join once */
        private const val AP_RETRY_DELAY_MS = 1_500L

        /** Pause between two LAN discovery rounds while waiting for the device to appear */
        private const val POST_SWITCH_RETRY_MS = 2_000L

        /** Background grace before heartbeats stop; the device then times the session out (PRD 16.5) */
        const val BACKGROUND_GRACE_MS = 60_000L

        /** Fallback session lifetime when the device does not report `expires_in_ms` */
        const val DEFAULT_SESSION_TTL_MS = 12 * 60 * 60 * 1000L

        /** Store session token length, matching DeviceConfig.STUDIO_TOKEN_MAX_LENGTH */
        const val STUDIO_TOKEN_LENGTH = 6

        /** Placeholder the system returns when the Wi-Fi name cannot be read */
        const val UNKNOWN_SSID = "<unknown ssid>"

        @Volatile
        private var instance: ConnectionManager? = null

        /**
         * Process-wide singleton: the session, the hotspot binding and the state machine are
         * process state, so two Activity instances must see the same one.
         */
        fun get(context: Context): ConnectionManager =
            instance ?: synchronized(this) {
                instance ?: ConnectionManager(context.applicationContext).also { instance = it }
            }
    }
}
