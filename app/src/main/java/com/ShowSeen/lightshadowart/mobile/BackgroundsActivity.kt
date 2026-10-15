package com.ShowSeen.lightshadowart.mobile

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ShowSeen.lightshadowart.mobile.net.DeviceClient
import com.ShowSeen.lightshadowart.mobile.net.FrameParams
import com.ShowSeen.lightshadowart.mobile.net.FramePatch
import com.ShowSeen.lightshadowart.mobile.net.SessionExpiredException
import com.ShowSeen.lightshadowart.mobile.net.toUploadSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Backgrounds (UI PRD §3.10): the fill behind the photo on the device — a solid colour or an
 * imported wallpaper.
 *
 * The red rectangle is only this page's preview of the fillable range; the device draws no boundary.
 * Colours go through the same HSV picker as the frame line, and applying sends background fields
 * only, so a colour change cannot reset the frame line, width or geometry.
 *
 * Solids and wallpapers follow "image first, colour as the base": choosing a colour clears
 * `background_image` while the uploaded file stays on the device, so switching back is free.
 */
class BackgroundsActivity : AppCompatActivity() {

    private lateinit var client: DeviceClient
    private lateinit var previewView: FramePreviewView
    private lateinit var tvStatus: TextView
    private lateinit var colorPreview: View
    private lateinit var btnImport: MaterialButton
    private lateinit var btnApply: MaterialButton

    /** Locally picked wallpaper that still has to be uploaded; null when there is nothing new. */
    private var pickedUri: Uri? = null
    private var previewBitmap: Bitmap? = null
    private var applying = false

    private var selectedColor: Int = FrameParams.DEFAULT_PAPER_COLOR
    private var imageMode: Boolean = false
    private var hasBackground: Boolean = false
    private var loaded = false

