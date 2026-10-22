package com.ShowSeen.lightshadowart.mobile.net

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
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
        UNREACHABLE
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
        cancelReconnect()
        if (endpoint.hasHotspot && !isOnWifi(endpoint.apSsid)) {
            publish(Snapshot(State.JOINING_AP, link = Link.AP))
            hotspotConnector.connect(endpoint.apSsid, endpoint.apPassword) { result ->
                if (gen == generation) {
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
            if (endpoint.hasHotspot) {
                SessionStore.rememberWifiSsid(endpoint.apSsid)
                SessionStore.rememberApCredentials(endpoint)
            }
            authenticate(endpoint, gen, sendStudioToken = true)
        }
    }

    /**
     * User pressed "reconnect": retry the last link only (PRD NW-5). Without a recorded link, use the
     * saved endpoint once, then fall back to LAN discovery.
     */
    fun reconnect() {
        val gen = ++generation
        cancelReconnect()
        when (SessionStore.lastLink) {
            SessionStore.LINK_AP -> {
                val ap = SessionStore.apEndpoint()
                if (ap == null) {
                    publish(Snapshot(State.FAILED, Failure.NO_TARGET))
                    return
                }
                if (isOnWifi(ap.apSsid)) {
                    val host = hotspotConnector.boundGateway() ?: ap.host
                    authenticate(ap.copy(host = host), gen, sendStudioToken = false)
                } else {
                    joinAp(ap, gen)
                }
            }

            SessionStore.LINK_LAN -> discoverLan(gen)

            else -> {
                val endpoint = SessionStore.endpoint
                if (endpoint != null) authenticate(endpoint, gen, sendStudioToken = false) else publish(Snapshot(State.FAILED, Failure.NO_TARGET))
            }
        }
    }

    /**
     * Push the phone's current Wi-Fi to the device and wait for it on the LAN (PRD NW-4).
     *
     * The caller has already made sure the phone is on [ssid]; this only provisions the device and
     * switches the session over. A failure is reported as [Failure.LAN_SWITCH_TIMEOUT] — the device
     * rolls back to its hotspot in that case, and the screen offers a retry.
     */
    fun switchToLan(ssid: String, password: String) {
        val endpoint = SessionStore.endpoint
        val sessionId = SessionStore.sessionId
        if (endpoint == null || sessionId == null) {
            publish(Snapshot(State.FAILED, Failure.NO_TARGET))
            return
        }
        val gen = ++generation
        cancelReconnect()
        publish(Snapshot(State.SWITCHING, link = Link.AP))
        scope.launch {
            val accepted = client.wifiConfig(endpoint, sessionId, ssid, password)
            if (gen != generation) return@launch
            accepted.onFailure { error ->
                if (error is SessionExpiredException) onUnauthorized() else publish(
                    Snapshot(State.FAILED, Failure.AUTH_FAILED, Link.AP, error.message)
                )
            }
            if (accepted.isFailure) return@launch

            val found = locator.findLanEndpoint(SessionStore.lastDeviceToken, LAN_SWITCH_TIMEOUT_MS)
            if (gen != generation) return@launch
            if (found == null) {
                publish(Snapshot(State.FAILED, Failure.LAN_SWITCH_TIMEOUT, Link.NONE))
                return@launch
            }
            SessionStore.rememberWifiSsid(ssid)
            authenticate(found, gen, sendStudioToken = false)
        }
    }

    /** End the session; [handover] also makes the device unbind and show a fresh QR code */
    fun disconnect(handover: Boolean) {
        val endpoint = SessionStore.endpoint
        val sessionId = SessionStore.sessionId
        val gen = ++generation
        cancelReconnect()
        publish(Snapshot(State.DISCONNECTING))
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
            if (gen != generation) return@launch
            stopHeartbeat()
            hotspotConnector.release()
            SessionStore.clear()
            publish(Snapshot(State.IDLE))
            listeners.forEach { it.onSessionGone() }
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
        scope.launch {
            val result = client.auth(endpoint, "")
            reauthenticating = false
            if (gen != generation) return@launch
            result.onSuccess { applyAuth(endpoint, it, announceStudioFolder = false) }
                .onFailure { clearSession(Failure.TOKEN_REJECTED) }
        }
    }

    // ------------------------------------------------------------------
    // Foreground / background (PRD NW-5)
    // ------------------------------------------------------------------

    fun onForeground() {
        backgroundJob?.cancel()
        backgroundJob = null
        restoreSessionIfNeeded()
        startHeartbeat()
    }

    fun onBackground() {
        if (backgroundJob?.isActive == true) return
        backgroundJob = scope.launch {
            // Grace covers the frames the user does not perceive as "leaving" (system pickers and
            // dialogs are covered by AppVisibility.keepAlive instead; this is the real background)
            delay(BACKGROUND_GRACE_MS)
            stopHeartbeat()
        }
    }

    /**
     * Resume the persisted session without user action (PRD NW-6): a session saved across a process
     * restart is reused when it has not expired; otherwise the last link is retried silently.
     */
    private fun restoreSessionIfNeeded() {
        if (snapshot.isBusy || snapshot.state == State.CONNECTED) return
        if (SessionStore.isActive) {
            // A session survived a process restart: show it as connected and let the heartbeat prove
            // reachability (a failure moves it to DEGRADED, then FAILED)
            publish(Snapshot(State.CONNECTED, link = linkFromLastSession()))
            startHeartbeat()
            return
        }
        if (SessionStore.lastDeviceToken.isEmpty()) return
        val endpoint = SessionStore.endpoint
        if (endpoint != null) reauthenticateSaved(endpoint)
    }

    private fun linkFromLastSession(): Link = when (SessionStore.lastLink) {
        SessionStore.LINK_AP -> Link.AP
        SessionStore.LINK_LAN -> Link.LAN
        else -> Link.NONE
    }

    private fun reauthenticateSaved(endpoint: DeviceEndpoint?) {
        val target = endpoint ?: SessionStore.apEndpoint() ?: return
        val gen = ++generation
        publish(Snapshot(State.SEARCHING, link = linkOf(target)))
        scope.launch {
            val result = client.auth(target, "")
            if (gen != generation) return@launch
            result.onSuccess { applyAuth(target, it, announceStudioFolder = false) }
                .onFailure {
                    // No session yet: keep the page's "not connected" state and let the user reconnect
                    publish(Snapshot(State.FAILED, Failure.UNREACHABLE))
                }
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun joinAp(endpoint: DeviceEndpoint, gen: Int) {
        publish(Snapshot(State.JOINING_AP, link = Link.AP))
        hotspotConnector.connect(endpoint.apSsid, endpoint.apPassword) { result ->
            if (gen == generation) {
                result.onSuccess {
                    val host = hotspotConnector.boundGateway() ?: endpoint.host
                    val joined = endpoint.copy(host = host)
                    SessionStore.rememberApCredentials(joined)
                    authenticate(joined, gen, sendStudioToken = false)
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
    }

    private fun discoverLan(gen: Int) {
        publish(Snapshot(State.SEARCHING, link = Link.LAN))
        scope.launch {
            // The hotspot request may still be binding this process; drop it so discovery runs on the LAN
            hotspotConnector.release()
            val found = locator.findLanEndpoint(SessionStore.lastDeviceToken, DISCOVER_TIMEOUT_MS)
            if (gen != generation) return@launch
            if (found == null) {
                publish(Snapshot(State.FAILED, Failure.LAN_NOT_FOUND))
            } else {
                authenticate(found, gen, sendStudioToken = false)
            }
        }
    }

    private fun authenticate(endpoint: DeviceEndpoint, gen: Int, sendStudioToken: Boolean) {
        publish(Snapshot(State.AUTHENTICATING, link = linkOf(endpoint)))
        scope.launch {
            val result = client.auth(endpoint, if (sendStudioToken) studioTokenFor(endpoint) else "" )
            if (gen != generation) return@launch
            result.onSuccess { applyAuth(endpoint, it, announceStudioFolder = true) }
                .onFailure { error ->
                    if (error is SessionExpiredException) {
                        clearSession(Failure.TOKEN_REJECTED)
                    } else {
                        publish(Snapshot(State.FAILED, Failure.AUTH_FAILED, Link.NONE, error.message))
                    }
                }
        }
    }

    private suspend fun applyAuth(
        endpoint: DeviceEndpoint,
        result: AuthResult,
        announceStudioFolder: Boolean
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
        SessionStore.setLastLink(if (effective.hasHotspot) SessionStore.LINK_AP else SessionStore.LINK_LAN)
        Log.i(
            TAG,
            "auth ok: ${effective.baseUrl}, session=${result.sessionId}, role=${result.role}, " +
                "mode=${result.mode}, link=${SessionStore.lastLink}"
        )
        client.heartbeat(effective, result.sessionId).onSuccess { heartbeat ->
            SessionStore.touch(heartbeat.expiresInMs)
            SessionStore.updateParams(heartbeat.params)
        }
        publish(Snapshot(State.CONNECTED, link = linkOf(effective)))
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
                    }.onFailure { error ->
                        if (error is SessionExpiredException) {
                            onUnauthorized()
                        } else if (snapshot.state == State.CONNECTED) {
                            markDegraded()
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
    }

    private fun clearSession(failure: Failure) {
        stopHeartbeat()
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
