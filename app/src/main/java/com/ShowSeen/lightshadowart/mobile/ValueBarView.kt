package com.ShowSeen.lightshadowart.mobile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Brightness bar for the colour wheel: black on the left, the wheel's current hue/saturation at full
 * strength on the right. The wheel cannot express darkness, so this is where it is set.
 */
class ValueBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x33000000
    }
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = Color.WHITE
    }
    private val markerShadow = Paint(marker).apply { color = 0x66000000 }
    private val rect = RectF()

    private var hue = 0f
    private var saturation = 1f

    /** Brightness 0..1 */
    var brightness: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var onPicked: ((Float) -> Unit)? = null

    /** Keeps the gradient in step with the wheel so the bar previews the hue being edited */
    fun setHueSaturation(newHue: Float, newSaturation: Float) {
        if (hue == newHue && saturation == newSaturation) return
        hue = newHue
        saturation = newSaturation
        invalidate()
    }

    private val radius: Float = 8f * density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = marker.strokeWidth
        rect.set(inset, inset, width - inset, height - inset)
        if (rect.width() <= 0f || rect.height() <= 0f) return

        track.shader = LinearGradient(
            rect.left, rect.top, rect.right, rect.top,
            Color.BLACK,
            Color.HSVToColor(floatArrayOf(hue, saturation, 1f)),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, radius, radius, track)
        canvas.drawRoundRect(rect, radius, radius, border)

        val cx = rect.left + rect.width() * brightness
        val cy = rect.centerY()
        val markerRadius = rect.height() / 2f
        canvas.drawCircle(cx, cy, markerRadius, markerShadow)
        canvas.drawCircle(cx, cy, markerRadius, marker)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                val inset = marker.strokeWidth
                val usable = (width - 2 * inset).coerceAtLeast(1f)
                brightness = ((event.x - inset) / usable).coerceIn(0f, 1f)
                onPicked?.invoke(brightness)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
