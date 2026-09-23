package com.warke.lightshadowart.mobile.net

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
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
 * 设备端 HTTP 客户端（纯 HTTP + Bearer 会话）。
 *
 * 对应设备端 `ApiRoutes.kt`：
 * auth / upload / wifi-config / heartbeat / session-end。
 */
class DeviceClient(private val resolver: ContentResolver) {

    // -----------------------------------------------------------------------
    // 接口
    // -----------------------------------------------------------------------

    /** POST /api/v1/auth —— 用二维码里的 device_token 换取 session_id */
    suspend fun auth(endpoint: DeviceEndpoint): Result<String> = call {
        val body = jsonBody(JSONObject().put("device_token", endpoint.deviceToken))
        val request = Request.Builder()
            .url(endpoint.urlOf("/auth"))
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val json = response.jsonOrNull()
            ensureSuccess(response, json)
            json?.optString("session_id").orEmpty().takeIf { it.isNotEmpty() }
                ?: throw ApiException(response.code, "设备端未返回 session_id")
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

    /** POST /api/v1/heartbeat —— 返回会话剩余有效毫秒数 */
    suspend fun heartbeat(endpoint: DeviceEndpoint, sessionId: String): Result<Long> = call {
        val request = Request.Builder()
            .url(endpoint.urlOf("/heartbeat"))
            .header(AUTH_HEADER, bearer(sessionId))
            .post(EMPTY_BODY)
            .build()

        client.newCall(request).execute().use { response ->
            val json = response.jsonOrNull()
            ensureSuccess(response, json)
            json?.optLong("expires_in_ms") ?: 0L
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
