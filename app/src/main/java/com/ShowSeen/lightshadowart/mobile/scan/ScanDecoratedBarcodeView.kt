package com.ShowSeen.lightshadowart.mobile.scan

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import com.ShowSeen.lightshadowart.mobile.R
import com.journeyapps.barcodescanner.CameraPreview
import com.journeyapps.barcodescanner.DecoratedBarcodeView

/**
 * 自定义扫码取景视图：把「扫描二维码」标题贴在页面顶部，
 * 把提示文字放在取景框下方。
 *
 * 取景框沿用 ZXing 默认的垂直居中位置，于是标题在顶、取景框居中、提示紧随其下，
 * 三者自上而下铺开，不会全部挤在页面上半部。
 */
class ScanDecoratedBarcodeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : DecoratedBarcodeView(context, attrs, defStyleAttr) {

    private val titleView: TextView? = findViewById(R.id.scan_title)
    private val promptView: TextView? = findViewById(R.id.zxing_status_view)

    private val gapPx: Int = (12 * resources.displayMetrics.density).toInt()

    // 标题固定在屏幕顶部（避开状态栏，再留一点间距）
    private val titleTopPx: Int = statusBarHeightPx() + (12 * resources.displayMetrics.density).toInt()

    init {
        getBarcodeView().addStateListener(object : CameraPreview.StateListener {
            override fun previewSized() = positionOverlays()
            override fun previewStarted() {}
            override fun previewStopped() {}
            override fun cameraError(error: Exception?) {}
            override fun cameraClosed() {}
        })
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        positionOverlays()
    }

    private fun positionOverlays() {
        val frame = getBarcodeView().framingRect ?: return
        titleView?.let {
            it.translationY = titleTopPx.toFloat()
            it.visibility = View.VISIBLE
        }
        promptView?.let {
            it.translationY = (frame.bottom + gapPx).toFloat()
            it.visibility = View.VISIBLE
        }
    }

    private fun statusBarHeightPx(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }
}
