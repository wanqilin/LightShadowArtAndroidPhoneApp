package com.ShowSeen.lightshadowart.mobile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.ShowSeen.lightshadowart.mobile.net.DeviceClient
import com.ShowSeen.lightshadowart.mobile.net.FrameBackground
import com.ShowSeen.lightshadowart.mobile.net.FrameConfig
import com.ShowSeen.lightshadowart.mobile.net.FrameParamInfo
import com.ShowSeen.lightshadowart.mobile.net.FrameStyleInfo
import com.ShowSeen.lightshadowart.mobile.net.PanelInfo
import com.ShowSeen.lightshadowart.mobile.net.SessionExpiredException
import com.ShowSeen.lightshadowart.mobile.net.toUploadSource
import kotlinx.coroutines.launch

/**
 * Frame + background editor (PRD 6.9 / 6.10).
 *
 * One page, two scopes:
 * - [SCOPE_IMAGE]: the picture on screen keeps its own parameters and background;
 * - [SCOPE_DEFAULT]: the fallback every un-customised picture uses, reachable from Settings → Parameters.
 *
 * Rows are generated from the device's style schema, so only the parameters the selected style really
 * reads appear, and every row is a 44dp compact line (label + value + slider) so the live preview
 * still fits one screen. Values are sent as a partial update: only what this page owns is written.
 */
class FrameDesignActivity : AppCompatActivity() {

    companion object {
        /** [SCOPE_IMAGE] edits one picture, [SCOPE_DEFAULT] edits the fallback layer */
        const val EXTRA_SCOPE = "extra_frame_scope"
        /** Absolute media path, required for [SCOPE_IMAGE] */
        const val EXTRA_PATH = "extra_frame_path"
        /** File name of the picture on screen, only used for the "applies to" line */
        const val EXTRA_DISPLAY_NAME = "extra_frame_display_name"

        const val SCOPE_DEFAULT = "default"
        const val SCOPE_IMAGE = "image"

        private const val ROW_HEIGHT_DP = 44
        private const val HALF_STEP = 2f

        /** Step of the -/+ buttons, in dp */
        private const val STEP_DP = 0.5f
        private const val DISABLED_ALPHA = 0.45f
    }

    private lateinit var client: DeviceClient
    private lateinit var tvScope: TextView
    private lateinit var preview: FramePreviewView
    private lateinit var switchEnabled: MaterialSwitch
    private lateinit var styleSpinner: Spinner
    private lateinit var containerParams: LinearLayout
    private lateinit var containerAdvanced: LinearLayout
    private lateinit var tvAdvanced: TextView
    private lateinit var cardBackground: MaterialCardView
    private lateinit var btnBgBlur: MaterialButton
    private lateinit var btnBgSolid: MaterialButton
    private lateinit var btnBgImage: MaterialButton
    private lateinit var rowBgColor: View
    private lateinit var bgColorPreview: View
    private lateinit var btnBgUpload: MaterialButton
    private lateinit var tvStatus: TextView
    private lateinit var btnReset: MaterialButton
    private lateinit var btnApply: MaterialButton

    private var scope = SCOPE_DEFAULT
    private var path: String? = null
    private var displayName = ""

    private var config: FrameConfig? = null
    private var styleId = ""
    private var values = mutableMapOf<String, Any>()
    private var background = FrameBackground()
    private var frameEnabled = false
    private var advancedExpanded = false
    private var applying = false
    private var loaded = false
    private var bgBitmap: Bitmap? = null

    /** Values per style within this session, so switching styles back and forth does not lose tuning */
    private val styleValueCache = mutableMapOf<String, MutableMap<String, Any>>()

    /** Rows currently on screen, by parameter id (used to toggle the dependent rows) */
    private val rowsById = mutableMapOf<String, View>()
    private val swatchesById = mutableMapOf<String, View>()

    private var pendingColorParam: FrameParamInfo? = null
    private var pendingBackgroundColor = false

