package com.ShowSeen.lightshadowart.mobile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.util.LruCache
import android.view.View
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.ShowSeen.lightshadowart.mobile.net.DeviceClient
import com.ShowSeen.lightshadowart.mobile.net.DeviceEndpoint
import com.ShowSeen.lightshadowart.mobile.net.DeviceParams
import com.ShowSeen.lightshadowart.mobile.net.DiscoveredDevice
import com.ShowSeen.lightshadowart.mobile.net.HotspotConnector
import com.ShowSeen.lightshadowart.mobile.net.MediaItem
import com.ShowSeen.lightshadowart.mobile.net.NsdDiscoverer
import com.ShowSeen.lightshadowart.mobile.net.QrPayloadParser
import com.ShowSeen.lightshadowart.mobile.net.SessionExpiredException
import com.ShowSeen.lightshadowart.mobile.net.toUploadSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.Inet4Address
import kotlin.coroutines.resume

/**
 * 主界面：标题栏 + 底部三栏标签（空间 / 主页 / 设置），需求第 6 节。
 *
 * - 空间：浏览模式（沉浸/日期/喜爱）、媒体网格、设备控制按键、喜爱与多选删除（需求 6.1）
 * - 主页：连接状态与信息、扫码连接、上传、Wi-Fi 下发、刷新网络、断开（需求第 6 节）
 * - 设置：心跳/会话超时/无操作倒计时/自动播放，仅管理员可配（需求 6.3）
 */
class MainActivity : AppCompatActivity() {

    // 标题栏与三栏容器
    private lateinit var viewSessionDot: View
    private lateinit var viewSpace: View
    private lateinit var viewHome: View
    private lateinit var viewSettings: View

    // 空间
    private lateinit var groupBrowseMode: RadioGroup
    private lateinit var btnSpaceRefresh: MaterialButton
    private lateinit var btnSpaceSelect: MaterialButton
    private lateinit var btnSpaceDelete: MaterialButton
    private lateinit var tvMediaEmpty: TextView
    private lateinit var recyclerMedia: RecyclerView

    // 主页
    private lateinit var tvDevice: TextView
    private lateinit var tvSession: TextView
    private lateinit var tvWifiHint: TextView
    private lateinit var btnScan: MaterialButton
    private lateinit var btnPick: MaterialButton
    private lateinit var btnWifi: MaterialButton
    private lateinit var btnRefresh: MaterialButton
    private lateinit var btnDisconnect: MaterialButton
    private lateinit var btnStudioFolder: MaterialButton
    private lateinit var tvUploadSummary: TextView
    private lateinit var tvUploadEmpty: TextView

    // 设置
    private lateinit var etHeartbeat: TextInputEditText
    private lateinit var etSessionTimeout: TextInputEditText
    private lateinit var etNoOpCountdown: TextInputEditText
    private lateinit var etAutoPlay: TextInputEditText
    private lateinit var btnSettingsSave: MaterialButton
    private lateinit var tvSettingsHint: TextView

    private val uploadAdapter = UploadAdapter()
    private lateinit var mediaAdapter: MediaGridAdapter
    private lateinit var client: DeviceClient
    private lateinit var hotspotConnector: HotspotConnector
    private lateinit var nsdDiscoverer: NsdDiscoverer

    private var uploadJob: Job? = null
    private var mediaJob: Job? = null

    /** 缩略图内存缓存：按条目数计（每张算 1），避免大图过多占用内存 */
    private val thumbCache = LruCache<String, Bitmap>(THUMB_CACHE_ITEMS)

    /** 用户点了「刷新连接」：正在扫描局域网并连接，按钮显示「连接中…」且保持置灰 */
    private var connecting = false

    /** 正在把已失效的热点地址自动迁移到局域网地址；与 [connecting] 分开，避免改动「刷新连接」按钮的状态 */
    private var migratingToLan = false

    /** 上次自动迁移的时间戳，给心跳失败触发的兜底迁移限流，避免反复空跑发现 */
    private var lastLanMigrateAtMs = 0L

    /** 等待热点权限授予后再连接的设备（权限回调里使用） */
    private var pendingEndpoint: DeviceEndpoint? = null

    /** 空间栏「设备控制」按键，统一按会话可用性启用/禁用 */
    private val spaceControlIds = intArrayOf(
        R.id.btn_ctrl_up, R.id.btn_ctrl_down, R.id.btn_ctrl_left, R.id.btn_ctrl_right,
        R.id.btn_ctrl_confirm, R.id.btn_ctrl_exit, R.id.btn_ctrl_rotate, R.id.btn_ctrl_favourite
    )

