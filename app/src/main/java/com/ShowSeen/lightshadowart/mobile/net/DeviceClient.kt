package com.ShowSeen.lightshadowart.mobile.net

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.ShowSeen.lightshadowart.mobile.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/** The endpoint returned non-2xx, or the body code is not 200 */
open class ApiException(val code: Int, message: String) : IOException(message)

/** Device returned 401: the session is missing, expired, or was force-ended by staff */
class SessionExpiredException(message: String) : ApiException(401, message)

/** Device returned 413: a single file exceeds the 4G limit and was rejected (req 4.5 / T-4) */
class FileTooLargeException(message: String) : ApiException(413, message)

/**
 * Device returned 409 `occupied`: another phone is driving the frame, so a silent recovery must not
 * take the seat from it (PRD 16.2 NW-18). The session on the device is untouched.
 */
class DeviceOccupiedException(message: String) : ApiException(409, message)

/** Local media waiting to be uploaded */
data class UploadSource(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    /** -1 when unknown, in which case upload progress cannot be computed */
    val sizeBytes: Long
)

/** Device receipt after a successful upload */
data class UploadResult(val filePath: String, val mediaType: String)

/**
 * Configurable device run parameters (req 5.2.2 / 6.3).
 *
 * All units are milliseconds; the UI shows seconds / minutes and converts on read and write.
 */
data class DeviceParams(
    val heartbeatIntervalMs: Long,
    val sessionTimeoutMs: Long,
    val noOpCountdownMs: Long,
    val autoPlayIntervalMs: Long
) {
    companion object {
        /** Defaults come from the mobile config AppConfig: heartbeat 5 s, session timeout 10 min, idle countdown 60 s, auto-play 3 min */
        val DEFAULT = DeviceParams(
            heartbeatIntervalMs = AppConfig.DEFAULT_HEARTBEAT_INTERVAL_MS,
            sessionTimeoutMs = AppConfig.DEFAULT_SESSION_TIMEOUT_MS,
            noOpCountdownMs = AppConfig.DEFAULT_NO_OP_COUNTDOWN_MS,
            autoPlayIntervalMs = AppConfig.DEFAULT_AUTO_PLAY_INTERVAL_MS
        )
    }
}

/** POST /auth receipt: session + role + authorised folder + interaction mode + run params + device version */
data class AuthResult(
    val sessionId: String,
    val role: String,
    val folder: String,
    val mode: Int,
    /** Session lifetime reported by the device; 0 when the device did not send it */
    val expiresInMs: Long,
    val params: DeviceParams,
    /** Device app version; empty string when the device does not report it */
    val deviceVersion: String
) {
    /** Admins can configure params and hand over control; selection users are read-only */
    val isAdmin: Boolean get() = role.equals(DeviceEndpoint.ROLE_ADMIN, ignoreCase = true)
}

/** GET /storage receipt: device free space and the low-storage verdict (req 4.5 / T-3) */
data class StorageState(
    /** Remaining free bytes; negative when the device cannot probe them */
    val freeBytes: Long,
    /** Low-storage flag as judged by the device (free < 200MB) */
    val low: Boolean
)

/** GET /network receipt: which link the device is on and its address (PRD 16.7) */
data class DeviceNetworkStatus(
    val link: String,
    val ip: String,
    val ssid: String,
    val port: Int,
    val staState: String
)

/** POST /heartbeat receipt: remaining lifetime + session warning + latest params */
data class HeartbeatResult(
    val expiresInMs: Long,
    val warning: Boolean,
    val params: DeviceParams
)

/** A media entry returned by GET /media */
data class MediaItem(
    val path: String,
    val name: String,
    val type: String,
    val size: Long,
    val lastModified: Long,
    /** Grouping key for the date view (yyyy-MM-dd) */
    val date: String,
    val favourite: Boolean
) {
    val isVideo: Boolean get() = type.equals("video", ignoreCase = true)
}

