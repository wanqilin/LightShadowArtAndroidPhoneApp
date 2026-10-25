package com.ShowSeen.lightshadowart.mobile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Colour wheel: hue by angle, saturation by distance from the centre.
 *
 * This is the "pick roughly what you have in mind" control; the RGB sliders under it are for setting
 * exact channel values afterwards. Brightness is not part of the disc (a disc cannot show darkness) —
 * [ValueBarView] owns that.
 *
 * Drawn with two shaders instead of a pre-rendered bitmap: a hue sweep, then a white→transparent
 * radial fade for saturation, then a black overlay for brightness.
 */
class ColorWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val wheelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val satPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val markerOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        color = Color.BLACK
    }
    private val markerInner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.WHITE
    }

    private var hueColors: IntArray = IntArray(0)

    /** Hue in degrees, 0..360 */
    var hue: Float = 0f
        set(value) {
            field = ((value % 360f) + 360f) % 360f
            invalidate()
        }

    /** Saturation 0..1 */
    var saturation: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    /** Brightness 0..1, comes from [ValueBarView]; only dims the disc */
    var brightness: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    /** Called while dragging; hue in degrees, saturation 0..1 */
    var onPicked: ((Float, Float) -> Unit)? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 361 entries so the last one equals the first and the sweep closes seamlessly
        hueColors = IntArray(361) { i ->
            Color.HSVToColor(floatArrayOf((i % 360).toFloat(), 1f, 1f))
        }
    }

    private fun radius(): Float = min(width, height) / 2f - 2f * density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (hueColors.isEmpty()) return
        val cx = width / 2f
        val cy = height / 2f
        val r = radius()
        if (r <= 0f) return

        wheelPaint.shader = SweepGradient(cx, cy, hueColors, null)
        canvas.drawCircle(cx, cy, r, wheelPaint)

        satPaint.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(Color.WHITE, 0x00FFFFFF),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, satPaint)

        if (brightness < 1f) {
            dimPaint.color = Color.argb(((1f - brightness) * 255f).toInt(), 0, 0, 0)
            canvas.drawCircle(cx, cy, r, dimPaint)
        }

        // Marker: white ring with a black outline so it reads on any part of the wheel
        val angle = Math.toRadians(hue.toDouble())
        val distance = saturation * r
        val mx = cx + (cos(angle) * distance).toFloat()
        val my = cy + (sin(angle) * distance).toFloat()
        canvas.drawCircle(mx, my, 8f * density, markerOuter)
        canvas.drawCircle(mx, my, 8f * density, markerInner)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                pick(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP -> {
                pick(event.x, event.y)
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun pick(x: Float, y: Float) {
        val cx = width / 2f
        val cy = height / 2f
        val r = radius()
        if (r <= 0f) return
        val dx = x - cx
        val dy = y - cy
        val distance = sqrt(dx * dx + dy * dy)
        val degrees = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        hue = degrees
        saturation = (distance / r).coerceIn(0f, 1f)
        onPicked?.invoke(hue, saturation)
    }
}