    private val pickLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) loadPreview(uri)
    }

    /** A colour was chosen: the background becomes that solid, so image mode goes off. */
    private val colorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        selectedColor = result.data?.getIntExtra(LineColorActivity.EXTRA_COLOR, selectedColor)
            ?: selectedColor
        imageMode = false
        renderPreview()
        renderStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_backgrounds)

        SessionStore.init(this)
        client = DeviceClient(contentResolver)

        previewView = findViewById(R.id.view_backgrounds_preview)
        tvStatus = findViewById(R.id.tv_backgrounds_status)
        colorPreview = findViewById(R.id.view_backgrounds_color_preview)
        btnImport = findViewById(R.id.btn_backgrounds_import)
        btnApply = findViewById(R.id.btn_backgrounds_apply)

        findViewById<PageTopBarView>(R.id.backgrounds_top_bar).apply {
            setTitle(getString(R.string.backgrounds_title))
            setOnBackClickListener { finish() }
        }
        findViewById<LinearLayout>(R.id.row_backgrounds_color).setOnClickListener {
            colorLauncher.launch(
                Intent(this, LineColorActivity::class.java)
                    .putExtra(LineColorActivity.EXTRA_COLOR, selectedColor)
                    .putExtra(LineColorActivity.EXTRA_TITLE_RES, R.string.line_color_title_background)
            )
        }
        btnImport.setOnClickListener { pickImage() }
        btnApply.setOnClickListener { applyToDevice() }

        SessionStore.endpoint?.let { endpoint ->
            Log.i(TAG, "background editor for ${endpoint.baseUrl}")
        }
        renderPreview()
        renderStatus()
        loadFromDevice()
    }

    override fun onDestroy() {
        previewView.setPreviewBitmap(null)
        previewBitmap?.recycle()
        previewBitmap = null
        super.onDestroy()
    }

    /**
     * Read the current background state from the device.
     *
     * The initial [imageMode] also accepts the legacy encoding (`style_id = custom` plus a stored
     * image) left over from the previous version, so upgrading does not change what the user sees.
     */
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
                handleFailure(error)
                finish()
                return@launch
            }
            val params = config.params
            selectedColor = params.paperColor
            hasBackground = params.hasBackground
            imageMode = (params.backgroundImage ||
                (STYLE_ID_CUSTOM == params.styleId && params.hasBackground)) && params.hasBackground
            loaded = true
            renderPreview()
            renderStatus()
        }
    }

    private fun pickImage() {
        pickLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    /**
     * Wallpapers must be 1920x1280, matching the device screen. A near miss (1921x1281 from a photo
     * editor) is not rejected outright: the user is told the required size and chooses.
     */
    private fun loadPreview(uri: Uri) {
        lifecycleScope.launch {
            val size = withContext(Dispatchers.IO) { readImageSize(uri) }
            if (size == null) {
                toast(getString(R.string.frame_style_image_failed))
                return@launch
            }
            if (size.first != REQUIRED_WIDTH || size.second != REQUIRED_HEIGHT) {
                MaterialAlertDialogBuilder(this@BackgroundsActivity)
                    .setTitle(R.string.dialog_wallpaper_size_title)
                    .setMessage(
                        getString(
                            R.string.dialog_wallpaper_size_message,
                            size.first,
                            size.second,
                            REQUIRED_WIDTH,
                            REQUIRED_HEIGHT
                        )
                    )
                    .setPositiveButton(R.string.dialog_wallpaper_size_reselect) { _, _ -> pickImage() }
                    .setNegativeButton(R.string.dialog_wallpaper_size_use) { _, _ -> decodeAndPreview(uri) }
                    .show()
                return@launch
            }
            decodeAndPreview(uri)
        }
    }

    /** Decode and preview (two-pass sampling); a picked image switches the mode to wallpaper. */
    private fun decodeAndPreview(uri: Uri) {
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { decodeSampled(uri, MAX_PREVIEW_PX) }
            if (bitmap == null) {
                toast(getString(R.string.frame_style_image_failed))
                return@launch
            }
            previewView.setPreviewBitmap(bitmap)
            previewBitmap?.recycle()
            previewBitmap = bitmap
            pickedUri = uri
            imageMode = true
            renderPreview()
            renderStatus()
        }
    }

    /** Read the source dimensions without decoding pixels. */
    private fun readImageSize(uri: Uri): Pair<Int, Int>? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            bounds.outWidth to bounds.outHeight
        }
    }.getOrNull()

    /** The preview mirrors the device layer: the wallpaper when there is one, otherwise the colour. */
    private fun renderPreview() {
        colorPreview.setBackgroundColor(opaque(selectedColor))
        previewView.setPreviewColor(opaque(selectedColor))
        previewView.setPreviewBitmap(if (imageMode) previewBitmap else null)
    }

    /**
     * Apply to the device, sending background fields only.
     *
     * Wallpaper mode uploads a newly picked file first and then sets `background_image = true`;
     * colour mode sends the colour with `background_image = false`. `paper_enabled` is always true —
     * this page means "not the blur backfill", and nothing else can turn it off.
     */
    private fun applyToDevice() {
        val endpoint = SessionStore.endpoint
        val sessionId = SessionStore.sessionId
        if (endpoint == null || sessionId == null) {
            toast(getString(R.string.toast_need_session))
            return
        }
        if (!loaded || applying) return
        applying = true
        renderStatus()

        lifecycleScope.launch {
            val uri = pickedUri
            if (imageMode && uri != null) {
                val ok = client.uploadFrameImage(
                    endpoint,
                    sessionId,
                    contentResolver.toUploadSource(uri)
                ).getOrElse { error ->
                    applying = false
                    renderStatus()
                    handleFailure(error)
                    return@launch
                }
                if (!ok) {
                    applying = false
                    renderStatus()
                    toast(getString(R.string.frame_style_upload_failed))
                    return@launch
                }
            }

            val patch = FramePatch(
                paperColor = opaque(selectedColor),
                paperEnabled = true,
                backgroundImage = imageMode
            )
            client.writeFrame(endpoint, sessionId, patch)
                .onSuccess { applied ->
                    selectedColor = applied.params.paperColor
                    hasBackground = applied.params.hasBackground
                    imageMode = applied.params.backgroundImage && hasBackground
                    pickedUri = null
                    applying = false
                    renderPreview()
                    renderStatus()
                    toast(getString(R.string.frame_style_applied))
                }
                .onFailure { error ->
                    applying = false
                    renderStatus()
                    handleFailure(error)
                }
        }
    }

    private fun handleFailure(error: Throwable) {
        if (error is SessionExpiredException) {
            SessionStore.clear()
            toast(getString(R.string.toast_session_expired))
        } else {
            toast(getString(R.string.frame_style_apply_failed, error.message.orEmpty()))
        }
    }

    private fun renderStatus() {
        tvStatus.text = when {
            applying -> getString(R.string.frame_style_applying)
            pickedUri != null -> getString(R.string.frame_style_selected)
            imageMode && hasBackground -> getString(R.string.frame_background_status_image)
            else -> getString(R.string.frame_background_status_solid)
        }
        btnImport.isEnabled = !applying
        btnApply.isEnabled = !applying && loaded
    }

    /** Two-pass decode: a 4000x3000 phone photo decoded whole would risk an OOM for a preview. */
    private fun decodeSampled(uri: Uri, maxPx: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return@runCatching null

        var sampleSize = 1
        while (longest / sampleSize > maxPx) sampleSize *= 2

        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }.getOrNull()

    private fun opaque(color: Int): Int =
        Color.rgb(Color.red(color), Color.green(color), Color.blue(color))

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val TAG = "BackgroundsActivity"

        /** Preview longest edge; the preview area is a few centimetres wide. */
        const val MAX_PREVIEW_PX = 1920

        /** Legacy marker for "background uses the image", read-only fallback for migration. */
        const val STYLE_ID_CUSTOM = "custom"

        /** Wallpaper size, matching the device's logical resolution. */
        const val REQUIRED_WIDTH = 1920
        const val REQUIRED_HEIGHT = 1280
    }
}
