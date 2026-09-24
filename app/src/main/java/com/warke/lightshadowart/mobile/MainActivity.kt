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

    /** 用户点了「刷新连接」：正在扫描局域网并连接，按钮显示「连接中…」且保持置灰 */
    private var connecting = false

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
                connecting = false
                tvSession.text = getString(R.string.state_no_session)
                // 连接失败也要把按钮态交回统一规则，否则「连接中…」永远亮不回来
                renderSession()
                showConnectError(getString(R.string.toast_connect_failed, error.message.orEmpty()), endpoint)
                return@launch
            }

            Log.i(TAG, "auth ok: ${endpoint.baseUrl}, session=$sessionId")
            val ttl = client.heartbeat(endpoint, sessionId).getOrDefault(DEFAULT_SESSION_TTL_MS)
            SessionStore.save(endpoint, sessionId, ttl)
            connecting = false
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
     * 用户点「刷新连接」即在局域网内重新发现设备并重建会话，不需要设备再弹二维码。
     *
     * 不在后台预先扫描：按钮只在「上一次连接的不是设备热点」时可点，由用户点击驱动这一次发现。
     *
     * 会话不再由「结束会话」按钮结束：手机端退到后台后停止心跳，设备端空闲 5 分钟自动结束。
     *
     * 只有这里能确认「设备真的接入了手机所在的 Wi‑Fi」，因此「设备已接入的网络」也在这里记录。
     */
    private fun refresh() {
        if (connecting) return
        connecting = true
        tvDevice.text = getString(R.string.state_refreshing)
        renderSession()

        // 设备切到 STA 后原来的热点就不存在了，先解绑，避免请求仍绑在已失效的网络上
        hotspotConnector.release()

        lifecycleScope.launch {
            val devices = probeLan(DISCOVER_TIMEOUT_MS)

            // 同一局域网可能有多台设备：优先选上次连接过的那台
            val device = devices.firstOrNull { it.deviceToken == SessionStore.lastDeviceToken }
                ?: devices.firstOrNull()
            if (device == null) {
                connecting = false
                // renderSession() 会把「未连接设备」写回去，并把按钮交回「刷新连接」态
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
            // 找到设备就直接开始连接，按钮保持「连接中…」，成功后由 renderSession() 变为「已连接」
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
        renderRefreshButton(active)
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

    /**
     * 「刷新连接」按钮三态：
     * - 已连接：显示「已连接」，不可点；
     * - 点击后正在局域网内查找并连接：显示「连接中…」，不可点；
     * - 空闲：显示「刷新连接」，只有「上一次连接的不是设备热点」时才可点——
     *   还挂在设备热点上说明设备处于 AP 模式，局域网里找不到它，点了只会落空。
     *
     * 会话再次断开（超时或用户点「断开连接」）即回到「刷新连接」态。
     */
    private fun renderRefreshButton(active: Boolean) {
        when {
            active -> {
                btnRefresh.setText(R.string.action_refresh_connected)
                btnRefresh.isEnabled = false
            }

            connecting -> {
                btnRefresh.setText(R.string.action_refresh_connecting)
                btnRefresh.isEnabled = false
            }

            else -> {
                btnRefresh.setText(R.string.action_refresh)
                btnRefresh.isEnabled = !onDeviceHotspot()
            }
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
     * 跑一次局域网发现（最多等 [timeoutMs]），由用户点「刷新连接」触发，不在后台周期扫描。
     */
    private suspend fun probeLan(timeoutMs: Long): List<DiscoveredDevice> =
        suspendCancellableCoroutine { continuation ->
            nsdDiscoverer.discover(timeoutMs) { devices ->
                if (continuation.isActive) continuation.resume(devices)
            }
            continuation.invokeOnCancellation { nsdDiscoverer.cancel() }
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

        /** 没有权限读取 Wi-Fi 名时系统返回的占位值 */
        const val UNKNOWN_SSID = "<unknown ssid>"
    }
}