/**
 * Device HTTP client (plain HTTP + Bearer session).
 *
 * Mirrors the device-side `ApiRoutes.kt`:
 * auth / upload / control / wifi-config / heartbeat / session-end /
 * config / studio-folder / studio-confirm / media / media-raw / media-delete / favourite /
 * session-handover.
 */
class DeviceClient(private val resolver: ContentResolver) {

    // -----------------------------------------------------------------------
    // Endpoints
    // -----------------------------------------------------------------------

    /**
     * POST /api/v1/auth —— exchange the device_token from the QR code for a session_id.
     *
     * Also returns the role / folder / mode / params decided by the device:
     * the first device to bind is the admin, everyone else is a selection user (req 4.2.2 / 7).
     *
     * @param studioToken token unique to this session in studio selection mode (req 3.4 / T-12):
     *                    the device builds a `date+serial+token` folder from it and returns that
     *                    folder in the receipt. Pass an empty string in home mode.
     * @param intent      [AppConfig.AUTH_INTENT_BIND] when the user scanned the code, otherwise
     *                    [AppConfig.AUTH_INTENT_RECOVER]. The device refuses a recovery that would
     *                    displace the phone currently driving the frame (409 → [DeviceOccupiedException]).
     * @param clientId    this phone's stable identity, so the device can tell "my own recovery" from
     *                    "another phone wants the seat".
     */
    suspend fun auth(
        endpoint: DeviceEndpoint,
        studioToken: String = "",
        intent: String = AppConfig.AUTH_INTENT_BIND,
        clientId: String = ""
    ): Result<AuthResult> = call {
        val payload = JSONObject()
            .put("device_token", endpoint.deviceToken)
            .put("intent", intent)
        // Only a scan declares the admin role from the QR code; a silent recovery must never look like
        // a takeover to the device (that was how a returning phone kicked the current one off)
        if (intent == AppConfig.AUTH_INTENT_BIND && endpoint.role.isNotEmpty()) {
            payload.put("role", endpoint.role)
        }
        if (clientId.isNotEmpty()) payload.put("device_id", clientId)
        if (studioToken.isNotEmpty()) payload.put("studio_token", studioToken)
        val body = jsonBody(payload)
        val request = Request.Builder()
            .url(endpoint.urlOf("/auth"))
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val json = response.jsonOrNull()
            ensureSuccess(response, json)
            val sessionId = json?.optString("session_id").orEmpty()
            if (sessionId.isEmpty()) {
                throw ApiException(response.code, "设备端未返回 session_id")
            }
            AuthResult(
                sessionId = sessionId,
                role = json?.optString("role").orEmpty(),
                folder = json?.optString("folder").orEmpty(),
                mode = json?.optInt("mode", endpoint.mode) ?: endpoint.mode,
                expiresInMs = json?.optLong("expires_in_ms") ?: 0L,
                params = json?.optJSONObject("params").toParams(),
                deviceVersion = json?.optString("device_version").orEmpty()
            )
        }
    }

    /**
     * POST /api/v1/upload —— streaming multipart upload that sends while reading and reports progress
     * percentage. The callback runs on the IO thread and is never invoked when the total length is unknown.
     */
    suspend fun upload(
        endpoint: DeviceEndpoint,
        sessionId: String,
        source: UploadSource,
        onProgress: (Int) -> Unit = {}
    ): Result<UploadResult> = call {
        val filePart = FilePartBody(resolver, source, onProgress)
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", source.displayName, filePart)
            .build()

        val request = Request.Builder()
            .url(endpoint.urlOf("/upload"))
            .header(AUTH_HEADER, bearer(sessionId))
            .post(multipart)
            .build()

        client.newCall(request).execute().use { response ->
            val json = response.jsonOrNull()
            ensureSuccess(response, json)
            UploadResult(
                filePath = json?.optString("file_path").orEmpty(),
                mediaType = json?.optString("media_type").orEmpty()
            )
        }
    }

    /** POST /api/v1/wifi-config —— push SSID and password so the device joins the LAN */
    suspend fun wifiConfig(
        endpoint: DeviceEndpoint,
        sessionId: String,
        ssid: String,
        password: String
    ): Result<Unit> = call {
        val body = jsonBody(
            JSONObject()
                .put("ssid", ssid)
                .put("password", password)
        )
        val request = Request.Builder()
            .url(endpoint.urlOf("/wifi-config"))
            .header(AUTH_HEADER, bearer(sessionId))
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            ensureSuccess(response, response.jsonOrNull())
        }
    }

    /**
     * POST /api/v1/heartbeat —— returns the session's remaining lifetime in ms and the latest params.
     *
     * Heartbeat gets its own short timeout: the default read timeout is 60 s, and silent packet loss
     * (typical when the device reboots or the hotspot disappears) reports no error until the timeout,
     * so the request hangs — which makes "auto-reconnect within 30 s" (T-5) impossible.
     * On a real device, iptables dropping packets for 14 s left the app with no reaction at all;
     * these 60 s were the cause.
     */
    suspend fun heartbeat(endpoint: DeviceEndpoint, sessionId: String): Result<HeartbeatResult> =
        call {
            val request = Request.Builder()
                .url(endpoint.urlOf("/heartbeat"))
                .header(AUTH_HEADER, bearer(sessionId))
                .post(EMPTY_BODY)
                .build()

            val call = client.newCall(request)
            call.timeout().timeout(HEARTBEAT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            call.execute().use { response ->
                val json = response.jsonOrNull()
                ensureSuccess(response, json)
                HeartbeatResult(
                    expiresInMs = json?.optLong("expires_in_ms") ?: 0L,
                    warning = json?.optBoolean("warning", false) ?: false,
                    params = json?.optJSONObject("params").toParams()
                )
            }
        }

    /** POST /api/v1/session/end —— end the session; the device idle screen goes back to the QR state */
    suspend fun endSession(endpoint: DeviceEndpoint, sessionId: String): Result<Unit> = call {
        val request = Request.Builder()
            .url(endpoint.urlOf("/session/end"))
            .header(AUTH_HEADER, bearer(sessionId))
            .post(EMPTY_BODY)
            .build()

        client.newCall(request).execute().use { response ->
            ensureSuccess(response, response.jsonOrNull())
        }
    }

    /**
     * POST /api/v1/session/handover —— admin "sign out" means handing over admin rights (req 5.7.2 / T-7).
     *
     * Difference from [endSession]: besides ending the session, the device also **unbinds itself and
     * shows the QR code on its own** so someone else can scan it and take over as admin.
     */
    suspend fun handoverSession(endpoint: DeviceEndpoint, sessionId: String): Result<Unit> = call {
        val request = Request.Builder()
            .url(endpoint.urlOf("/session/handover"))
            .header(AUTH_HEADER, bearer(sessionId))
            .post(EMPTY_BODY)
            .build()

        client.newCall(request).execute().use { response ->
            ensureSuccess(response, response.jsonOrNull())
        }
    }

    /**
     * POST /api/v1/control —— control device playback and rotation (req 3.2.1).
     *
     * action takes the device-side `MtkPlayerBridge` constants: PLAY / PAUSE / RESUME / ROTATE,
     * plus the navigation keys UP / DOWN / LEFT / RIGHT / CONFIRM / EXIT / NEXT / PREV.
     * Rotation may pass `degree` in params.
     */
    suspend fun control(
        endpoint: DeviceEndpoint,
        sessionId: String,
        action: String,
        params: JSONObject? = null
    ): Result<Unit> = call {
        val payload = JSONObject().put("action", action)
        if (params != null) payload.put("params", params)
        val request = Request.Builder()
            .url(endpoint.urlOf("/control"))
            .header(AUTH_HEADER, bearer(sessionId))
            .post(jsonBody(payload))
            .build()

        client.newCall(request).execute().use { response ->
            ensureSuccess(response, response.jsonOrNull())
        }
    }

    /** GET /api/v1/config —— read device run parameters */
    suspend fun readConfig(endpoint: DeviceEndpoint, sessionId: String): Result<DeviceParams> = call {
        val request = Request.Builder()
            .url(endpoint.urlOf("/config"))
            .header(AUTH_HEADER, bearer(sessionId))
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val json = response.jsonOrNull()
            ensureSuccess(response, json)
            json?.optJSONObject("params").toParams()
        }
    }

    /** POST /api/v1/config —— change run parameters; admin only, selection users get 403 */
    suspend fun writeConfig(
        endpoint: DeviceEndpoint,
        sessionId: String,
        params: DeviceParams
    ): Result<DeviceParams> = call {
        val payload = JSONObject()
            .put("heartbeat_interval_ms", params.heartbeatIntervalMs)
            .put("session_timeout_ms", params.sessionTimeoutMs)
            .put("no_op_countdown_ms", params.noOpCountdownMs)
            .put("auto_play_interval_ms", params.autoPlayIntervalMs)
        val request = Request.Builder()
            .url(endpoint.urlOf("/config"))
            .header(AUTH_HEADER, bearer(sessionId))
            .post(jsonBody(payload))
            .build()

        client.newCall(request).execute().use { response ->
            val json = response.jsonOrNull()
            ensureSuccess(response, json)
            json?.optJSONObject("params").toParams()
        }
    }

    /**
     * POST /api/v1/frame/image —— upload a custom background image (req 3.3.2 / T-9, admin only).
     *
     * Reuses the same streaming body as `/upload` (`FilePartBody`) instead of writing a second one;
     * the device keeps the image in its app-private directory, never in the customer photo folder.
     */
    suspend fun uploadFrameImage(
        endpoint: DeviceEndpoint,
        sessionId: String,
        source: UploadSource
    ): Result<Boolean> = call {
        val filePart = FilePartBody(resolver, source) {}
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", source.displayName, filePart)
            .build()

        val request = Request.Builder()
            .url(endpoint.urlOf("/frame/image"))
            .header(AUTH_HEADER, bearer(sessionId))
            .post(multipart)
            .build()

        client.newCall(request).execute().use { response ->
            val json = response.jsonOrNull()
            ensureSuccess(response, json)
            json?.optBoolean("has_background", false) ?: false
        }
    }

    /** GET /api/v1/frame —— read the device frame preset params and the available style list (req 3.3.1 / T-13) */
    suspend fun readFrame(endpoint: DeviceEndpoint, sessionId: String): Result<FrameConfig> = call {
        val request = Request.Builder()
            .url(endpoint.urlOf("/frame"))
            .header(AUTH_HEADER, bearer(sessionId))
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val json = response.jsonOrNull()
            ensureSuccess(response, json)
            json.toFrameConfig()
        }
    }

    /**
     * POST /api/v1/frame —— push frame parameters; admin only (selection users get 403).
     *
     * Only the **non-null** fields of [FramePatch] go into the request body; anything not mentioned
     * keeps the device's current value, and the receipt holds the values actually in effect. The
     * previous version always sent the whole set (every `FrameParams` field has a default), so
     * switching only the style on the Style page also reset the user's line colour / width / paper —
     * with a patch type, "send only what changed" is guaranteed by the type.
     */
    suspend fun writeFrame(
        endpoint: DeviceEndpoint,
        sessionId: String,
        patch: FramePatch
    ): Result<FrameConfig> = call {
        val payload = JSONObject()
        patch.styleId?.let { payload.put("style_id", it) }
        patch.frameEnabled?.let { payload.put("frame_enabled", it) }
        patch.lineColor?.let { payload.put("line_color", it) }
        patch.lineWidthDp?.let { payload.put("line_width_dp", it.toDouble()) }
        patch.paperColor?.let { payload.put("paper_color", it) }
        patch.paperEnabled?.let { payload.put("paper_enabled", it) }
        patch.backgroundImage?.let { payload.put("background_image", it) }
        patch.paddingDp?.let { payload.put("padding_dp", it.toDouble()) }
        patch.borderGapDp?.let { payload.put("border_gap_dp", it.toDouble()) }
        patch.insetDp?.let { payload.put("inset_dp", it.toDouble()) }
        val request = Request.Builder()
            .url(endpoint.urlOf("/frame"))
            .header(AUTH_HEADER, bearer(sessionId))
            .post(jsonBody(payload))
            .build()

        client.newCall(request).execute().use { response ->
            val json = response.jsonOrNull()
            ensureSuccess(response, json)
            json.toFrameConfig()
        }
    }

    /** POST /api/v1/studio/folder —— studio mode: admin creates the next customer folder, returns its name */
    suspend fun createStudioFolder(endpoint: DeviceEndpoint, sessionId: String): Result<String> =
        call {
            val request = Request.Builder()
                .url(endpoint.urlOf("/studio/folder"))
                .header(AUTH_HEADER, bearer(sessionId))
                .post(EMPTY_BODY)
                .build()

            client.newCall(request).execute().use { response ->
                val json = response.jsonOrNull()
                ensureSuccess(response, json)
                json?.optString("folder").orEmpty()
            }
        }

    /**
     * POST /api/v1/studio/confirm —— studio mode: the photographer has put the photos into this
     * session's folder and the user taps "confirm and start selecting"; the device then ends the
     * on-screen prompt and enters the selection UI (req 3.4 / T-12).
     */
    suspend fun confirmStudioSelection(
        endpoint: DeviceEndpoint,
        sessionId: String
    ): Result<String> = call {
        val request = Request.Builder()
            .url(endpoint.urlOf("/studio/confirm"))
            .header(AUTH_HEADER, bearer(sessionId))
            .post(EMPTY_BODY)
            .build()

        client.newCall(request).execute().use { response ->
            val json = response.jsonOrNull()
            ensureSuccess(response, json)
            json?.optString("folder").orEmpty()
        }
    }

    /** GET /api/v1/media —— list media in the authorised folder; favouriteOnly returns only hearted items */
    suspend fun listMedia(
        endpoint: DeviceEndpoint,
        sessionId: String,
        favouriteOnly: Boolean = false
    ): Result<List<MediaItem>> = call {
        val url = endpoint.urlOf("/media").toHttpUrlOrNull()
            ?: throw ApiException(0, "设备地址无效")
        val requestUrl = url.newBuilder()
            .addQueryParameter("view", if (favouriteOnly) VIEW_FAVOURITE else VIEW_ALL)
            .build()
        val request = Request.Builder()
            .url(requestUrl)
            .header(AUTH_HEADER, bearer(sessionId))
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val json = response.jsonOrNull()
            ensureSuccess(response, json)
            json?.optJSONArray("items").toMediaItems()
        }
    }

    /** GET /api/v1/media/raw —— read raw media bytes for preview; returns null on failure */
    suspend fun mediaRaw(
        endpoint: DeviceEndpoint,
        sessionId: String,
        path: String
    ): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val url = endpoint.urlOf("/media/raw").toHttpUrlOrNull() ?: return@runCatching null
            val requestUrl = url.newBuilder().addQueryParameter("path", path).build()
            val request = Request.Builder()
                .url(requestUrl)
                .header(AUTH_HEADER, bearer(sessionId))
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.bytes() else null
            }
        }.getOrNull()
    }

    /** POST /api/v1/media/delete —— multi-select delete in personal mode, returns the count actually deleted */
    suspend fun deleteMedia(
        endpoint: DeviceEndpoint,
        sessionId: String,
        paths: List<String>
    ): Result<Int> = call {
        val array = JSONArray()
        paths.forEach { array.put(it) }
        val request = Request.Builder()
            .url(endpoint.urlOf("/media/delete"))
            .header(AUTH_HEADER, bearer(sessionId))
            .post(jsonBody(JSONObject().put("paths", array)))
            .build()

        client.newCall(request).execute().use { response ->
            val json = response.jsonOrNull()
            ensureSuccess(response, json)
            json?.optInt("deleted") ?: 0
        }
    }

    /** POST /api/v1/favourite —— heart favourite flag, returns the latest state confirmed by the device */
    suspend fun favourite(
        endpoint: DeviceEndpoint,
        sessionId: String,
        path: String,
        favourite: Boolean
    ): Result<Boolean> = call {
        val request = Request.Builder()
            .url(endpoint.urlOf("/favourite"))
            .header(AUTH_HEADER, bearer(sessionId))
            .post(jsonBody(JSONObject().put("path", path).put("favourite", favourite)))
            .build()

        client.newCall(request).execute().use { response ->
            val json = response.jsonOrNull()
            ensureSuccess(response, json)
            json?.optBoolean("favourite", favourite) ?: favourite
        }
    }

    /**
     * POST /api/v1/favourite/export —— sync heart-marked media into a separate Fav folder
     * (ALL_Fav in personal mode, the selection folder + _Fav in studio mode); returns the file count.
     */
    suspend fun exportFavourite(endpoint: DeviceEndpoint, sessionId: String): Result<Int> = call {
        val request = Request.Builder()
            .url(endpoint.urlOf("/favourite/export"))
            .header(AUTH_HEADER, bearer(sessionId))
            .post(EMPTY_BODY)
            .build()

        client.newCall(request).execute().use { response ->
            val json = response.jsonOrNull()
            ensureSuccess(response, json)
            json?.optInt("count") ?: 0
        }
    }

    /**
     * GET /api/v1/storage —— read-only probe of the device's free space (req 4.5 / T-3).
     *
     * The app calls it before uploading and asks the user to free space when `low` is true (free < 200MB).
     */
    suspend fun storage(endpoint: DeviceEndpoint, sessionId: String): Result<StorageState> = call {
        val request = Request.Builder()
            .url(endpoint.urlOf("/storage"))
            .header(AUTH_HEADER, bearer(sessionId))
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val json = response.jsonOrNull()
            ensureSuccess(response, json)
            StorageState(
                freeBytes = json?.optLong("free_bytes") ?: -1L,
                low = json?.optBoolean("low", false) ?: false
            )
        }
    }

    /**
     * GET /api/v1/playback —— read-only device playback state.
     *
     * Feeds the playback capsule and the "now playing" preview, so the values must come from the
     * device; a failed call means "unknown", never a locally guessed state.
     */
    suspend fun playback(endpoint: DeviceEndpoint, sessionId: String): Result<PlaybackState> = call {
        val request = Request.Builder()
            .url(endpoint.urlOf("/playback"))
            .header(AUTH_HEADER, bearer(sessionId))
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val json = response.jsonOrNull()
            ensureSuccess(response, json)
            PlaybackState(
                playbackActive = json?.optBoolean("playback_active", false) ?: false,
                autoLoop = json?.optBoolean("auto_loop", false) ?: false,
                screenMode = json?.optInt("screen_mode", PlaybackState.DEFAULT_SCREEN_MODE_AUTO)
                    ?: PlaybackState.DEFAULT_SCREEN_MODE_AUTO,
                browserMode = json?.optInt(
                    "browser_mode",
                    PlaybackState.DEFAULT_BROWSER_MODE_IMMERSIVE
                ) ?: PlaybackState.DEFAULT_BROWSER_MODE_IMMERSIVE,
                lastFile = json?.optString("last_file").orEmpty()
            )
        }
    }

    /**
     * GET /api/v1/network —— which link the device is on (hotspot / LAN), its address and SSID.
     *
     * Read-only; the Network Connection page uses it to mark the device's current network in the
     * Wi-Fi list (the only ✓) and to render diagnostics (PRD 16.7).
     */
    suspend fun networkStatus(
        endpoint: DeviceEndpoint,
        sessionId: String
    ): Result<DeviceNetworkStatus> = call {
        val request = Request.Builder()
            .url(endpoint.urlOf("/network"))
            .header(AUTH_HEADER, bearer(sessionId))
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val json = response.jsonOrNull()
            ensureSuccess(response, json)
            DeviceNetworkStatus(
                link = json?.optString("link").orEmpty(),
                ip = json?.optString("ip").orEmpty(),
                ssid = json?.optString("ssid").orEmpty(),
                port = json?.optInt("port", endpoint.port) ?: endpoint.port,
                staState = json?.optString("sta_state").orEmpty()
            )
        }
    }

    /**
     * Probe whether the device is reachable: a single TCP connect, no full HTTP round trip.
     *
     * Used before sending Wi-Fi to confirm the phone really sits on the device hotspot link — the
     * hotspot changes subnet on every reboot and the system may switch the phone back to another
     * Wi-Fi, so sending right away would just burn the full 10 s connect timeout.
     */
    suspend fun probe(endpoint: DeviceEndpoint, timeoutMs: Int = PROBE_TIMEOUT_MS): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                Socket().use {
                    it.connect(InetSocketAddress(endpoint.host, endpoint.port), timeoutMs)
                }
            }.isSuccess
        }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private suspend fun <T> call(block: () -> T): Result<T> =
        withContext(Dispatchers.IO) { runCatching { block() } }

    private fun ensureSuccess(response: Response, json: JSONObject?) {
        if (response.isSuccessful) return
        val message = json?.optString("message").orEmpty().ifEmpty { "HTTP ${response.code}" }
        if (response.code == 401) throw SessionExpiredException(message)
        if (response.code == 409) throw DeviceOccupiedException(message)
        if (response.code == 413) throw FileTooLargeException(message)
        throw ApiException(response.code, message)
    }

    private companion object {
        const val AUTH_HEADER = "Authorization"
        const val CALL_TIMEOUT_SECONDS = 0L
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val READ_TIMEOUT_SECONDS = 60L

        /**
         * Whole-call timeout for heartbeat (seconds).
         * Heartbeats are tiny, so they must fail within the heartbeat interval scale; otherwise
         * "auto-reconnect within 30 s" (T-5) is unattainable under silent packet loss.
         */
        const val HEARTBEAT_TIMEOUT_SECONDS = 8L

        /** Values of the GET /media `view` parameter, matching the device-side ApiRoutes */
        const val VIEW_ALL = "all"
        const val VIEW_FAVOURITE = "favourite"

        /** Timeout for the reachability probe; far shorter than a normal request so failures don't stall the user */
        const val PROBE_TIMEOUT_MS = 3_000

        /** Large video uploads take unpredictable time, so the write timeout is switched off */
        val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)

        val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

        fun bearer(sessionId: String) = "Bearer $sessionId"

        fun jsonBody(json: JSONObject): RequestBody =
            json.toString().toRequestBody(JSON_MEDIA_TYPE)

        val JSON_MEDIA_TYPE: MediaType? = "application/json; charset=utf-8".toMediaTypeOrNull()

        fun Response.jsonOrNull(): JSONObject? =
            runCatching { JSONObject(body?.string().orEmpty()) }.getOrNull()
    }
}

