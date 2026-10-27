package com.ShowSeen.lightshadowart.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.os.Build
import android.util.AttributeSet
import android.view.View
import com.ShowSeen.lightshadowart.mobile.net.FrameBackground
import com.ShowSeen.lightshadowart.mobile.net.FrameParamInfo
import com.ShowSeen.lightshadowart.mobile.net.PanelInfo

/**
 * Live preview of the device panel (PRD 6.10).
 *
 * The user could only "change a number, walk to the wall and look at the screen" before, which is why
 * the parameters were unreadable. This view draws the same layers the device draws — background →
 * photo → frame bands — using the panel's real aspect ratio, so a slider change is visible at once.
 *
 * Rendered from parameter ids, not from a per-style switch: whatever the device declares is what gets
 * previewed, so a new device-side style needs no phone change here.
 */
class FramePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val srcRect = Rect()
    private val dstRect = RectF()

    private var panel = PanelInfo()
    private var params: List<FrameParamInfo> = emptyList()
    private var values: Map<String, Any> = emptyMap()
    private var background = FrameBackground()
    private var backgroundBitmap: Bitmap? = null

    /** Aspect used when the real picture is not available yet (4:3 landscape, the common case) */
    private val placeholderAspect = 4f / 3f

    /** The picture being edited, so the preview really shows what the panel will show */
    private var photoBitmap: Bitmap? = null

    fun setFrame(
        params: List<FrameParamInfo>,
        values: Map<String, Any>,
        background: FrameBackground,
        panel: PanelInfo
    ) {
        this.params = params
        this.values = values
        this.background = background
        this.panel = panel
        requestLayout()
        invalidate()
    }

    /** Background image to preview (the shared device-side backdrop); null clears it */
    fun setBackgroundBitmap(bitmap: Bitmap?) {
        backgroundBitmap = bitmap
        invalidate()
    }

    /** The picture being edited (fetched from the device); null shows a neutral placeholder */
    fun setPhotoBitmap(bitmap: Bitmap?) {
        if (photoBitmap === bitmap) return
        photoBitmap = bitmap
        invalidate()
    }

    private fun photoAspect(): Float {
        val bitmap = photoBitmap
        return if (bitmap != null && bitmap.height > 0) {
            bitmap.width.toFloat() / bitmap.height
        } else {
            placeholderAspect
        }
    }

    private fun lengthDp(id: String): Float =
        (values[id] as? Number)?.toFloat()
            ?: params.firstOrNull { it.id == id }?.defaultDp
            ?: 0f

    private fun color(id: String): Int =
        (values[id] as? Number)?.toInt()
            ?: params.firstOrNull { it.id == id }?.defaultColor
            ?: Color.TRANSPARENT

    private fun has(id: String): Boolean = params.any { it.id == id }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        // Respect the height the layout asked for; the aspect ratio only fills it in for wrap_content
        val height = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            else -> (width * panel.aspect).toInt()
        }
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        // 1) background: what the device paints outside the photo
        drawBackground(canvas)

        val scale = width.toFloat() / panel.widthPx
        val insets = insetsDp()
        val unit = panel.density * scale
        val box = RectF(
            insets[0] * unit,
            insets[1] * unit,
            width - insets[2] * unit,
            height - insets[3] * unit
        )
        if (box.width() <= 0f || box.height() <= 0f) return

        // 2) the picture itself, contain-fitted exactly like the device does
        val aspect = photoAspect()
        val photoWidth: Float
        val photoHeight: Float
        if (box.width() / box.height() > aspect) {
            photoHeight = box.height()
            photoWidth = photoHeight * aspect
        } else {
            photoWidth = box.width()
            photoHeight = photoWidth / aspect
        }
        val photo = RectF(
            box.centerX() - photoWidth / 2f,
            box.centerY() - photoHeight / 2f,
            box.centerX() + photoWidth / 2f,
            box.centerY() + photoHeight / 2f
        )
        val bitmap = photoBitmap
        if (bitmap != null && !bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) {
            srcRect.set(0, 0, bitmap.width, bitmap.height)
            dstRect.set(photo)
            canvas.drawBitmap(bitmap, srcRect, dstRect, imagePaint)
        } else {
            fillPaint.color = PHOTO_COLOR
            canvas.drawRect(photo, fillPaint)
        }

        // 3) frame bands, pushed outward from the photo rect (same rule as the device)
        drawFrame(canvas, photo, panel.density * scale)
    }

    private fun drawBackground(canvas: Canvas) {
        when (background.mode) {
            FrameBackground.MODE_IMAGE -> {
                val bitmap = backgroundBitmap
                if (bitmap != null && !bitmap.isRecycled) {
                    val scale = maxOf(
                        width.toFloat() / bitmap.width,
                        height.toFloat() / bitmap.height
                    )
                    val drawWidth = bitmap.width * scale
                    val drawHeight = bitmap.height * scale
                    srcRect.set(0, 0, bitmap.width, bitmap.height)
                    dstRect.set(
                        (width - drawWidth) / 2f,
                        (height - drawHeight) / 2f,
                        (width + drawWidth) / 2f,
                        (height + drawHeight) / 2f
                    )
                    canvas.save()
                    canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
                    canvas.drawBitmap(bitmap, srcRect, dstRect, imagePaint)
                    canvas.restore()
                } else {
                    fillPaint.color = Color.parseColor("#6B7280")
                    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)
                }
            }
            FrameBackground.MODE_SOLID -> {
                fillPaint.color = background.color
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)
            }
            else -> {
                fillPaint.color = Color.parseColor("#202226")
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)
            }
        }
    }

    /** Inset of the current style per side, in dp: left, top, right, bottom */
    private fun insetsDp(): FloatArray {
        var inset = 0f
        if (has("band_dp")) inset += lengthDp("band_dp")
        if (has("offset_dp")) inset += lengthDp("offset_dp") + lengthDp("gap_dp")
        inset += lengthDp("margin_dp")

        // The shadow only needs room where it is drawn (the cast side), so its inset is per side — the
        // same rule the device uses, otherwise the preview would shrink the photo differently.
        var left = inset
        var top = inset
        var right = inset
        var bottom = inset
        if (has("spread_dp")) {
            // The shadow is swept out to `offset` and flares by `spread` on the cast sides only
            val reach = maxOf(0f, lengthDp("shadow_offset_dp")) + maxOf(0f, lengthDp("spread_dp"))
            val radians = Math.toRadians(lengthDp("shadow_angle_deg").toDouble())
            val ux = Math.cos(radians).toFloat()
            val uy = Math.sin(radians).toFloat()
            left += reach * maxOf(0f, -ux)
            right += reach * maxOf(0f, ux)
            top += reach * maxOf(0f, -uy)
            bottom += reach * maxOf(0f, uy)
        }
        bottom += maxOf(0f, lengthDp("bottom_extra_dp"))
        return floatArrayOf(maxOf(0f, left), maxOf(0f, top), maxOf(0f, right), maxOf(0f, bottom))
    }

    private fun expand(rect: RectF, left: Float, top: Float, right: Float, bottom: Float): RectF =
        RectF(rect.left - left, rect.top - top, rect.right + right, rect.bottom + bottom)

    /** Fills the difference between two nested rects as four side rects (no overlap, no seams) */
    private fun drawBand(canvas: Canvas, inner: RectF, outer: RectF) {
        if (outer.top < inner.top) {
            canvas.drawRect(outer.left, outer.top, outer.right, inner.top, fillPaint)
        }
        if (outer.bottom > inner.bottom) {
            canvas.drawRect(outer.left, inner.bottom, outer.right, outer.bottom, fillPaint)
        }
        if (outer.left < inner.left) {
            canvas.drawRect(outer.left, inner.top, inner.left, inner.bottom, fillPaint)
        }
        if (outer.right > inner.right) {
            canvas.drawRect(inner.right, inner.top, outer.right, inner.bottom, fillPaint)
        }
    }

    private fun drawFrame(canvas: Canvas, photo: RectF, density: Float) {
        // mat: paper → bevel → photo shadow → decorative line → outline
        if (has("band_dp")) {
            val band = lengthDp("band_dp") * density
            val extra = lengthDp("bottom_extra_dp") * density
            val bandColor = color("band_color")
            // Bottom side is band + extra (0 extra = uniform mat)
            fillBand(photo, band, band + extra, bandColor, canvas)

            val bevel = lengthDp("bevel_dp") * density
            if (bevel > 0f) {
                fillBand(photo, bevel, bevel, lighten(bandColor, 0.55f), canvas)
            }
            // 1dp shadow right against the photo
            val shadowLine = maxOf(1f, density * 0.5f)
            fillBand(photo, shadowLine, shadowLine, shade(bandColor, 0.55f), canvas)

            val deco = lengthDp("deco_line_dp") * density
            if (deco > 0f) {
                val rect = RectF(photo)
                val offset = lengthDp("deco_offset_dp") * density
                rect.inset(-offset, -offset)
                linePaint.color = color("deco_line_color")
                linePaint.strokeWidth = deco
                canvas.drawRect(rect, linePaint)
            }
            val outer = lengthDp("outer_line_dp") * density
            if (outer > 0f) {
                val half = outer / 2f
                val rect = RectF(
                    photo.left - band - half,
                    photo.top - band - half,
                    photo.right + band + half,
                    photo.bottom + band + extra + half
                )
                linePaint.color = color("outer_line_color")
                linePaint.strokeWidth = outer
                canvas.drawRect(rect, linePaint)
            }
        }

        // shadow: the silhouette is swept along the shadow direction and fades out at the far end;
        // every copy moves away from the light, so the lit side gets exactly zero shadow
        if (has("spread_dp")) {
            val reach = lengthDp("shadow_offset_dp") * density
            val flare = lengthDp("spread_dp") * density
            val blur = minOf(lengthDp("blur_dp") * density, reach)
            val radians = Math.toRadians(lengthDp("shadow_angle_deg").toDouble())
            val ux = Math.cos(radians).toFloat()
            val uy = Math.sin(radians).toFloat()
            val alphaMax = (lengthDp("shadow_opacity").coerceIn(0f, 100f) * 2.55f).toInt()
            val shadowColor = color("shadow_color")
            val inner = lengthDp("inner_line_dp") * density

            val gLeft = flare * maxOf(0f, -ux)
            val gTop = flare * maxOf(0f, -uy)
            val gRight = flare * maxOf(0f, ux)
            val gBottom = flare * maxOf(0f, uy)

            if (alphaMax > 0 && reach > 0f) {
                canvas.save()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    canvas.clipOutRect(photo)
                } else {
                    @Suppress("DEPRECATION")
                    canvas.clipRect(photo, Region.Op.DIFFERENCE)
                }
                val r = Color.red(shadowColor)
                val g = Color.green(shadowColor)
                val b = Color.blue(shadowColor)

                val solidUntil = reach - blur
                var previous = 1f
                val steps = 64
                for (i in 1..steps) {
                    val t = i.toFloat() / steps
                    val distance = t * reach
                    val profile = if (solidUntil <= 0f) {
                        1f - t
                    } else if (distance <= solidUntil) {
                        1f
                    } else {
                        1f - (distance - solidUntil) / blur
                    }
                    val a = (alphaMax * (previous - profile)).toInt()
                    previous = profile
                    if (a <= 0) continue
                    fillPaint.color = Color.argb(a, r, g, b)
                    val copy = RectF(photo)
                    copy.offset(ux * distance, uy * distance)
                    copy.left -= gLeft
                    copy.top -= gTop
                    copy.right += gRight
                    copy.bottom += gBottom
                    canvas.drawRect(copy, fillPaint)
                }
                canvas.restore()
            }
            if (inner > 0f) {
                fillBand(photo, inner, inner, color("inner_line_color"), canvas)
            }
        }

        // simple_line: inner line on photo+offset, outer on inner+gap
        if (has("offset_dp")) {
            val line = maxOf(1f, lengthDp("line_dp") * density)
            linePaint.color = color("line_color")
            linePaint.strokeWidth = line
            val inner = RectF(photo)
            val offset = lengthDp("offset_dp") * density
            inner.inset(-offset, -offset)
            canvas.drawRect(inner, linePaint)
            val gap = lengthDp("gap_dp") * density
            if (gap > 0f) {
                val outer = RectF(photo)
                val outerOffset = offset + gap
                outer.inset(-outerOffset, -outerOffset)
                canvas.drawRect(outer, linePaint)
            }
        }
    }

    /** Fills a band whose bottom side may be wider (bottom-weighted mat), like the device */
    private fun fillBand(photo: RectF, width: Float, bottomExtra: Float, color: Int, canvas: Canvas) {
        if (width <= 0f && bottomExtra <= 0f) return
        fillPaint.color = color
        canvas.drawRect(photo.left - width, photo.top - width, photo.right + width, photo.top, fillPaint)
        canvas.drawRect(photo.left - width, photo.bottom, photo.right + width, photo.bottom + bottomExtra, fillPaint)
        canvas.drawRect(photo.left - width, photo.top, photo.left, photo.bottom, fillPaint)
        canvas.drawRect(photo.right, photo.top, photo.right + width, photo.bottom, fillPaint)
    }

    /** Fills the band [photo, photo + width] (centre-line stroke, like the device's fillBand) */
    private fun lined(width: Float, colorUnused: Int, color: Int, canvas: Canvas, photo: RectF) {
        if (width <= 0f) return
        val rect = RectF(photo)
        rect.inset(-width / 2f, -width / 2f)
        linePaint.color = color
        linePaint.strokeWidth = width
        canvas.drawRect(rect, linePaint)
    }

    private fun lighten(color: Int, ratio: Float): Int {
        val r = (Color.red(color) + (255 - Color.red(color)) * ratio).toInt()
        val g = (Color.green(color) + (255 - Color.green(color)) * ratio).toInt()
        val b = (Color.blue(color) + (255 - Color.blue(color)) * ratio).toInt()
        return Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
    }

    private fun shade(color: Int, ratio: Float): Int {
        val r = (Color.red(color) * ratio).toInt()
        val g = (Color.green(color) * ratio).toInt()
        val b = (Color.blue(color) * ratio).toInt()
        return Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
    }

    private companion object {
        /** Neutral stand-in for the photo: the preview shows the frame, not the picture */
        const val PHOTO_COLOR = 0xFF8CA0B8.toInt()
    }
}
