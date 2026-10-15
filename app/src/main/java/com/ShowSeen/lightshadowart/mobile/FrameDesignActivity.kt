package com.ShowSeen.lightshadowart.mobile

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.ShowSeen.lightshadowart.mobile.net.DeviceClient
import com.ShowSeen.lightshadowart.mobile.net.FrameParams
import com.ShowSeen.lightshadowart.mobile.net.FramePatch
import com.ShowSeen.lightshadowart.mobile.net.FrameStyleInfo
import com.ShowSeen.lightshadowart.mobile.net.SessionExpiredException
import kotlinx.coroutines.launch

/**
 * Frame Design (UI PRD §3.8).
 *
 * Flow: read the device's current frame with `GET /frame`, edit, then send only the frame fields
 * with `POST /frame` (partial update — the wallpaper fields stay untouched).
 *
 * Control order matches the specification: switch → style → ① → ⑤ → ② → ④ → colour row → Apply.
 * The style list comes from the device, and the colour row opens the HSV picker as a sub-page.
 *
 * Switching the frame off greys the whole parameter block out and makes it untouchable, while the
 * switch itself stays usable — otherwise it could never be turned back on. A 22dp corner card holds
 * the parameters so the page reads as one grouped setting rather than a form.
 */
class FrameDesignActivity : AppCompatActivity() {

    private lateinit var client: DeviceClient
    private lateinit var paramsContainer: ViewGroup
    private lateinit var switchEnabled: MaterialSwitch
    private lateinit var styleSpinner: Spinner
    private lateinit var etPadding: EditText
    private lateinit var etBorderGap: EditText
    private lateinit var etInset: EditText
    private lateinit var etWidth: EditText
    private lateinit var colorPreview: View
    private lateinit var btnApply: MaterialButton

    /** Selectable styles as reported by the device; the client never hard-codes style names. */
    private var styles: List<FrameStyleInfo> = emptyList()

    /** Frame the device reported; null until it has been read, which also gates Apply. */
    private var current: FrameParams? = null

    private var selectedColor: Int = FrameParams.DEFAULT_LINE_COLOR
    private var applying = false

