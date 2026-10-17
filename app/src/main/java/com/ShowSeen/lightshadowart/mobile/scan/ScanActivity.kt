package com.ShowSeen.lightshadowart.mobile.scan

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.os.LocaleList
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatDelegate
import com.ShowSeen.lightshadowart.mobile.R
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView

/**
 * Full-screen scanner page (UI PRD §3.2 B): portrait camera preview with the guide layer on top.
 *
 * [CaptureActivity] extends the framework Activity, so it does not inherit the AppCompat
 * per-app language and would follow the system locale instead of the one picked on the Welcome
 * page. [attachBaseContext] wraps the base context with the current app locale to keep them equal.
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The guide layer sits in the activity layout, which already exists once super.onCreate ran.
        findViewById<ImageButton>(R.id.btn_scan_back).setOnClickListener { finish() }
    }
}
