package com.ShowSeen.lightshadowart.mobile

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.google.android.material.button.MaterialButton

/**
 * First-run guide, step 1 (UI PRD §3.1): language choice plus the entry into device binding.
 *
 * The guide only appears when this phone has never bound a frame: with a bound (or remembered)
 * device the app goes straight to the Dashboard, which is what the previous single-page app did
 * and what the user expects on every later launch.
 */
class WelcomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SessionStore.init(this)

        if (SessionStore.isActive || SessionStore.lastDeviceToken.isNotEmpty()) {
            openDashboard()
            return
        }

        setContentView(R.layout.activity_welcome)

        bindLanguageCard(R.id.card_language_zh, LANGUAGE_ZH)
        bindLanguageCard(R.id.card_language_en, LANGUAGE_EN)
        renderLanguageSelection()

        findViewById<MaterialButton>(R.id.btn_welcome_start).setOnClickListener {
            startActivity(Intent(this, BindDeviceActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        renderLanguageSelection()
    }

    /**
     * Choosing a language takes effect immediately: AppCompat applies the new locale and recreates
     * the activity, so the copy on this page switches right away. Persistence is handled by
     * `AppLocalesMetadataHolderService` with `autoStoreLocales` in the manifest.
     */
    private fun bindLanguageCard(cardId: Int, tag: String) {
        findViewById<LinearLayout>(cardId).setOnClickListener {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
        }
    }

    /** Selected state is shown by the stroke colour *and* a check mark, never by colour alone. */
    private fun renderLanguageSelection() {
        val selected = currentLanguageTag()
        applySelection(R.id.card_language_zh, R.id.iv_language_zh_check, selected == LANGUAGE_ZH)
        applySelection(R.id.card_language_en, R.id.iv_language_en_check, selected == LANGUAGE_EN)
    }

    private fun applySelection(cardId: Int, checkId: Int, isSelected: Boolean) {
        findViewById<LinearLayout>(cardId).setBackgroundResource(
            if (isSelected) R.drawable.bg_card_selected else R.drawable.bg_card_outline
        )
        findViewById<ImageView>(checkId).visibility =
            if (isSelected) View.VISIBLE else View.INVISIBLE
    }

    /**
     * The language actually in use, read from the resources: with no manual choice the app follows
     * the system language, and only the configuration reflects that.
     */
    private fun currentLanguageTag(): String = resources.configuration.locales[0].language

    private fun openDashboard() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private companion object {
        const val LANGUAGE_ZH = "zh"
        const val LANGUAGE_EN = "en"
    }
}