    private val colorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val color = result.data?.getIntExtra(LineColorActivity.EXTRA_COLOR, Color.BLACK)
            ?: Color.BLACK
        val param = pendingColorParam
        when {
            pendingBackgroundColor -> {
                background = background.copy(color = opaque(color))
                renderBackground()
                renderPreview()
            }
            param != null -> {
                values[param.id] = opaque(color)
                swatchesById[param.id]?.setBackgroundColor(opaque(color))
                renderPreview()
            }
        }
        pendingColorParam = null
        pendingBackgroundColor = false
    }

    private val imageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) uploadBackground(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_frame_design)

        scope = intent.getStringExtra(EXTRA_SCOPE) ?: SCOPE_DEFAULT
        path = intent.getStringExtra(EXTRA_PATH)
        displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME).orEmpty()

        SessionStore.init(this)
        client = DeviceClient(contentResolver)

        tvScope = findViewById(R.id.tv_frame_scope)
        preview = findViewById(R.id.preview_frame)
        switchEnabled = findViewById(R.id.switch_frame_design_enabled)
        styleSpinner = findViewById(R.id.spinner_frame_style)
        containerParams = findViewById(R.id.container_frame_params)
        containerAdvanced = findViewById(R.id.container_frame_advanced)
        tvAdvanced = findViewById(R.id.tv_frame_advanced)
        cardBackground = findViewById(R.id.card_frame_background)
        btnBgBlur = findViewById(R.id.btn_frame_bg_blur)
        btnBgSolid = findViewById(R.id.btn_frame_bg_solid)
        btnBgImage = findViewById(R.id.btn_frame_bg_image)
        rowBgColor = findViewById(R.id.row_frame_bg_color)
        bgColorPreview = findViewById(R.id.view_frame_bg_color_preview)
        btnBgUpload = findViewById(R.id.btn_frame_bg_upload)
        tvStatus = findViewById(R.id.tv_frame_status)
        btnReset = findViewById(R.id.btn_frame_design_reset)
        btnApply = findViewById(R.id.btn_frame_design_apply)

        findViewById<PageTopBarView>(R.id.frame_design_top_bar).apply {
            setTitle(
                getString(
                    if (scope == SCOPE_IMAGE) R.string.frame_design_title
                    else R.string.frame_default_title
                )
            )
            setOnBackClickListener { finish() }
        }

        switchEnabled.setOnCheckedChangeListener { _, checked ->
            frameEnabled = checked
            renderParamsEnabled()
            renderPreview()
        }
        tvAdvanced.setOnClickListener {
            advancedExpanded = !advancedExpanded
            renderAdvancedVisibility()
        }
        btnBgBlur.setOnClickListener { setBackgroundMode(FrameBackground.MODE_BLUR) }
        btnBgSolid.setOnClickListener { setBackgroundMode(FrameBackground.MODE_SOLID) }
        btnBgImage.setOnClickListener {
            if (background.hasImage) {
                setBackgroundMode(FrameBackground.MODE_IMAGE)
            } else {
                // No backdrop on the device yet: pick one first
                imageLauncher.launch("image/*")
            }
        }
        btnBgUpload.setOnClickListener { imageLauncher.launch("image/*") }
        rowBgColor.setOnClickListener {
            pendingBackgroundColor = true
            pendingColorParam = null
            colorLauncher.launch(
                android.content.Intent(this, LineColorActivity::class.java)
                    .putExtra(LineColorActivity.EXTRA_COLOR, background.color)
                    .putExtra(
                        LineColorActivity.EXTRA_TITLE_RES,
                        R.string.line_color_title_background
                    )
            )
        }
        btnReset.setOnClickListener { resetToDefault() }
        btnApply.setOnClickListener { applyToDevice() }
        // The inline preview is only 140dp tall: tapping it opens a bigger one for the fine details
        preview.setOnClickListener { showFullScreenPreview() }

        // Apply and reset stay disabled until the device state has been read
        btnApply.isEnabled = false
        btnReset.isEnabled = false
        tvScope.text = getString(
            if (scope == SCOPE_IMAGE) R.string.frame_scope_image else R.string.frame_scope_default
        )

        load()
    }

    // ------------------------------------------------------------------
    // Device state
    // ------------------------------------------------------------------

    private fun load() {
        val endpoint = SessionStore.endpoint
        val sessionId = SessionStore.sessionId
        if (endpoint == null || sessionId == null) {
            toast(getString(R.string.toast_need_session))
            finish()
            return
        }
        lifecycleScope.launch {
            client.readFrame(endpoint, sessionId, if (scope == SCOPE_IMAGE) path else null)
                .onSuccess { loaded0 ->
                    config = loaded0
                    val state = loaded0.state
                    styleId = state.styleId.ifEmpty {
                        loaded0.styles.firstOrNull()?.id.orEmpty()
                    }
                    values = state.values.toMutableMap()
                    styleValueCache[styleId] = values
                    background = state.background
                    frameEnabled = state.frameEnabled
                    loaded = true
                    renderScope()
                    renderSpinner()
                    renderAll()
                    loadPreviewPhoto(endpoint, sessionId)
                }
                .onFailure { failure ->
                    handleFailure(failure, getString(R.string.frame_load_failed, ""))
                    finish()
                }
        }
    }

    /**
     * Fetches the picture on screen so the preview shows the real thing instead of a placeholder.
     *
     * Only the image scope has a picture; the default layer has none and keeps the placeholder.
     * Decoded at most 1024px on the long edge (the same rule the space page uses) so a 20MP photo
     * does not blow up the editor.
     */
    private fun loadPreviewPhoto(endpoint: com.ShowSeen.lightshadowart.mobile.net.DeviceEndpoint,
                                 sessionId: String) {
        val mediaPath = path
        if (scope != SCOPE_IMAGE || mediaPath.isNullOrEmpty()) return
        lifecycleScope.launch {
            val bytes = client.mediaRaw(endpoint, sessionId, mediaPath) ?: return@launch
            val bitmap = decodeSampledBytes(bytes, 1024) ?: return@launch
            preview.setPhotoBitmap(bitmap)
        }
    }

    private fun decodeSampledBytes(bytes: ByteArray, maxPx: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }.getOrNull()

    /** Column separator-free label lookup used by both the row builder and the precise-entry dialog */
    private fun specOf(id: String): FrameParamInfo? =
        currentStyle()?.params?.firstOrNull { it.id == id }

    private fun currentStyle(): FrameStyleInfo? {
        val cfg = config ?: return null
        return cfg.styles.firstOrNull { it.id == styleId } ?: cfg.styles.firstOrNull()
    }

    private fun handleFailure(failure: Throwable, fallback: String) {
        if (failure is SessionExpiredException) {
            SessionStore.clear()
            toast(getString(R.string.toast_session_expired))
        } else {
            toast(fallback + (failure.message ?: ""))
        }
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private fun renderScope() {
        if (scope == SCOPE_IMAGE && displayName.isNotEmpty()) {
            tvScope.text = getString(R.string.frame_scope_image_named, displayName)
        }
    }

    private fun renderSpinner() {
        val cfg = config ?: return
        styleSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            cfg.styles.map { it.name }
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        cfg.styles.indexOfFirst { it.id == styleId }.takeIf { it >= 0 }?.let {
            styleSpinner.setSelection(it)
        }
        styleSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val picked = cfg.styles.getOrNull(position) ?: return
                if (picked.id == styleId) return
                styleId = picked.id
                values = styleValueCache.getOrPut(styleId) {
                    picked.params.associate { it.id to it.defaultValue }.toMutableMap()
                }
                renderAll()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun renderAll() {
        switchEnabled.isChecked = frameEnabled
        renderRows()
        renderAdvancedVisibility()
        renderBackground()
        renderParamsEnabled()
        renderPreview()
        btnApply.isEnabled = loaded && !applying
        btnReset.isEnabled = loaded && !applying
        // "Restore default" only means something for one picture: the default layer is the bottom level
        btnReset.isVisible = scope == SCOPE_IMAGE
    }

    private fun renderParamsEnabled() {
        val alpha = if (frameEnabled) 1f else DISABLED_ALPHA
        styleSpinner.alpha = alpha
        containerParams.alpha = alpha
        tvAdvanced.alpha = alpha
        containerAdvanced.alpha = alpha
        cardBackground.alpha = alpha
        // No frame means no frame parameters: only the switch stays usable
        styleSpinner.isEnabled = frameEnabled
        containerParams.isEnabled = frameEnabled
        containerAdvanced.isEnabled = frameEnabled
        tvAdvanced.isEnabled = frameEnabled
        btnApply.isEnabled = loaded && !applying
    }

    private fun renderRows() {
        val style = currentStyle() ?: return
        containerParams.removeAllViews()
        containerAdvanced.removeAllViews()
        rowsById.clear()
        swatchesById.clear()

        for (spec in style.params) {
            val parent = if (spec.advanced) containerAdvanced else containerParams
            val row = buildRow(spec)
            rowsById[spec.id] = row
            parent.addView(row)
        }
        updateDependentRows()
    }

    private fun renderAdvancedVisibility() {
        val hasAdvanced = currentStyle()?.params?.any { it.advanced } == true
        tvAdvanced.isVisible = hasAdvanced
        containerAdvanced.isVisible = hasAdvanced && advancedExpanded
        tvAdvanced.text = getString(
            if (advancedExpanded) R.string.frame_advanced_hide else R.string.frame_advanced_show
        )
    }

    private fun renderBackground() {
        // The active mode is marked with a check icon: the pill is too narrow for a text suffix
        // (the suffix was clipped on device) and the state must not rely on colour alone (PRD 2.1)
        markBackgroundMode(btnBgBlur, R.string.frame_bg_blur, FrameBackground.MODE_BLUR)
        markBackgroundMode(btnBgSolid, R.string.frame_bg_solid, FrameBackground.MODE_SOLID)
        markBackgroundMode(btnBgImage, R.string.frame_bg_image, FrameBackground.MODE_IMAGE)
        rowBgColor.isVisible = background.mode == FrameBackground.MODE_SOLID
        btnBgUpload.isVisible = background.mode == FrameBackground.MODE_IMAGE
        btnBgUpload.text = getString(
            if (background.hasImage) R.string.frame_bg_image_replace else R.string.frame_bg_image_pick
        )
        bgColorPreview.setBackgroundColor(opaque(background.color))
        tvStatus.isVisible = background.mode == FrameBackground.MODE_IMAGE &&
            background.hasImage && bgBitmap == null
        if (tvStatus.isVisible) tvStatus.text = getString(R.string.frame_bg_image_remote)
    }

    private fun renderPreview() {
        val style = currentStyle() ?: return
        val panel = config?.panel ?: PanelInfo()
        preview.setFrame(style.params, values, background, panel)
        preview.setBackgroundBitmap(
            if (background.mode == FrameBackground.MODE_IMAGE) bgBitmap else null
        )
    }

    private fun markBackgroundMode(button: MaterialButton, labelRes: Int, mode: Int) {
        button.text = getString(labelRes)
        val active = background.mode == mode
        button.icon = if (active) ContextCompat.getDrawable(this, R.drawable.ic_check) else null
        button.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        // Three pills share one row: zero the default insets and keep the tick small, otherwise the
        // icon squeezes the label ("纯色" was clipped to "纯")
        button.iconSize = dp(14)
        button.iconPadding = dp(2)
        // MaterialButton's default horizontal insets plus the pill's padding leave no room for
        // "icon + two characters" in a third of the row, so trim the text padding here
        button.setPadding(dp(6), button.paddingTop, dp(6), button.paddingBottom)
    }

    // ------------------------------------------------------------------
    // Rows
    // ------------------------------------------------------------------

    private fun buildRow(spec: FrameParamInfo): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dp(ROW_HEIGHT_DP)
        }
        val label = TextView(this).apply {
            text = paramLabel(spec.id)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.1f)
        }
        row.addView(label)

        if (spec.isLength) {
            val current = (values[spec.id] as? Number)?.toFloat() ?: spec.defaultDp

            fun applyValue(value: Float) {
                val clamped = value.coerceIn(spec.minDp, spec.maxDp)
                values[spec.id] = clamped
                updateDependentRows()
                renderPreview()
            }

            // Steppers: dragging a slider to an exact number is impractical, so -/+ give 0.5dp steps
            val minus = stepButton("-") {
                applyValue(((values[spec.id] as? Number)?.toFloat() ?: spec.defaultDp) - STEP_DP)
                renderRows()
            }
            val plus = stepButton("+") {
                applyValue(((values[spec.id] as? Number)?.toFloat() ?: spec.defaultDp) + STEP_DP)
                renderRows()
            }
            // The number itself opens a precise-entry dialog (typed value, clamped to the range)
            val valueText = TextView(this).apply {
                text = formatDp(current)
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                setTextColor(getColor(R.color.primary_blue))
                setBackgroundResource(R.drawable.bg_pill_tint)
                minWidth = dp(62)
                setPadding(dp(4), dp(4), dp(4), dp(4))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(4); marginEnd = dp(4) }
                isClickable = true
                setOnClickListener { showPreciseEntry(spec) }
            }
            val seek = SeekBar(this).apply {
                max = (((spec.maxDp - spec.minDp) * HALF_STEP).toInt()).coerceAtLeast(1)
                progress = (((current - spec.minDp) * HALF_STEP).toInt()).coerceIn(0, max)
                layoutParams = LinearLayout.LayoutParams(0, dp(32), 1f)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        bar: SeekBar?,
                        progressValue: Int,
                        fromUser: Boolean
                    ) {
                        if (!fromUser) return
                        val value = spec.minDp + progressValue / HALF_STEP
                        values[spec.id] = value
                        valueText.text = formatDp(value)
                        updateDependentRows()
                        renderPreview()
                    }

                    override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar?) = Unit
                })
            }
            row.addView(minus)
            row.addView(valueText)
            row.addView(plus)
            row.addView(seek)
        } else {
            val swatch = View(this).apply {
                setBackgroundColor(
                    opaque((values[spec.id] as? Number)?.toInt() ?: spec.defaultColor)
                )
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(26))
            }
            swatchesById[spec.id] = swatch
            row.addView(swatch)
            row.addView(android.widget.ImageView(this).apply {
                setImageResource(R.drawable.ic_chevron_right)
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                    marginStart = dp(8)
                }
            })
            row.setOnClickListener {
                pendingColorParam = spec
                pendingBackgroundColor = false
                val current = (values[spec.id] as? Number)?.toInt() ?: spec.defaultColor
                colorLauncher.launch(
                    android.content.Intent(this, LineColorActivity::class.java)
                        .putExtra(LineColorActivity.EXTRA_COLOR, current)
                        .putExtra(LineColorActivity.EXTRA_TITLE_RES, R.string.line_color_title)
                )
            }
        }
        return row
    }

    private fun stepButton(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 18f
            gravity = android.view.Gravity.CENTER
            setTextColor(getColor(R.color.primary_blue))
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(36))
            setOnClickListener { onClick() }
        }

    /**
     * Precise entry for one length parameter.
     *
     * A slider is fine for exploring but cannot be dragged onto an exact value, which is why the
     * number is a button: it opens this dialog with the current value in an editable field, and the
     * typed value is clamped to the range the device declared.
     */
    private fun showPreciseEntry(spec: FrameParamInfo) {
        val current = (values[spec.id] as? Number)?.toFloat() ?: spec.defaultDp
        val input = android.widget.EditText(this).apply {
            setText(formatDp(current))
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSelection(text.length)
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        val title = getString(
            R.string.frame_precise_title,
            paramLabel(spec.id),
            formatDp(spec.minDp),
            formatDp(spec.maxDp)
        )
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.frame_precise_ok) { _, _ ->
                val typed = input.text.toString().trim().toFloatOrNull()
                if (typed == null) {
                    toast(getString(R.string.frame_precise_invalid))
                    return@setPositiveButton
                }
                val clamped = typed.coerceIn(spec.minDp, spec.maxDp)
                if (clamped != typed) {
                    toast(
                        getString(
                            R.string.frame_precise_clamped,
                            formatDp(spec.minDp),
                            formatDp(spec.maxDp)
                        )
                    )
                }
                values[spec.id] = clamped
                renderRows()
                updateDependentRows()
                renderPreview()
            }
            .show()
    }

    /**
     * Hides parameters that cannot do anything yet (a line colour with the line switched off), so the
     * page never shows a knob without an effect — the original complaint about this screen.
     */
    private fun updateDependentRows() {
        for ((id, row) in rowsById) {
            row.isVisible = isRowVisible(id)
        }
    }

    private fun isRowVisible(id: String): Boolean {
        fun length(paramId: String): Float =
            (values[paramId] as? Number)?.toFloat()
                ?: currentStyle()?.params?.firstOrNull { it.id == paramId }?.defaultDp
                ?: 0f

        return when (id) {
            "deco_offset_dp", "deco_line_color" -> length("deco_line_dp") > 0f
            "outer_line_color" -> length("outer_line_dp") > 0f
            "inner_line_color" -> length("inner_line_dp") > 0f
            else -> true
        }
    }

    private fun paramLabel(id: String): String {
        val resId = when (id) {
            "band_dp" -> R.string.frame_param_band
            "band_color" -> R.string.frame_param_band_color
            "margin_dp" -> R.string.frame_param_margin
            "bevel_dp" -> R.string.frame_param_bevel
            "outer_line_dp" -> R.string.frame_param_outer_line
            "outer_line_color" -> R.string.frame_param_outer_color
            "deco_line_dp" -> R.string.frame_param_deco_line
            "deco_offset_dp" -> R.string.frame_param_deco_offset
            "deco_line_color" -> R.string.frame_param_deco_color
            "bottom_extra_dp" -> R.string.frame_param_bottom_extra
            "spread_dp" -> R.string.frame_param_spread
            "inner_line_dp" -> R.string.frame_param_inner_line
            "inner_line_color" -> R.string.frame_param_inner_color
            "offset_dp" -> R.string.frame_param_offset
            "gap_dp" -> R.string.frame_param_gap
            "line_dp" -> R.string.frame_param_line_width
            "line_color" -> R.string.frame_param_line_color
            else -> 0
        }
        return if (resId == 0) id else getString(resId)
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    /**
     * Full-screen look at the same preview.
     *
     * 2dp-level details (bevel, thin lines) are unreadable in a 140dp box, so the same drawing is shown
     * at the panel's aspect ratio and the whole dialog width.
     */
    private fun showFullScreenPreview() {
        val style = currentStyle() ?: return
        val panel = config?.panel ?: PanelInfo()
        val bitmap = if (background.mode == FrameBackground.MODE_IMAGE) bgBitmap else null

        val big = FramePreviewView(this).apply {
            setFrame(style.params, values, this@FrameDesignActivity.background, panel)
            setBackgroundBitmap(bitmap)
        }
        val title = TextView(this).apply {
            text = getString(R.string.frame_preview_title)
            textSize = 14f
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
        val close = MaterialButton(this).apply {
            text = getString(R.string.frame_preview_close)
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackground(android.graphics.drawable.ColorDrawable(Color.parseColor("#1A1C1E")))
            addView(title)
            addView(
                big,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                close,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(16), dp(8), dp(16), dp(16)) }
            )
        }
        val dialog = android.app.Dialog(this)
        dialog.setContentView(column)
        dialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        close.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun setBackgroundMode(mode: Int) {
        background = background.copy(mode = mode)
        renderBackground()
        renderPreview()
    }

    private fun uploadBackground(uri: Uri) {
        val endpoint = SessionStore.endpoint
        val sessionId = SessionStore.sessionId
        if (endpoint == null || sessionId == null) {
            toast(getString(R.string.toast_need_session))
            return
        }
        lifecycleScope.launch {
            client.uploadFrameImage(endpoint, sessionId, contentResolver.toUploadSource(uri))
                .onSuccess { saved ->
                    if (!saved) {
                        toast(getString(R.string.frame_style_upload_failed))
                        return@onSuccess
                    }
                    bgBitmap = decodePreviewBitmap(uri)
                    background = background.copy(
                        mode = FrameBackground.MODE_IMAGE,
                        hasImage = true
                    )
                    renderBackground()
                    renderPreview()
                }
                .onFailure { handleFailure(it, getString(R.string.frame_style_upload_failed)) }
        }
    }

    private fun applyToDevice() {
        if (!loaded || applying) return
        val endpoint = SessionStore.endpoint
        val sessionId = SessionStore.sessionId
        if (endpoint == null || sessionId == null) {
            toast(getString(R.string.toast_need_session))
            finish()
            return
        }
        applying = true
        btnApply.isEnabled = false
        lifecycleScope.launch {
            client.writeFrame(
                endpoint = endpoint,
                sessionId = sessionId,
                scope = scope,
                path = if (scope == SCOPE_IMAGE) path else null,
                styleId = styleId,
                values = values.toMap(),
                background = background,
                frameEnabled = frameEnabled
            )
                .onSuccess { applied ->
                    config = applied
                    applying = false
                    toast(getString(R.string.frame_applied))
                    renderAll()
                }
                .onFailure {
                    applying = false
                    btnApply.isEnabled = true
                    handleFailure(it, getString(R.string.frame_save_failed, ""))
                }
        }
    }

    /** Drops this picture's override so it follows the default layer again */
    private fun resetToDefault() {
        if (scope != SCOPE_IMAGE || applying) return
        val endpoint = SessionStore.endpoint
        val sessionId = SessionStore.sessionId
        if (endpoint == null || sessionId == null) {
            toast(getString(R.string.toast_need_session))
            finish()
            return
        }
        applying = true
        btnReset.isEnabled = false
        lifecycleScope.launch {
            client.writeFrame(
                endpoint = endpoint,
                sessionId = sessionId,
                scope = SCOPE_IMAGE,
                path = path,
                styleId = null,
                values = emptyMap(),
                background = null,
                frameEnabled = null,
                clear = true
            )
                .onSuccess {
                    applying = false
                    toast(getString(R.string.frame_reset_done))
                    load()
                }
                .onFailure {
                    applying = false
                    btnReset.isEnabled = true
                    handleFailure(it, getString(R.string.frame_save_failed, ""))
                }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun decodePreviewBitmap(uri: Uri): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / sample > 1024) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }.getOrNull()

    private fun formatDp(value: Float): String =
        if (value == value.toInt().toFloat()) "${value.toInt()}" else String.format("%.1f", value)

    private fun opaque(color: Int): Int =
        Color.rgb(Color.red(color), Color.green(color), Color.blue(color))

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