/** Sends while reading, reporting a percentage from the bytes sent, so the whole file never sits in memory */
private class FilePartBody(
    private val resolver: ContentResolver,
    private val source: UploadSource,
    private val onProgress: (Int) -> Unit
) : RequestBody() {

    override fun contentType(): MediaType? = source.mimeType.toMediaTypeOrNull()

    override fun contentLength(): Long = source.sizeBytes.takeIf { it > 0 } ?: -1L

    override fun writeTo(sink: BufferedSink) {
        val input: InputStream = resolver.openInputStream(source.uri)
            ?: throw IOException("无法读取所选文件")
        input.use { stream ->
            val buffer = ByteArray(BUFFER_SIZE)
            val total = source.sizeBytes
            var sent = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read == -1) break
                sink.write(buffer, 0, read)
                sent += read
                if (total > 0) onProgress(((sent * 100) / total).toInt().coerceIn(0, 100))
            }
            sink.flush()
        }
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }
}

/** Read the display name, MIME and size of the picked media; unknown size returns -1 (that upload goes chunked) */
fun ContentResolver.toUploadSource(uri: Uri): UploadSource {
    var name: String? = null
    var size = -1L
    runCatching {
        query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex >= 0) name = cursor.getString(nameIndex)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
    }

    return UploadSource(
        uri = uri,
        displayName = name?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/').orEmpty().ifEmpty { "upload" },
        mimeType = getType(uri) ?: "application/octet-stream",
        sizeBytes = size
    )
}

