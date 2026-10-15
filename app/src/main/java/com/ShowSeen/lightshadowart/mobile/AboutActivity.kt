package com.ShowSeen.lightshadowart.mobile

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * About and Version page (UI PRD §3.7).
 *
 * Everything shown here is a real value: the app version comes from [AppConfig], the firmware
 * version from the session receipt, and a missing firmware version says so instead of inventing one.
 */
class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
        SessionStore.init(this)

        findViewById<PageTopBarView>(R.id.about_top_bar).apply {
            setTitle(getString(R.string.about_title))
            setOnBackClickListener { finish() }
        }

        findViewById<TextView>(R.id.tv_about_mobile_version).text = AppConfig.VERSION
        findViewById<TextView>(R.id.tv_about_device_version).text = SessionStore.deviceVersion
            .ifEmpty { getString(R.string.about_device_version_unknown) }
        findViewById<TextView>(R.id.tv_about_copyright).text =
            getString(R.string.about_copyright, AppConfig.COMPANY)
    }
}
