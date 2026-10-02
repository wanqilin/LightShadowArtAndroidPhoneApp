package com.ShowSeen.lightshadowart.mobile.net

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
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

/** 接口返回非 2xx，或响应体 code 非 200 */
open class ApiException(val code: Int, message: String) : IOException(message)

/** 设备端返回 401：session 不存在、已过期或被店员强制结束 */
class SessionExpiredException(message: String) : ApiException(401, message)

/** 待上传的本地媒体 */
data class UploadSource(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    /** 未知时为 -1，此时上传进度不可计算 */
    val sizeBytes: Long
)

/** 上传成功后的设备端回执 */
data class UploadResult(val filePath: String, val mediaType: String)

/**
 * 设备端可配置的运行参数（需求 5.2.2 / 6.3）。
 *
 * 单位统一为毫秒；界面按「秒 / 分钟」展示，读写时做换算。
 */
data class DeviceParams(
    val heartbeatIntervalMs: Long,
    val sessionTimeoutMs: Long,
    val noOpCountdownMs: Long,
    val autoPlayIntervalMs: Long
) {
    companion object {
        /** 设备端默认值：心跳 5 秒、会话超时 10 分钟、无操作倒计时 60 秒、自动播放 3 分钟 */
        val DEFAULT = DeviceParams(
            heartbeatIntervalMs = 5_000L,
            sessionTimeoutMs = 10 * 60_000L,
            noOpCountdownMs = 60_000L,
            autoPlayIntervalMs = 3 * 60_000L
        )
    }
}

/** POST /auth 回执：会话 + 角色 + 授权文件夹 + 交互模式 + 运行参数 */
data class AuthResult(
    val sessionId: String,
    val role: String,
    val folder: String,
    val mode: Int,
    val params: DeviceParams
) {
    /** 管理员可配置参数、可移交控制权；选片用户只读 */
    val isAdmin: Boolean get() = role.equals(DeviceEndpoint.ROLE_ADMIN, ignoreCase = true)
}

/** POST /heartbeat 回执：剩余有效期 + 会话预警 + 最新参数 */
data class HeartbeatResult(
    val expiresInMs: Long,
    val warning: Boolean,
    val params: DeviceParams
)

/** GET /media 返回的媒体条目 */
data class MediaItem(
    val path: String,
    val name: String,
    val type: String,
    val size: Long,
    val lastModified: Long,
    /** 日期视图分组键（yyyy-MM-dd） */
    val date: String,
    val favourite: Boolean
) {
    val isVideo: Boolean get() = type.equals("video", ignoreCase = true)
}

/**
 * 设备端 HTTP 客户端（纯 HTTP + Bearer 会话）。
 *
 * 对应设备端 `ApiRoutes.kt`：
 * auth / upload / control / wifi-config / heartbeat / session-end /
 * config / studio-folder / media / media-raw / media-delete / favourite。
 */
class DeviceClient(private val resolver: ContentResolver) {

    // -----------------------------------------------------------------------
    // 接口
    // -----------------------------------------------------------------------

