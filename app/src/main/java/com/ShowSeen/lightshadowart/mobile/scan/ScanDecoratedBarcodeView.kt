package com.ShowSeen.lightshadowart.mobile.scan

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import com.ShowSeen.lightshadowart.mobile.R
import com.journeyapps.barcodescanner.CameraPreview
import com.journeyapps.barcodescanner.DecoratedBarcodeView

/**
 * 自定义扫码取景视图：把「扫描二维码」标题贴在取景框顶部，
 * 把底部提示文字挪到取景框下方。
 *
 * 取景框位置由相机预览尺寸决定，故这里监听 previewSized 回调，
 * 拿到 framingRect 后再把两个文字视图定位到框的上/下方。
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
        // 取景框顶部贴到「扫描二维码」标题下方，先测量标题高度再设定目标位置
        titleView?.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val titleHeight = titleView?.measuredHeight ?: 0
        (getBarcodeView() as? ScanBarcodeView)?.framingRectTop = titleTopPx + titleHeight + gapPx

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
