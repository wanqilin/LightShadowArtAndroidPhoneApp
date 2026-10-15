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
    // Wi-Fi 名带入与选择
    // -----------------------------------------------------------------------

    /** 自动带入手机当前连接的 Wi-Fi，并在输入框下方说明来源 */
    private fun prefillSsid() {
        val current = currentSsid()
        if (etSsid.text.isNullOrBlank()) {
            // 读不到当前网络（未连 Wi-Fi / 无权限）时退回上次连接过的那个，通常是同一台路由器
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
     * 选到本 App 曾下发过的 Wi-Fi 时，自动带入记下的密码。
     *
     * 安卓不允许 App 读取系统已保存的 Wi-Fi 密码（getConfiguredNetworks 的敏感字段被系统抹除），
     * 因此只能用本 App 自己成功下发过的那份缓存；从未下发过的网络没有记录，
     * 这时保持密码框原样，不把用户已经输入的内容清掉。
     *
     * @param fromPicker true=用户刚在列表里选了 SSID（有记录就用该网络的密码覆盖）；
     *                   false=页面初始化带入（仅在密码框为空时补上）
     */
    private fun applySavedPassword(ssid: String, fromPicker: Boolean) {
        val saved = SessionStore.wifiPasswordFor(ssid)
        if (saved.isEmpty()) return
        if (fromPicker || etPassword.text.isNullOrEmpty()) {
            etPassword.setText(saved)
            etPassword.setSelection(saved.length)
        }
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
            .setItems(labels.toTypedArray()) { _, which ->
                val ssid = ssids[which]
                etSsid.setText(ssid)
                // 选中的是本 App 下发过的网络时，密码一并带上，省去再次手输
                applySavedPassword(ssid, fromPicker = true)
            }
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
                // 密码存进本 App 自己的缓存，下次选到同一 SSID 自动带入（安卓读不到系统保存的密码）
                SessionStore.rememberWifiPassword(ssid, password)
                tvResult.setTextColor(ContextCompat.getColor(this@WifiConfigActivity, R.color.success))
                tvResult.text = getString(R.string.wifi_success, ssid)
                // 密码一并交给等待流程：设备接入后手机也要用同一份凭据接入这张 Wi-Fi
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
     * 下发成功后在局域网里等设备出现，等到了就自动返回首页。
     *
     * 设备要关热点、连目标 Wi‑Fi、拿到地址再注册 NSD，需要几秒到几十秒；这段时间用户留在本页无事可做，
     * 让他自己返回首页再点「刷新连接」是多余的一步，所以这里替他把设备等出来：
     * 发现设备即说明它已接入局域网，顺手把会话地址换成局域网地址（热点地址已失效），然后 [finish] 回首页。
     *
     * 等不到就留在本页说明情况——手机没接入该 Wi‑Fi 时提示手机侧原因，手机已在该网上时说明设备侧情况。
     *
     * @param password 用户本页输入的 Wi‑Fi 密码，用于把手机也接入同一网络
     */
    private fun waitForLanAndReturn(ssid: String, password: String) {
        if (waitingForLan) return
        waitingForLan = true

        lifecycleScope.launch {
            // 设备热点这张网已经没有用（设备正在切走 / 已切走），进程不能继续绑在上面：
            // 绑着它时本进程的请求与 mDNS 都走这张网，即便手机已经连上目标 Wi-Fi 也发现不到设备。
            hotspotConnector.release()

            val joinError = joinTargetWifi(ssid, password)
            if (joinError != null) {
                // 系统可能已经把手机切到目标网络，却不给我们的请求回调成功：实测 Android 16
                // 上对着**手机已保存 / 已连接**的网络发起 WifiNetworkSpecifier 请求会直接
                // 回调 onUnavailable，而与此同时手机确实连在该网络上（默认网络已是 WIFI/VALIDATED）。
                // 所以失败后复查手机实际所在的网络：真到了目标网络就继续做发现，不把用户的网络
                // 状况误报成失败；确实没切过去才报错。
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
                // 到这一刻才失败，说明手机侧能做的都做完了（已在该 Wi-Fi 上），
                // 打一条快照：万一再失败，日志能直接说明卡在手机网络还是设备没上来，不必再猜
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
     * 把手机也接入目标 Wi‑Fi（[waitForLanAndReturn] 里 LAN 发现的前置步骤）。
     *
     * 为什么必须由 App 主动接：设备热点是 tethering 形态，设备切 Wi‑Fi 后热点**不会关**，
     * 手机因此可能仍关联在设备热点上；只解绑进程网络并不等于把手机切到目标网络，
     * 这时进程没有可用的局域网通路，mDNS 发现无从发起，只能等满整轮然后报「未能在局域网内发现设备」。
     * 现场实测：手机一旦确实连上同一 Wi‑Fi，发现立刻成功——所以这一步是确定性的关键。
     *
     * @return null 表示手机已在该 Wi‑Fi 上（可以开始局域网发现）；否则返回给用户看的失败原因
     */
    private suspend fun joinTargetWifi(ssid: String, password: String): String? {
        // 已经在目标网络上就不必再请系统连一次：既省掉一次系统确认框，
        // 也避免某些机型「请求自己已连接的网络」要等满超时才回调，把一个本来能成功的场景拖垮
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

    /** 手机接入 Wi‑Fi 失败的原因文案：区分「系统没能确认」「系统连不上」「请求异常」 */
    private fun joinFailureMessageRes(outcome: HotspotConnector.Outcome): Int =
        when (outcome) {
            HotspotConnector.Outcome.UNAVAILABLE -> R.string.wifi_join_unavailable
            HotspotConnector.Outcome.LOST -> R.string.wifi_join_lost
            HotspotConnector.Outcome.ERROR -> R.string.wifi_join_error
            HotspotConnector.Outcome.NOT_ATTEMPTED -> R.string.wifi_join_unsupported
            // REQUESTING：等到超时系统仍停在「正在请求」，多半是系统确认框没被确认
            HotspotConnector.Outcome.REQUESTING -> R.string.wifi_join_dialog
            HotspotConnector.Outcome.CONNECTED -> R.string.wifi_join_error
        }

    /**
     * 失败瞬间的网络快照，供日志定位（B）。
     *
     * 记四件事：手机当前 SSID、系统默认网络及其能力、进程绑定网络、本轮 NSD 是否发现过设备。
     * 后两项的组合能把原因分开——「绑着旧热点」＝没切网，「无默认网络」＝手机根本没连上网，
     * 「NSD 发现数为 0 且网络正常」＝设备侧没上来或组播不通。
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

    /** 「WIFI / INTERNET / VALIDATED / 192.168.8.126」；网络不可用时为「无可用网络」 */
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

        /**
         * 等待系统把手机接入目标 Wi-Fi 的时间。
         *
         * 必须大于 HotspotConnector 的请求超时（60 秒）——那段超时里含用户在系统确认框上停留的时间，
         * 这里若先超时，用户还在看确认框就被判成失败，等于白等一场。
         */
        const val JOIN_TIMEOUT_MS = 65_000L

        /** 回连成功后的可达性复核次数与间隔 */
        const val PROBE_ATTEMPTS = 5
        const val PROBE_RETRY_INTERVAL_MS = 1_000L

        /** 下发后等设备进入局域网的轮询：单轮发现窗口、轮间隔与最多轮数（合计约 48 秒） */
        const val LAN_PROBE_TIMEOUT_MS = 3_000L
        const val LAN_WAIT_INTERVAL_MS = 1_000L
        const val LAN_WAIT_ATTEMPTS = 12
    }
}
