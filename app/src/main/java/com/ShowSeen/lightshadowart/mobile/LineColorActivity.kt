package com.ShowSeen.lightshadowart.mobile

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
import kotlin.math.roundToInt

/**
 * Line / mat / background colour picker (UI PRD §3.9).
 *
 * Plain RGB: three channel sliders with their numbers, a live preview and 最近使用. Hue/saturation and
 * the preset palette were tried and dropped — the user wants to set the channel values directly. No
 * hex value is ever shown. The device receives the final RGB, so nothing is lost in conversion.
 *
 * The chosen colour travels back in the result intent, so the back arrow, the system back gesture and
 * Done all confirm in one step.
 */
class LineColorActivity : AppCompatActivity() {

    private lateinit var redSlider: Slider
    private lateinit var greenSlider: Slider
    private lateinit var blueSlider: Slider
    private lateinit var tvRed: TextView
    private lateinit var tvGreen: TextView
    private lateinit var tvBlue: TextView
    private lateinit var preview: android.view.View
    private lateinit var wheel: ColorWheelView
    private lateinit var brightnessBar: ValueBarView
    private lateinit var recentContainer: LinearLayout
    private lateinit var recentCard: MaterialCardView

    private val swatches = mutableListOf<SwatchView>()

    /** Source of truth for the wheel: RGB alone cannot express "grey at hue 200" */
    private val hsv = floatArrayOf(0f, 0f, 1f)

