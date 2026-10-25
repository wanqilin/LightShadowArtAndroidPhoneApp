package com.ShowSeen.lightshadowart.mobile

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Line / mat / background colour picker (UI PRD §3.9).
 *
 * Three direct ways to get a colour — 常用色, 最近使用, 从照片取色 — and only then the HSV 微调, because
 * "which hue and what percentage?" is not how anyone finds the colour they have in mind. No hex value
 * is ever shown; the device receives the final RGB so a round trip through HSV cannot drift.
 *
 * The chosen colour travels back in the result intent, so the back arrow, the system back gesture and
 * Done all confirm in one step.
 */
class LineColorActivity : AppCompatActivity() {

    private lateinit var hueStrip: HueStripView
    private lateinit var saturationSlider: Slider
    private lateinit var valueSlider: Slider
    private lateinit var tvHue: TextView
    private lateinit var tvSaturation: TextView
    private lateinit var tvValue: TextView
    private lateinit var preview: View
    private lateinit var presetContainer: LinearLayout
    private lateinit var recentContainer: LinearLayout
    private lateinit var recentCard: MaterialCardView
    private lateinit var photoCard: MaterialCardView
    private lateinit var photoView: ImageView

    private val swatches = mutableListOf<SwatchView>()

    /** Photo shown for 从照片取色; null hides that card */
    private var photoBitmap: Bitmap? = null

