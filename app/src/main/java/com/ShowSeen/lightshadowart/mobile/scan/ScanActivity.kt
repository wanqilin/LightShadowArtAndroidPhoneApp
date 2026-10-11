package com.ShowSeen.lightshadowart.mobile.scan

import com.ShowSeen.lightshadowart.mobile.R
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView

/**
 * 自定义扫码页：竖屏取景，取景框顶部显示「扫描二维码」，
 * 取景框下方显示操作提示。
 */
class ScanActivity : CaptureActivity() {

    override fun initializeContent(): DecoratedBarcodeView {
        setContentView(R.layout.activity_scan)
        return findViewById(R.id.zxing_barcode_scanner)
    }
}
