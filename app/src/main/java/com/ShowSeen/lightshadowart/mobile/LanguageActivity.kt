package com.ShowSeen.lightshadowart.mobile

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Language selection page (F-3: promoted from a dialog to its own page).
 *
 * Choosing takes effect at once: AppCompat applies the locale and recreates the activity, so the
 * copy on this page and in Settings switches immediately. Persistence is handled by
 * `AppLocalesMetadataHolderService` with `autoStoreLocales` in the manifest.
 */
class LanguageActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_language)

        findViewById<PageTopBarView>(R.id.language_top_bar).apply {
            setTitle(getString(R.string.settings_language))
            setOnBackClickListener { finish() }
        }

        bind(R.id.row_language_zh, R.id.iv_language_check_zh, LANGUAGE_ZH)
        bind(R.id.row_language_en, R.id.iv_language_check_en, LANGUAGE_EN)
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
     * The language actually in use, read from the resources: with no manual choice the app follows
     * the system language, and only the configuration reflects that.
     */
    private fun currentLanguageTag(): String = resources.configuration.locales[0].language

    private companion object {
        const val LANGUAGE_ZH = "zh"
        const val LANGUAGE_EN = "en"
    }
}
