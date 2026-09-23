package com.warke.lightshadowart.mobile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.warke.lightshadowart.mobile.net.DeviceClient
import com.warke.lightshadowart.mobile.net.DeviceEndpoint
import com.warke.lightshadowart.mobile.net.DiscoveredDevice
import com.warke.lightshadowart.mobile.net.HotspotConnector
import com.warke.lightshadowart.mobile.net.NsdDiscoverer
import com.warke.lightshadowart.mobile.net.SessionExpiredException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Wi-Fi 连接页：把 SSID 与密码发给设备，让设备加入同一局域网。
 * 复用主界面已建立的会话（[SessionStore]）。
 *
 * 为省去手打 SSID，进入本页会尝试自动带入手机当前连接的 Wi-Fi 名，
 * 读不到时也可从「选择 Wi-Fi」列表里挑一个。
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

    /** 正在等设备进入局域网并自动返回，避免重复起多轮等待 */
    private var waitingForLan = false

    private val wifiManager: WifiManager? by lazy {
        applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    }

    /** 用户点「选择 Wi-Fi」时若还没定位权限，授权通过后自动把列表续上 */
    private var pickAfterPermission = false

    /**
     * 读取 Wi-Fi 名/列表所需的运行时权限。
     *
     * 定位权限必须申请：官方文档明确 getScanResults() 即使 targetSdk 33+ 也要求
     * ACCESS_FINE_LOCATION（只给「大概位置」时同样读不到）；NEARBY_WIFI_DEVICES 在这里
     * 只能覆盖读当前 SSID，不足以拿到列表。
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

        client = DeviceClient(contentResolver)
        hotspotConnector = HotspotConnector(this)
        nsdDiscoverer = NsdDiscoverer(this)
        SessionStore.init(this)

        val endpoint = SessionStore.endpoint
        if (endpoint == null || !SessionStore.isActive) {
            toast(getString(R.string.toast_need_session))
            finish()
            return
        }

        tvTarget.text = getString(R.string.state_connected, endpoint.baseUrl, endpoint.modeName)
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
    // Wi-Fi 名带入与选择
    // -----------------------------------------------------------------------

    /** 自动带入手机当前连接的 Wi-Fi，并在输入框下方说明来源 */
    private fun prefillSsid() {
        val current = currentSsid()
        if (etSsid.text.isNullOrBlank()) {
            // 读不到当前网络（未连 Wi-Fi / 无权限）时退回上次连接过的那个，通常是同一台路由器
            val fallback = SessionStore.lastWifiSsid.takeIf { it.isNotEmpty() }
            (current ?: fallback)?.let { etSsid.setText(it) }
        }
        layoutSsid.helperText = current?.let { getString(R.string.wifi_current_hint, it) }
            ?: getString(R.string.wifi_current_unknown)
    }

    /** 列出当前 Wi-Fi 与扫描到的 Wi-Fi，选中即填入输入框 */
    private fun showWifiPicker() {
        if (!hasLocationPermission()) {
            // 没有定位权限时 getScanResults() 只会返回空列表，先把授权补上；
            // 已被系统记住拒绝（不再弹窗）则直接给设置入口，避免点了没反应
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
            .setItems(labels.toTypedArray()) { _, which -> etSsid.setText(ssids[which]) }
            .setNegativeButton(R.string.manual_dialog_cancel, null)
            .show()
    }

    /** 列表拿不到时说明卡在哪一步（权限 / 定位开关 / 确实没扫到），而不是只丢一句「读不到」 */
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

    /** 手机当前连接的 Wi-Fi 名；未连 Wi-Fi 或读不到（无权限）返回 null */
    private fun currentSsid(): String? =
        cleanSsid(runCatching { wifiManager?.connectionInfo?.ssid }.getOrNull())

    /** 扫描到的 Wi-Fi 名，信号强的在前 */
    private fun scannedSsids(): List<String> =
        runCatching { wifiManager?.scanResults.orEmpty() }
            .getOrDefault(emptyList())
            .sortedByDescending { it.level }
            .mapNotNull { cleanSsid(it.SSID) }
            .distinct()

    /** 系统返回的 SSID 带引号，且无权限时是 "<unknown ssid>" */
    private fun cleanSsid(raw: String?): String? =
        raw?.trim('"')?.takeIf { it.isNotEmpty() && it != UNKNOWN_SSID }

    private fun locationPermissions(): List<String> =
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    /** 「精确位置」是否已授予——只给大概位置时扫描列表同样是空的 */
    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** 系统定位总开关关着时，即便有权限也扫不到网络 */
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
    // 下发
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
            // 下发请求必须经设备热点链路才能发出去，先确认链路可用，否则只会等满 10 秒超时
            val linkError = ensureDeviceReachable(endpoint)
            val result = if (linkError == null) {
                client.wifiConfig(endpoint, sessionId, ssid, password)
            } else {
                Result.failure(IllegalStateException(linkError))
            }

            progress.isVisible = false
            btnSubmit.isEnabled = true

            result.onSuccess {
                // 这里只表示「设备收到了配置」，不代表它已经接入该网络，
                // 因此不在此记录「设备接入的网络」——等手机在局域网里真的发现设备了再记（见 waitForLanAndReturn）
                tvResult.setTextColor(ContextCompat.getColor(this@WifiConfigActivity, R.color.success))
                tvResult.text = getString(R.string.wifi_success, ssid)
                waitForLanAndReturn(ssid)
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
     * 下发成功后在局域网里等设备出现，等到了就自动返回首页。
     *
     * 设备要关热点、连目标 Wi‑Fi、拿到地址再注册 NSD，需要几秒到几十秒；这段时间用户留在本页无事可做，
     * 让他自己返回首页再点「刷新连接」是多余的一步，所以这里替他把设备等出来：
     * 发现设备即说明它已接入局域网，顺手把会话地址换成局域网地址（热点地址已失效），然后 [finish] 回首页。
     *
     * 等不到就留在本页说明情况——设备可能没连上该 Wi‑Fi，用户可重试下发或返回首页点「刷新连接」。
     */
    private fun waitForLanAndReturn(ssid: String) {
        if (waitingForLan) return
        waitingForLan = true

        lifecycleScope.launch {
            // 设备关热点后这张网已经不存在，进程不能继续绑在上面，否则连上目标 Wi-Fi 也发不出请求
            hotspotConnector.release()

            val device = awaitDeviceOnLan()
            waitingForLan = false
            if (device == null) {
                Log.w(TAG, "device not found on lan after wifi-config: $ssid")
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

    /** 反复在局域网里找设备，找到即返回；等满 [LAN_WAIT_ATTEMPTS] 轮仍未出现返回 null */
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

    /** 跑一次局域网发现（最多等 [LAN_PROBE_TIMEOUT_MS]） */
    private suspend fun probeLan(): List<DiscoveredDevice> =
        suspendCancellableCoroutine { continuation ->
            nsdDiscoverer.discover(LAN_PROBE_TIMEOUT_MS) { devices ->
                if (continuation.isActive) continuation.resume(devices)
            }
            continuation.invokeOnCancellation { nsdDiscoverer.cancel() }
        }

    /**
     * 下发前确认手机确实处在设备热点链路上。
     *
     * 设备热点每次重启都会换一个网段，手机也可能被系统切回其它 Wi-Fi；
     * 此时请求只会等满 10 秒连接超时，用户看到的是一句看不懂的报错。
     * 这里先用一次 TCP 探测短超时判定，断了就按二维码里的凭据自动回连，仍不通再给出明确指引。
     *
     * @return null 表示链路可用；否则返回给用户看的失败原因
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

        // 系统回调 onAvailable 时未必已分到 IPv4，探不通就隔一会儿重试几次
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

        /** 没有权限读取 Wi-Fi 名时系统返回的占位值 */
        const val UNKNOWN_SSID = "<unknown ssid>"

        /** 等待系统连上设备热点并绑定进程网络的时间；超时说明连不上热点 */
        const val RELINK_TIMEOUT_MS = 15_000L

        /** 回连成功后的可达性复核次数与间隔 */
        const val PROBE_ATTEMPTS = 5
        const val PROBE_RETRY_INTERVAL_MS = 1_000L

        /** 下发后等设备进入局域网的轮询：单轮发现窗口、轮间隔与最多轮数（合计约 48 秒） */
        const val LAN_PROBE_TIMEOUT_MS = 3_000L
        const val LAN_WAIT_INTERVAL_MS = 1_000L
        const val LAN_WAIT_ATTEMPTS = 12
    }
}