/** Parse the device `params` object; missing or invalid fields fall back to defaults (field names match ApiRoutes.paramsJson) */
private fun JSONObject?.toParams(): DeviceParams {
    val fallback = DeviceParams.DEFAULT
    if (this == null) return fallback
    return DeviceParams(
        heartbeatIntervalMs = optLong("heartbeat_interval_ms").takeIf { it > 0L }
            ?: fallback.heartbeatIntervalMs,
        sessionTimeoutMs = optLong("session_timeout_ms").takeIf { it > 0L }
            ?: fallback.sessionTimeoutMs,
        noOpCountdownMs = optLong("no_op_countdown_ms").takeIf { it > 0L }
            ?: fallback.noOpCountdownMs,
        autoPlayIntervalMs = optLong("auto_play_interval_ms").takeIf { it > 0L }
            ?: fallback.autoPlayIntervalMs
    )
}

/**
 * Parse the `GET/POST /frame` receipt (field names match the device-side `ApiRoutes.frameJson`).
 * Missing fields fall back to [FrameParams] defaults so older devices still parse.
 */
private fun JSONObject?.toFrameConfig(): FrameConfig {
    if (this == null) return FrameConfig(FrameParams(), emptyList())
    val params = FrameParams(
        styleId = optString("style_id").orEmpty(),
        frameEnabled = optBoolean("frame_enabled", FrameParams.DEFAULT_FRAME_ENABLED),
        lineColor = optInt("line_color", FrameParams.DEFAULT_LINE_COLOR),
        lineWidthDp = optDouble("line_width_dp", FrameParams.DEFAULT_LINE_WIDTH_DP.toDouble())
            .toFloat(),
        paperColor = optInt("paper_color", FrameParams.DEFAULT_PAPER_COLOR),
        paperEnabled = optBoolean("paper_enabled", FrameParams.DEFAULT_PAPER_ENABLED),
        backgroundImage = optBoolean("background_image", FrameParams.DEFAULT_BACKGROUND_IMAGE),
        hasBackground = optBoolean("has_background", false),
        // Parsed geometry values are never null: the UI fills them straight into the form
        paddingDp = optDouble("padding_dp", FrameParams.DEFAULT_PADDING_DP.toDouble()).toFloat(),
        borderGapDp = optDouble("border_gap_dp", FrameParams.DEFAULT_BORDER_GAP_DP.toDouble())
            .toFloat(),
        insetDp = optDouble("inset_dp", FrameParams.DEFAULT_INSET_DP.toDouble()).toFloat()
    )
    val styles = buildList {
        val array = optJSONArray("styles") ?: return@buildList
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("id")
            if (id.isEmpty()) continue
            add(FrameStyleInfo(id, item.optString("name")))
        }
    }
    return FrameConfig(params, styles)
}

/** Parse the GET /media items array */private fun JSONArray?.toMediaItems(): List<MediaItem> {
    val array = this ?: return emptyList()
    return buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            add(
                MediaItem(
                    path = item.optString("path"),
                    name = item.optString("name"),
                    type = item.optString("type"),
                    size = item.optLong("size"),
                    lastModified = item.optLong("last_modified"),
                    date = item.optString("date"),
                    favourite = item.optBoolean("favourite")
                )
            )
        }
    }
}
