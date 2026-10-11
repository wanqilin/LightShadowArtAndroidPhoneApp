package com.ShowSeen.lightshadowart.mobile.scan

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import com.journeyapps.barcodescanner.BarcodeView

/**
 * 扫码取景用的 BarcodeView。
 *
 * ZXing 默认把取景框在容器内垂直居中；这里允许外部指定取景框顶部的目标 Y，
 * 使其紧贴「扫描二维码」标题下方。
 */
class ScanBarcodeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BarcodeView(context, attrs, defStyleAttr) {

    /** 取景框顶部目标 Y（相对本 View）；小于 0 时回退到默认居中位置。 */
    var framingRectTop: Int = -1

    override fun calculateFramingRect(container: Rect, surface: Rect): Rect {
        val rect = super.calculateFramingRect(container, surface)
        if (framingRectTop >= 0) {
            val maxTop = (container.bottom - rect.height()).coerceAtLeast(container.top)
            rect.offsetTo(rect.left, framingRectTop.coerceIn(container.top, maxTop))
        }
        return rect
    }
}
