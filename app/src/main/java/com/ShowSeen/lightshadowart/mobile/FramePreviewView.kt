package com.ShowSeen.lightshadowart.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/**
 * Edit preview for the background config page (requirement 3.3.2 / T-9; F-6 round three extended it to solid colors).
 *
 * - A **red border** marks the maximum boundary 1920x1280 (same as the device logical resolution), warning the user that design content must stay inside it;
 * - Inside the boundary the user-imported background image is drawn with **centerCrop**, matching how the device's `BlurFillView` fills the area;
 * - With no image, the **solid background color** is painted (F-6 round three), matching the device's "image first, solid color as the base" priority,
 *   so the preview shows the same layer the device would paint.
 *
 * Note: the preview is an approximation (the device actually renders at 1920x1280 logical resolution); the device screen is the final word on appearance.
 */
class FramePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val colorPaint = Paint()
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ContextCompat.getColor(context, R.color.frame_boundary)
        strokeWidth = resources.displayMetrics.density * 2f
    }
    private val srcRect = Rect()
    private val dstRect = RectF()

    private var previewBitmap: Bitmap? = null

    /** Solid background color (the base when no image is selected); `null` means paint none */
    private var previewColor: Int? = null

    /** Sets the background image to preview; null clears it (the caller is responsible for recycling the Bitmap) */
    fun setPreviewBitmap(bitmap: Bitmap?) {
        previewBitmap = bitmap
        invalidate()
    }

    /** Sets the solid background color (the base outside the photo, the same color the device's `BlurFillView` paints) */
    fun setPreviewColor(color: Int?) {
        previewColor = color
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        // Fill mode: fill proportionally from the phone's available width, height derived from 1920:1280 (user's spec, requirement 3.3.2 / T-9)
        val height = (width * BOUNDARY_HEIGHT / BOUNDARY_WIDTH).toInt()
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val boundary = boundaryRect()
        if (boundary.width() <= 0f || boundary.height() <= 0f) return

        // Paint the solid base first: an image covers it when present (same priority as the device)
        previewColor?.let { color ->
            colorPaint.color = color
            canvas.drawRect(boundary, colorPaint)
        }

        val bitmap = previewBitmap
        if (bitmap != null && !bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) {
            // centerCrop: the short edge fills the boundary, the long edge is center-cropped, matching how the device fills the base
            val scale = maxOf(
                boundary.width() / bitmap.width,
                boundary.height() / bitmap.height
            )
            val drawWidth = bitmap.width * scale
            val drawHeight = bitmap.height * scale
            val left = boundary.left + (boundary.width() - drawWidth) / 2f
            val top = boundary.top + (boundary.height() - drawHeight) / 2f
            srcRect.set(0, 0, bitmap.width, bitmap.height)
            dstRect.set(left, top, left + drawWidth, top + drawHeight)
            // Clip anything past the maximum boundary so nothing is drawn outside it
            canvas.save()
            canvas.clipRect(boundary)
            canvas.drawBitmap(bitmap, srcRect, dstRect, imagePaint)
            canvas.restore()
        }

        canvas.drawRect(boundary, borderPaint)
    }

    /** The maximum boundary is the whole View: this View's size is already set to 1920:1280 by [onMeasure] */
    private fun boundaryRect(): RectF {
        return RectF(0f, 0f, width.toFloat(), height.toFloat())
    }

    companion object {
        /** Maximum boundary: matches the device logical resolution (requirement 3.3.2 / T-9) */
        const val BOUNDARY_WIDTH = 1920f
        const val BOUNDARY_HEIGHT = 1280f
    }
}
