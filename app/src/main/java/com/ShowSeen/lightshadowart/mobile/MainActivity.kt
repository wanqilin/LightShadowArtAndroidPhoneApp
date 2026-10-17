package com.ShowSeen.lightshadowart.mobile

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.LruCache
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ShowSeen.lightshadowart.mobile.net.DeviceEndpoint
import com.ShowSeen.lightshadowart.mobile.net.DeviceParams
import com.ShowSeen.lightshadowart.mobile.net.FileTooLargeException
import com.ShowSeen.lightshadowart.mobile.net.FramePatch
import com.ShowSeen.lightshadowart.mobile.net.MediaItem
import com.ShowSeen.lightshadowart.mobile.net.PlaybackMode
import com.ShowSeen.lightshadowart.mobile.net.PlaybackState
import com.ShowSeen.lightshadowart.mobile.net.QrPayloadParser
import com.ShowSeen.lightshadowart.mobile.net.SessionExpiredException
import com.ShowSeen.lightshadowart.mobile.net.toUploadSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Main Dashboard and the two sub-pages it hosts: Space Art Gallery and Settings.
 *
 * The Dashboard is the navigation surface (Network Connection / Upload Media / Frame Settings /
 * Browse Art); there is deliberately no bottom tab bar. The session plumbing lives in
 * [DeviceActivity]; this class owns what the pages show and the device actions they trigger.
 */
class MainActivity : DeviceActivity() {

    private enum class Page { DASHBOARD, SPACE, SETTINGS }

    // Dashboard
    private lateinit var cardNetwork: View
    private lateinit var cardUpload: View
    private lateinit var cardSettings: View
    private lateinit var cardGallery: View
    private lateinit var cardUploads: View
    private lateinit var tvDashboardConnection: TextView
    private lateinit var tvDashboardNetwork: TextView
    private lateinit var viewDashboardDot: View
    private lateinit var btnStudioFolder: MaterialButton
    private lateinit var tvUploadSummary: TextView

    // Space
    private lateinit var spaceTopBar: PageTopBarView
    private lateinit var pillImmersive: TextView
    private lateinit var pillDate: TextView
    private lateinit var pillFavourite: TextView
    private lateinit var btnSpaceRefresh: MaterialButton
    private lateinit var btnSpaceSelect: MaterialButton
    private lateinit var btnSpaceFavourite: MaterialButton
    private lateinit var btnSpaceDelete: MaterialButton
    private lateinit var btnSpaceExportFav: MaterialButton
    private lateinit var rowSpaceSelection: View
    private lateinit var recyclerMedia: RecyclerView
    private lateinit var spaceEmptyState: View
    private lateinit var nowPlaying: View
    private lateinit var ivNowPlaying: ImageView
    private lateinit var progressNowPlaying: ProgressBar
    private lateinit var tvNowPlayingStatus: TextView
    private lateinit var toolbarDesign: View
    private lateinit var toolbarBackgrounds: View
    private lateinit var toolbarLayers: View
    private lateinit var toolbarReset: View
    private lateinit var controlPad: DeviceControlPadView

    // Settings
    private lateinit var settingsTopBar: PageTopBarView
    private lateinit var settingsParamsTopBar: PageTopBarView
    private lateinit var settingsMenu: View
    private lateinit var settingsParamsPage: View
    private lateinit var btnSettingsLanguage: View
    private lateinit var btnSettingsParams: View
    private lateinit var btnSettingsAbout: View
    private lateinit var btnSettingsLogout: View
    private lateinit var tvSettingsLanguageValue: TextView
    private lateinit var tvSettingsHint: TextView
    private lateinit var tvSettingsIdentity: TextView
    private lateinit var etHeartbeat: EditText
    private lateinit var etSessionTimeout: EditText
    private lateinit var etNoOpCountdown: EditText
    private lateinit var etAutoPlay: EditText
    private lateinit var btnSettingsSave: MaterialButton
    private lateinit var rowSettingsHeartbeat: View
    private lateinit var rowSettingsSessionTimeout: View
    private lateinit var rowSettingsNoOpCountdown: View
    private lateinit var rowSettingsAutoPlay: View
    private lateinit var dividerSettingsHeartbeat: View
    private lateinit var dividerSettingsSessionTimeout: View
    private lateinit var dividerSettingsNoOpCountdown: View
    private lateinit var dividerSettingsAutoPlay: View

    private lateinit var viewDashboard: View
    private lateinit var viewSpace: View
    private lateinit var viewSettings: View

    private val uploadAdapter = UploadAdapter()
    private lateinit var mediaAdapter: MediaGridAdapter

    private var uploadJob: Job? = null
    private var mediaJob: Job? = null

    /** Polls the device playback state while the gallery is on screen (U-11 / U-14). */
    private var playbackJob: Job? = null

    /** Last playback state the device reported; null means "not known yet" (offline / loading). */
    private var playbackState: PlaybackState? = null

    /** Path currently drawn in the Now Playing area, to avoid reloading the same frame. */
    private var nowPlayingPath: String = ""

    /** Thumbnail cache, counted by entry: one entry per image, so a big library cannot blow memory. */
    private val thumbCache = LruCache<String, Bitmap>(THUMB_CACHE_ITEMS)

    private var currentPage = Page.DASHBOARD
    private var currentBrowseMode = BROWSER_MODE_IMMERSIVE

