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
 * Hue strip of the HSV colour picker (UI PRD §3.9): a 0–360 degree gradient bar with a draggable
 * indicator. Saturation and brightness are the two Material sliders next to it.
 *
 * Only the hue lives here because a hue bar cannot be expressed as a plain slider; both sliders
 * stay stock components so their touch target and accessibility come for free.
 */
class HueStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Hue in degrees, 0..360. */
    var hue: Float = 0f
        set(value) {
            field = value.coerceIn(0f, MAX_HUE)
            invalidate()
        }

    var onHueChanged: ((Float) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val cornerRadius = 8f * density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = Color.WHITE
    }
    private val track = RectF()

    private var gradient: LinearGradient? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = resolveSize(suggestedMinimumWidth, widthMeasureSpec)
        // Respect the height the layout asks for: consuming it here would break the parent scroll
        val height = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> minOf(defaultHeightPx(), MeasureSpec.getSize(heightMeasureSpec))
            else -> defaultHeightPx()
        }
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val inset = indicatorRadius()
        track.set(inset, inset, (w - inset).toFloat(), (h - inset).toFloat())
        gradient = LinearGradient(
            track.left, 0f, track.right, 0f,
            HUE_COLORS, null, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (track.isEmpty) return

        paint.shader = gradient
        canvas.drawRoundRect(track, cornerRadius, cornerRadius, paint)
        paint.shader = null

        val x = track.left + (track.width() * (hue / MAX_HUE))
        val cy = track.centerY()
        val radius = indicatorRadius()
        canvas.drawCircle(x, cy, radius, indicatorPaint)
        paint.color = hueColor(hue)
        canvas.drawCircle(x, cy, radius - indicatorPaint.strokeWidth, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                hue = hueAt(event.x)
                onHueChanged?.invoke(hue)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
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

    private fun hueAt(x: Float): Float {
        if (track.width() <= 0f) return 0f
        return ((x - track.left) / track.width() * MAX_HUE).coerceIn(0f, MAX_HUE)
    }

    private fun indicatorRadius(): Float = 10f * density

    private fun defaultHeightPx(): Int = (44f * density).toInt()

    companion object {
        const val MAX_HUE = 360f

        /** Seven stops give a linear gradient with the correct hue spacing */
        private val HUE_COLORS = IntArray(7) { i -> Color.HSVToColor(floatArrayOf(i * 60f, 1f, 1f)) }

        /** Full-saturation hue colour, used for the slider tracks and the indicator fill */
        fun hueColor(hue: Float): Int = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
    }
}
