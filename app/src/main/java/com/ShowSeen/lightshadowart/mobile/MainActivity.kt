package com.ShowSeen.lightshadowart.mobile

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.LruCache
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
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
import kotlin.math.abs

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
    private lateinit var viewDashboardDot: View
    private lateinit var btnStudioFolder: MaterialButton
    private lateinit var tvUploadSummary: TextView

    // Space
    private lateinit var spaceTopBar: PageTopBarView
    private lateinit var spaceSelectBar: View
    private lateinit var btnSpaceCancel: View
    private lateinit var tvSpaceSelected: TextView
    private lateinit var btnSpaceSelectAll: TextView
    private lateinit var tabsSpace: TabLayout
    private lateinit var pagerSpace: ViewPager2
    private lateinit var swipeSpace: SwipeRefreshLayout
    private lateinit var btnToolbarFrame: View
    private lateinit var btnToolbarWall: View
    private lateinit var btnToolbarRotate: View
    private lateinit var btnToolbarPlaybackFav: View
    private lateinit var btnToolbarFav: View
    private lateinit var btnToolbarExportFav: View
    private lateinit var btnToolbarDelete: View
    private lateinit var ivToolbarPlaybackFavIcon: ImageView
    private lateinit var ivToolbarFavIcon: ImageView
    private lateinit var galleryToolbarSelect: View
    private lateinit var galleryToolbarPlayback: View
    private lateinit var spaceEmptyState: View
    private lateinit var nowPlaying: View
    private lateinit var ivNowPlaying: ImageView
    private lateinit var ivNowPlayingFavourite: ImageView
    private lateinit var progressNowPlaying: ProgressBar
    private lateinit var tvNowPlayingStatus: TextView
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

    /** One media adapter per gallery view, in tab order: immersive, by date, favourites. */
    private lateinit var pageAdapters: List<MediaGridAdapter>

    /** Index of the view the pager is on; drives which adapter the page actions read from. */
    private var viewIndex = 0

    private var uploadJob: Job? = null
    private var mediaJob: Job? = null

    /** Hides the finished upload summary a few seconds after the last file settles. */
    private var uploadSummaryHideJob: Job? = null

    /** Polls the device playback state while the gallery is on screen (U-11 / U-14). */
    private var playbackJob: Job? = null

    /** Last playback state the device reported; null means "not known yet" (offline / loading). */
    private var playbackState: PlaybackState? = null

    /** Keeps a second rotate request from stacking on top of one still in flight. */
    private var rotateInFlight = false

    /** Path currently drawn in the Now Playing area, to avoid reloading the same frame. */
    private var nowPlayingPath: String = ""

    /** Local favourite state of the paused frame, so the marker follows a tap immediately. */
    private var nowPlayingFavourite: Boolean? = null

    /** Frame whose favourite mark was last read from the device, so it is not re-read every poll. */
    private var favouriteSyncedPath: String? = null

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

    /**
     * Android back first leaves full-screen playback, then returns to the Dashboard; only from
     * there does it leave the app. An edge swipe is delivered as the system back gesture, so
     * without this branch it would skip the page and leave the device playing.
     */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (currentPage == Page.SPACE && isPlaybackActive()) {
            exitFullScreen()
            return
        }
        if (currentPage != Page.DASHBOARD) {
            showPage(Page.DASHBOARD)
            return
        }
        super.onBackPressed()
    }

    private fun isPlaybackActive(): Boolean {
        val mode = playbackState?.mode
        return mode == PlaybackMode.STATIC || mode == PlaybackMode.AUTO
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
        viewDashboardDot = findViewById(R.id.view_dashboard_dot)
        btnStudioFolder = findViewById(R.id.btn_studio_folder)
        tvUploadSummary = findViewById(R.id.tv_upload_summary)

        spaceTopBar = findViewById(R.id.space_top_bar)
        spaceSelectBar = findViewById(R.id.space_select_bar)
        btnSpaceCancel = findViewById(R.id.btn_space_cancel)
        tvSpaceSelected = findViewById(R.id.tv_space_selected)
        btnSpaceSelectAll = findViewById(R.id.btn_space_select_all)
        tabsSpace = findViewById(R.id.tabs_space)
        pagerSpace = findViewById(R.id.pager_space)
        swipeSpace = findViewById(R.id.swipe_space)
        btnToolbarFrame = findViewById(R.id.btn_toolbar_frame)
        btnToolbarWall = findViewById(R.id.btn_toolbar_wall)
        btnToolbarRotate = findViewById(R.id.btn_toolbar_rotate)
        btnToolbarPlaybackFav = findViewById(R.id.btn_toolbar_playback_fav)
        btnToolbarFav = findViewById(R.id.btn_toolbar_fav)
        btnToolbarExportFav = findViewById(R.id.btn_toolbar_export_fav)
        btnToolbarDelete = findViewById(R.id.btn_toolbar_delete)
        ivToolbarPlaybackFavIcon = btnToolbarPlaybackFav.findViewById(R.id.iv_toolbar_fav_icon)
        ivToolbarFavIcon = btnToolbarFav.findViewById(R.id.iv_toolbar_fav_icon)
        galleryToolbarSelect = findViewById(R.id.gallery_toolbar_select)
        galleryToolbarPlayback = findViewById(R.id.gallery_toolbar_playback)
        spaceEmptyState = findViewById(R.id.space_empty_state)
        nowPlaying = findViewById(R.id.now_playing)
        ivNowPlaying = findViewById(R.id.iv_now_playing)
        ivNowPlayingFavourite = findViewById(R.id.iv_now_playing_favourite)
        progressNowPlaying = findViewById(R.id.progress_now_playing)
        tvNowPlayingStatus = findViewById(R.id.tv_now_playing_status)
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

        setupGalleryPager()
        setupSpaceBars()

        controlPad.onAction = { action -> sendControl(action) }
        controlPad.onCenterClick = { onControlPadCenter() }
        controlPad.onExpandedChanged = { expanded -> applyGalleryBottomInset(expanded) }

        setupNowPlayingGestures()
    }

    /**
     * Gestures on the full-screen frame: a tap keeps its old meaning (toggle auto play / pause),
     * a horizontal swipe (either direction) leaves playback and returns to the browser. Both static
     * and auto playback take the swipe, and travel is measured so a slow drag works as well as a flick.
     */
    private fun setupNowPlayingGestures() {
        val minSwipeDx = resources.displayMetrics.density * SWIPE_EXIT_MIN_DP
        val tapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                onControlPadCenter()
                return true
            }
        })
        var downX = 0f
        var downY = 0f
        nowPlaying.setOnTouchListener { _, event ->
            tapDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                }

                MotionEvent.ACTION_UP -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    val inPlayback = playbackState?.mode == PlaybackMode.STATIC ||
                        playbackState?.mode == PlaybackMode.AUTO
                    if (inPlayback && abs(dx) >= minSwipeDx && abs(dx) > abs(dy)) {
                        exitFullScreen()
                        return@setOnTouchListener true
                    }
                }
            }
            true
        }
        ivNowPlaying.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            positionNowPlayingFavourite()
        }
    }

    /**
     * Three gallery views as one control (UI PRD §3.6 B): a TabLayout drives a ViewPager2 whose pages
     * are the three grids. Tab and swiping both select the view, and the indicator plus the bold
     * label follow, so the selection is not shown by colour alone.
     */
    private fun setupGalleryPager() {
        val modes = intArrayOf(BROWSER_MODE_IMMERSIVE, BROWSER_MODE_DATE, BROWSER_MODE_FAVOURITE)
        pageAdapters = modes.map { mode ->
            MediaGridAdapter(thumbnailProvider).apply {
                setGroupByDate(mode == BROWSER_MODE_DATE)
                onItemClick = { item ->
                    // Send the tapped path: the device may not have loaded this view's order yet
                    sendControl(CTRL_PLAY, JSONObject().put("path", item.path))
                    toast(getString(R.string.toast_media_playing))
                    Log.i(TAG, "media click: ${item.path}")
                }
                onItemLongClick = { item -> startSelection(this, item) }
                onSelectionChanged = { _, _ -> renderSelectionUi() }
            }
        }

        // Keep all three pages alive so each view keeps its scroll position across tabs
        pagerSpace.offscreenPageLimit = modes.size
        pagerSpace.adapter = GalleryPagerAdapter(modes.size) { position, list ->
            list.layoutManager = if (modes[position] == BROWSER_MODE_DATE) {
                LinearLayoutManager(this)
            } else {
                GridLayoutManager(this, MEDIA_SPAN_COUNT)
            }
            list.adapter = pageAdapters[position]
        }

        val labels = intArrayOf(
            R.string.space_mode_immersive,
            R.string.space_mode_date,
            R.string.space_mode_favourite
        )
        labels.forEach { labelRes ->
            val tab = tabsSpace.newTab()
            tab.customView = layoutInflater.inflate(R.layout.item_space_tab, tabsSpace, false)
                .apply { (this as TextView).setText(labelRes) }
            tabsSpace.addTab(tab)
        }
        renderTabSelection(0)

        tabsSpace.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                renderTabSelection(tab.position)
                if (pagerSpace.currentItem != tab.position) pagerSpace.setCurrentItem(tab.position, true)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit

            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        pagerSpace.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                selectGalleryView(position)
            }
        })

        swipeSpace.setOnRefreshListener {
            if (SessionStore.isActive) {
                loadGalleryView(viewIndex, refreshing = true)
            } else {
                swipeSpace.isRefreshing = false
                requireSession()
            }
        }
    }

    private fun setupSpaceBars() {
        btnSpaceCancel.setOnClickListener { exitSelection() }
        btnSpaceSelectAll.setOnClickListener {
            adapterForCurrentView().toggleSelectAll()
        }
        btnToolbarFrame.setOnClickListener { openFrameDesign() }
        btnToolbarWall.setOnClickListener { openBackgrounds() }
        btnToolbarRotate.setOnClickListener { rotate() }
        btnToolbarPlaybackFav.setOnClickListener { onFavouriteClick() }
        btnToolbarFav.setOnClickListener { onFavouriteClick() }
        btnToolbarExportFav.setOnClickListener { exportFavourite() }
        btnToolbarDelete.setOnClickListener { onDeleteClick() }
    }

    private fun adapterForCurrentView(): MediaGridAdapter = pageAdapters[viewIndex]

    /** Tab selection state: primary colour and bold weight, on top of the indicator bar. */
    private fun renderTabSelection(position: Int) {
        for (i in 0 until tabsSpace.tabCount) {
            val label = tabsSpace.getTabAt(i)?.customView as? TextView ?: continue
            val selected = i == position
            label.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (selected) R.color.primary_blue else R.color.text_secondary
                )
            )
            label.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    /**
     * Switch the gallery to [position]: exit any selection, then load that view if it is empty.
     *
     * The tab is selected here too, so a swipe and a tab tap leave the same state: the indicator, the
     * bold label and the visible page always agree.
     */
    private fun selectGalleryView(position: Int) {
        if (viewIndex == position) {
            tabsSpace.elevateSelection(position)
            return
        }
        // A selection belongs to one view, so switching tabs ends it rather than acting across views
        adapterForCurrentView().clearSelection()
        viewIndex = position
        currentBrowseMode = BROWSER_VIEW_MODES[position]
        tabsSpace.elevateSelection(position)
        if (adapterForCurrentView().itemCount == 0) loadGalleryView(position)
        sendBrowseMode(currentBrowseMode)
        updateMediaEmpty()
    }

    /** Move the TabLayout's own selection (indicator included) without re-entering this method. */
    private fun TabLayout.elevateSelection(position: Int) {
        val tab = getTabAt(position) ?: return
        if (selectedTabPosition != position) selectTab(tab)
    }

    /** Bottom inset for the grids: the floating bars always, plus the pad while it is expanded. */
    private fun applyGalleryBottomInset(padExpanded: Boolean) {
        val extra = if (padExpanded) resources.getDimensionPixelSize(R.dimen.control_pad_diameter) else 0
        val base = resources.getDimensionPixelSize(R.dimen.gallery_bottom_inset) + extra
        for (index in 0 until pagerSpace.childCount) {
            val page = pagerSpace.getChildAt(index) as? RecyclerView ?: continue
            page.setPadding(page.paddingLeft, page.paddingTop, page.paddingRight, base)
        }
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
                renderTabSelection(viewIndex)
                loadGalleryView(viewIndex)
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
        pageAdapters.forEach { it.submit(emptyList()) }
        updateMediaEmpty()
        playbackState = null
        nowPlayingPath = ""
        nowPlayingFavourite = null
        favouriteSyncedPath = null
        ivNowPlayingFavourite.isVisible = false
        renderFavouriteIcons()
    }

    override fun onSessionEstablished() {
        exitSelection()
        loadGalleryView(viewIndex)
    }

    override fun onStudioSelectionConfirmed() {
        loadGalleryView(viewIndex)
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
     * Dashboard brand block (UI PRD §3.3 A): the state dot sits beside the brand name; its state is
     * also exposed as content description so the meaning never relies on color alone. Network name
     * and device address stay on the Network Connection page instead of the dashboard.
     */
    private fun renderDashboard() {
        val endpoint = SessionStore.endpoint
        val active = endpoint != null && SessionStore.isActive

        val stateRes = when {
            connecting -> R.string.network_state_connecting
            active -> R.string.network_state_connected
            else -> R.string.dashboard_not_connected
        }
        viewDashboardDot.contentDescription = getString(stateRes)

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

        btnStudioFolder.isVisible = active && SessionStore.inStudioMode && SessionStore.isAdmin
    }

    private fun renderSpaceAvailability() {
        swipeSpace.isEnabled = SessionStore.isActive
        renderToolbarAvailability()
    }

    // -----------------------------------------------------------------------
    // Space: the three gallery views
    // -----------------------------------------------------------------------

    /**
     * Load one gallery view. Each tab keeps its own data set and scroll position, so switching tabs
     * never refetches what is already there; a pull-to-refresh forces the reload.
     */
    private fun loadGalleryView(position: Int, refreshing: Boolean = false) {
        val adapter = pageAdapters[position]
        val endpoint = SessionStore.endpoint
        val sessionId = SessionStore.sessionId
        if (endpoint == null || sessionId == null) {
            adapter.submit(emptyList())
            swipeSpace.isRefreshing = false
            updateMediaEmpty()
            return
        }

        mediaJob?.cancel()
        val favouriteOnly = BROWSER_VIEW_MODES[position] == BROWSER_MODE_FAVOURITE
        mediaJob = lifecycleScope.launch {
            client.listMedia(endpoint, sessionId, favouriteOnly)
                .onSuccess {
                    adapter.submit(it)
                    updateMediaEmpty()
                    if (refreshing) toast(getString(R.string.space_refresh_done, it.size))
                }
                .onFailure { error ->
                    if (error is SessionExpiredException) {
                        handleSessionExpired()
                        return@launch
                    }
                    // Never pretend a refresh worked: say what failed and allow another pull
                    toast(getString(R.string.toast_media_failed, error.message.orEmpty()))
                }
            swipeSpace.isRefreshing = false
        }
    }

    private fun sendBrowseMode(mode: Int) {
        if (!SessionStore.isActive) return
        sendControl(CTRL_SWITCH_VIEW, JSONObject().put("mode", mode))
    }

    /** Empty state belongs to the view that is on screen, and never overlaps the Now Playing frame. */
    private fun updateMediaEmpty() {
        val empty = adapterForCurrentView().itemCount == 0
        spaceEmptyState.isVisible = empty && !nowPlaying.isVisible
    }

    /**
     * Re-render the top bar and the bottom bar for the current selection state (UI PRD §3.6
     * A / D / F): the top bar swaps to cancel / count / select all, and the multi-select bar
     * appears with favourite / delete / export favourites.
     */
    private fun renderSelectionUi() {
        val adapter = adapterForCurrentView()
        val selecting = adapter.selectionMode
        val count = adapter.selectedPaths().size

        spaceTopBar.isVisible = !selecting
        spaceSelectBar.isVisible = selecting
        tvSpaceSelected.text = getString(R.string.space_selected_count, count)
        btnSpaceSelectAll.setText(
            if (adapter.allSelected()) R.string.space_deselect_all else R.string.space_select_all
        )

        renderToolbarAvailability()
    }

    /**
     * The two bottom bars never show together (UI PRD §3.6 D): a multi-select shows favourite /
     * delete / export favourites, a paused full-screen frame shows favourite / frame / wallpaper /
     * rotate. Both stay hidden in every other state. The paused bar keeps favourite so the frame on
     * screen can be marked without leaving playback. Dimmed tools stay clickable so a tap explains
     * why the action is unavailable instead of failing silently.
     */
    private fun renderToolbarAvailability() {
        val selecting = adapterForCurrentView().selectionMode
        val static = SessionStore.isActive && playbackState?.mode == PlaybackMode.STATIC

        galleryToolbarSelect.isVisible = selecting
        galleryToolbarPlayback.isVisible = static && !selecting

        setToolAvailable(btnToolbarDelete, !SessionStore.inStudioMode)
        setToolAvailable(btnToolbarFrame, SessionStore.isAdmin)
        setToolAvailable(btnToolbarWall, SessionStore.isAdmin)
        setToolAvailable(btnToolbarRotate, true)
        renderFavouriteIcons()
    }

    private fun setToolAvailable(view: View, available: Boolean) {
        view.alpha = if (available) 1f else DISABLED_ALPHA
    }

    /**
     * The favourite keys show an outline heart until what they act on really is a favourite: the
     * paused frame for the playback bar, every selected item for the multi-select bar. The frame's
     * state is read from the device (see [syncCurrentFrameFavourite]), so entering a photo that is
     * already marked shows a filled heart straight away.
     */
    private fun renderFavouriteIcons() {
        setFavouriteIcon(ivToolbarPlaybackFavIcon, currentFrameFavourite() == true)
        setFavouriteIcon(ivToolbarFavIcon, adapterForCurrentView().selectedAllFavourite())
    }

    private fun setFavouriteIcon(icon: ImageView, favourited: Boolean) {
        icon.setImageResource(
            if (favourited) R.drawable.ic_favourite else R.drawable.ic_favourite_outline
        )
    }

    /** Best known favourite state of the frame on screen; null while nothing is known yet. */
    private fun currentFrameFavourite(): Boolean? {
        val path = playbackState?.lastFile.orEmpty()
        if (path.isEmpty()) return null
        return nowPlayingFavourite ?: pageAdapters.firstNotNullOfOrNull { it.favouriteOf(path) }
    }

    /**
     * Reads the paused frame's favourite mark from the device, so the key never trusts a stale
     * local value: the frame may not be part of the loaded grid at all (another view, a fresh
     * session). One request per frame, not per poll.
     */
    private fun syncCurrentFrameFavourite(path: String) {
        if (playbackState?.mode != PlaybackMode.STATIC || path.isEmpty()) {
            favouriteSyncedPath = null
            return
        }
        if (path == favouriteSyncedPath) return
        val endpoint = SessionStore.endpoint
        val sessionId = SessionStore.sessionId
        if (endpoint == null || sessionId == null) return

        favouriteSyncedPath = path
        lifecycleScope.launch {
            client.listMedia(endpoint, sessionId, favouriteOnly = true)
                .onSuccess { items ->
                    // The device may have moved on while the list was in flight
                    if (playbackState?.lastFile != path ||
                        playbackState?.mode != PlaybackMode.STATIC
                    ) {
                        return@onSuccess
                    }
                    val favourite = items.any { it.path == path }
                    nowPlayingFavourite = favourite
                    pageAdapters.forEach { it.updateFavourite(path, favourite) }
                    ivNowPlayingFavourite.isVisible = favourite
                    renderFavouriteIcons()
                }
        }
    }

    private fun startSelection(adapter: MediaGridAdapter, item: MediaItem) {
        adapter.startSelection(item)
        renderSelectionUi()
    }

    private fun exitSelection() {
        pageAdapters.forEach { it.clearSelection() }
        renderSelectionUi()
    }

    /** Fav: batch in selection mode, otherwise the single frame the device is showing. */
    private fun onFavouriteClick() {
        if (adapterForCurrentView().selectionMode) {
            favouriteSelected()
            return
        }
        if (!requireCurrentFrame()) return
        val path = playbackState?.lastFile.orEmpty()
        if (path.isEmpty()) {
            toast(getString(R.string.space_frame_not_ready))
            return
        }
        val endpoint = SessionStore.endpoint
        val sessionId = SessionStore.sessionId
        if (endpoint == null || sessionId == null) {
            requireSession()
            return
        }
        // The local mark wins over the lists: the paused frame may not be in the loaded view at all
        val known = currentFrameFavourite()
        val target = known != true
        lifecycleScope.launch {
            client.favourite(endpoint, sessionId, path, target)
                .onSuccess {
                    toast(
                        getString(
                            if (target) R.string.toast_favourite_added
                            else R.string.toast_favourite_removed
                        )
                    )
                    // Mark the paused frame, its key and every loaded thumbnail before the reload
                    nowPlayingFavourite = target
                    favouriteSyncedPath = path
                    ivNowPlayingFavourite.isVisible = target
                    pageAdapters.forEach { it.updateFavourite(path, target) }
                    renderFavouriteIcons()
                    loadGalleryView(viewIndex)
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

    /**
     * Rotate the physical frame 90° clockwise (UI PRD §3.6 D). Requests are serialised so a double
     * tap cannot queue two turns, and a failure says the connection should be checked.
     */
    private fun rotate() {
        if (!requireCurrentFrame() || rotateInFlight) return
        val endpoint = SessionStore.endpoint
        val sessionId = SessionStore.sessionId
        if (endpoint == null || sessionId == null) {
            requireSession()
            return
        }
        rotateInFlight = true
        btnToolbarRotate.isEnabled = false
        lifecycleScope.launch {
            client.control(endpoint, sessionId, CTRL_ROTATE, JSONObject().put("degree", ROTATE_DEGREE))
                .onSuccess { startPlaybackPolling(refreshSoon = true) }
                .onFailure { error ->
                    if (error is SessionExpiredException) {
                        handleSessionExpired()
                    } else {
                        toast(getString(R.string.space_rotate_failed))
                    }
                }
            rotateInFlight = false
            btnToolbarRotate.isEnabled = true
            renderToolbarAvailability()
        }
    }

    /** Fav / Del on the current frame need a connected device sitting on a paused photo. */
    private fun requireCurrentFrame(): Boolean {
        if (!SessionStore.isActive) {
            requireSession()
            return false
        }
        if (adapterForCurrentView().selectionMode) {
            toast(getString(R.string.space_edit_need_exit_selection))
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

    /** Favourite the selected items, then leave the selection (UI PRD §3.6 D / F). */
    private fun favouriteSelected() {
        val adapter = adapterForCurrentView()
        val paths = adapter.selectedPaths()
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
        val target = !adapter.selectedAllFavourite()
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
            exitSelection()
            loadGalleryView(viewIndex)
        }
    }

    /** Del: batch in selection mode, otherwise the single frame the device is showing. */
    private fun onDeleteClick() {
        if (adapterForCurrentView().selectionMode) {
            deleteSelected()
            return
        }
        if (!requireCurrentFrame()) return
        if (SessionStore.inStudioMode) {
            toast(getString(R.string.toast_delete_personal_only))
            return
        }
        val path = playbackState?.lastFile.orEmpty()
        if (path.isEmpty()) {
            toast(getString(R.string.space_frame_not_ready))
            return
        }
        confirmDelete(listOf(path))
    }

    /** Multi-select delete; personal mode only (the service refuses store mode). */
    private fun deleteSelected() {
        val paths = adapterForCurrentView().selectedPaths()
        if (paths.isEmpty()) {
            toast(getString(R.string.toast_delete_none))
            return
        }
        if (SessionStore.inStudioMode) {
            toast(getString(R.string.toast_delete_personal_only))
            return
        }
        confirmDelete(paths)
    }

    /** Delete always asks first; [paths] is either the selection or the one frame on screen. */
    private fun confirmDelete(paths: List<String>) {
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
                            exitSelection()
                            loadGalleryView(viewIndex)
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
     * Export every favourite into their own Fav folder. Driven by the device's current markers:
     * newly marked items are copied in, unmarked leftovers are removed. Independent of the current
     * frame and of any selection (UI PRD §3.6 D).
     */
    private fun exportFavourite() {
        val endpoint = SessionStore.endpoint
        val sessionId = SessionStore.sessionId
        if (endpoint == null || sessionId == null) {
            requireSession()
            return
        }
        btnToolbarExportFav.isEnabled = false
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
                    exitSelection()
                    loadGalleryView(viewIndex)
                }
                .onFailure { error ->
                    if (error is SessionExpiredException) {
                        handleSessionExpired()
                    } else {
                        toast(getString(R.string.toast_export_fav_failed, error.message.orEmpty()))
                    }
                }
            btnToolbarExportFav.isEnabled = true
            renderToolbarAvailability()
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

        // The whole bar follows the device state: only a paused frame unlocks every action
        renderToolbarAvailability()
        renderNowPlaying(state)
        if (nowPlaying.isVisible) ivNowPlaying.post { positionNowPlayingFavourite() }
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
            nowPlayingFavourite = null
            favouriteSyncedPath = null
            ivNowPlayingFavourite.isVisible = false
            ivNowPlaying.setImageDrawable(null)
            renderFavouriteIcons()
            updateMediaEmpty()
            return
        }

        updateMediaEmpty()
        val path = state?.lastFile.orEmpty()
        renderNowPlayingFavourite(state, path)
        // Read the mark from the device once per frame, so an already-favourited photo shows a
        // filled heart without waiting for the gallery list to be reloaded
        syncCurrentFrameFavourite(path)
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
                ivNowPlaying.post { positionNowPlayingFavourite() }
            }
        }
    }

    /**
     * The full-screen frame is drawn with `fitCenter`, so the visible photo is smaller than its
     * container on one axis. Anchor the heart to the visible photo's top-right corner (not the
     * container corner), otherwise it floats on the letterbox above the artwork.
     */
    private fun positionNowPlayingFavourite() {
        val drawable = ivNowPlaying.drawable ?: return
        val intrinsicW = drawable.intrinsicWidth
        val intrinsicH = drawable.intrinsicHeight
        val viewW = ivNowPlaying.width
        val viewH = ivNowPlaying.height
        if (intrinsicW <= 0 || intrinsicH <= 0 || viewW <= 0 || viewH <= 0) return
        if (nowPlaying.width <= 0 || ivNowPlayingFavourite.width <= 0) return

        val scale = minOf(viewW.toFloat() / intrinsicW, viewH.toFloat() / intrinsicH)
        val imageW = intrinsicW * scale
        val imageH = intrinsicH * scale
        val imageLeft = ivNowPlaying.left + (viewW - imageW) / 2f
        val imageTop = ivNowPlaying.top + (viewH - imageH) / 2f
        val gap = resources.getDimensionPixelSize(R.dimen.item_gap)
        val right = (nowPlaying.width - (imageLeft + imageW) + gap).toInt()
        val top = (imageTop + gap).toInt()

        val lp = ivNowPlayingFavourite.layoutParams as FrameLayout.LayoutParams
        if (lp.gravity == (Gravity.TOP or Gravity.END) && lp.topMargin == top && lp.rightMargin == right) {
            return
        }
        lp.gravity = Gravity.TOP or Gravity.END
        lp.topMargin = top
        lp.rightMargin = right
        ivNowPlayingFavourite.layoutParams = lp
    }

    /**
     * Heart on the paused frame: the local value wins right after a tap, the gallery lists and the
     * device read fill it in otherwise. Only a static frame carries the marker, so auto play never
     * shows a stale heart.
     */
    private fun renderNowPlayingFavourite(state: PlaybackState?, path: String) {
        if (path != nowPlayingPath) nowPlayingFavourite = null
        if (state?.mode != PlaybackMode.STATIC || path.isEmpty()) {
            ivNowPlayingFavourite.isVisible = false
            renderFavouriteIcons()
            return
        }
        val favourite = currentFrameFavourite() ?: false
        ivNowPlayingFavourite.isVisible = favourite
        renderFavouriteIcons()
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

    /** Horizontal swipe on the frame: end playback on the device and come back to the browser. */
    private fun exitFullScreen() {
        if (!SessionStore.isActive) {
            requireSession()
            return
        }
        sendControl(CTRL_PAUSE)
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
        if (adapterForCurrentView().selectionMode) {
            toast(getString(R.string.space_edit_need_exit_selection))
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
        uploadSummaryHideJob?.cancel()
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

            uploadSummaryHideJob = lifecycleScope.launch {
                delay(UPLOAD_SUMMARY_AUTO_HIDE_MS)
                uploadAdapter.clear()
                updateUploadSummary()
            }
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

        /** How long the finished upload summary stays on the dashboard before it hides itself */
        const val UPLOAD_SUMMARY_AUTO_HIDE_MS = 3_000L

        /** Longest edge used when decoding the device frame for the Now Playing area */
        const val NOW_PLAYING_MAX_PX = 1280

        const val DISABLED_ALPHA = 0.4f

        /** Minimum horizontal travel of the swipe that leaves full-screen playback */
        const val SWIPE_EXIT_MIN_DP = 72f

        /** Gallery views in tab order: immersive, by date, favourites (UI PRD §3.6 B). */
        val BROWSER_VIEW_MODES = intArrayOf(
            BROWSER_MODE_IMMERSIVE,
            BROWSER_MODE_DATE,
            BROWSER_MODE_FAVOURITE
        )
    }
}
