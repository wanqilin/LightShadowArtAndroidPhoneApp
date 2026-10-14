package com.ShowSeen.lightshadowart.mobile

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.ShowSeen.lightshadowart.mobile.net.DeviceClient
import com.ShowSeen.lightshadowart.mobile.net.FrameParams
import com.ShowSeen.lightshadowart.mobile.net.FrameStyleInfo
import com.ShowSeen.lightshadowart.mobile.net.SessionExpiredException
import kotlinx.coroutines.launch

/**
 * 「添加边框」二级页面（F-6，取代原「边框预设」弹窗）。
 *
 * 流程：进页先 `GET /frame` 读回设备端当前值填表 → 用户按下面的顺序改参数 →
 * 点「Apply to device」`POST /frame`（部分更新，未提及字段设备端沿用）。
 *
 * 参数顺序（用户口径）：**样式 → ①边框与图片的距离 → ⑤两个边框的间距 → ②边框与外边界的距离
 * → ④线宽 → ③颜色 → Apply to device**（线宽排在边距之后）。
 *
 * 颜色只在本页留一行「③ 边框颜色 + 色块预览」，点击进入独立的 [FrameColorActivity] 选色
 * （色盘 + R/G/B，间距充裕），选中返回本页更新预览；**界面上不出现十六进制**。
 *
 * 整页**不放 ScrollView**（用户口径：上下滑动容易误触发）：控件已压缩到一屏内，
 * Apply 按钮与全部参数在一屏里可见可点。
 */
class AddFrameActivity : AppCompatActivity() {

    private lateinit var client: DeviceClient
    private lateinit var styleSpinner: Spinner
    private lateinit var etPadding: EditText
    private lateinit var etBorderGap: EditText
    private lateinit var etInset: EditText
    private lateinit var etWidth: EditText
    private lateinit var colorPreview: View
    private lateinit var btnApply: MaterialButton

    /** 设备端下发的可选样式（客户端不硬编码样式名） */
    private var styles: List<FrameStyleInfo> = emptyList()

    /** 读到的设备端当前参数；为空表示还没读到（此时不允许下发，避免用默认值覆盖设备端） */
    private var current: FrameParams? = null

    /** 当前边框颜色：在颜色二级页里改，回来后只更新预览 */
    private var selectedColor: Int = FrameParams.DEFAULT_LINE_COLOR

    private var applying = false

    /** 颜色二级页回传选中的颜色（不选也会回传原值，预览因此不会莫名变色） */
    private val colorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val color = result.data?.getIntExtra(FrameColorActivity.EXTRA_COLOR, selectedColor)
        showColor(color ?: selectedColor)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_frame)

        SessionStore.init(this)
        client = DeviceClient(contentResolver)

        styleSpinner = findViewById(R.id.spinner_add_frame_style)
        etPadding = findViewById(R.id.et_add_frame_padding)
        etBorderGap = findViewById(R.id.et_add_frame_border_gap)
        etInset = findViewById(R.id.et_add_frame_inset)
        etWidth = findViewById(R.id.et_add_frame_width)
        colorPreview = findViewById(R.id.view_add_frame_preview)
        btnApply = findViewById(R.id.btn_add_frame_apply)

        findViewById<ImageButton>(R.id.btn_add_frame_back).setOnClickListener { finish() }
        findViewById<LinearLayout>(R.id.row_add_frame_color).setOnClickListener {
            colorLauncher.launch(
                Intent(this, FrameColorActivity::class.java)
                    .putExtra(FrameColorActivity.EXTRA_COLOR, selectedColor)
            )
        }
        btnApply.setOnClickListener { applyToDevice() }
        // 读回设备端当前值之前不允许下发：否则会拿界面默认值把设备端已有的参数覆盖掉
        btnApply.isEnabled = false

        showColor(selectedColor)
        loadFromDevice()
    }

    /** 读取设备端当前边框参数与样式列表填表 */
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
                this@AddFrameActivity,
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
            btnApply.isEnabled = true
        }
    }

    /** 更新颜色预览色块 */
    private fun showColor(color: Int) {
        selectedColor = color
        colorPreview.setBackgroundColor(Color.rgb(Color.red(color), Color.green(color), Color.blue(color)))
    }

    /** 校验并下发（部分更新：只发本页可改的字段 + 读取到的其它字段原值） */
    private fun applyToDevice() {
        val endpoint = SessionStore.endpoint
        val sessionId = SessionStore.sessionId
        if (endpoint == null || sessionId == null) {
            toast(getString(R.string.toast_need_session))
            finish()
            return
        }
        val base = current
        if (base == null) return
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
        val params = FrameParams(
            styleId = styleId,
            lineColor = selectedColor,
            lineWidthDp = width,
            // 背景纸不在本页：原样回传读到的值，避免改边框顺带改掉它
            paperColor = base.paperColor,
            paperEnabled = base.paperEnabled,
            paddingDp = padding,
            borderGapDp = borderGap,
            insetDp = inset
        )

        lifecycleScope.launch {
            client.writeFrame(endpoint, sessionId, params)
                .onSuccess { applied ->
                    // 回执是设备端真实生效值：记为新的当前值，供下一次修改基于它下发
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

    /** 去掉浮点尾巴，便于编辑：12.0 → "12"，12.5 → "12.5" */
    private fun trimFloat(value: Float): String =
        if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
