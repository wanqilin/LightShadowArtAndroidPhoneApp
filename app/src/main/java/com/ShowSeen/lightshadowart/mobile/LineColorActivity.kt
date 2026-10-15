package com.ShowSeen.lightshadowart.mobile

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.ShowSeen.lightshadowart.mobile.net.FrameParams
import kotlin.math.roundToInt

/**
 * Line / background colour picker (UI PRD §3.9), reached from Frame Design and from Backgrounds.
 *
 * HSV only: a hue strip plus saturation and brightness sliders and a live preview. No hex value is
 * ever shown. The device is sent the final RGB so a round trip through HSV cannot drift.
 *
 * The chosen colour travels back in the result intent, so the back arrow, the system back gesture
 * and Done all confirm in one step.
 */
class LineColorActivity : AppCompatActivity() {

    private lateinit var hueStrip: HueStripView
    private lateinit var saturationSlider: Slider
    private lateinit var valueSlider: Slider
    private lateinit var tvSaturation: TextView
    private lateinit var tvValue: TextView
    private lateinit var preview: android.view.View

    /** Guards against the controls updating each other in a loop */
    private var syncing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_line_color)

        hueStrip = findViewById(R.id.view_color_hue)
        saturationSlider = findViewById(R.id.slider_color_saturation)
        valueSlider = findViewById(R.id.slider_color_value)
        tvSaturation = findViewById(R.id.tv_color_saturation)
        tvValue = findViewById(R.id.tv_color_value)
        preview = findViewById(R.id.view_color_preview)

        val titleRes = intent.getIntExtra(EXTRA_TITLE_RES, R.string.line_color_title)
        findViewById<PageTopBarView>(R.id.color_top_bar).apply {
            setTitle(getString(titleRes))
            setOnBackClickListener { returnColor() }
        }
        findViewById<MaterialButton>(R.id.btn_color_done).setOnClickListener { returnColor() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = returnColor()
        })

        hueStrip.onHueChanged = { if (!syncing) render() }
        saturationSlider.addOnChangeListener { _, _, fromUser -> if (fromUser) render() }
        valueSlider.addOnChangeListener { _, _, fromUser -> if (fromUser) render() }

        applyColor(intent.getIntExtra(EXTRA_COLOR, FrameParams.DEFAULT_LINE_COLOR))
    }

    /** Load an existing 0xAARRGGBB colour into H/S/V; alpha is dropped (device colours are opaque). */
    private fun applyColor(color: Int) {
        val hsv = FloatArray(3)
        Color.colorToHSV(opaque(color), hsv)
        syncing = true
        hueStrip.hue = hsv[0]
        saturationSlider.value = (hsv[1] * 100f).roundToInt().toFloat().coerceIn(0f, 100f)
        valueSlider.value = (hsv[2] * 100f).roundToInt().toFloat().coerceIn(0f, 100f)
        syncing = false
        render()
    }

    /** Keep the preview, the two read-outs and the slider tracks in step with the current H/S/V. */
    private fun render() {
        val color = selectedColor()
        preview.setBackgroundColor(color)
        tvSaturation.text = getString(
            R.string.color_percent_value,
            saturationSlider.value.toInt()
        )
        tvValue.text = getString(R.string.color_percent_value, valueSlider.value.toInt())

        // Tracks show what each slider does: saturation goes grey → full hue, brightness black → hue
        saturationSlider.trackActiveTintList = ColorStateList.valueOf(color)
        saturationSlider.trackInactiveTintList = ColorStateList.valueOf(
            Color.HSVToColor(floatArrayOf(hueStrip.hue, 0f, valueSlider.value / 100f))
        )
        valueSlider.trackActiveTintList = ColorStateList.valueOf(color)
        valueSlider.trackInactiveTintList = ColorStateList.valueOf(
            Color.HSVToColor(floatArrayOf(hueStrip.hue, saturationSlider.value / 100f, 0.15f))
        )
        hueStrip.contentDescription = getString(R.string.color_hue_value, hueStrip.hue.toInt())
    }

    /** Current colour as an opaque RGB int, ready for the device. */
    private fun selectedColor(): Int = Color.HSVToColor(
        floatArrayOf(hueStrip.hue, saturationSlider.value / 100f, valueSlider.value / 100f)
    )

    private fun returnColor() {
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_COLOR, selectedColor()))
        finish()
    }

    private fun opaque(color: Int): Int =
        Color.rgb(Color.red(color), Color.green(color), Color.blue(color))

    companion object {
        /** Result extra: the chosen colour as 0xAARRGGBB. */
        const val EXTRA_COLOR = "extra_line_color"

        /** Input extra: string resource for the page title (line colour / background colour). */
        const val EXTRA_TITLE_RES = "extra_line_color_title_res"
    }
}
