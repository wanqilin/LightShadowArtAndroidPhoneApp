package com.ShowSeen.lightshadowart.mobile.scan

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import com.ShowSeen.lightshadowart.mobile.R
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView

/**
 * 自定义扫码页：竖屏取景，取景框顶部显示「扫描二维码」，
 * 取景框下方显示操作提示。
 *
 * [CaptureActivity] 继承自系统 Activity，不会自动套用 AppCompat 的应用级语言，
 * 于是页面内的字符串会跟随系统语言，与入口页（Bind Your Device）不一致。
 * 这里在 attachBaseContext 时用当前 App 语言包装 Context，使二者语言统一。
 */
class ScanActivity : CaptureActivity() {

    override fun attachBaseContext(newBase: Context) {
        val locales = AppCompatDelegate.getApplicationLocales()
        val base = if (locales.isEmpty) {
            newBase
        } else {
            val config = Configuration(newBase.resources.configuration)
            config.setLocales(LocaleList.forLanguageTags(locales.toLanguageTags()))
            newBase.createConfigurationContext(config)
        }
        super.attachBaseContext(base)
    }

    override fun initializeContent(): DecoratedBarcodeView {
        setContentView(R.layout.activity_scan)
        return findViewById(R.id.zxing_barcode_scanner)
    }
}
