package com.ShowSeen.lightshadowart.mobile

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageButton
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
 * **背景配置页**（需求 3.3.2 / T-9，F-6 第三轮扩展）。
 *
 * 入口：由「添加边框」页的**「背景色」行**进入（空间页原来那个「风格」入口已按用户口径删除，
 * 该功能整体挪到这里）；类名沿用 T-9 的 `FrameStyleActivity`，布局 id 前缀 `frame_style_*` 亦保留。
 *
 * 本页保留 T-9 的设计：**红色边界框**标示最大边界 1920×1280（仅供预览，设备端不画这个框）
 * + **导入自定义图片**；第三轮另加**「背景颜色」色盘入口**（交互与边框颜色页完全一致：
 * 色盘 + R/G/B，界面上不出现十六进制）。
 *
 * 纯色与图片的取舍（用户要求明确口径）：**图片优先，纯色是未选图片时的底色**——
 * 设备端按 `background_image` 决定：为 true 且存有图片时铺图片（预览即图片），
 * 否则铺纯色（预览即纯色）。**本页选颜色即把 `background_image` 置回 false**，
 * 于是用户随时能在「图片 / 纯色」之间来回切；图片文件仍留在设备端，随时可再选回图片。
 *
 * 下发一律走 `/frame` 的**部分更新**：只带本页负责的背景字段（`paper_color` /
 * `paper_enabled` / `background_image`），不再像上一版那样整份回传把用户调好的线条颜色、
 * 线宽、几何参数重置为默认值（那是一个真实缺陷，本轮修掉）。
 */
class FrameStyleActivity : AppCompatActivity() {

    private lateinit var client: DeviceClient
    private lateinit var previewView: FramePreviewView
    private lateinit var tvHint: TextView
    private lateinit var tvStatus: TextView
    private lateinit var colorPreview: View
    private lateinit var btnImport: MaterialButton
    private lateinit var btnApply: MaterialButton

    /** 用户本次选中的本地图片；为空表示没有新图要传 */
    private var pickedUri: Uri? = null
    private var previewBitmap: Bitmap? = null
    private var applying = false

    /** 当前背景纯色（来自设备端，或在「背景颜色」页改过的新值） */
    private var selectedColor: Int = FrameParams.DEFAULT_PAPER_COLOR

    /** 背景是否用设备端已存的自定义图片；由设备端 `background_image` 决定，本页选颜色会置 false */
    private var imageMode: Boolean = false

    /** 设备端是否已存有自定义背景图（决定「图片」这条路是否可走） */
    private var hasBackground: Boolean = false

    /** 设备端读过一次参数才允许下发，避免用界面默认值覆盖设备端 */
    private var loaded = false

