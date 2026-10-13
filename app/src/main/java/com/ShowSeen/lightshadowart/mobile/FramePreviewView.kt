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
 * 自定义边框的编辑预览（需求 3.3.2 / T-9）。
 *
 * - 用**红色边框**标示最大边界 1920x1280（与设备端逻辑分辨率一致），提示用户设计内容须落在该范围内；
 * - 边界内按 **centerCrop** 叠加用户导入的背景图，与设备端 `BlurFillView` 的铺图方式保持一致，
 *   预览才有参考价值。
 *
 * 注意：预览是近似（实际由设备端按 3840x2560 物理屏渲染），最终观感以设备屏为准。
 */
class FramePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ContextCompat.getColor(context, R.color.frame_boundary)
        strokeWidth = resources.displayMetrics.density * 2f
    }
    private val srcRect = Rect()
    private val dstRect = RectF()

    private var previewBitmap: Bitmap? = null

    /** 设置待预览的背景图；传 null 表示清空（调用方负责 Bitmap 的回收） */
    fun setPreviewBitmap(bitmap: Bitmap?) {
        previewBitmap = bitmap
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val boundary = boundaryRect()
        if (boundary.width() <= 0f || boundary.height() <= 0f) return

        val bitmap = previewBitmap
        if (bitmap != null && !bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) {
            // centerCrop：短边铺满边界、长边居中裁掉，和设备端铺底方式一致
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
            // 超出最大边界的部分裁掉，保证「边界之外什么都没有」
            canvas.save()
            canvas.clipRect(boundary)
            canvas.drawBitmap(bitmap, srcRect, dstRect, imagePaint)
            canvas.restore()
        }

        canvas.drawRect(boundary, borderPaint)
    }

    /** 把 1920x1280 等比装进本 View 并居中，得到最大边界矩形 */
    private fun boundaryRect(): RectF {
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return RectF()

        val scale = minOf(viewWidth / BOUNDARY_WIDTH, viewHeight / BOUNDARY_HEIGHT)
        val boundaryWidth = BOUNDARY_WIDTH * scale
        val boundaryHeight = BOUNDARY_HEIGHT * scale
        val left = (viewWidth - boundaryWidth) / 2f
        val top = (viewHeight - boundaryHeight) / 2f
        return RectF(left, top, left + boundaryWidth, top + boundaryHeight)
    }

    companion object {
        /** 最大边界：与设备端逻辑分辨率一致（需求 3.3.2 / T-9） */
        const val BOUNDARY_WIDTH = 1920f
        const val BOUNDARY_HEIGHT = 1280f
    }
}
