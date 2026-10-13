package com.ShowSeen.lightshadowart.mobile

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * 语言选择二级页面（F-3：用户要求由弹窗改为二级页面）。
 *
 * 选中即生效：AppCompat 会因语言变化重建 Activity，本页与设置页文案随之切换；
 * 持久化由 Manifest 里 `AppLocalesMetadataHolderService` 的 `autoStoreLocales` 负责。
 */
class LanguageActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_language)

        findViewById<ImageButton>(R.id.btn_language_back).setOnClickListener { finish() }

        // 与 MainActivity.LANGUAGE_TAGS 同序同值（其 LANGUAGE_ZH / LANGUAGE_EN 即 "zh" / "en"）
        bind(R.id.row_language_zh, R.id.iv_language_check_zh, "zh")
        bind(R.id.row_language_en, R.id.iv_language_check_en, "en")
    }

    private fun bind(rowId: Int, checkId: Int, tag: String) {
        val check = findViewById<ImageView>(checkId)
        check.visibility = if (tag == currentLanguageTag()) View.VISIBLE else View.INVISIBLE
        findViewById<LinearLayout>(rowId).setOnClickListener {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
            finish()
        }
    }

    /**
     * 当前界面实际使用的语言。
     * 取自资源配置而不是 AppCompat 存的值：用户没手动选过时跟随系统语言，
     * 此时只有资源配置能反映真正在显示的是哪一套文案（与 MainActivity 同口径）。
     */
    private fun currentLanguageTag(): String = resources.configuration.locales[0].language
}