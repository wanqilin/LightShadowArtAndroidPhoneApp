package com.ShowSeen.lightshadowart.mobile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.ImageButton
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
import com.ShowSeen.lightshadowart.mobile.net.SessionExpiredException
import com.ShowSeen.lightshadowart.mobile.net.toUploadSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 自定义边框编辑页（需求 3.3.2 / T-9）。
 *
 * 流程：编辑页用红色边框标示最大边界 1920x1280 → 用户导入一张背景图并在边界内预览
 * （centerCrop，与设备端铺底方式一致）→ 点「应用到设备」把图上传到 `/frame/image`
 * 并把设备端样式切到 `custom`。
 *
 * 设备端把背景图存在 App 私有目录，不会混进客户照片文件夹。
 * 预览是近似，最终观感以设备屏为准。
 */
class FrameStyleActivity : AppCompatActivity() {

    private lateinit var client: DeviceClient
    private lateinit var previewView: FramePreviewView
    private lateinit var tvHint: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnImport: MaterialButton
    private lateinit var btnApply: MaterialButton

    /** 用户本次选中的本地图片；为空表示没有新图要传（只切样式） */
    private var pickedUri: Uri? = null
    private var previewBitmap: Bitmap? = null
    private var applying = false

    private val pickLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) loadPreview(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_frame_style)

        SessionStore.init(this)
        client = DeviceClient(contentResolver)

        previewView = findViewById(R.id.view_frame_preview)
        tvHint = findViewById(R.id.tv_frame_style_hint)
        tvStatus = findViewById(R.id.tv_frame_style_status)
        btnImport = findViewById(R.id.btn_frame_style_import)
        btnApply = findViewById(R.id.btn_frame_style_apply)

        findViewById<ImageButton>(R.id.btn_frame_style_back).setOnClickListener { finish() }
        btnImport.setOnClickListener { pickImage() }
        btnApply.setOnClickListener { applyToDevice() }

        SessionStore.endpoint?.let { endpoint ->
            tvHint.text = getString(R.string.frame_style_boundary_hint)
            Log.i(TAG, "frame style editor for ${endpoint.baseUrl}")
        }
        renderStatus()
    }

    override fun onDestroy() {
        previewView.setPreviewBitmap(null)
        previewBitmap?.recycle()
        previewBitmap = null
        super.onDestroy()
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

    /** 解码并显示预览（两遍采样，避免整张大图进内存） */
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

    /** 把选中的图上传到设备端，并把样式切到「自定义背景」（需求 3.3.2 / T-9） */
    private fun applyToDevice() {
        val endpoint = SessionStore.endpoint
        val sessionId = SessionStore.sessionId
        if (endpoint == null || sessionId == null) {
            toast(getString(R.string.toast_need_session))
            return
        }
        if (applying) return
        applying = true
        renderStatus()

        lifecycleScope.launch {
            val uri = pickedUri
            if (uri != null) {
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

            client.writeFrame(
                endpoint,
                sessionId,
                FrameParams(styleId = STYLE_ID_CUSTOM)
            ).onSuccess {
                applying = false
                renderStatus()
                toast(getString(R.string.frame_style_applied))
            }.onFailure { error ->
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
            else -> getString(R.string.frame_style_no_image)
        }
        btnImport.isEnabled = !applying
        btnApply.isEnabled = !applying
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

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val TAG = "FrameStyleActivity"

        /** 预览图的最长边上限：预览区只有几厘米，没必要按原图分辨率解码 */
        const val MAX_PREVIEW_PX = 1920

        /** 与设备端 `PhotoFrameRegistry.ID_CUSTOM` 一致 */
        const val STYLE_ID_CUSTOM = "custom"

        /** 背景图规格要求：与设备屏逻辑分辨率一致（用户口径） */
        const val REQUIRED_WIDTH = 1920
        const val REQUIRED_HEIGHT = 1280
    }
}