    /** The HSV picker returns the chosen colour, or the previous one when nothing was changed. */
    private val colorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val color = result.data?.getIntExtra(LineColorActivity.EXTRA_COLOR, selectedColor)
        showColor(color ?: selectedColor)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_frame_design)

        SessionStore.init(this)
        client = DeviceClient(contentResolver)

        paramsContainer = findViewById(R.id.container_frame_design_params)
        switchEnabled = findViewById(R.id.switch_frame_design_enabled)
        styleSpinner = findViewById(R.id.spinner_frame_design_style)
        etPadding = findViewById(R.id.et_frame_design_padding)
        etBorderGap = findViewById(R.id.et_frame_design_border_gap)
        etInset = findViewById(R.id.et_frame_design_inset)
        etWidth = findViewById(R.id.et_frame_design_width)
        colorPreview = findViewById(R.id.view_frame_design_color_preview)
        btnApply = findViewById(R.id.btn_frame_design_apply)

        findViewById<PageTopBarView>(R.id.frame_design_top_bar).apply {
            setTitle(getString(R.string.frame_design_title))
            setOnBackClickListener { finish() }
        }
        findViewById<LinearLayout>(R.id.row_frame_design_color).setOnClickListener {
            colorLauncher.launch(
                Intent(this, LineColorActivity::class.java)
                    .putExtra(LineColorActivity.EXTRA_COLOR, selectedColor)
                    .putExtra(LineColorActivity.EXTRA_TITLE_RES, R.string.line_color_title)
            )
        }
        switchEnabled.setOnCheckedChangeListener { _, checked -> renderParamRows(checked) }
        btnApply.setOnClickListener { applyToDevice() }
        // Apply stays disabled until the device has been read, so interface defaults can never
        // overwrite parameters the user set earlier.
        btnApply.isEnabled = false

        showColor(selectedColor)
        renderParamRows(switchEnabled.isChecked)
        loadFromDevice()
    }

    /** Read the device frame parameters and the style list, then fill the form. */
    private fun loadFromDevice() {
        val endpoint = SessionStore.endpoint
        val sessionId = SessionStore.sessionId
        if (endpoint == null || sessionId == null) {
            toast(getString(R.string.toast_need_session))
            finish()
            return
        }
        lifecycleScope.launch {
            val config = client.readFrame(endpoint, sessionId).getOrElse { error ->
                if (error is SessionExpiredException) {
                    SessionStore.clear()
                    toast(getString(R.string.toast_session_expired))
                } else {
                    toast(getString(R.string.frame_load_failed, error.message.orEmpty()))
                }
                finish()
                return@launch
            }

            current = config.params
            styles = config.styles
            styleSpinner.adapter = ArrayAdapter(
                this@FrameDesignActivity,
                android.R.layout.simple_spinner_item,
                styles.map { it.name }
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            styles.indexOfFirst { it.id == config.params.styleId }
                .takeIf { it >= 0 }
                ?.let { styleSpinner.setSelection(it) }

            etPadding.setText(trimFloat(config.params.paddingDp ?: FrameParams.DEFAULT_PADDING_DP))
            etBorderGap.setText(
                trimFloat(config.params.borderGapDp ?: FrameParams.DEFAULT_BORDER_GAP_DP)
            )
            etInset.setText(trimFloat(config.params.insetDp ?: FrameParams.DEFAULT_INSET_DP))
            etWidth.setText(trimFloat(config.params.lineWidthDp))
            showColor(config.params.lineColor)
            switchEnabled.isChecked = config.params.frameEnabled
            renderParamRows(config.params.frameEnabled)
            btnApply.isEnabled = true
        }
    }

    private fun showColor(color: Int) {
        selectedColor = color
        colorPreview.setBackgroundColor(opaque(color))
    }

    /**
     * Grey out and disable every parameter while the frame switch is off.
     *
     * `ViewGroup.setEnabled(false)` does not reach children — they still consume touches — so each
     * child is disabled explicitly; the alpha carries the visual cue.
     */
    private fun renderParamRows(enabled: Boolean) {
        setEnabledRecursively(paramsContainer, enabled)
        paramsContainer.alpha = if (enabled) 1f else DISABLED_ALPHA
    }

    private fun setEnabledRecursively(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) setEnabledRecursively(view.getChildAt(i), enabled)
        }
    }

    /**
     * Validate and send only what this page owns: switch, style, colour, width and ①⑤②.
     * The wallpaper fields are configured on the Backgrounds page and stay untouched here.
     */
    private fun applyToDevice() {
        val endpoint = SessionStore.endpoint
        val sessionId = SessionStore.sessionId
        if (endpoint == null || sessionId == null) {
            toast(getString(R.string.toast_need_session))
            finish()
            return
        }
        val base = current ?: return
        val padding = etPadding.text.toString().trim().toFloatOrNull()
        val borderGap = etBorderGap.text.toString().trim().toFloatOrNull()
        val inset = etInset.text.toString().trim().toFloatOrNull()
        val width = etWidth.text.toString().trim().toFloatOrNull()
        if (padding == null || padding < 0f || borderGap == null || borderGap < 0f ||
            inset == null || inset < 0f || width == null || width <= 0f
        ) {
            toast(getString(R.string.frame_invalid))
            return
        }
        if (applying) return
        applying = true
        btnApply.isEnabled = false

        val styleId = styles.getOrNull(styleSpinner.selectedItemPosition)?.id ?: base.styleId
        val patch = FramePatch(
            styleId = styleId,
            frameEnabled = switchEnabled.isChecked,
            lineColor = selectedColor,
            lineWidthDp = width,
            paddingDp = padding,
            borderGapDp = borderGap,
            insetDp = inset
        )

        lifecycleScope.launch {
            client.writeFrame(endpoint, sessionId, patch)
                .onSuccess { applied ->
                    // The receipt carries the values that really took effect; base the next edit on it
                    current = applied.params
                    applying = false
                    btnApply.isEnabled = true
                    toast(getString(R.string.frame_applied))
                }
                .onFailure { error ->
                    applying = false
                    btnApply.isEnabled = true
                    if (error is SessionExpiredException) {
                        SessionStore.clear()
                        toast(getString(R.string.toast_session_expired))
                    } else {
                        toast(getString(R.string.frame_save_failed, error.message.orEmpty()))
                    }
                }
        }
    }

    /** Drop the float tail so the field stays editable: 12.0 → "12", 12.5 → "12.5" */
    private fun trimFloat(value: Float): String =
        if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()

    /** Swatches only care about RGB; device colours are opaque, so alpha is dropped. */
    private fun opaque(color: Int): Int =
        Color.rgb(Color.red(color), Color.green(color), Color.blue(color))

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val DISABLED_ALPHA = 0.4f
    }
}