    /** 空间缩略图加载：按会话从设备拉取原始字节并解码，命中缓存直接回调 */
    private val thumbnailProvider = object : ThumbnailProvider {
        override fun load(item: MediaItem, onResult: (Bitmap?) -> Unit) {
            // 视频缩略图不解析原始字节，避免为大文件拉取整段视频
            if (item.isVideo) {
                onResult(null)
                return
            }
            thumbCache.get(item.path)?.let {
                onResult(it)
                return
            }
            val endpoint = SessionStore.endpoint
            val sessionId = SessionStore.sessionId
            if (endpoint == null || sessionId == null) {
                onResult(null)
                return
            }
            lifecycleScope.launch {
                val bytes = client.mediaRaw(endpoint, sessionId, item.path)
                val bitmap = if (bytes == null) {
                    null
                } else {
                    withContext(Dispatchers.Default) {
                        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
                    }
                }
                if (bitmap != null) thumbCache.put(item.path, bitmap)
                onResult(bitmap)
            }
        }
    }

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

        bindViews()

        SessionStore.init(this)
        client = DeviceClient(contentResolver)
        hotspotConnector = HotspotConnector.get(this)
        nsdDiscoverer = NsdDiscoverer(this)

        setupMediaGrid()
        setupBottomNav()
        setupSpace()
        setupHome()
        setupSettings()