    /** Guards against the controls updating each other in a loop */
    private var syncing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_line_color)

        hueStrip = findViewById(R.id.view_color_hue)
        saturationSlider = findViewById(R.id.slider_color_saturation)
        valueSlider = findViewById(R.id.slider_color_value)
        tvHue = findViewById(R.id.tv_color_hue)
        tvSaturation = findViewById(R.id.tv_color_saturation)
        tvValue = findViewById(R.id.tv_color_value)
        preview = findViewById(R.id.view_color_preview)
        presetContainer = findViewById(R.id.container_color_presets)
        recentContainer = findViewById(R.id.container_color_recent)
        recentCard = findViewById(R.id.card_color_recent)
        photoCard = findViewById(R.id.card_color_photo)
        photoView = findViewById(R.id.view_color_photo)

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

        hueStrip.onHueChanged = { if (!syncing) render() }
        saturationSlider.addOnChangeListener { _, _, fromUser -> if (fromUser) render() }
        valueSlider.addOnChangeListener { _, _, fromUser -> if (fromUser) render() }

        buildPresets()
        buildRecent()
        loadPhoto(intent.getStringExtra(EXTRA_PHOTO_PATH))

        applyColor(intent.getIntExtra(EXTRA_COLOR, DEFAULT_LINE_COLOR))
    }

    // ---------------------------------------------------------------- direct picking

    private data class Preset(val color: Int, val nameRes: Int)

    private fun presets(): List<Preset> = listOf(
        Preset(0xFFF6F1E7.toInt(), R.string.color_preset_cream),
        Preset(0xFFFFFCF5.toInt(), R.string.color_preset_ivory),
        Preset(0xFFD8DDE2.toInt(), R.string.color_preset_light_grey),
        Preset(0xFF3A4046.toInt(), R.string.color_preset_dark_grey),
        Preset(0xFF000000.toInt(), R.string.color_preset_black),
        Preset(0xFFD32F2F.toInt(), R.string.color_preset_red),
        Preset(0xFF8E1F2B.toInt(), R.string.color_preset_wine),
        Preset(0xFFC9A227.toInt(), R.string.color_preset_gold),
        Preset(0xFF17A2CD.toInt(), R.string.color_preset_lake),
        Preset(0xFF1F6B4E.toInt(), R.string.color_preset_green)
    )

    /** Two rows of five: the colours that actually get used on a mat and on a line. */
    private fun buildPresets() {
        val all = presets()
        presetContainer.removeAllViews()
        all.chunked(5).forEach { rowItems ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            }
            rowItems.forEach { preset ->
                row.addView(makeSwatch(preset.color, getString(preset.nameRes)))
            }
            // Keep the last row aligned with the first when it is shorter
            repeat(5 - rowItems.size) {
                row.addView(View(this).apply { layoutParams = weighted() })
            }
            presetContainer.addView(row)
        }
    }

    /** Newest five colours from history; the card hides itself when there is no history yet. */
    private fun buildRecent() {
        val recent = ColorHistoryStore.load(this).take(5)
        recentCard.isVisible = recent.isNotEmpty()
        recentContainer.removeAllViews()
        recent.forEach { color ->
            recentContainer.addView(makeSwatch(color, getString(R.string.color_recent)))
        }
    }

    private fun makeSwatch(color: Int, label: String): SwatchView =
        SwatchView(this).apply {
            this.color = color
            layoutParams = weighted()
            setOnClickListener {
                applyColor(color)
                ColorHistoryStore.remember(this@LineColorActivity, color)
                buildRecent()
            }
            describe(label, false)
            swatches += this
        }

    private fun weighted(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            marginStart = dp(4)
            marginEnd = dp(4)
        }

    /**
     * 从照片取色: taps sample the picture that is being framed, so the line or mat colour can be taken
     * straight from the photo instead of guessed.
     */
    private fun loadPhoto(path: String?) {
        if (path.isNullOrEmpty()) return
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { decodeForPicking(path) } ?: return@launch
            photoBitmap = bitmap
            photoCard.isVisible = true
            photoView.setImageBitmap(bitmap)
            photoView.setOnTouchListener { view, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    sampleAt(view, event.x, event.y)?.let { picked ->
                        applyColor(picked)
                        ColorHistoryStore.remember(this@LineColorActivity, picked)
                        buildRecent()
                    }
                }
                true
            }
        }
    }

    private fun decodeForPicking(path: String): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(java.io.File(path))
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.isMutableRequired = false
                // HARDWARE bitmaps have no pixel access, and getPixel() is exactly what this screen
                // is for — decode into software memory
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val scale = min(1f, MAX_PICK_PX.toFloat() / info.size.width)
                if (scale < 1f) {
                    decoder.setTargetSize(
                        (info.size.width * scale).toInt().coerceAtLeast(1),
                        (info.size.height * scale).toInt().coerceAtLeast(1)
                    )
                }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= MAX_PICK_PX) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }.getOrNull()

    /** Maps a touch inside the ImageView to a bitmap pixel (fitCenter letterboxing) and averages 3×3. */
    private fun sampleAt(view: View, x: Float, y: Float): Int? {
        val bitmap = photoBitmap ?: return null
        if (view.width <= 0 || view.height <= 0) return null
        val scale = min(view.width.toFloat() / bitmap.width, view.height.toFloat() / bitmap.height)
        val drawnWidth = bitmap.width * scale
        val drawnHeight = bitmap.height * scale
        val offsetX = (view.width - drawnWidth) / 2f
        val offsetY = (view.height - drawnHeight) / 2f
        val bx = ((x - offsetX) / scale).toInt()
        val by = ((y - offsetY) / scale).toInt()
        if (bx < 0 || by < 0 || bx >= bitmap.width || by >= bitmap.height) return null

        return runCatching {
            var r = 0
            var g = 0
            var b = 0
            var n = 0
            for (dx in -1..1) {
                for (dy in -1..1) {
                    val px = (bx + dx).coerceIn(0, bitmap.width - 1)
                    val py = (by + dy).coerceIn(0, bitmap.height - 1)
                    val c = bitmap.getPixel(px, py)
                    r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++
                }
            }
            Color.rgb(r / n, g / n, b / n)
        }.getOrNull()
    }

    // ---------------------------------------------------------------- fine tuning

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

    /** Keep the preview, the read-outs, the slider tracks and the swatch ticks in step with H/S/V. */
    private fun render() {
        val color = selectedColor()
        preview.setBackgroundColor(color)
        tvHue.text = getString(R.string.color_hue_value, hueStrip.hue.toInt())
        tvSaturation.text = getString(R.string.color_percent_value, saturationSlider.value.toInt())
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

        // Ticks mark the swatch that matches the current colour, so the pick is visible not implied.
        // Compared with a 2/255 tolerance: RGB → HSV → RGB rounds, so an exact match is not reliable.
        swatches.forEach { it.picked = sameColor(it.color, color) }
    }

    /** Current colour as an opaque RGB int, ready for the device. */
    private fun selectedColor(): Int = Color.HSVToColor(
        floatArrayOf(hueStrip.hue, saturationSlider.value / 100f, valueSlider.value / 100f)
    )

    private fun returnColor() {
        val color = selectedColor()
        ColorHistoryStore.remember(this, color)
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_COLOR, color))
        finish()
    }

    private fun sameColor(a: Int, b: Int): Boolean =
        kotlin.math.abs(Color.red(a) - Color.red(b)) <= 2 &&
            kotlin.math.abs(Color.green(a) - Color.green(b)) <= 2 &&
            kotlin.math.abs(Color.blue(a) - Color.blue(b)) <= 2

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

        /** Input extra: a photo file to sample colours from; the card is hidden when absent. */
        const val EXTRA_PHOTO_PATH = "extra_line_color_photo_path"

        private const val MAX_PICK_PX = 900
    }
}
