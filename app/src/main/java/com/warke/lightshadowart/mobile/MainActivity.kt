package com.warke.lightshadowart.mobile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.warke.lightshadowart.mobile.net.DeviceClient
import com.warke.lightshadowart.mobile.net.DeviceEndpoint
import com.warke.lightshadowart.mobile.net.DiscoveredDevice
import com.warke.lightshadowart.mobile.net.HotspotConnector
import com.warke.lightshadowart.mobile.net.NsdDiscoverer
import com.warke.lightshadowart.mobile.net.QrPayloadParser
import com.warke.lightshadowart.mobile.net.SessionExpiredException
import com.warke.lightshadowart.mobile.net.toUploadSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.Inet4Address
import kotlin.coroutines.resume

/**
 * 主界面：扫码/手动连接设备 → 建立会话 → 选图片或视频上传 → 进入 Wi-Fi 下发页。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvDevice: TextView
    private lateinit var tvSession: TextView
    private lateinit var tvWifiHint: TextView
    private lateinit var tvUploadSummary: TextView
    private lateinit var tvUploadEmpty: TextView
    private lateinit var viewSessionDot: View
    private lateinit var btnScan: MaterialButton
    private lateinit var btnManual: MaterialButton
    private lateinit var btnPick: MaterialButton
    private lateinit var btnWifi: MaterialButton
    private lateinit var btnRefresh: MaterialButton
    private lateinit var btnDisconnect: MaterialButton

    private val adapter = UploadAdapter()
    private lateinit var client: DeviceClient
    private lateinit var hotspotConnector: HotspotConnector
    private lateinit var nsdDiscoverer: NsdDiscoverer
    private var uploadJob: Job? = null

    /** 局域网内是否已发现设备——「刷新连接」点得通的前提 */
    private var deviceOnLan = false

    /** 用户点了「刷新连接」，扫描期间按钮保持置灰 */
    private var refreshing = false

    /** 后台探测与手动刷新各自都要独占一次发现，串行避免后发起的一次把前一次的结论吞掉 */
    private val lanProbeMutex = Mutex()

    /** 等待热点权限授予后再连接的设备（权限回调里使用） */
    private var pendingEndpoint: DeviceEndpoint? = null

    /** 扫码：设备端待机页二维码 */
    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents.isNullOrBlank()) {
            toast(getString(R.string.toast_scan_cancelled))
            return@registerForActivityResult
        }
        QrPayloadParser.parse(contents)
            .onSuccess { connect(it) }
            .onFailure { toast(it.message ?: getString(R.string.toast_manual_input_invalid)) }
    }

    /** 接入设备热点所需的运行时权限 */
    private val wifiPermissionLauncher = registerForActivityResult(
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

    /** 相册多选：图片与视频都支持 */
    private val pickLauncher = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK_COUNT)
    ) { uris ->
        if (uris.isNotEmpty()) startUpload(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvDevice = findViewById(R.id.tv_device)
        tvSession = findViewById(R.id.tv_session)
        tvWifiHint = findViewById(R.id.tv_wifi_hint)
        tvUploadSummary = findViewById(R.id.tv_upload_summary)
        tvUploadEmpty = findViewById(R.id.tv_upload_empty)
        viewSessionDot = findViewById(R.id.view_session_dot)
        btnScan = findViewById(R.id.btn_scan)
        btnManual = findViewById(R.id.btn_manual)
        btnPick = findViewById(R.id.btn_pick)
        btnWifi = findViewById(R.id.btn_wifi)
        btnRefresh = findViewById(R.id.btn_refresh)
        btnDisconnect = findViewById(R.id.btn_disconnect)

        SessionStore.init(this)
        client = DeviceClient(contentResolver)
        hotspotConnector = HotspotConnector(this)
        nsdDiscoverer = NsdDiscoverer(this)

        findViewById<RecyclerView>(R.id.recycler_uploads).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        btnScan.setOnClickListener { startScan() }
        btnManual.setOnClickListener { showManualDialog() }
        btnPick.setOnClickListener { pickMedia() }
        btnWifi.setOnClickListener { openWifiConfig() }
        btnRefresh.setOnClickListener { refresh() }
        btnDisconnect.setOnClickListener { disconnect() }

        updateUploadSummary()
        renderSession()
        startHeartbeat()
        watchDeviceOnLan()
    }

    override fun onResume() {
        super.onResume()
        renderSession()
    }

    override fun onDestroy() {
        nsdDiscoverer.cancel()
        super.onDestroy()
    }

    // -----------------------------------------------------------------------
    // 连接与会话
    // -----------------------------------------------------------------------

    private fun startScan() {
        scanLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(getString(R.string.scan_prompt))
                .setBeepEnabled(false)
                .setOrientationLocked(true)
        )
    }

    private fun showManualDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_manual_connect, null)
        val etHost = view.findViewById<TextInputEditText>(R.id.et_host)
        val etToken = view.findViewById<TextInputEditText>(R.id.et_token)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.manual_dialog_title)
            .setView(view)
            .setNegativeButton(R.string.manual_dialog_cancel, null)
            .setPositiveButton(R.string.manual_dialog_confirm) { _, _ ->
                QrPayloadParser
                    .fromManualInput(etHost.text?.toString().orEmpty(), etToken.text?.toString().orEmpty())
                    .onSuccess { connect(it) }
                    .onFailure { toast(it.message ?: getString(R.string.toast_manual_input_invalid)) }
            }
            .show()
    }

    /**
     * 连接入口（扫码 / 手动）：二维码带设备热点时先接入热点，再换取 session_id。
     * 接入热点在 Android 13+ 需要 NEARBY_WIFI_DEVICES（旧版本为定位权限），故先申请权限。
     */
    private fun connect(endpoint: DeviceEndpoint) {
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
            // 手动输入或旧版二维码：没有热点信息，直接按设备地址鉴权
            authenticate(endpoint)
            return
        }

        tvDevice.text = getString(R.string.state_joining_hotspot, endpoint.apSsid)
        Log.i(TAG, "connectHotspot: ssid=${endpoint.apSsid}, device=${endpoint.baseUrl}")
        hotspotConnector.connect(endpoint.apSsid, endpoint.apPassword) { result ->
            result
                .onSuccess {
                    Log.i(TAG, "hotspot ok: ${endpoint.apSsid}")
                    // 二维码带 ap_ssid 就说明设备此刻是自建热点形态，手机也刚接入这个热点，
                    // 「设备已接入的网络」应记成热点名；否则会一直留着上一次下发过的局域网名
                    SessionStore.rememberWifiSsid(endpoint.apSsid)
                    authenticate(endpoint)
                }
                .onFailure { error ->
                    Log.e(TAG, "hotspot failed: ${endpoint.apSsid}", error)
                    tvDevice.text = getString(R.string.state_disconnected)
                    // 自动接入失败时给出退路：手动连热点 + 手动填设备地址
                    showConnectError(
                        getString(
                            R.string.toast_hotspot_failed,
                            endpoint.apSsid,
                            error.message.orEmpty(),
                            endpoint.host
                        ),
                        endpoint
                    )
                }
        }
    }

    /** 用 device_token 换取 session_id，并立即心跳一次拿到准确剩余时间 */
    private fun authenticate(endpoint: DeviceEndpoint) {
        tvDevice.text = getString(R.string.state_connecting, endpoint.baseUrl)
        lifecycleScope.launch {
            val sessionId = client.auth(endpoint).getOrElse { error ->
                Log.e(TAG, "auth failed: ${endpoint.baseUrl}", error)
                tvDevice.text = getString(R.string.state_disconnected)
                tvSession.text = getString(R.string.state_no_session)
                // 刷新失败也要把按钮态交回统一规则，否则扫描期间置灰的「刷新连接」永远点不亮
                renderSession()
                showConnectError(getString(R.string.toast_connect_failed, error.message.orEmpty()), endpoint)
                return@launch
            }

            Log.i(TAG, "auth ok: ${endpoint.baseUrl}, session=$sessionId")
            val ttl = client.heartbeat(endpoint, sessionId).getOrDefault(DEFAULT_SESSION_TTL_MS)
            SessionStore.save(endpoint, sessionId, ttl)
            renderSession()
        }
    }

    /** 自动接入设备热点所需的权限：13+ 用「附近的设备」，10~12 用定位，更低版本无需权限 */
    private fun requiredWifiPermissions(): List<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        else -> emptyList()
    }

    /**
     * 刷新：设备接入手机下发的 Wi-Fi 后会注册 NSD 服务，此时手机与设备同处一个局域网，
     * 在局域网内重新发现设备并重建会话即可，不需要设备再弹二维码。
     *
     * 会话不再由「结束会话」按钮结束：手机端退到后台后停止心跳，设备端空闲 5 分钟自动结束。
     *
     * 只有这里能确认「设备真的接入了手机所在的 Wi‑Fi」，因此「设备已接入的网络」也在这里记录。
     */
    private fun refresh() {
        if (refreshing) return
        refreshing = true
        tvDevice.text = getString(R.string.state_refreshing)
        renderSession()

        // 设备切到 STA 后原来的热点就不存在了，先解绑，避免请求仍绑在已失效的网络上
        hotspotConnector.release()

        lifecycleScope.launch {
            val devices = probeLan(DISCOVER_TIMEOUT_MS)
            refreshing = false

            // 同一局域网可能有多台设备：优先选上次连接过的那台
            val device = devices.firstOrNull { it.deviceToken == SessionStore.lastDeviceToken }
                ?: devices.firstOrNull()
            if (device == null) {
                // 这次没找到就先把按钮置灰，别让用户连点；后台探测再发现设备时会重新点亮
                updateDeviceOnLan(false)
                renderSession()
                val ssid = SessionStore.lastWifiSsid
                toast(
                    if (ssid.isEmpty()) {
                        getString(R.string.toast_refresh_not_found_without_wifi)
                    } else {
                        getString(R.string.toast_refresh_not_found, ssid)
                    }
                )
                return@launch
            }
            // 设备能被局域网发现，才说明它真的接入了手机现在所在的这个 Wi‑Fi，此时才记录
            currentSsid().takeIf { it.isNotEmpty() }?.let { SessionStore.rememberWifiSsid(it) }
            updateDeviceOnLan(true)
            authenticate(device.toEndpoint())
        }
    }

    private fun handleSessionExpired() {
        SessionStore.clear()
        renderSession()
        toast(getString(R.string.toast_session_expired))
    }

    /**
     * 用户主动断开：结束设备端会话，设备待机页据此回到出码态，手机可以交给下一个人重新扫码。
     *
     * 先发请求再解绑热点：请求要经热点链路才能到设备，提前解绑会发不出去。
     * 本地会话无论如何都要清掉——用户点「断开连接」的意图就是立刻交还设备；
     * 本地会话已失效（设备端空闲超时）时没有可通知的对象，就只清本地状态。
     */
    private fun disconnect() {
        btnDisconnect.isEnabled = false
        tvDevice.text = getString(R.string.state_disconnecting)

        val endpoint = SessionStore.endpoint
        val sessionId = SessionStore.sessionId

        lifecycleScope.launch {
            val error = if (endpoint != null && sessionId != null) {
                client.endSession(endpoint, sessionId).exceptionOrNull()
            } else {
                null
            }

            hotspotConnector.release()
            SessionStore.clear()
            renderSession()

            // 401 说明设备端会话本就不存在（已超时或已结束），效果与断开一致，不必报错
            toast(
                when {
                    endpoint == null || sessionId == null -> getString(R.string.toast_disconnected_local)
                    error == null || error is SessionExpiredException -> getString(R.string.toast_disconnected)
                    else -> getString(R.string.toast_disconnect_failed, error.message.orEmpty())
                }
            )
        }
    }

    /** 设备端会话默认 12 小时；心跳每 5 秒刷新一次剩余时间 */
    private fun startHeartbeat() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    val endpoint = SessionStore.endpoint
                    val sessionId = SessionStore.sessionId
                    if (endpoint != null && sessionId != null) {
                        client.heartbeat(endpoint, sessionId)
                            .onSuccess { SessionStore.touch(it) }
                            .onFailure { if (it is SessionExpiredException) handleSessionExpired() }
                        renderSession()
                    }
                    delay(HEARTBEAT_INTERVAL_MS)
                }
            }
        }
    }

    private fun renderSession() {
        val endpoint = SessionStore.endpoint
        if (endpoint == null || !SessionStore.isActive) {
            tvDevice.text = getString(R.string.state_disconnected)
            tvSession.text = getString(R.string.state_no_session)
        } else {
            tvDevice.text = getString(R.string.state_connected, endpoint.baseUrl, endpoint.modeName)
            tvSession.text = getString(
                R.string.state_session_ready,
                endpoint.modeName,
                formatRemaining(SessionStore.remainingMs)
            )
        }
        val active = SessionStore.isActive
        // 标题旁的状态点：会话断开为红、已连接为绿
        viewSessionDot.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (active) R.color.success else R.color.error)
        )
        btnWifi.isEnabled = active
        btnPick.isEnabled = active && uploadJob?.isActive != true
        // 「刷新连接」靠局域网发现设备：只有后台探测真的找到了设备才可点，点了就不会落空
        btnRefresh.isEnabled = deviceOnLan && !refreshing
        // 「断开连接」要随时能把本地状态收拾干净：会话已失效、手机还原地挂在设备热点上都算
        btnDisconnect.isEnabled = active || hotspotConnector.isConnected ||
            SessionStore.lastDeviceToken.isNotEmpty()

        // 提醒设备接入的是哪个网络：手机不在同一 Wi-Fi 时「刷新连接」找不到设备
        val ssid = SessionStore.lastWifiSsid
        tvWifiHint.isVisible = ssid.isNotEmpty()
        if (ssid.isNotEmpty()) {
            tvWifiHint.text = getString(R.string.state_wifi_hint, ssid)
        }
    }

    /** 手机当前连接的 Wi‑Fi 名；未连 Wi‑Fi 或读不到（无权限）返回空串 */
    private fun currentSsid(): String {
        val manager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val raw = runCatching { manager?.connectionInfo?.ssid }.getOrNull() ?: return ""
        return raw.trim('"').takeIf { it.isNotEmpty() && it != UNKNOWN_SSID }.orEmpty()
    }

    /** 手机是否还挂在设备自建热点上——这时设备一定不在局域网里 */
    private fun onDeviceHotspot(): Boolean {
        if (hotspotConnector.isConnected) return true
        val apSsid = SessionStore.endpoint?.apSsid ?: return false
        return currentSsid().equals(apSsid, ignoreCase = true)
    }

    /**
     * 有没有可能在局域网里找到设备：手机连着 Wi‑Fi，且不是挂在设备自己的热点上。
     *
     * 挂在设备热点上说明设备还在 AP 模式，局域网里不可能有它，探测没有意义。
     */
    private fun lanProbeWorthwhile(): Boolean = currentSsid().isNotEmpty() && !onDeviceHotspot()

    /**
     * 后台探测：手机挂着 Wi‑Fi 时周期性在局域网里找一遍设备，只有真的找到才点亮「刷新连接」。
     *
     * 「刷新连接」是重建会话的入口，设备不在局域网时点了只会弹一句「未发现设备」让人困惑；
     * 这里先替用户探好——探到按钮才可点，点下去必然能连上；探不到按钮就置灰，
     * 由「断开连接」把本地状态收拾干净（设备端回到出码态，可重新扫码）。
     */
    private fun watchDeviceOnLan() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    if (!refreshing) {
                        val devices = if (lanProbeWorthwhile()) {
                            probeLan(LAN_PROBE_TIMEOUT_MS)
                        } else {
                            emptyList()
                        }
                        updateDeviceOnLan(devices.isNotEmpty())
                        adoptLanAddress(devices)
                    }
                    delay(LAN_PROBE_INTERVAL_MS)
                }
            }
        }
    }

    /**
     * 设备接入手机下发的 Wi‑Fi 后，地址会从热点网段换到路由器网段；[SessionStore] 里记的还是旧热点地址，
     * 「已连接」那一行就会一直显示一个已经不通的地址。
     *
     * 会话本身存在设备端、换网不受影响，因此发现地址变了就把本地地址换过去，不必等用户点「刷新连接」；
     * 换过去的地址若是假的（例如设备又关机了），下一次心跳就会报会话失效，界面随之回到未连接。
     */
    private fun adoptLanAddress(devices: List<DiscoveredDevice>) {
        val endpoint = SessionStore.endpoint ?: return
        if (!SessionStore.isActive) return

        val device = devices.firstOrNull { it.deviceToken == SessionStore.lastDeviceToken }
            ?: devices.firstOrNull() ?: return
        if (device.host == endpoint.host && device.port == endpoint.port) return

        Log.i(TAG, "device address changed: ${endpoint.baseUrl} -> ${device.host}:${device.port}")
        currentSsid().takeIf { it.isNotEmpty() }?.let { SessionStore.rememberWifiSsid(it) }
        SessionStore.updateEndpoint(device.toEndpoint())
        renderSession()
    }

    /** 探测结论有变化时才重绘，避免每轮心跳之外再反复扰动界面 */
    private fun updateDeviceOnLan(found: Boolean) {
        if (deviceOnLan == found) return
        deviceOnLan = found
        renderSession()
    }

    /**
     * 跑一次局域网发现（最多等 [timeoutMs]）。
     *
     * 发现过程独占 NSD，后发起的一次会让前一次收不到回调，因此与手动刷新串行。
     */
    private suspend fun probeLan(timeoutMs: Long): List<DiscoveredDevice> =
        lanProbeMutex.withLock {
            suspendCancellableCoroutine { continuation ->
                nsdDiscoverer.discover(timeoutMs) { devices ->
                    if (continuation.isActive) continuation.resume(devices)
                }
                continuation.invokeOnCancellation { nsdDiscoverer.cancel() }
            }
        }

    // -----------------------------------------------------------------------
    // 上传
    // -----------------------------------------------------------------------

    private fun pickMedia() {
        if (!requireSession()) return
        if (uploadJob?.isActive == true) {
            toast(getString(R.string.toast_upload_busy))
            return
        }
        pickLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
        )
    }

    /** 逐个上传，进度按位置回写到列表项 */
    private fun startUpload(uris: List<Uri>) {
        val baseIndex = adapter.itemCount
        val items = uris.map { UploadItem(contentResolver.toUploadSource(it)) }
        adapter.addAll(items)
        updateUploadSummary()

        btnPick.isEnabled = false
        uploadJob = lifecycleScope.launch {
            var success = 0
            var failed = 0
            var expired = false

            items.forEachIndexed { index, item ->
                val position = baseIndex + index
                val endpoint = SessionStore.endpoint
                val sessionId = SessionStore.sessionId

                if (expired || endpoint == null || sessionId == null) {
                    item.status = UploadStatus.FAILED
                    item.detail = getString(R.string.toast_need_session)
                    failed++
                    adapter.refresh(position)
                    return@forEachIndexed
                }

                item.status = UploadStatus.UPLOADING
                item.progress = -1
                adapter.refresh(position)

                client.upload(endpoint, sessionId, item.source) { percent ->
                    if (percent != item.progress) {
                        item.progress = percent
                        runOnUiThread { adapter.refresh(position) }
                    }
                }.onSuccess { uploaded ->
                    item.status = UploadStatus.DONE
                    item.detail = uploaded.mediaType.ifEmpty { uploaded.filePath }
                    success++
                }.onFailure { error ->
                    item.status = UploadStatus.FAILED
                    item.detail = error.message.orEmpty()
                    failed++
                    if (error is SessionExpiredException) {
                        expired = true
                        handleSessionExpired()
                    }
                }

                adapter.refresh(position)
                tvUploadSummary.text = getString(R.string.upload_summary_running, index + 1, items.size)
            }

            tvUploadSummary.text = getString(R.string.upload_summary_done, success, failed)
            renderSession()
        }
    }

    private fun updateUploadSummary() {
        val hasItems = adapter.itemCount > 0
        tvUploadEmpty.isVisible = !hasItems
        tvUploadSummary.text = if (hasItems) {
            getString(R.string.upload_summary_running, 0, adapter.itemCount)
        } else {
            getString(R.string.upload_summary_empty)
        }
    }

    // -----------------------------------------------------------------------
    // Wi-Fi 下发
    // -----------------------------------------------------------------------

    private fun openWifiConfig() {
        if (!requireSession()) return
        startActivity(Intent(this, WifiConfigActivity::class.java))
    }

    // -----------------------------------------------------------------------
    // 工具
    // -----------------------------------------------------------------------

    private fun requireSession(): Boolean {
        if (SessionStore.isActive) return true
        toast(getString(R.string.toast_need_session))
        return false
    }

    private fun formatRemaining(ms: Long): String {
        val totalMinutes = ms / 60_000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "${hours}小时${minutes}分" else "${minutes}分"
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * 连接类错误信息较长（OkHttp 会带上目标地址、来源地址与超时时间），Toast 只能显示一两行会被截断，
     * 改用可滚动、可复制的对话框完整展示，便于用户看清并反馈。
     */
    private fun showError(message: String) {
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

    /** 失败弹窗统一附上网络诊断，用户截图这一张图即可定位问题，不必连 adb 抓日志 */
    private fun showConnectError(message: String, endpoint: DeviceEndpoint) {
        showError("$message\n\n${diagnostics(endpoint)}")
    }

    /**
     * 诊断信息。
     *
     * OkHttp 的报错只说「没连上」，要看清原因得知道请求实际走了哪张网：例如报错来源地址仍是家里
     * Wi‑Fi 的地址，就说明设备热点没接上或进程绑定没生效。这里把默认网络、进程绑定网络、VPN 与
     * 热点接入结论一并列出。
     */
    private fun diagnostics(endpoint: DeviceEndpoint): String {
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
                    describeNetwork(manager, manager?.boundNetworkForProcess, getString(R.string.diag_not_bound))
                )
            )
            appendLine(
                getString(
                    R.string.diag_vpn,
                    getString(
                        if (hasVpn(manager)) R.string.diag_vpn_on else R.string.diag_vpn_off
                    )
                )
            )
            appendLine(getString(R.string.diag_hotspot, describeHotspot()))
            append(getString(R.string.diag_device, "${endpoint.host}:${endpoint.port}"))
        }
    }

    /** 「Wi‑Fi / 192.168.8.126」；网络不可用时返回调用方给的占位文案 */
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

    /** 「LightShadowArt-0051B8 · 已连接，但随后被系统断开 · 192.168.230.193」 */
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

    private companion object {
        const val TAG = "MainActivity"
        const val HEARTBEAT_INTERVAL_MS = 5_000L
        const val DEFAULT_SESSION_TTL_MS = 12 * 60 * 60 * 1000L
        const val MAX_PICK_COUNT = 10

        /** 手动「刷新连接」的发现窗口，与 NsdDiscoverer 默认值一致 */
        const val DISCOVER_TIMEOUT_MS = 5_000L

        /** 后台探测窗口：只为判断设备在不在，探到即可，不必等满 */
        const val LAN_PROBE_TIMEOUT_MS = 2_500L

        /** 后台探测周期：设备接入 Wi‑Fi 后最多这么久「刷新连接」就会点亮 */
        const val LAN_PROBE_INTERVAL_MS = 10_000L

        /** 没有权限读取 Wi-Fi 名时系统返回的占位值 */
        const val UNKNOWN_SSID = "<unknown ssid>"
    }
}