    /**
     * POST /api/v1/auth —— 用二维码里的 device_token 换取 session_id。
     *
     * 同时回传设备端判定的 role / folder / mode / params：
     * 设备首次绑定者即管理员，其余为选片用户（需求 4.2.2 / 7）。
     */
    suspend fun auth(endpoint: DeviceEndpoint): Result<AuthResult> = call {
        val payload = JSONObject()
            .put("device_token", endpoint.deviceToken)
        // 二维码声明 role=admin（手动唤醒出码 / 管理员移交控制权）时带上，设备端据此判定管理员
        if (endpoint.role.isNotEmpty()) payload.put("role", endpoint.role)
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
                params = json?.optJSONObject("params").toParams()
            )
        }
    }

    /**
     * POST /api/v1/upload —— multipart 流式上传，边读边发并回调进度百分比。
     * 进度回调发生在 IO 线程；总长度未知时不会回调。
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

    /** POST /api/v1/wifi-config —— 下发 SSID 与密码，让设备加入局域网 */
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

    /** POST /api/v1/heartbeat —— 返回会话剩余有效毫秒数与最新参数 */
    suspend fun heartbeat(endpoint: DeviceEndpoint, sessionId: String): Result<HeartbeatResult> =
        call {
            val request = Request.Builder()
                .url(endpoint.urlOf("/heartbeat"))
                .header(AUTH_HEADER, bearer(sessionId))
                .post(EMPTY_BODY)
                .build()

            client.newCall(request).execute().use { response ->
                val json = response.jsonOrNull()
                ensureSuccess(response, json)
                HeartbeatResult(
                    expiresInMs = json?.optLong("expires_in_ms") ?: 0L,
                    warning = json?.optBoolean("warning", false) ?: false,
                    params = json?.optJSONObject("params").toParams()
                )
            }
        }

    /** POST /api/v1/session/end —— 结束会话，设备端待机页回到二维码态 */
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
     * POST /api/v1/control —— 控制设备端播放与旋转（需求 3.2.1）。
     *
     * action 取设备端 `MtkPlayerBridge` 常量：PLAY / PAUSE / RESUME / ROTATE，
     * 以及导航键 UP / DOWN / LEFT / RIGHT / CONFIRM / EXIT / NEXT / PREV。
     * 旋转时可在 params 里带 `degree`。
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

    /** GET /api/v1/config —— 读取设备端运行参数 */
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

    /** POST /api/v1/config —— 修改运行参数；仅管理员，选片用户会收到 403 */
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

    /** POST /api/v1/studio/folder —— 门店模式：管理员创建下一个客户文件夹，返回文件夹名 */
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

    /** GET /api/v1/media —— 列出授权文件夹下的媒体；favouriteOnly 时仅返回已标记喜爱的 */
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

    /** GET /api/v1/media/raw —— 读取媒体原始字节用于预览；失败返回 null */
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

    /** POST /api/v1/media/delete —— 个人模式多选删除，返回实际删除数量 */
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

    /** POST /api/v1/favourite —— 心形喜爱标记，返回设备端确认后的最新状态 */
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
     * 探测设备是否可达：只做一次 TCP 连接，不走完整 HTTP。
     *
     * 下发 Wi-Fi 前用它确认手机确实处在设备热点链路上——设备热点每次重启都会换一个网段，
     * 手机也可能被系统切回其它 Wi-Fi，直接下发只会等满 10 秒连接超时。
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
    // 内部工具
    // -----------------------------------------------------------------------

    private suspend fun <T> call(block: () -> T): Result<T> =
        withContext(Dispatchers.IO) { runCatching { block() } }

    private fun ensureSuccess(response: Response, json: JSONObject?) {
        if (response.isSuccessful) return
        val message = json?.optString("message").orEmpty().ifEmpty { "HTTP ${response.code}" }
        if (response.code == 401) throw SessionExpiredException(message)
        throw ApiException(response.code, message)
    }

    private companion object {
        const val AUTH_HEADER = "Authorization"
        const val CALL_TIMEOUT_SECONDS = 0L
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val READ_TIMEOUT_SECONDS = 60L

        /** GET /media 的 view 参数取值，与设备端 ApiRoutes 一致 */
        const val VIEW_ALL = "all"
        const val VIEW_FAVOURITE = "favourite"

        /** 探测设备是否可达的超时；明显短于正常请求，避免失败时让用户干等 */
        const val PROBE_TIMEOUT_MS = 3_000

        /** 大视频上传耗时不可预估，写超时关闭 */
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

/** 边读边发，按已发送字节数回调百分比，避免把整个文件读进内存 */
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

/** 读取所选媒体的展示名、MIME 与大小；大小未知返回 -1（该上传将走 chunked） */
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

/** 解析设备端 `params` 对象；缺失或非法字段回退到默认值（字段名与 ApiRoutes.paramsJson 一致） */
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

/** 解析 GET /media 的 items 数组 */
private fun JSONArray?.toMediaItems(): List<MediaItem> {
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
