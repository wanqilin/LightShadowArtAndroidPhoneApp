package com.ShowSeen.lightshadowart.mobile

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.ShowSeen.lightshadowart.mobile.net.FrameParams

/**
 * 颜色二级页面（F-6 ③ 边框颜色；F-6 第三轮起「背景颜色」复用本页）。
 *
 * 用户口径：**用色盘选色**并能显示 / 调节 **R/G/B**，**界面上不得出现十六进制**；
 * 原先把色盘与 R/G/B 挤在「添加边框」页底部，三条滑杆间距太窄，故拆成独立页面。
 * 第三轮又把行距从 76dp 收到 **56dp**（用户反馈「上下间隔又太宽了」），
 * 同时保证 SeekBar 可触摸高度 48dp、`Done` 按钮完整可见。
 *
 * 返回时把选中的颜色放在 `Intent` 的 [EXTRA_COLOR] 里回传给上一页更新预览：
 * 返回箭头、系统返回键与「完成」按钮都走 [returnColor]，用户不必额外确认一次。
 * 页面标题由调用方通过 [EXTRA_TITLE_RES] 指定（边框颜色 / 背景颜色）。
 */
class FrameColorActivity : AppCompatActivity() {

    private lateinit var wheel: ColorWheelView
    private lateinit var tvTitle: TextView
    private lateinit var seekR: SeekBar
    private lateinit var seekG: SeekBar
    private lateinit var seekB: SeekBar
    private lateinit var tvR: TextView
    private lateinit var tvG: TextView
    private lateinit var tvB: TextView
    private lateinit var preview: View

    /** 程序化改滑杆 / 色盘时抑制回调，避免两边互相触发 */
    private var syncing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_frame_color)

        wheel = findViewById(R.id.view_color_wheel)
        tvTitle = findViewById(R.id.tv_color_title)
        seekR = findViewById(R.id.seek_color_r)
        seekG = findViewById(R.id.seek_color_g)
        seekB = findViewById(R.id.seek_color_b)
        tvR = findViewById(R.id.tv_color_r)
        tvG = findViewById(R.id.tv_color_g)
        tvB = findViewById(R.id.tv_color_b)
        preview = findViewById(R.id.view_color_preview)

        tvTitle.setText(intent.getIntExtra(EXTRA_TITLE_RES, R.string.add_frame_color))

        findViewById<ImageButton>(R.id.btn_color_back).setOnClickListener { returnColor() }
        findViewById<MaterialButton>(R.id.btn_color_done).setOnClickListener { returnColor() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = returnColor()
        })

        wheel.onColorPicked = { color -> showColor(color, fromWheel = true) }
        val seekListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!syncing) showColor(selectedColor(), fromWheel = false)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }
        seekR.setOnSeekBarChangeListener(seekListener)
        seekG.setOnSeekBarChangeListener(seekListener)
        seekB.setOnSeekBarChangeListener(seekListener)

        showColor(
            intent.getIntExtra(EXTRA_COLOR, FrameParams.DEFAULT_LINE_COLOR),
            fromWheel = false
        )
    }

    /**
     * 把颜色铺到色盘 / R/G/B 滑杆 / 预览块上。
     *
     * `fromWheel = true` 时不再回写色盘（本来就是它报上来的），避免自激；
     * 滑杆→颜色这条路径则要把色盘的位置指示同步过去。
     */
    private fun showColor(color: Int, fromWheel: Boolean) {
        syncing = true
        if (!fromWheel) wheel.setColor(color)
        seekR.progress = Color.red(color)
        seekG.progress = Color.green(color)
        seekB.progress = Color.blue(color)
        // 只显示 R/G/B 数值，不给用户看十六进制（用户口径）
        tvR.text = Color.red(color).toString()
        tvG.text = Color.green(color).toString()
        tvB.text = Color.blue(color).toString()
        preview.setBackgroundColor(color)
        syncing = false
    }

    /** 当前 R/G/B 滑杆代表的颜色（alpha 固定不透明） */
    private fun selectedColor(): Int = Color.rgb(seekR.progress, seekG.progress, seekB.progress)

    /** 把选中的颜色回传给「添加边框」页（未改动时回传原值，预览不变） */
    private fun returnColor() {
        setResult(
            Activity.RESULT_OK,
            Intent().putExtra(EXTRA_COLOR, selectedColor())
        )
        finish()
    }

    companion object {
        /** 回传字段：选中的颜色，0xAARRGGBB */
        const val EXTRA_COLOR = "extra_frame_line_color"

        /** 入参字段：页面标题的字符串资源 id（边框颜色 / 背景颜色共用本页） */
        const val EXTRA_TITLE_RES = "extra_color_title_res"
    }
}