    private val thumbnailProvider = object : ThumbnailProvider {
        override fun load(item: MediaItem, onResult: (Bitmap?) -> Unit) {
            // Videos are not fetched in full just to draw a frame
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
                val bitmap = bytes?.let {
                    withContext(Dispatchers.Default) {
                        runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull()
                    }
                }
                if (bitmap != null) thumbCache.put(item.path, bitmap)
                onResult(bitmap)
            }
        }
    }

    /** Media picker: photos and videos, then straight into the upload queue */
    private val pickLauncher = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK_COUNT)
    ) { uris ->
        if (uris.isNotEmpty()) startUpload(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupDashboard()
        setupSpace()
        setupSettings()

        updateUploadSummary()
        renderSession()
        showPage(Page.DASHBOARD)
        startHeartbeat()

        handleScannedEndpoint(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleScannedEndpoint(intent)
    }

    override fun onResume() {
        super.onResume()
        renderSession()
        // The session can end while another page is on top (the Wi-Fi page clears it on a 401):
        // drop the thumbnail cache so a reconnect cannot show the previous session's pictures.
        if (!SessionStore.isActive) thumbCache.evictAll()
        migrateToLan()
        if (currentPage == Page.SPACE) startPlaybackPolling()
    }

    override fun onPause() {
        super.onPause()
        stopPlaybackPolling()
    }

    /** Android back first returns to the Dashboard; only from there does it leave the app. */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (currentPage != Page.DASHBOARD) {
            showPage(Page.DASHBOARD)
            return
        }
        super.onBackPressed()
    }

    // -----------------------------------------------------------------------
    // View wiring
    // -----------------------------------------------------------------------

    private fun bindViews() {
        viewDashboard = findViewById(R.id.view_dashboard)
        viewSpace = findViewById(R.id.view_space)
        viewSettings = findViewById(R.id.view_settings)

        cardNetwork = findViewById(R.id.card_network)
        cardUpload = findViewById(R.id.card_upload)
        cardSettings = findViewById(R.id.card_settings)
        cardGallery = findViewById(R.id.card_gallery)
        cardUploads = findViewById(R.id.card_uploads)
        tvDashboardConnection = findViewById(R.id.tv_dashboard_connection)
        tvDashboardNetwork = findViewById(R.id.tv_dashboard_network)
        viewDashboardDot = findViewById(R.id.view_dashboard_dot)
        btnStudioFolder = findViewById(R.id.btn_studio_folder)
        tvUploadSummary = findViewById(R.id.tv_upload_summary)

        spaceTopBar = findViewById(R.id.space_top_bar)
        pillImmersive = findViewById(R.id.pill_space_immersive)
        pillDate = findViewById(R.id.pill_space_date)
        pillFavourite = findViewById(R.id.pill_space_favourite)
        btnSpaceRefresh = findViewById(R.id.btn_space_refresh)
        btnSpaceSelect = findViewById(R.id.btn_space_select)
        btnSpaceFavourite = findViewById(R.id.btn_space_favourite)
        btnSpaceDelete = findViewById(R.id.btn_space_delete)
        btnSpaceExportFav = findViewById(R.id.btn_space_export_fav)
        rowSpaceSelection = findViewById(R.id.row_space_selection)
        recyclerMedia = findViewById(R.id.recycler_media)
        spaceEmptyState = findViewById(R.id.space_empty_state)
        nowPlaying = findViewById(R.id.now_playing)
        ivNowPlaying = findViewById(R.id.iv_now_playing)
        progressNowPlaying = findViewById(R.id.progress_now_playing)
        tvNowPlayingStatus = findViewById(R.id.tv_now_playing_status)
        toolbarDesign = findViewById(R.id.btn_toolbar_design)
        toolbarBackgrounds = findViewById(R.id.btn_toolbar_backgrounds)
        toolbarLayers = findViewById(R.id.btn_toolbar_layers)
        toolbarReset = findViewById(R.id.btn_toolbar_reset)
        controlPad = findViewById(R.id.device_control_pad)

        settingsTopBar = findViewById(R.id.settings_top_bar)
        settingsParamsTopBar = findViewById(R.id.settings_params_top_bar)
        settingsMenu = findViewById(R.id.settings_menu)
        settingsParamsPage = findViewById(R.id.settings_params_page)
        btnSettingsLanguage = findViewById(R.id.btn_settings_language)
        btnSettingsParams = findViewById(R.id.btn_settings_params)
        btnSettingsAbout = findViewById(R.id.btn_settings_about)
        btnSettingsLogout = findViewById(R.id.btn_settings_logout)
        tvSettingsLanguageValue = findViewById(R.id.tv_settings_language_value)
        tvSettingsHint = findViewById(R.id.tv_settings_hint)
        tvSettingsIdentity = findViewById(R.id.tv_settings_identity)
        etHeartbeat = findViewById(R.id.et_heartbeat)
        etSessionTimeout = findViewById(R.id.et_session_timeout)
        etNoOpCountdown = findViewById(R.id.et_no_op_countdown)
        etAutoPlay = findViewById(R.id.et_auto_play)
        btnSettingsSave = findViewById(R.id.btn_settings_save)
        rowSettingsHeartbeat = findViewById(R.id.row_settings_heartbeat)
        rowSettingsSessionTimeout = findViewById(R.id.row_settings_session_timeout)
        rowSettingsNoOpCountdown = findViewById(R.id.row_settings_no_op_countdown)
        rowSettingsAutoPlay = findViewById(R.id.row_settings_auto_play)
        dividerSettingsHeartbeat = findViewById(R.id.divider_settings_heartbeat)
        dividerSettingsSessionTimeout = findViewById(R.id.divider_settings_session_timeout)
        dividerSettingsNoOpCountdown = findViewById(R.id.divider_settings_no_op_countdown)
        dividerSettingsAutoPlay = findViewById(R.id.divider_settings_auto_play)
    }

    private fun setupDashboard() {
        cardNetwork.setOnClickListener {
            startActivity(Intent(this, NetworkConnectionActivity::class.java))
        }
        cardUpload.setOnClickListener { pickMedia() }
        cardSettings.setOnClickListener { showPage(Page.SETTINGS) }
        cardGallery.setOnClickListener { showPage(Page.SPACE) }
        btnStudioFolder.setOnClickListener { createStudioFolder() }

        findViewById<RecyclerView>(R.id.recycler_uploads).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = uploadAdapter
        }
    }

    private fun setupSpace() {
        spaceTopBar.setTitle(getString(R.string.space_title))
        spaceTopBar.setOnBackClickListener { showPage(Page.DASHBOARD) }

        pillImmersive.setOnClickListener { selectBrowseMode(BROWSER_MODE_IMMERSIVE) }
        pillDate.setOnClickListener { selectBrowseMode(BROWSER_MODE_DATE) }
        pillFavourite.setOnClickListener { selectBrowseMode(BROWSER_MODE_FAVOURITE) }

        mediaAdapter = MediaGridAdapter(thumbnailProvider)
        mediaAdapter.onItemClick = { item ->
            // The device control API takes actions only, so a tap asks the device to start playing
            sendControl(CTRL_PLAY)
            toast(getString(R.string.toast_media_playing))
            Log.i(TAG, "media click: ${item.path}")
        }
        mediaAdapter.onItemLongClick = { item -> showItemMenu(item) }
        mediaAdapter.onSelectionChanged = { count ->
            btnSpaceDelete.isEnabled = mediaAdapter.selectionMode && count > 0
            rowSpaceSelection.isVisible = mediaAdapter.selectionMode
            updateFavouriteButton()
        }
        recyclerMedia.adapter = mediaAdapter
        applyMediaLayout(BROWSER_MODE_IMMERSIVE)
        renderFilterPills(BROWSER_MODE_IMMERSIVE)
        updateSelectButton()

        btnSpaceRefresh.setOnClickListener {
            if (SessionStore.isActive) reloadMedia() else requireSession()
        }
        btnSpaceSelect.setOnClickListener { toggleSelectionMode() }
        btnSpaceFavourite.setOnClickListener { favouriteSelected() }
        btnSpaceDelete.setOnClickListener { deleteSelected() }
        btnSpaceExportFav.setOnClickListener { exportFavourite() }

        toolbarDesign.setOnClickListener { openFrameDesign() }
        toolbarBackgrounds.setOnClickListener { openBackgrounds() }
        toolbarLayers.setOnClickListener { toast(getString(R.string.space_layers_unavailable)) }
        toolbarReset.setOnClickListener { confirmResetDesign() }

        controlPad.onAction = { action -> sendControl(action) }
        controlPad.onCenterClick = { onControlPadCenter() }
        controlPad.onExpandedChanged = { expanded ->
            // Keep the pad from covering the last row while it is expanded
            val extra = if (expanded) resources.getDimensionPixelSize(R.dimen.control_pad_diameter) else 0
            recyclerMedia.setPadding(
                recyclerMedia.paddingLeft,
                recyclerMedia.paddingTop,
                recyclerMedia.paddingRight,
                resources.getDimensionPixelSize(R.dimen.gallery_bottom_inset) + extra
            )
        }

        nowPlaying.setOnClickListener { onControlPadCenter() }
    }

    private fun setupSettings() {
        settingsTopBar.setTitle(getString(R.string.tab_settings))
        settingsTopBar.setOnBackClickListener { showPage(Page.DASHBOARD) }
        settingsParamsTopBar.setTitle(getString(R.string.settings_advanced_title))
        settingsParamsTopBar.setOnBackClickListener { showSettingsPage(SETTINGS_PAGE_MENU) }

        btnSettingsLanguage.setOnClickListener {
            startActivity(Intent(this, LanguageActivity::class.java))
        }
        btnSettingsParams.setOnClickListener { showSettingsPage(SETTINGS_PAGE_PARAMS) }
        btnSettingsAbout.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        btnSettingsLogout.setOnClickListener { confirmLogout() }
        btnSettingsSave.setOnClickListener { saveSettings() }
    }

    // -----------------------------------------------------------------------
    // Page switching
    // -----------------------------------------------------------------------

    private fun showPage(page: Page) {
        currentPage = page
        viewDashboard.isVisible = page == Page.DASHBOARD
        viewSpace.isVisible = page == Page.SPACE
        viewSettings.isVisible = page == Page.SETTINGS

        when (page) {
            Page.DASHBOARD -> {
                stopPlaybackPolling()
                renderDashboard()
            }

            Page.SPACE -> {
                renderFilterPills(currentBrowseMode)
                reloadMedia()
                startPlaybackPolling()
            }

            Page.SETTINGS -> {
                stopPlaybackPolling()
                showSettingsPage(SETTINGS_PAGE_MENU)
                loadSettings()
            }
        }
    }

    private fun showSettingsPage(page: Int) {
        settingsMenu.isVisible = page == SETTINGS_PAGE_MENU
        settingsParamsPage.isVisible = page == SETTINGS_PAGE_PARAMS
    }

    // -----------------------------------------------------------------------
    // Session rendering (DeviceActivity callbacks)
    // -----------------------------------------------------------------------

    override fun onSessionChanged() {
        renderSession()
    }

    override fun onSessionCleared() {
        // The cached thumbnails belong to the session that just ended
        thumbCache.evictAll()
        mediaAdapter.submit(emptyList())
        updateMediaEmpty()
        playbackState = null
        nowPlayingPath = ""
    }

    override fun onSessionEstablished() {
        mediaAdapter.setSelectionMode(false)
        updateSelectButton()
        reloadMedia()
    }

    override fun onStudioSelectionConfirmed() {
        reloadMedia()
        showPage(Page.SPACE)
    }

    override fun canMigrateToLan(): Boolean = currentPage == Page.DASHBOARD

    private fun renderSession() {
        renderDashboard()
        renderSpaceAvailability()
        renderSettingsRole()
        controlPad.setPadEnabled(SessionStore.isActive)
    }

    /**
     * Dashboard brand block (UI PRD §3.3 A): one line of real state plus the network the device is
     * on, with the device address underneath. Nothing here is fabricated — offline says so.
     */
    private fun renderDashboard() {
        val endpoint = SessionStore.endpoint
        val active = endpoint != null && SessionStore.isActive

        val state = getString(
            when {
                connecting -> R.string.network_state_connecting
                active -> R.string.network_state_connected
                else -> R.string.dashboard_not_connected
            }
        )
        val ssid = SessionStore.lastWifiSsid
        tvDashboardConnection.text = if (active && ssid.isNotEmpty()) {
            getString(R.string.dashboard_status_with_network, state, ssid)
        } else {
            state
        }

        viewDashboardDot.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(
                this,
                when {
                    connecting -> R.color.primary_blue
                    active -> R.color.status_connected
                    else -> R.color.status_destructive
                }
            )
        )

        // The address is extra detail, so it lives on its own smaller line and only while connected.
        tvDashboardNetwork.isVisible = active
        tvDashboardNetwork.text = endpoint?.let { "${it.host}:${it.port}" }.orEmpty()

        btnStudioFolder.isVisible = active && SessionStore.inStudioMode && SessionStore.isAdmin
    }

    private fun renderSpaceAvailability() {
        val active = SessionStore.isActive
        btnSpaceRefresh.isEnabled = active
        btnSpaceExportFav.isEnabled = active
        // Store mode has no delete entry, and the service refuses it as well
        btnSpaceDelete.isVisible = active && !SessionStore.inStudioMode
    }

    // -----------------------------------------------------------------------
    // Space: browse modes and media
    // -----------------------------------------------------------------------

    private fun selectBrowseMode(mode: Int) {
        if (currentBrowseMode == mode) return
        currentBrowseMode = mode
        renderFilterPills(mode)
        applyMediaLayout(mode)
        reloadMedia()
        sendBrowseMode(mode)
    }

    /** Filter pills: the selected one is filled *and* bolder, so colour is not the only cue. */
    private fun renderFilterPills(selectedMode: Int) {
        listOf(
            pillImmersive to BROWSER_MODE_IMMERSIVE,
            pillDate to BROWSER_MODE_DATE,
            pillFavourite to BROWSER_MODE_FAVOURITE
        ).forEach { (pill, mode) ->
            val selected = mode == selectedMode
            pill.setBackgroundResource(
                if (selected) R.drawable.bg_pill_primary else R.drawable.bg_pill_outline
            )
            pill.setTextColor(
                ContextCompat.getColor(this, if (selected) R.color.on_primary else R.color.text_secondary)
            )
            pill.typeface = if (selected) {
                android.graphics.Typeface.DEFAULT_BOLD
            } else {
                android.graphics.Typeface.DEFAULT
            }
        }
    }

    /**
     * Local arrangement per browse mode: the date view is a single-column list with date headers,
     * the immersive / favourite views are three-column grids (matching the device).
     */
    private fun applyMediaLayout(mode: Int) {
        val dateMode = mode == BROWSER_MODE_DATE
        mediaAdapter.setGroupByDate(dateMode)
        recyclerMedia.layoutManager = if (dateMode) {
            LinearLayoutManager(this)
        } else {
            GridLayoutManager(this, MEDIA_SPAN_COUNT)
        }
    }

    private fun reloadMedia() {
        val endpoint = SessionStore.endpoint
        val sessionId = SessionStore.sessionId
        if (endpoint == null || sessionId == null) {
            mediaAdapter.submit(emptyList())
            updateMediaEmpty()
            return
        }

        mediaJob?.cancel()
        val favouriteOnly = currentBrowseMode == BROWSER_MODE_FAVOURITE
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

    private fun sendBrowseMode(mode: Int) {
        if (!SessionStore.isActive) return
        sendControl(CTRL_SWITCH_VIEW, JSONObject().put("mode", mode))
    }

    private fun updateMediaEmpty() {
        val empty = mediaAdapter.itemCount == 0
        spaceEmptyState.isVisible = empty && !nowPlaying.isVisible
    }

    private fun updateSelectButton() {
        val selecting = mediaAdapter.selectionMode
        btnSpaceSelect.setText(
            if (selecting) R.string.space_select_done else R.string.space_select
        )
        // The favourite / delete pair only makes sense once something can be selected
        rowSpaceSelection.isVisible = selecting
    }

    private fun toggleSelectionMode() {
        mediaAdapter.setSelectionMode(!mediaAdapter.selectionMode)
        updateSelectButton()
    }

    private fun updateFavouriteButton() {
        btnSpaceFavourite.setText(
            if (mediaAdapter.selectedAllFavourite()) {
                R.string.space_menu_favourite_cancel
            } else {
                R.string.space_menu_favourite
            }
        )
    }

    /**
     * Long-press menu (UI PRD §3.6 F): Play / Rotate / Favourite for one item. Multi-select is the
     * job of the Select button, so a long press never turns into a selection.
     */
    private fun showItemMenu(item: MediaItem) {
        val actions = mutableListOf<String>()
        val actionIds = mutableListOf<Int>()
        actions.add(getString(R.string.space_menu_play)); actionIds.add(ACTION_PLAY_ITEM)
        actions.add(getString(R.string.space_menu_rotate)); actionIds.add(ACTION_ROTATE_ITEM)
        actions.add(
            getString(
                if (item.favourite) R.string.space_menu_favourite_cancel
                else R.string.space_menu_favourite
            )
        )
        actionIds.add(ACTION_FAVOURITE_ITEM)

        if (actions.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.space_menu_title)
            .setItems(actions.toTypedArray()) { _, which ->
                when (actionIds[which]) {
                    ACTION_PLAY_ITEM -> {
                        // Enter playback and stop there, exactly like the control pad centre key
                        sendControl(CTRL_PLAY)
                        startPlaybackPolling(refreshSoon = true)
                    }

                    ACTION_ROTATE_ITEM -> sendControl(
                        CTRL_ROTATE,
                        JSONObject().put("degree", ROTATE_DEGREE)
                    )

                    ACTION_FAVOURITE_ITEM -> setFavourite(item, !item.favourite)
                }
            }
            .show()
    }

    private fun setFavourite(item: MediaItem, favourite: Boolean) {
        val endpoint = SessionStore.endpoint
        val sessionId = SessionStore.sessionId
        if (endpoint == null || sessionId == null) {
            requireSession()
            return
        }
        lifecycleScope.launch {
            client.favourite(endpoint, sessionId, item.path, favourite)
                .onSuccess {
                    toast(
                        getString(
                            if (favourite) R.string.toast_favourite_added
                            else R.string.toast_favourite_removed
                        )
                    )
                    reloadMedia()
                }
                .onFailure { error ->
                    if (error is SessionExpiredException) {
                        handleSessionExpired()
                    } else {
                        toast(getString(R.string.toast_favourite_failed, error.message.orEmpty()))
                    }
                }
        }
    }

    /** Favourite the selected items (batch, via the Select button flow). */
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
        val target = !mediaAdapter.selectedAllFavourite()
        lifecycleScope.launch {
            var success = 0
            var firstError: String? = null
            paths.forEach { path ->
                client.favourite(endpoint, sessionId, path, target)
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
                when {
                    success <= 0 -> getString(R.string.toast_favourite_failed, firstError.orEmpty())
                    target -> getString(R.string.toast_favourite_added)
                    else -> getString(R.string.toast_favourite_removed)
                }
            )
            reloadMedia()
        }
    }

    /** Multi-select delete; personal mode only (the service refuses it in store mode). */
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

    /**
     * Export the favourites into their own Fav folder. Always driven by the device's current
     * markers: newly marked items are copied in, unmarked leftovers are removed.
     */
    private fun exportFavourite() {
        val endpoint = SessionStore.endpoint
        val sessionId = SessionStore.sessionId
        if (endpoint == null || sessionId == null) {
            requireSession()
            return
        }
        btnSpaceExportFav.isEnabled = false
        lifecycleScope.launch {
            client.exportFavourite(endpoint, sessionId)
                .onSuccess { count ->
                    toast(
                        if (count > 0) {
                            getString(R.string.toast_export_fav_done, count)
                        } else {
                            getString(R.string.toast_export_fav_empty)
                        }
                    )
                    reloadMedia()
                }
                .onFailure { error ->
                    if (error is SessionExpiredException) {
                        handleSessionExpired()
                    } else {
                        toast(getString(R.string.toast_export_fav_failed, error.message.orEmpty()))
                    }
                }
            btnSpaceExportFav.isEnabled = SessionStore.isActive
        }
    }

    // -----------------------------------------------------------------------
    // Space: playback state, now playing and the control pad
    // -----------------------------------------------------------------------

    /**
     * Poll the device playback state while the gallery is visible. The state is what the device
     * reports, so the capsule and the editing rules never contradict the physical frame.
     */
    private fun startPlaybackPolling(refreshSoon: Boolean = false) {
        playbackJob?.cancel()
        playbackJob = lifecycleScope.launch {
            if (refreshSoon) delay(PLAYBACK_REFRESH_DELAY_MS)
            while (true) {
                refreshPlaybackState()
                delay(PLAYBACK_POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopPlaybackPolling() {
        playbackJob?.cancel()
        playbackJob = null
    }

    private suspend fun refreshPlaybackState() {
        val endpoint = SessionStore.endpoint
        val sessionId = SessionStore.sessionId
        if (endpoint == null || sessionId == null) {
            playbackState = null
            renderPlaybackState()
            return
        }
        client.playback(endpoint, sessionId)
            .onSuccess { playbackState = it }
            .onFailure { error ->
                if (error is SessionExpiredException) {
                    handleSessionExpired()
                    return
                }
                // Unreachable device: report "unknown" instead of keeping a stale state
                playbackState = null
            }
        renderPlaybackState()
    }

    private fun renderPlaybackState() {
        val state = playbackState
        val mode = state?.mode

        val (iconRes, labelRes, colorRes) = when (mode) {
            PlaybackMode.BROWSE -> Triple(
                R.drawable.ic_grid, R.string.playback_state_browse, R.color.text_secondary
            )

            PlaybackMode.STATIC -> Triple(
                R.drawable.ic_play, R.string.playback_state_static, R.color.primary_blue
            )

            PlaybackMode.AUTO -> Triple(
                R.drawable.ic_pause, R.string.playback_state_auto, R.color.status_connected
            )

            null -> Triple(
                R.drawable.ic_warning, R.string.playback_state_unknown, R.color.text_disabled
            )
        }
        spaceTopBar.showTrailingPill(iconRes, getString(labelRes), colorRes)

        controlPad.setCenterState(
            when (mode) {
                PlaybackMode.BROWSE, null -> DeviceControlPadView.CenterState.OK
                PlaybackMode.STATIC -> DeviceControlPadView.CenterState.PLAY
                PlaybackMode.AUTO -> DeviceControlPadView.CenterState.PAUSE
            }
        )

        // Editing the frame / wallpaper is only allowed while the device sits on a paused photo
        renderEditAvailability(mode)
        renderNowPlaying(state)
    }

    /**
     * Frame Design and Backgrounds are only usable in static playback (UI PRD §3.6 D).
     *
     * The entries are dimmed but stay clickable on purpose: tapping a dimmed entry explains why it
     * is unavailable instead of failing silently. Only administrators may edit at all.
     */
    private fun renderEditAvailability(mode: PlaybackMode?) {
        val editable = mode == PlaybackMode.STATIC && SessionStore.isActive && SessionStore.isAdmin
        listOf(toolbarDesign, toolbarBackgrounds).forEach { view ->
            view.alpha = if (editable) 1f else DISABLED_ALPHA
        }
    }

    /**
     * While the device plays full screen the gallery shows that one frame, scaled to fit the area
     * the grid used to occupy. A single tap toggles between auto play and pause.
     */
    private fun renderNowPlaying(state: PlaybackState?) {
        val playing = state != null && state.mode != PlaybackMode.BROWSE
        nowPlaying.isVisible = playing
        if (!playing) {
            nowPlayingPath = ""
            ivNowPlaying.setImageDrawable(null)
            updateMediaEmpty()
            return
        }

        updateMediaEmpty()
        val path = state?.lastFile.orEmpty()
        if (path.isEmpty()) {
            showNowPlayingPlaceholder(getString(R.string.space_now_playing_loading))
            return
        }
        if (path == nowPlayingPath && ivNowPlaying.drawable != null) return

        nowPlayingPath = path
        showNowPlayingPlaceholder(getString(R.string.space_now_playing_loading))
        lifecycleScope.launch {
            val endpoint = SessionStore.endpoint
            val sessionId = SessionStore.sessionId
            if (endpoint == null || sessionId == null) {
                showNowPlayingPlaceholder(getString(R.string.space_now_playing_failed))
                return@launch
            }
            val bitmap = loadNowPlaying(endpoint, sessionId, path)
            // The device may have moved on while the frame was being fetched
            if (nowPlayingPath != path) return@launch
            if (bitmap == null) {
                showNowPlayingPlaceholder(getString(R.string.space_now_playing_failed))
            } else {
                progressNowPlaying.isVisible = false
                tvNowPlayingStatus.isVisible = false
                ivNowPlaying.setImageBitmap(bitmap)
            }
        }
    }

    private fun showNowPlayingPlaceholder(message: String) {
        ivNowPlaying.setImageDrawable(null)
        progressNowPlaying.isVisible = message == getString(R.string.space_now_playing_loading)
        tvNowPlayingStatus.isVisible = !progressNowPlaying.isVisible
        tvNowPlayingStatus.text = message
    }

    private suspend fun loadNowPlaying(
        endpoint: DeviceEndpoint,
        sessionId: String,
        path: String
    ): Bitmap? {
        thumbCache.get(path)?.let { return it }
        val bytes = client.mediaRaw(endpoint, sessionId, path) ?: return null
        val bitmap = withContext(Dispatchers.Default) {
            runCatching { decodeSampled(bytes, NOW_PLAYING_MAX_PX) }.getOrNull()
        }
        if (bitmap != null) thumbCache.put(path, bitmap)
        return bitmap
    }

    /** Centre key / tap on the current frame: one meaning per real state (UI PRD §3.6 E). */
    private fun onControlPadCenter() {
        if (!SessionStore.isActive) {
            requireSession()
            return
        }
        when (playbackState?.mode) {
            // Enter playback and stop on the first frame
            PlaybackMode.BROWSE, null -> sendControl(CTRL_PLAY)
            // Paused: start the auto play timer
            PlaybackMode.STATIC -> sendControl(CTRL_AUTO_PLAY)
            // Auto play: pause, keeping the frame
            PlaybackMode.AUTO -> sendControl(CTRL_AUTO_PAUSE)
        }
        startPlaybackPolling(refreshSoon = true)
    }

    private fun openFrameDesign(): Boolean {
        if (!requireEditable()) return false
        startActivity(Intent(this, FrameDesignActivity::class.java))
        return true
    }

    private fun openBackgrounds(): Boolean {
        if (!requireEditable()) return false
        startActivity(Intent(this, BackgroundsActivity::class.java))
        return true
    }

    /** Editing requires an administrator session inside static playback; otherwise say why. */
    private fun requireEditable(): Boolean {
        if (!SessionStore.isActive) {
            requireSession()
            return false
        }
        if (!SessionStore.isAdmin) {
            toast(getString(R.string.toast_admin_only))
            return false
        }
        when (playbackState?.mode) {
            PlaybackMode.STATIC -> return true
            PlaybackMode.AUTO -> {
                toast(getString(R.string.space_edit_need_pause_auto))
                return false
            }

            else -> {
                toast(getString(R.string.space_edit_need_enter_playback))
                return false
            }
        }
    }

    /** Reset to the device defaults: frame off and no custom wallpaper, i.e. back to blur. */
    private fun confirmResetDesign() {
        if (!SessionStore.isActive) {
            requireSession()
            return
        }
        if (!SessionStore.isAdmin) {
            toast(getString(R.string.toast_admin_only))
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.space_reset_confirm_title)
            .setMessage(R.string.space_reset_confirm_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.space_toolbar_reset) { _, _ -> resetDesign() }
            .show()
    }

    private fun resetDesign() {
        val endpoint = SessionStore.endpoint
        val sessionId = SessionStore.sessionId
        if (endpoint == null || sessionId == null) {
            requireSession()
            return
        }
        lifecycleScope.launch {
            client.writeFrame(
                endpoint,
                sessionId,
                FramePatch(frameEnabled = false, backgroundImage = false, paperEnabled = true)
            )
                .onSuccess { toast(getString(R.string.space_reset_done)) }
                .onFailure { error ->
                    if (error is SessionExpiredException) {
                        handleSessionExpired()
                    } else {
                        toast(getString(R.string.space_reset_failed, error.message.orEmpty()))
                    }
                }
        }
    }

    // -----------------------------------------------------------------------
    // Upload
    // -----------------------------------------------------------------------

    private fun pickMedia() {
        if (!requireSession()) return
        if (uploadJob?.isActive == true) {
            toast(getString(R.string.toast_upload_busy))
            return
        }
        // Probe free space before opening the picker so a full device is reported before uploading
        lifecycleScope.launch {
            val endpoint = SessionStore.endpoint
            val sessionId = SessionStore.sessionId
            val low = if (endpoint != null && sessionId != null) {
                client.storage(endpoint, sessionId).getOrNull()?.low == true
            } else {
                false
            }
            if (low) {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setMessage(R.string.toast_storage_low)
                    .setPositiveButton(R.string.dialog_continue_upload) { _, _ -> launchPicker() }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } else {
                launchPicker()
            }
        }
    }

    private fun launchPicker() {
        pickLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
        )
    }

    /**
     * A file over the 4G limit needs a blocking notice: the previous toast flashed by and users
     * thought the tap had done nothing. Both ends already refuse the file.
     */
    private fun showFileTooLargeDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_file_too_large_title)
            .setMessage(R.string.dialog_file_too_large_message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun startUpload(uris: List<Uri>) {
        val baseIndex = uploadAdapter.itemCount
        val items = uris.map { UploadItem(contentResolver.toUploadSource(it)) }
        uploadAdapter.addAll(items)
        updateUploadSummary()

        // Pre-check: a file over the 4G limit is refused locally, so it is never sent in full
        var tooLarge = false
        items.forEachIndexed { index, item ->
            if (item.source.sizeBytes > AppConfig.MAX_UPLOAD_FILE_BYTES) {
                item.status = UploadStatus.FAILED
                item.detail = getString(R.string.toast_file_too_large)
                uploadAdapter.refresh(baseIndex + index)
                tooLarge = true
            }
        }
        if (tooLarge) showFileTooLargeDialog()

        cardUpload.isEnabled = false
        uploadJob = lifecycleScope.launch {
            var success = 0
            var failed = 0
            var expired = false

            items.forEachIndexed { index, item ->
                val position = baseIndex + index
                val endpoint = SessionStore.endpoint
                val sessionId = SessionStore.sessionId

                if (item.status == UploadStatus.FAILED) {
                    failed++
                    return@forEachIndexed
                }

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
                    item.detail = if (error is FileTooLargeException) {
                        getString(R.string.toast_file_too_large)
                    } else {
                        error.message.orEmpty()
                    }
                    failed++
                    if (error is FileTooLargeException) {
                        showFileTooLargeDialog()
                    }
                    if (error is SessionExpiredException) {
                        expired = true
                        handleSessionExpired()
                    }
                }

                uploadAdapter.refresh(position)
                tvUploadSummary.text = getString(R.string.upload_summary_running, index + 1, items.size)
            }

            tvUploadSummary.text = getString(R.string.upload_summary_done, success, failed)
            cardUpload.isEnabled = true
            renderSession()
        }
    }

    private fun updateUploadSummary() {
        val hasItems = uploadAdapter.itemCount > 0
        cardUploads.isVisible = hasItems
        tvUploadSummary.text = if (hasItems) {
            getString(R.string.upload_summary_running, 0, uploadAdapter.itemCount)
        } else {
            getString(R.string.upload_summary_empty)
        }
    }

    // -----------------------------------------------------------------------
    // Settings: parameters and session actions
    // -----------------------------------------------------------------------

    private fun loadSettings() {
        applyParams(SessionStore.params)
        renderSettingsRole()

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
            renderSettingsRole()
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
        val endpoint = SessionStore.endpoint ?: return
        val sessionId = SessionStore.sessionId ?: return

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
            renderSettingsRole()
        }
    }

    /** The fields show seconds / minutes; the device takes milliseconds. */
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

    private fun readPositive(edit: EditText): Long? =
        edit.text?.toString()?.trim()?.toLongOrNull()?.takeIf { it > 0L }

    /**
     * Settings availability: administrators can edit, everybody else reads. Parameter rows follow
     * the device interaction mode (session timeout / countdown are store mode, auto play is home mode).
     */
    private fun renderSettingsRole() {
        val active = SessionStore.isActive
        val admin = active && SessionStore.isAdmin
        applySettingsParamsVisibility()
        etHeartbeat.isEnabled = admin
        etSessionTimeout.isEnabled = admin
        etNoOpCountdown.isEnabled = admin
        etAutoPlay.isEnabled = admin
        btnSettingsSave.isEnabled = admin
        btnSettingsLogout.isVisible = admin
        tvSettingsHint.text = when {
            !active -> getString(R.string.settings_need_session)
            !admin -> getString(R.string.settings_viewer_readonly)
            else -> getString(R.string.settings_admin_hint)
        }
        renderSettingsIdentity()
        renderLanguage()
    }

    /**
     * Parameter rows by device mode: the mode arrives in the auth receipt, so until something is
     * known all four rows stay visible rather than hiding half the page on entry.
     */
    private fun applySettingsParamsVisibility() {
        val modeKnown = SessionStore.endpoint != null
        val studio = modeKnown && SessionStore.inStudioMode
        val home = modeKnown && !SessionStore.inStudioMode
        setRowVisible(rowSettingsHeartbeat, dividerSettingsHeartbeat, true)
        setRowVisible(rowSettingsSessionTimeout, dividerSettingsSessionTimeout, studio)
        setRowVisible(rowSettingsNoOpCountdown, dividerSettingsNoOpCountdown, studio)
        setRowVisible(rowSettingsAutoPlay, dividerSettingsAutoPlay, home)
    }

    private fun setRowVisible(row: View, divider: View, visible: Boolean) {
        row.isVisible = visible
        divider.isVisible = visible
    }

    /** The language actually in use: with no manual choice the app follows the system language. */
    private fun currentLanguageTag(): String = resources.configuration.locales[0].language

    private fun renderLanguage() {
        tvSettingsLanguageValue.text = getString(
            if (currentLanguageTag() == LANGUAGE_ZH) R.string.language_zh else R.string.language_en
        )
    }

    /**
     * Identity line. Home mode has no shared users (F-5): the only role there is administrator, and
     * other people take over through "sign out".
     */
    private fun renderSettingsIdentity() {
        val active = SessionStore.isActive
        tvSettingsIdentity.isVisible = active
        if (!active) return
        val studio = SessionStore.inStudioMode
        val admin = SessionStore.isAdmin
        tvSettingsIdentity.text = getString(
            when {
                studio && admin -> R.string.settings_identity_studio_admin
                studio -> R.string.settings_identity_studio_viewer
                else -> R.string.settings_identity_home_admin
            }
        )
    }

    /** "Sign out" = hand over the administrator role, so the device shows its QR code again. */
    private fun confirmLogout() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_logout)
            .setMessage(R.string.toast_logout_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> disconnect(handover = true) }
            .show()
    }

    // -----------------------------------------------------------------------
    // Scan hand-off
    // -----------------------------------------------------------------------

    /** A payload coming back from the bind page: parse it and connect right away. */
    private fun handleScannedEndpoint(intent: Intent?) {
        val payload = intent?.getStringExtra(EXTRA_SCANNED_ENDPOINT).orEmpty()
        if (payload.isEmpty()) return
        intent?.removeExtra(EXTRA_SCANNED_ENDPOINT)
        QrPayloadParser.parse(payload)
            .onSuccess { connect(it) }
            .onFailure { toast(it.message ?: getString(R.string.scan_invalid_qr)) }
    }

    // -----------------------------------------------------------------------
    // Device control
    // -----------------------------------------------------------------------

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

    /**
     * Downsample while decoding: the device frame is a full-size photo and decoding it as-is would
     * risk an out-of-memory crash for nothing (the view is at most screen sized).
     */
    private fun decodeSampled(bytes: ByteArray, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    companion object {
        const val TAG = "MainActivity"
        const val LANGUAGE_ZH = "zh"

        /** Extra sent by the bind page with the scanned QR payload */
        const val EXTRA_SCANNED_ENDPOINT = "extra_scanned_endpoint"

        /** Playback polling: quick enough to follow the device, slow enough to stay cheap */
        const val PLAYBACK_POLL_INTERVAL_MS = 3_000L
        const val PLAYBACK_REFRESH_DELAY_MS = 900L

        /** Longest edge used when decoding the device frame for the Now Playing area */
        const val NOW_PLAYING_MAX_PX = 1280

        const val DISABLED_ALPHA = 0.4f

        // Long-press menu item ids
        const val ACTION_PLAY_ITEM = 0
        const val ACTION_ROTATE_ITEM = 1
        const val ACTION_FAVOURITE_ITEM = 2
    }
}