    private val pickLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) loadPreview(uri)
    }

    /** 「背景颜色」页回传选中的颜色：选颜色即用纯色（图片优先的规则下，等于把图片来源关掉） */
    private val colorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        selectedColor = result.data?.getIntExtra(FrameColorActivity.EXTRA_COLOR, selectedColor)
            ?: selectedColor
        imageMode = false
        renderPreview()
        renderStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_frame_style)

        SessionStore.init(this)
        client = DeviceClient(contentResolver)

        previewView = findViewById(R.id.view_frame_preview)
        tvHint = findViewById(R.id.tv_frame_style_hint)
        tvStatus = findViewById(R.id.tv_frame_style_status)
        colorPreview = findViewById(R.id.view_frame_style_color_preview)
        btnImport = findViewById(R.id.btn_frame_style_import)
        btnApply = findViewById(R.id.btn_frame_style_apply)

        findViewById<ImageButton>(R.id.btn_frame_style_back).setOnClickListener { finish() }
        findViewById<LinearLayout>(R.id.row_frame_style_color).setOnClickListener {
            colorLauncher.launch(
                Intent(this, FrameColorActivity::class.java)
                    .putExtra(FrameColorActivity.EXTRA_COLOR, selectedColor)
                    .putExtra(FrameColorActivity.EXTRA_TITLE_RES, R.string.frame_background_color)
            )
        }
        btnImport.setOnClickListener { pickImage() }
        btnApply.setOnClickListener { applyToDevice() }

        SessionStore.endpoint?.let { endpoint ->
            tvHint.text = getString(R.string.frame_style_boundary_hint)
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
     * 读回设备端当前背景状态（F-6 第三轮）。
     *
     * `imageMode` 的初值同时接受**上一版遗留的迁移形态**：`style_id = custom` + 设备端已有图片，
     * 意味着旧客户端把「背景用图片」编码在样式里；这里按「图片」呈现，用户点一次「应用到设备」
     * 就会把新的 `background_image` 字段补上，升级后观感不变。
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

    /** 拉起相册选一张背景图 */
    private fun pickImage() {
        pickLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    /**
     * 规格检查后进入预览（用户口径）：背景图**要求 1920x1280**，与设备屏一致。
     *
     * 尺寸不符时不直接拒绝——用户手边可能只有 1921x1281 这类差一点的裁切——
     * 而是明确告知规格并用提醒框让用户选「重新选择 / 仍然使用」。
     */
    private fun loadPreview(uri: Uri) {
        lifecycleScope.launch {
            val size = withContext(Dispatchers.IO) { readImageSize(uri) }
            if (size == null) {
                toast(getString(R.string.frame_style_image_failed))
                return@launch
            }
            if (size.first != REQUIRED_WIDTH || size.second != REQUIRED_HEIGHT) {
                MaterialAlertDialogBuilder(this@FrameStyleActivity)
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

    /** 解码并显示预览（两遍采样，避免整张大图进内存）；选中图片即切到「图片背景」 */
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

    /** 只读图片原始尺寸（不真正解码像素） */
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

    /**
     * 预览 = 设备端会铺的那一层：**图片优先**，没有图片才是纯色。
     *
     * 因此红框里看到的就是设备端的实际铺底方式（纯色填充 / 图片 centerCrop），
     * 与 `/frame` 的 `background_image` 语义一一对应。
     */
    private fun renderPreview() {
        colorPreview.setBackgroundColor(opaque(selectedColor))
        previewView.setPreviewColor(opaque(selectedColor))
        previewView.setPreviewBitmap(if (imageMode) previewBitmap else null)
    }

    /**
     * 应用到设备：只下发本页负责的背景字段（部分更新）。
     *
     * - 图片模式：有新图先上传到 `/frame/image`，再下发 `background_image = true`；
     * - 纯色模式：下发 `background_image = false` + 当前颜色。
     *   `paper_enabled` 恒为 true——本页表达的就是「照片外不是模糊」，关掉它没有别的入口，
     *   也就不会出现「选了颜色但设备端仍在模糊」的死角。
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
            if (imageMode && pickedUri != null) {
                val ok = client.uploadFrameImage(
                    endpoint,
                    sessionId,
                    contentResolver.toUploadSource(pickedUri!!)
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

            // 只带背景字段：线条颜色 / 线宽 / 几何 / 边框开关都不在请求体里，设备端原样保留
            val patch = FramePatch(
                paperColor = opaque(selectedColor),
                paperEnabled = true,
                backgroundImage = imageMode
            )
            client.writeFrame(endpoint, sessionId, patch)
                .onSuccess { applied ->
                    // 回执是设备端真实生效值：据此刷新本页状态（例如图片上传成功后 has_background 才为 true）
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

    /**
     * 两遍解码：先只读尺寸算出采样率，再按采样率真正解码。
     * 用户随手拍的一张 4000x3000 照片若整张读进内存，预览页很容易 OOM。
     */
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

    /** 背景色只关心 RGB：设备端的纯色是不透明的，去掉 alpha 免得预览与设备端不一致 */
    private fun opaque(color: Int): Int = Color.rgb(Color.red(color), Color.green(color), Color.blue(color))

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val TAG = "FrameStyleActivity"

        /** 预览图的最长边上限：预览区只有几厘米，没必要按原图分辨率解码 */
        const val MAX_PREVIEW_PX = 1920

        /** 上一版把「背景用图片」编码在样式里的遗留取值（只在读取时用于迁移兜底） */
        const val STYLE_ID_CUSTOM = "custom"

        /** 背景图规格要求：与设备屏逻辑分辨率一致（用户口径） */
        const val REQUIRED_WIDTH = 1920
        const val REQUIRED_HEIGHT = 1280
    }
}