    /** Guards against the controls updating each other in a loop */
    private var syncing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_line_color)

        redSlider = findViewById(R.id.slider_color_red)
        greenSlider = findViewById(R.id.slider_color_green)
        blueSlider = findViewById(R.id.slider_color_blue)
        tvRed = findViewById(R.id.tv_color_red)
        tvGreen = findViewById(R.id.tv_color_green)
        tvBlue = findViewById(R.id.tv_color_blue)
        preview = findViewById(R.id.view_color_preview)
        wheel = findViewById(R.id.view_color_wheel)
        brightnessBar = findViewById(R.id.view_color_brightness)
        recentContainer = findViewById(R.id.container_color_recent)
        recentCard = findViewById(R.id.card_color_recent)

        val titleRes = intent.getIntExtra(EXTRA_TITLE_RES, R.string.line_color_title)
        val titleText = intent.getStringExtra(EXTRA_TITLE_TEXT) ?: getString(titleRes)
        findViewById<PageTopBarView>(R.id.color_top_bar).apply {
            setTitle(titleText)
            setOnBackClickListener { returnColor() }
        }
        findViewById<MaterialButton>(R.id.btn_color_done).setOnClickListener { returnColor() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = returnColor()
        })

        // The wheel picks roughly; it drives the RGB sliders, which stay authoritative afterwards
        wheel.onPicked = { hue, saturation ->
            hsv[0] = hue
            hsv[1] = saturation
            applyHsvToSliders()
        }
        brightnessBar.onPicked = { brightness ->
            hsv[2] = brightness
            applyHsvToSliders()
        }

        redSlider.addOnChangeListener { _, _, fromUser -> if (fromUser) render() }
        greenSlider.addOnChangeListener { _, _, fromUser -> if (fromUser) render() }
        blueSlider.addOnChangeListener { _, _, fromUser -> if (fromUser) render() }

        buildRecent()
        applyColor(intent.getIntExtra(EXTRA_COLOR, DEFAULT_LINE_COLOR))
    }

    /** Recently used colours: one tap restores a colour picked before. */
    private fun buildRecent() {
        val recent = ColorHistoryStore.load(this).take(5)
        recentCard.isVisible = recent.isNotEmpty()
        recentContainer.removeAllViews()
        swatches.clear()
        recent.forEach { color ->
            recentContainer.addView(
                SwatchView(this).apply {
                    this.color = color
                    layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                        marginStart = dp(4)
                        marginEnd = dp(4)
                    }
                    setOnClickListener {
                        applyColor(color)
                        ColorHistoryStore.remember(this@LineColorActivity, color)
                        buildRecent()
                        render()
                    }
                    describe(getString(R.string.color_recent), false)
                    swatches += this
                }
            )
        }
        render()
    }

    /** Load an existing 0xAARRGGBB colour into the controls; alpha is dropped (colours are opaque). */
    private fun applyColor(color: Int) {
        Color.colorToHSV(opaque(color), hsv)
        applyHsvToSliders()
    }

    /** Pushes the HSV state into the sliders, the wheel and the brightness bar. */
    private fun applyHsvToSliders() {
        val color = Color.HSVToColor(hsv)
        syncing = true
        redSlider.value = Color.red(color).toFloat()
        greenSlider.value = Color.green(color).toFloat()
        blueSlider.value = Color.blue(color).toFloat()
        syncing = false
        syncWheel(color)
        render()
    }

    /** Keeps the wheel marker and the brightness bar in step with [color]. */
    private fun syncWheel(color: Int) {
        wheel.hue = hsv[0]
        wheel.saturation = hsv[1]
        wheel.brightness = hsv[2]
        brightnessBar.setHueSaturation(hsv[0], hsv[1])
        brightnessBar.brightness = hsv[2]
    }

    /** Keep the preview, the three numbers, the slider tracks and the recent ticks in step. */
    private fun render() {
        val color = selectedColor()
        if (!syncing) {
            // RGB was edited directly: move the wheel marker to the closest hue/saturation/brightness
            Color.colorToHSV(color, hsv)
            syncWheel(color)
        }
        preview.setBackgroundColor(color)
        tvRed.text = getString(R.string.color_rgb_value, Color.red(color))
        tvGreen.text = getString(R.string.color_rgb_value, Color.green(color))
        tvBlue.text = getString(R.string.color_rgb_value, Color.blue(color))

        // The track carries the resulting colour, both sides alike: a two-tone track would imply a
        // gradient the slider does not have, and the preview block already shows the result
        listOf(redSlider, greenSlider, blueSlider).forEach {
            it.trackActiveTintList = ColorStateList.valueOf(color)
            it.trackInactiveTintList = ColorStateList.valueOf(color)
        }

        // Ticks mark a recent colour that matches the current one (2/255 tolerance keeps it forgiving)
        swatches.forEach { it.picked = sameColor(it.color, color) }
    }

    /** Current colour as an opaque RGB int, ready for the device. */
    private fun selectedColor(): Int = Color.rgb(
        redSlider.value.roundToInt().coerceIn(0, 255),
        greenSlider.value.roundToInt().coerceIn(0, 255),
        blueSlider.value.roundToInt().coerceIn(0, 255)
    )

    private fun sameColor(a: Int, b: Int): Boolean =
        kotlin.math.abs(Color.red(a) - Color.red(b)) <= 2 &&
            kotlin.math.abs(Color.green(a) - Color.green(b)) <= 2 &&
            kotlin.math.abs(Color.blue(a) - Color.blue(b)) <= 2

    private fun returnColor() {
        val color = selectedColor()
        ColorHistoryStore.remember(this, color)
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_COLOR, color))
        finish()
    }

    private fun opaque(color: Int): Int =
        Color.rgb(Color.red(color), Color.green(color), Color.blue(color))

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        /** Result extra: the chosen colour as 0xAARRGGBB. */
        const val DEFAULT_LINE_COLOR: Int = 0xFFD32F2F.toInt()

        const val EXTRA_COLOR = "extra_line_color"

        /** Input extra: string resource for the page title (line colour / background colour). */
        const val EXTRA_TITLE_RES = "extra_line_color_title_res"

        /** Input extra: explicit page title (takes precedence over [EXTRA_TITLE_RES]). */
        const val EXTRA_TITLE_TEXT = "extra_line_color_title_text"
    }
}
