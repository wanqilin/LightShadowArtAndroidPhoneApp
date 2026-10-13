package com.ShowSeen.lightshadowart.mobile

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
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
 * 流程：进页先 `GET /frame` 读回设备端当前值填表 → 用户改四项参数 →
 * 点「下发到设备」`POST /frame`（部分更新，未提及字段设备端沿用）。
 *
 * 四项参数（用户口径）：
 * ① 边框与图片的距离 `padding_dp`、② 边框到最外层的距离 `inset_dp`、
 * ③ 边框颜色（**色盘 + R/G/B，界面上不出现十六进制**）、
 * ④ 边框样式（列表来自设备端 `styles[]`）与带粗细样式的粗细。
 *
 * 双框间距、背景纸开关不在本页（F-6 只要求上面四项）：读到的值原样回传，不会被本页改掉。
 */
class AddFrameActivity : AppCompatActivity() {

    private lateinit var client: DeviceClient
    private lateinit var styleSpinner: Spinner
    private lateinit var etPadding: EditText
    private lateinit var etInset: EditText
    private lateinit var etWidth: EditText
    private lateinit var wheel: ColorWheelView
    private lateinit var seekR: SeekBar
    private lateinit var seekG: SeekBar
    private lateinit var seekB: SeekBar
    private lateinit var tvR: TextView
    private lateinit var tvG: TextView
    private lateinit var tvB: TextView
    private lateinit var colorPreview: View
    private lateinit var btnApply: MaterialButton

    /** 设备端下发的可选样式（客户端不硬编码样式名） */
    private var styles: List<FrameStyleInfo> = emptyList()

    /** 读到的设备端当前参数；为空表示还没读到（此时不允许下发，避免用默认值覆盖设备端） */
    private var current: FrameParams? = null

    /** 程序化改滑杆 / 色盘时抑制回调，避免两边互相触发 */
    private var syncingColor = false
    private var applying = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_frame)

        SessionStore.init(this)
        client = DeviceClient(contentResolver)

        styleSpinner = findViewById(R.id.spinner_add_frame_style)
        etPadding = findViewById(R.id.et_add_frame_padding)
        etInset = findViewById(R.id.et_add_frame_inset)
        etWidth = findViewById(R.id.et_add_frame_width)
        wheel = findViewById(R.id.view_add_frame_wheel)
        seekR = findViewById(R.id.seek_add_frame_r)
        seekG = findViewById(R.id.seek_add_frame_g)
        seekB = findViewById(R.id.seek_add_frame_b)
        tvR = findViewById(R.id.tv_add_frame_r)
        tvG = findViewById(R.id.tv_add_frame_g)
        tvB = findViewById(R.id.tv_add_frame_b)
        colorPreview = findViewById(R.id.view_add_frame_preview)
        btnApply = findViewById(R.id.btn_add_frame_apply)

        findViewById<ImageButton>(R.id.btn_add_frame_back).setOnClickListener { finish() }
        btnApply.setOnClickListener { applyToDevice() }
        // 读回设备端当前值之前不允许下发：否则会拿界面默认值把设备端已有的参数覆盖掉
        btnApply.isEnabled = false

        wheel.onColorPicked = { color -> showColor(color, fromWheel = true) }
        val seekListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!syncingColor) showColor(selectedColor(), fromWheel = false)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }
        seekR.setOnSeekBarChangeListener(seekListener)
        seekG.setOnSeekBarChangeListener(seekListener)
        seekB.setOnSeekBarChangeListener(seekListener)

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
            etInset.setText(trimFloat(config.params.insetDp ?: FrameParams.DEFAULT_INSET_DP))
            etWidth.setText(trimFloat(config.params.lineWidthDp))
            showColor(config.params.lineColor, fromWheel = false)
            btnApply.isEnabled = true
        }
    }

    /**
     * 把颜色铺到色盘 / R/G/B 滑杆 / 预览块上。
     *
     * `fromWheel = true` 时不再回写色盘（本来就是它报上来的），避免自激；
     * 滑杆→颜色这条路径则要把色盘的位置指示同步过去。
     */
    private fun showColor(color: Int, fromWheel: Boolean) {
        syncingColor = true
        if (!fromWheel) wheel.setColor(color)
        seekR.progress = Color.red(color)
        seekG.progress = Color.green(color)
        seekB.progress = Color.blue(color)
        tvR.text = Color.red(color).toString()
        tvG.text = Color.green(color).toString()
        tvB.text = Color.blue(color).toString()
        colorPreview.setBackgroundColor(color)
        syncingColor = false
    }

    /** 当前 R/G/B 滑杆代表的颜色（alpha 固定不透明） */
    private fun selectedColor(): Int = Color.rgb(seekR.progress, seekG.progress, seekB.progress)

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
        val inset = etInset.text.toString().trim().toFloatOrNull()
        val width = etWidth.text.toString().trim().toFloatOrNull()
        if (padding == null || padding < 0f || inset == null || inset < 0f ||
            width == null || width <= 0f
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
            lineColor = selectedColor(),
            lineWidthDp = width,
            // 双框间距 / 背景纸不在本页：原样回传读到的值，避免改边框顺带改掉它们
            lineGapDp = base.lineGapDp,
            paperColor = base.paperColor,
            paperEnabled = base.paperEnabled,
            paddingDp = padding,
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
