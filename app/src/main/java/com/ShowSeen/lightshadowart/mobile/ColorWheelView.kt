package com.ShowSeen.lightshadowart.mobile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * 边框颜色色盘（F-6 ③）。
 *
 * 用户口径：**调用色盘**选色，并能显示 / 调节 R/G/B，**界面上不得出现十六进制数字**。
 * 本控件负责色盘本身（色相环 + 由中心到边缘的饱和度），点 / 拖动即取色；
 * 明度（V）由 R/G/B 三条滑杆负责——只靠色盘取不到深色。
 *
 * 取色只改色相与饱和度、保留当前明度，这样用户先用滑杆调暗再回色盘微调色相时，
 * 不会突然被拉回最亮。
 */
class ColorWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 当前颜色（HSV，alpha 固定 255） */
    private val hsv = floatArrayOf(0f, 1f, 1f)

    /** 色盘半径（像素） */
    private var radius = 0f
    private var wheelShader: Shader? = null

    /** 取色回调：色盘被点 / 拖动时触发（程序化 setColor 不触发） */
    var onColorPicked: ((Int) -> Unit)? = null

    /** 当前颜色，0xAARRGGBB（alpha 固定为不透明） */
    fun currentColor(): Int = Color.HSVToColor(255, hsv)

    /** 从外部（R/G/B 滑杆）同步颜色；纯黑没有色相可谈，色盘按最亮处理，避免选回不来颜色 */
    fun setColor(color: Int) {
        Color.colorToHSV(color, hsv)
        if (hsv[2] <= MIN_VALUE) hsv[2] = 1f
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 正方形：色盘按可用宽撑满，高度自动
        val width = MeasureSpec.getSize(widthMeasureSpec)
        // 布局给了确定高度就按它来（F-6 修）：原先一律 setMeasuredDimension(width, width)，
        // 色盘被撑成满宽正方形（约 1160px），加上它自己消费触摸，用户根本滑不到下面的 R/G/B。
        // onDraw / onTouchEvent 用的是 min(cx, cy) 与 width/2、height/2，非正方形也能正确居中和缩放。
        val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(heightMeasureSpec)
        } else {
            width
        }
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        radius = min(cx, cy) - MARKER_RADIUS_DP * resources.displayMetrics.density
        if (radius <= 0f) return

        if (wheelShader == null) {
            // 色相环：0° → 360° 扫过一整圈；饱和度：中心白 → 边缘纯色
            val hueColors = IntArray(HUE_STEPS + 1) { i ->
                Color.HSVToColor(floatArrayOf(i * (360f / HUE_STEPS) % 360f, 1f, 1f))
            }
            val hue = SweepGradient(cx, cy, hueColors, null)
            val saturation = RadialGradient(
                cx, cy, radius, Color.WHITE, TRANSPARENT_WHITE, Shader.TileMode.CLAMP
            )
            wheelShader = ComposeShader(hue, saturation, PorterDuff.Mode.SRC_OVER)
        }
        paint.shader = wheelShader
        canvas.drawCircle(cx, cy, radius, paint)
        paint.shader = null

        // 当前颜色的位置指示：先描一圈黑边再画白环，深浅底色上都看得见
        val angle = Math.toRadians(hsv[0].toDouble())
        val markerX = cx + (radius * hsv[1] * Math.cos(angle)).toFloat()
        val markerY = cy + (radius * hsv[1] * Math.sin(angle)).toFloat()
        val markerRadius = MARKER_RADIUS_DP * resources.displayMetrics.density
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = MARKER_STROKE_DP * resources.displayMetrics.density
        paint.color = Color.BLACK
        canvas.drawCircle(markerX, markerY, markerRadius, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(markerX, markerY, markerRadius, paint)
        paint.style = Paint.Style.FILL
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (!isEnabled) return false
                // 色盘嵌在 ScrollView 里：按住拖动是取色，不能被滚动抢走手势
                parent?.requestDisallowInterceptTouchEvent(true)
                pick(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** 按触点换算色相 / 饱和度（保留明度），越出圆盘时按边缘（饱和度 = 1）处理 */
    private fun pick(x: Float, y: Float) {
        if (radius <= 0f) return
        val dx = x - width / 2f
        val dy = y - height / 2f
        var hue = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        if (hue < 0f) hue += 360f
        hsv[0] = hue
        hsv[1] = (hypot(dx.toDouble(), dy.toDouble()).toFloat() / radius).coerceIn(0f, 1f)
        invalidate()
        onColorPicked?.invoke(currentColor())
    }

    private companion object {
        /** 色相环采样段数：每段 30°，36 段足够平滑 */
        const val HUE_STEPS = 36

        /** 位置指示环半径 / 描边宽度（dp） */
        const val MARKER_RADIUS_DP = 7f
        const val MARKER_STROKE_DP = 2f

        const val TRANSPARENT_WHITE = 0x00FFFFFF

        /** 明度低于该值就当成纯黑：色相 / 饱和度已无意义 */
        const val MIN_VALUE = 0.001f
    }
}