        updateUploadSummary()
        renderSession()
        startHeartbeat()
    }

    override fun onResume() {
        super.onResume()
        renderSession()
        // 从 Wi‑Fi 配置页回来时设备可能已换到局域网，本地却还记着已失效的热点地址，自动迁移一次
        migrateToLan()
    }

    override fun onDestroy() {
        nsdDiscoverer.cancel()
        super.onDestroy()
    }

    // -----------------------------------------------------------------------
    // 界面装配
    // -----------------------------------------------------------------------

    private fun bindViews() {
        viewSessionDot = findViewById(R.id.view_session_dot)
        viewSpace = findViewById(R.id.view_space)
        viewHome = findViewById(R.id.view_home)
        viewSettings = findViewById(R.id.view_settings)

        groupBrowseMode = findViewById(R.id.group_browse_mode)
        btnSpaceRefresh = findViewById(R.id.btn_space_refresh)
        btnSpaceSelect = findViewById(R.id.btn_space_select)
        btnSpaceDelete = findViewById(R.id.btn_space_delete)
        tvMediaEmpty = findViewById(R.id.tv_media_empty)
        recyclerMedia = findViewById(R.id.recycler_media)

        tvDevice = findViewById(R.id.tv_device)
        tvSession = findViewById(R.id.tv_session)
        tvWifiHint = findViewById(R.id.tv_wifi_hint)
        btnScan = findViewById(R.id.btn_scan)
        btnPick = findViewById(R.id.btn_pick)
        btnWifi = findViewById(R.id.btn_wifi)
        btnRefresh = findViewById(R.id.btn_refresh)
        btnDisconnect = findViewById(R.id.btn_disconnect)
        btnStudioFolder = findViewById(R.id.btn_studio_folder)
        tvUploadSummary = findViewById(R.id.tv_upload_summary)
        tvUploadEmpty = findViewById(R.id.tv_upload_empty)

        etHeartbeat = findViewById(R.id.et_heartbeat)
        etSessionTimeout = findViewById(R.id.et_session_timeout)
        etNoOpCountdown = findViewById(R.id.et_no_op_countdown)
        etAutoPlay = findViewById(R.id.et_auto_play)
        btnSettingsSave = findViewById(R.id.btn_settings_save)
        tvSettingsHint = findViewById(R.id.tv_settings_hint)
    }

    private fun setupBottomNav() {
        val nav: BottomNavigationView = findViewById(R.id.bottom_nav)
        nav.setOnItemSelectedListener { item ->
            showTab(item.itemId)
            true
        }
        // 默认停在「主页」
        showTab(R.id.tab_home)
        nav.selectedItemId = R.id.tab_home
    }

    private fun setupMediaGrid() {
        mediaAdapter = MediaGridAdapter(thumbnailProvider)
        mediaAdapter.onItemClick = { item ->
            // 设备端 /control 只认动作名，无法指定具体图片，点击即让设备进入播放
            sendControl(CTRL_PLAY)
            toast(getString(R.string.toast_media_playing))
            Log.i(TAG, "media click: ${item.path}")
        }
        mediaAdapter.onSelectionChanged = { count ->
            btnSpaceDelete.isEnabled = mediaAdapter.selectionMode && count > 0
        }
        recyclerMedia.adapter = mediaAdapter
        applyMediaLayout(groupBrowseMode.checkedRadioButtonId)
        updateSelectButton()
    }

    private fun setupSpace() {
        groupBrowseMode.setOnCheckedChangeListener { _, checkedId ->
            applyMediaLayout(checkedId)
            reloadMedia()
            sendBrowseMode(checkedId)
        }
        btnSpaceRefresh.setOnClickListener {
            if (SessionStore.isActive) reloadMedia() else requireSession()
        }
        btnSpaceSelect.setOnClickListener { toggleSelectionMode() }
        btnSpaceDelete.setOnClickListener { deleteSelected() }
        findViewById<MaterialButton>(R.id.btn_ctrl_rotate).setOnClickListener {
            sendControl(CTRL_ROTATE, JSONObject().put("degree", ROTATE_DEGREE))
        }
        findViewById<MaterialButton>(R.id.btn_ctrl_favourite).setOnClickListener { favouriteSelected() }
        mapOf(
            R.id.btn_ctrl_up to CTRL_UP,
            R.id.btn_ctrl_down to CTRL_DOWN,
            R.id.btn_ctrl_left to CTRL_LEFT,
            R.id.btn_ctrl_right to CTRL_RIGHT,
            R.id.btn_ctrl_confirm to CTRL_CONFIRM,
            R.id.btn_ctrl_exit to CTRL_EXIT
        ).forEach { (id, action) ->
            findViewById<MaterialButton>(id).setOnClickListener { sendControl(action) }
        }
    }

    /**
     * 按浏览模式切换移动端媒体排列（需求 3.1.2.1 / 6.1）：
     * 日期视图为单列列表并按日期分组（每组前显示日期标题，由近到远），
     * 沉浸 / 喜爱视图为每行 [MEDIA_SPAN_COUNT] 个的网格，
     * 与设备端「日期为列表、其余为网格」保持一致。
     */
    private fun applyMediaLayout(checkedId: Int) {
        val dateMode = checkedId == R.id.radio_date
        // 日期视图在每组前插入日期标题，列表呈现由近到远
        mediaAdapter.setGroupByDate(dateMode)
        recyclerMedia.layoutManager = if (dateMode) {
            LinearLayoutManager(this)
        } else {
            GridLayoutManager(this, MEDIA_SPAN_COUNT)
        }
    }

    private fun setupHome() {
        btnScan.setOnClickListener { startScan() }
        btnPick.setOnClickListener { pickMedia() }
        btnWifi.setOnClickListener { openWifiConfig() }
        btnRefresh.setOnClickListener { refresh() }
        btnDisconnect.setOnClickListener { disconnect() }
        btnStudioFolder.setOnClickListener { createStudioFolder() }

        findViewById<RecyclerView>(R.id.recycler_uploads).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = uploadAdapter
        }
    }

    private fun setupSettings() {
        btnSettingsSave.setOnClickListener { saveSettings() }
    }

    /** 底部标签切换：三栏同一时刻仅显示一个 */
    private fun showTab(tabId: Int) {
        viewSpace.isVisible = tabId == R.id.tab_space
        viewHome.isVisible = tabId == R.id.tab_home
        viewSettings.isVisible = tabId == R.id.tab_settings

        when (tabId) {
            R.id.tab_space -> reloadMedia()
            R.id.tab_settings -> loadSettings()
        }
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

    /**
     * 连接入口（扫码）：二维码带设备热点时先接入热点，再换取 session_id。
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
            // 旧版二维码没有热点信息，直接按设备地址鉴权
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
                    // 记下热点凭据：之后设备若又回到热点形态，「刷新连接」可用它回连，无需重新扫码
                    SessionStore.rememberApCredentials(endpoint)
                    authenticate(endpoint)
                }
                .onFailure { error ->
                    Log.e(TAG, "hotspot failed: ${endpoint.apSsid}", error)
                    tvDevice.text = getString(R.string.state_disconnected)
                    // 自动接入失败时给出退路：提示手动连热点后重试
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

    /** 用 device_token 换取会话，保存角色/文件夹/参数，并立即心跳一次拿到准确剩余时间 */
    private fun authenticate(endpoint: DeviceEndpoint) {
        tvDevice.text = getString(R.string.state_connecting, endpoint.baseUrl)
        lifecycleScope.launch {
            val result = client.auth(endpoint).getOrElse { error ->
                Log.e(TAG, "auth failed: ${endpoint.baseUrl}", error)
                connecting = false
                tvSession.text = getString(R.string.state_no_session)
                // 连接失败也要把按钮态交回统一规则，否则「连接中…」永远亮不回来
                renderSession()
                showConnectError(getString(R.string.toast_connect_failed, error.message.orEmpty()), endpoint)
                return@launch
            }

            Log.i(TAG, "auth ok: ${endpoint.baseUrl}, session=${result.sessionId}, role=${result.role}")
            SessionStore.save(
                endpoint,
                result.sessionId,
                DEFAULT_SESSION_TTL_MS,
                result.role,
                result.folder,
                result.params
            )
            client.heartbeat(endpoint, result.sessionId).onSuccess { heartbeat ->
                SessionStore.touch(heartbeat.expiresInMs)
                SessionStore.updateParams(heartbeat.params)
            }
            connecting = false
            mediaAdapter.setSelectionMode(false)
            updateSelectButton()
            renderSession()
            reloadMedia()
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
     * 刷新：先在局域网内重新发现设备（设备已接入手机下发的 Wi-Fi 时）；
     * 若局域网里没有设备，说明设备仍在自建热点形态，用记下的热点凭据回连热点，
     * 用户不必重新扫码。
     */
    private fun refresh() {
        if (connecting) return

        // 手机还挂在设备热点上：直接用记下的设备地址重建会话，不再请求系统连一次热点
        // （避免重复弹确认框）
        val apEndpoint = SessionStore.apEndpoint()
        if (apEndpoint != null && hotspotConnector.isConnected) {
            connecting = true
            renderSession()
            authenticate(apEndpoint)
            return
        }

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
            if (device != null) {
                // 设备能被局域网发现，才说明它真的接入了手机现在所在的这个 Wi‑Fi，此时才记录
                currentSsid().takeIf { it.isNotEmpty() }?.let { SessionStore.rememberWifiSsid(it) }
                // 找到设备就直接开始连接，按钮保持「连接中…」，成功后由 renderSession() 变为「已连接」
                authenticate(device.toEndpoint())
                return@launch
            }

            // 局域网里没有设备：设备没接入 Wi‑Fi，多半又回到了自建热点形态，回连设备热点
            reconnectHotspot()
        }
    }

    /**
     * 自动把已失效的热点地址迁移到局域网地址。
     *
     * 设备接入手机下发的 Wi‑Fi 后会关掉自建热点并换到新网段，本地若仍记着热点地址，
     * 主页信息框就会一直显示热点 IP 与热点名。这里在局域网里重新发现一次设备，
     * 找到就把会话地址换成局域网地址并重新鉴权，信息框随之刷新。
     *
     * 找不到什么都不做（设备可能仍在热点形态、或不在同一网络），也不自动回连热点——
     * 那会弹系统确认框打扰用户，留给用户点「刷新连接」决定。
     */
    private fun migrateToLan() {
        if (connecting || migratingToLan) return

        val endpoint = SessionStore.endpoint ?: return
        // 已是局域网地址，无需迁移
        if (!endpoint.hasHotspot) return
        // 手机还挂在设备热点上说明设备仍是热点形态，不能当它换过网
        if (hotspotConnector.isConnected) return

        val now = System.currentTimeMillis()
        if (now - lastLanMigrateAtMs < LAN_MIGRATE_COOLDOWN_MS) return
        lastLanMigrateAtMs = now

        migratingToLan = true
        Log.i(TAG, "migrateToLan: 热点地址 ${endpoint.baseUrl} 可能已失效，尝试在局域网内重新发现设备")
        lifecycleScope.launch {
            // 设备热点已不存在，先解绑，否则对新网络的请求仍会绑在失效的热点上
            hotspotConnector.release()
            val devices = probeLan(DISCOVER_TIMEOUT_MS)
            migratingToLan = false

            val device = devices.firstOrNull { it.deviceToken == SessionStore.lastDeviceToken }
                ?: devices.firstOrNull()
            if (device == null) {
                Log.i(TAG, "migrateToLan: 局域网内未发现设备，保留原地址 ${endpoint.baseUrl}")
                return@launch
            }

            Log.i(TAG, "migrateToLan: 设备已在局域网 ${device.host}:${device.port}")
            currentSsid().takeIf { it.isNotEmpty() }?.let { SessionStore.rememberWifiSsid(it) }
            authenticate(device.toEndpoint())
        }
    }

    /**
     * 局域网内没发现设备时回连设备自建热点。
     *
     * 优先用记下的热点凭据（含设备地址）；旧记录只存了「设备已接入的网络」而没存热点凭据时，
     * 只要这个名字就是设备热点名，就按默认热点密码回连，设备地址等连上热点后从热点网关解析，
     * 用户仍然不必重新扫码。连热点名都没有才提示重新扫码。
     */
    private fun reconnectHotspot() {
        val saved = SessionStore.apEndpoint()
        val ssid = saved?.apSsid
            ?: SessionStore.lastWifiSsid.takeIf { it.startsWith(AP_SSID_PREFIX) }.orEmpty()

        if (ssid.isEmpty()) {
            connecting = false
            renderSession()
            Log.i(TAG, "reconnectHotspot: 无可用热点名（lastWifiSsid=${SessionStore.lastWifiSsid}），需重新扫码")
            toast(
                if (SessionStore.lastWifiSsid.isEmpty()) {
                    getString(R.string.toast_refresh_not_found_without_wifi)
                } else {
                    getString(R.string.toast_refresh_not_found, SessionStore.lastWifiSsid)
                }
            )
            return
        }

        val password = saved?.apPassword?.takeIf { it.isNotEmpty() } ?: AP_PASSWORD
        val knownHost = saved?.host.orEmpty()
        tvDevice.text = getString(R.string.state_joining_hotspot, ssid)
        Log.i(TAG, "reconnectHotspot: ssid=$ssid, knownHost=$knownHost")
        hotspotConnector.connect(ssid, password) { result ->
            result
                .onSuccess {
                    // 设备地址未知（旧记录没存）时用热点网关兜底：设备自己就是热点网关
                    val host = knownHost.ifEmpty { hotspotConnector.boundGateway() }
                    if (host == null) {
                        Log.e(TAG, "reconnectHotspot: 已接入热点 $ssid 但解析不到设备地址")
                        connecting = false
                        renderSession()
                        showError(getString(R.string.toast_hotspot_gateway_unknown, ssid))
                        return@onSuccess
                    }
                    val endpoint = DeviceEndpoint(
                        host = host,
                        port = DeviceEndpoint.DEFAULT_PORT,
                        deviceToken = SessionStore.lastDeviceToken,
                        mode = SessionStore.lastDeviceMode,
                        apSsid = ssid,
                        apPassword = password
                    )
                    Log.i(TAG, "reconnectHotspot ok: ssid=$ssid, device=${endpoint.baseUrl}")
                    SessionStore.rememberApCredentials(endpoint)
                    SessionStore.rememberWifiSsid(ssid)
                    authenticate(endpoint)
                }
                .onFailure { error ->
                    Log.e(TAG, "reconnectHotspot failed: $ssid", error)
                    connecting = false
                    renderSession()
                    val endpoint = saved ?: DeviceEndpoint(
                        host = knownHost,
                        port = DeviceEndpoint.DEFAULT_PORT,
                        deviceToken = SessionStore.lastDeviceToken,
                        mode = SessionStore.lastDeviceMode,
                        apSsid = ssid,
                        apPassword = password
                    )
                    showConnectError(
                        if (knownHost.isEmpty()) {
                            getString(R.string.toast_hotspot_failed_brief, ssid, error.message.orEmpty())
                        } else {
                            getString(
                                R.string.toast_hotspot_failed,
                                ssid,
                                error.message.orEmpty(),
                                knownHost
                            )
                        },
                        endpoint
                    )
                }
        }
    }

    private fun handleSessionExpired() {
        SessionStore.clear()
        mediaAdapter.submit(emptyList())
        updateMediaEmpty()
        renderSession()
        toast(getString(R.string.toast_session_expired))
    }

    /**
     * 用户主动断开：结束设备端会话，设备待机页据此回到出码态，手机可以交给下一个人重新扫码。
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
            mediaAdapter.submit(emptyList())
            updateMediaEmpty()
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

    /**
     * 心跳循环：刷新剩余时间与最新参数；设备端会话默认 12 小时。
     *
     * 心跳不能跟着主页的可见性停摆：配网页 [WifiConfigActivity] 会盖住主页，配网「下发 + 等设备入网」
     * 全程可能接近一分钟，设备端 [disconnectTimeoutMs] 只有 60 秒，主页一旦非 RESUMED 就停心跳，
     * 设备会主动把会话判为断开；等用户配完网回到主页，第一个心跳直接 401，界面被清成「未连接设备」，
     * 于是局域网地址和网络名都刷不出来。故这里用 [lifecycleScope] 常驻跑心跳（Activity 销毁才取消），
     * 只把「刷界面」和「自动迁移到局域网」这类会跟配网页抢网络/视图的动作限制在主页可见时执行。
     */
    private fun startHeartbeat() {
        lifecycleScope.launch {
            while (true) {
                val endpoint = SessionStore.endpoint
                val sessionId = SessionStore.sessionId
                if (endpoint != null && sessionId != null) {
                    client.heartbeat(endpoint, sessionId)
                        .onSuccess {
                            SessionStore.touch(it.expiresInMs)
                            SessionStore.updateParams(it.params)
                        }
                        .onFailure {
                            if (it is SessionExpiredException) {
                                handleSessionExpired()
                            } else if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                // 仍记着热点地址却请求不通：设备多半已接入局域网，自动找回来。
                                // 只在主页可见时做——配网页自己有一整套等设备的发现流程，别互相抢
                                migrateToLan()
                            }
                        }
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) renderSession()
                }
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    private fun renderSession() {
        val endpoint = SessionStore.endpoint
        val active = endpoint != null && SessionStore.isActive

        if (!active) {
            tvDevice.text = getString(R.string.state_disconnected)
            tvSession.text = getString(R.string.state_no_session)
        } else {
            tvDevice.text = getString(R.string.state_connected, endpoint!!.baseUrl, endpoint.modeName)
            tvSession.text = getString(
                R.string.state_session_ready,
                endpoint.modeName,
                formatRemaining(SessionStore.remainingMs)
            )
        }

        // 标题旁的状态点：会话断开为红、已连接为绿
        viewSessionDot.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (active) R.color.success else R.color.error)
        )

        btnWifi.isEnabled = active
        btnPick.isEnabled = active && uploadJob?.isActive != true
        btnStudioFolder.isVisible = active && SessionStore.inStudioMode && SessionStore.isAdmin
        renderRefreshButton(active)
        // 「断开连接」要随时能把本地状态收拾干净：会话已失效、手机还原地挂在设备热点上都算
        btnDisconnect.isEnabled = active || hotspotConnector.isConnected ||
            SessionStore.lastDeviceToken.isNotEmpty()

        // 空间栏
        btnSpaceRefresh.isEnabled = active
        spaceControlIds.forEach { findViewById<MaterialButton>(it).isEnabled = active }

        // 设置栏的角色权限
        applySettingsRole()

        // 提醒设备接入的是哪个网络：手机不在同一 Wi-Fi 时「刷新连接」找不到设备
        val ssid = SessionStore.lastWifiSsid
        tvWifiHint.isVisible = ssid.isNotEmpty()
        if (ssid.isNotEmpty()) {
            tvWifiHint.text = getString(R.string.state_wifi_hint, ssid)
        }
    }

    /**
     * 「刷新连接」按钮三态：已连接 / 连接中 / 空闲。
     *
     * 空闲时只有存在可回连的目标才可点：手机还挂在设备热点上、记过设备热点凭据、
     * 或曾连接过设备（可借局域网发现找回来）。全新安装、未绑定过、或两端信息都被清空时，
     * 没有任何回连依据，刷新只会白跑一次发现，直接置灰。
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
                btnRefresh.isEnabled = hasReconnectTarget()
            }
        }
    }

    /** 是否存在可回连设备的目标：挂着设备热点、记过热点凭据、或曾连接过设备 */
    private fun hasReconnectTarget(): Boolean =
        hotspotConnector.isConnected ||
            SessionStore.apEndpoint() != null ||
            SessionStore.lastDeviceToken.isNotEmpty()

    /** 手机当前连接的 Wi‑Fi 名；未连 Wi‑Fi 或读不到（无权限）返回空串 */
    private fun currentSsid(): String {
        val manager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val raw = runCatching { manager?.connectionInfo?.ssid }.getOrNull() ?: return ""
        return raw.trim('"').takeIf { it.isNotEmpty() && it != UNKNOWN_SSID }.orEmpty()
    }

    /** 跑一次局域网发现（最多等 [timeoutMs]），由用户点「刷新连接」触发，不在后台周期扫描 */
    private suspend fun probeLan(timeoutMs: Long): List<DiscoveredDevice> =
        suspendCancellableCoroutine { continuation ->
            nsdDiscoverer.discover(timeoutMs) { devices ->
                if (continuation.isActive) continuation.resume(devices)
            }
            continuation.invokeOnCancellation { nsdDiscoverer.cancel() }
        }

    // -----------------------------------------------------------------------
    // 空间：媒体网格 / 控制 / 喜爱 / 删除
    // -----------------------------------------------------------------------

    private fun reloadMedia() {
        val endpoint = SessionStore.endpoint
        val sessionId = SessionStore.sessionId
        if (endpoint == null || sessionId == null) {
            mediaAdapter.submit(emptyList())
            updateMediaEmpty()
            return
        }

        mediaJob?.cancel()
        val favouriteOnly = groupBrowseMode.checkedRadioButtonId == R.id.radio_favourite
        mediaJob = lifecycleScope.launch {
            client.listMedia(endpoint, sessionId, favouriteOnly)
                .onSuccess {
                    mediaAdapter.submit(it)
                    updateMediaEmpty()
                }
                .onFailure { error ->
                    if (error is SessionExpiredException) {
                        handleSessionExpired()
                    } else {
                        toast(getString(R.string.toast_media_failed, error.message.orEmpty()))
                    }
                }
        }
    }

    /**
     * 浏览模式切换：把三视图单选结果下发设备端同步切换浏览视图（需求 3.1.2.1 / 6.1）。
     * 移动端本地列表随之刷新：沉浸 / 日期视图拉全量，喜爱视图只拉已标记喜爱的媒体。
     */
    private fun sendBrowseMode(checkedId: Int) {
        if (!SessionStore.isActive) return
        val mode = when (checkedId) {
            R.id.radio_immersive -> BROWSER_MODE_IMMERSIVE
            R.id.radio_favourite -> BROWSER_MODE_FAVOURITE
            else -> BROWSER_MODE_DATE
        }
        sendControl(CTRL_SWITCH_VIEW, JSONObject().put("mode", mode))
    }

    private fun updateMediaEmpty() {
        tvMediaEmpty.isVisible = mediaAdapter.itemCount == 0
    }

    private fun updateSelectButton() {
        btnSpaceSelect.setText(
            if (mediaAdapter.selectionMode) R.string.space_select_done else R.string.space_select
        )
    }

    private fun toggleSelectionMode() {
        mediaAdapter.setSelectionMode(!mediaAdapter.selectionMode)
        updateSelectButton()
    }

    /** 喜爱标记：对已选中的文件批量标记为喜爱（需求 6.1） */
    private fun favouriteSelected() {
        val paths = mediaAdapter.selectedPaths()
        if (paths.isEmpty()) {
            toast(getString(R.string.toast_select_none))
            return
        }
        val endpoint = SessionStore.endpoint
        val sessionId = SessionStore.sessionId
        if (endpoint == null || sessionId == null) {
            requireSession()
            return
        }
        lifecycleScope.launch {
            var success = 0
            var firstError: String? = null
            paths.forEach { path ->
                client.favourite(endpoint, sessionId, path, true)
                    .onSuccess { success++ }
                    .onFailure { error ->
                        if (error is SessionExpiredException) {
                            handleSessionExpired()
                            return@launch
                        }
                        if (firstError == null) firstError = error.message
                    }
            }
            toast(
                if (success > 0) {
                    getString(R.string.toast_favourite_added)
                } else {
                    getString(R.string.toast_favourite_failed, firstError.orEmpty())
                }
            )
            reloadMedia()
        }
    }

    /** 多选删除：仅个人模式支持（需求 6.1 / 7.2） */
    private fun deleteSelected() {
        val paths = mediaAdapter.selectedPaths()
        if (paths.isEmpty()) {
            toast(getString(R.string.toast_delete_none))
            return
        }
        if (SessionStore.inStudioMode) {
            toast(getString(R.string.toast_delete_personal_only))
            return
        }
        val endpoint = SessionStore.endpoint
        val sessionId = SessionStore.sessionId
        if (endpoint == null || sessionId == null) {
            requireSession()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.toast_delete_confirm_title)
            .setMessage(getString(R.string.toast_delete_confirm_message, paths.size))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    client.deleteMedia(endpoint, sessionId, paths)
                        .onSuccess { count ->
                            toast(getString(R.string.toast_deleted, count))
                            mediaAdapter.setSelectionMode(false)
                            updateSelectButton()
                            reloadMedia()
                        }
                        .onFailure { error ->
                            if (error is SessionExpiredException) {
                                handleSessionExpired()
                            } else {
                                toast(getString(R.string.toast_delete_failed, error.message.orEmpty()))
                            }
                        }
                }
            }
            .show()
    }

    private fun sendControl(action: String, params: JSONObject? = null) {
        val endpoint = SessionStore.endpoint
        val sessionId = SessionStore.sessionId
        if (endpoint == null || sessionId == null) {
            requireSession()
            return
        }
        lifecycleScope.launch {
            client.control(endpoint, sessionId, action, params).onFailure { error ->
                if (error is SessionExpiredException) {
                    handleSessionExpired()
                } else {
                    toast(getString(R.string.toast_control_failed, error.message.orEmpty()))
                }
            }
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
        val baseIndex = uploadAdapter.itemCount
        val items = uris.map { UploadItem(contentResolver.toUploadSource(it)) }
        uploadAdapter.addAll(items)
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
                    uploadAdapter.refresh(position)
                    return@forEachIndexed
                }

                item.status = UploadStatus.UPLOADING
                item.progress = -1
                uploadAdapter.refresh(position)

                client.upload(endpoint, sessionId, item.source) { percent ->
                    if (percent != item.progress) {
                        item.progress = percent
                        runOnUiThread { uploadAdapter.refresh(position) }
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

                uploadAdapter.refresh(position)
                tvUploadSummary.text = getString(R.string.upload_summary_running, index + 1, items.size)
            }

            tvUploadSummary.text = getString(R.string.upload_summary_done, success, failed)
            renderSession()
        }
    }

    private fun updateUploadSummary() {
        val hasItems = uploadAdapter.itemCount > 0
        tvUploadEmpty.isVisible = !hasItems
        tvUploadSummary.text = if (hasItems) {
            getString(R.string.upload_summary_running, 0, uploadAdapter.itemCount)
        } else {
            getString(R.string.upload_summary_empty)
        }
    }

    // -----------------------------------------------------------------------
    // 主页：Wi-Fi 下发 / 门店文件夹
    // -----------------------------------------------------------------------

    private fun openWifiConfig() {
        if (!requireSession()) return
        startActivity(Intent(this, WifiConfigActivity::class.java))
    }

    /** 门店模式：管理员创建下一个客户文件夹（需求 7.2） */
    private fun createStudioFolder() {
        when {
            !SessionStore.inStudioMode -> {
                toast(getString(R.string.toast_studio_folder_personal))
                return
            }

            !SessionStore.isAdmin -> {
                toast(getString(R.string.toast_admin_only))
                return
            }
        }
        if (!requireSession()) return
        val endpoint = SessionStore.endpoint!!
        val sessionId = SessionStore.sessionId!!

        btnStudioFolder.isEnabled = false
        lifecycleScope.launch {
            client.createStudioFolder(endpoint, sessionId)
                .onSuccess {
                    toast(getString(R.string.toast_studio_folder_created, it))
                    renderSession()
                }
                .onFailure { error ->
                    if (error is SessionExpiredException) {
                        handleSessionExpired()
                    } else {
                        toast(getString(R.string.toast_studio_folder_failed, error.message.orEmpty()))
                    }
                }
            btnStudioFolder.isEnabled = true
        }
    }

    // -----------------------------------------------------------------------
    // 设置：参数读写（需求 6.3）
    // -----------------------------------------------------------------------

    private fun loadSettings() {
        applyParams(SessionStore.params)
        applySettingsRole()

        val endpoint = SessionStore.endpoint
        val sessionId = SessionStore.sessionId
        if (endpoint == null || sessionId == null) return

        lifecycleScope.launch {
            client.readConfig(endpoint, sessionId)
                .onSuccess {
                    SessionStore.updateParams(it)
                    applyParams(it)
                }
                .onFailure { error ->
                    if (error is SessionExpiredException) {
                        handleSessionExpired()
                    } else {
                        toast(getString(R.string.settings_load_failed, error.message.orEmpty()))
                    }
                }
            applySettingsRole()
        }
    }

    private fun saveSettings() {
        if (!requireSession()) return
        if (!SessionStore.isAdmin) {
            toast(getString(R.string.toast_admin_only))
            return
        }
        val params = collectParams()
        if (params == null) {
            toast(getString(R.string.settings_invalid))
            return
        }
        val endpoint = SessionStore.endpoint!!
        val sessionId = SessionStore.sessionId!!

        btnSettingsSave.isEnabled = false
        btnSettingsSave.setText(R.string.settings_saving)
        lifecycleScope.launch {
            client.writeConfig(endpoint, sessionId, params)
                .onSuccess {
                    SessionStore.updateParams(it)
                    applyParams(it)
                    toast(getString(R.string.settings_saved))
                }
                .onFailure { error ->
                    if (error is SessionExpiredException) {
                        handleSessionExpired()
                    } else {
                        toast(getString(R.string.settings_failed, error.message.orEmpty()))
                    }
                }
            btnSettingsSave.setText(R.string.settings_save)
            applySettingsRole()
        }
    }

    /** 输入框按「秒 / 分钟」展示，写回设备端时换算为毫秒 */
    private fun applyParams(params: DeviceParams) {
        etHeartbeat.setText((params.heartbeatIntervalMs / 1_000L).toString())
        etSessionTimeout.setText((params.sessionTimeoutMs / 60_000L).toString())
        etNoOpCountdown.setText((params.noOpCountdownMs / 1_000L).toString())
        etAutoPlay.setText((params.autoPlayIntervalMs / 60_000L).toString())
    }

    private fun collectParams(): DeviceParams? {
        val heartbeat = readPositive(etHeartbeat) ?: return null
        val sessionTimeout = readPositive(etSessionTimeout) ?: return null
        val noOpCountdown = readPositive(etNoOpCountdown) ?: return null
        val autoPlay = readPositive(etAutoPlay) ?: return null
        return DeviceParams(
            heartbeatIntervalMs = heartbeat * 1_000L,
            sessionTimeoutMs = sessionTimeout * 60_000L,
            noOpCountdownMs = noOpCountdown * 1_000L,
            autoPlayIntervalMs = autoPlay * 60_000L
        )
    }

    private fun readPositive(edit: TextInputEditText): Long? =
        edit.text?.toString()?.trim()?.toLongOrNull()?.takeIf { it > 0L }

    /** 设置栏可用性：未连接提示先连接，选片用户只读，管理员可配 */
    private fun applySettingsRole() {
        val active = SessionStore.isActive
        val admin = active && SessionStore.isAdmin
        etHeartbeat.isEnabled = admin
        etSessionTimeout.isEnabled = admin
        etNoOpCountdown.isEnabled = admin
        etAutoPlay.isEnabled = admin
        btnSettingsSave.isEnabled = admin
        tvSettingsHint.text = when {
            !active -> getString(R.string.settings_need_session)
            !admin -> getString(R.string.settings_viewer_readonly)
            else -> getString(R.string.settings_admin_hint)
        }
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
     * 诊断信息：把默认网络、进程绑定网络、VPN 与热点接入结论一并列出。
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

        /** 自动迁移到局域网地址的最小间隔，避免心跳失败时反复空跑发现 */
        const val LAN_MIGRATE_COOLDOWN_MS = 20_000L

        /** 没有权限读取 Wi-Fi 名时系统返回的占位值 */
        const val UNKNOWN_SSID = "<unknown ssid>"

        /** 设备自建热点名前缀，与设备端 ServerConfig.AP_SSID_PREFIX 一致，用于识别记下的网络名是不是热点 */
        const val AP_SSID_PREFIX = "LightShadowArt-"

        /** 设备自建热点的默认密码，与设备端 ServerConfig.AP_PASSWORD 一致；旧记录没存密码时用它兜底 */
        const val AP_PASSWORD = "lightshadowart"

        /** 空间媒体网格每行个数 */
        const val MEDIA_SPAN_COUNT = 3

        /** 缩略图缓存条目数 */
        const val THUMB_CACHE_ITEMS = 60

        /** 旋转一次的角度（正值右旋，负值左旋，与设备端 MtkPlayerBridge 一致） */
        const val ROTATE_DEGREE = 90

        // 设备端 /control 动作名，与 MtkPlayerBridge 常量保持一致
        const val CTRL_PLAY = "PLAY"
        const val CTRL_ROTATE = "ROTATE"
        const val CTRL_SWITCH_VIEW = "SWITCH_VIEW"
        const val CTRL_UP = "UP"
        const val CTRL_DOWN = "DOWN"
        const val CTRL_LEFT = "LEFT"
        const val CTRL_RIGHT = "RIGHT"
        const val CTRL_CONFIRM = "CONFIRM"
        const val CTRL_EXIT = "EXIT"

        /** 浏览视图取值，与设备端 PlayStateStore.BROWSER_MODE_* 一致 */
        const val BROWSER_MODE_DATE = 0
        const val BROWSER_MODE_IMMERSIVE = 1
        const val BROWSER_MODE_FAVOURITE = 2
    }
}
