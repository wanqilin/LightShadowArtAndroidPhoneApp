package com.ShowSeen.lightshadowart.mobile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * One colour swatch: the colour itself plus a selection ring and a tick.
 *
 * Selection is never carried by colour alone (PRD 2.1): the selected swatch gets a white ring and a
 * tick, so "which one is picked" is visible even for near-white or near-black colours, and for users
 * who cannot tell two similar hues apart.
 */
class SwatchView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * resources.displayMetrics.density
        color = 0x33000000
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density
        color = Color.WHITE
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
    }
    private val tickShadow = Paint(tick).apply { color = 0x66000000 }

    private val rect = RectF()
    private val path = Path()

    /** The swatch colour, always opaque. */
    var color: Int = Color.WHITE
        set(value) {
            field = Color.rgb(Color.red(value), Color.green(value), Color.blue(value))
            invalidate()
        }

    var picked: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val radius: Float = 10f * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = ring.strokeWidth
        rect.set(inset, inset, width - inset, height - inset)
        fill.color = color
        canvas.drawRoundRect(rect, radius, radius, fill)
        canvas.drawRoundRect(rect, radius, radius, border)
        if (picked) {
            canvas.drawRoundRect(rect, radius, radius, ring)
            // Tick: dark underlay first so it stays visible on light colours
            val size = kotlin.math.min(width, height).toFloat()
            val cx = width / 2f
            val cy = height / 2f
            path.reset()
            path.moveTo(cx - size * 0.17f, cy)
            path.lineTo(cx - size * 0.04f, cy + size * 0.13f)
            path.lineTo(cx + size * 0.19f, cy - size * 0.15f)
            canvas.drawPath(path, tickShadow)
            canvas.drawPath(path, tick)
        }
    }

    /** Read back the colour for accessibility, e.g. "已选" plus the preset name set by the caller. */
    fun describe(label: String, isPicked: Boolean) {
        contentDescription =
            if (isPicked) context.getString(R.string.color_swatch_selected, label) else label
    }
}
