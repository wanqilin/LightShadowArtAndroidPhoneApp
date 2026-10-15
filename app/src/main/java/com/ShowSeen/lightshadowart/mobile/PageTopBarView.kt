package com.ShowSeen.lightshadowart.mobile

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat

/**
 * Page header used by every secondary page: back arrow on the left, title next to it and an
 * optional read-only status pill on the right (UI PRD §3.4 ~ §3.10, §6.3).
 *
 * Kept as one view instead of repeating the same three children in seven layouts; pages that
 * need the pill (Space Art Gallery) call [showTrailingPill], the rest leave it hidden.
 */
class PageTopBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val backButton: ImageButton
    private val titleView: TextView
    private val trailingView: TextView

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            resources.getDimensionPixelSize(R.dimen.top_bar_height)
        )
        View.inflate(context, R.layout.view_page_top_bar, this)
        backButton = findViewById(R.id.top_bar_back)
        titleView = findViewById(R.id.top_bar_title)
        trailingView = findViewById(R.id.top_bar_trailing)
    }

    fun setTitle(text: CharSequence) {
        titleView.text = text
    }

    /** The back arrow returns to the previous page; a page may hide it when it has no parent. */
    fun setOnBackClickListener(listener: OnClickListener?) {
        backButton.setOnClickListener(listener)
        backButton.visibility = if (listener == null) GONE else VISIBLE
    }

    /**
     * Show the read-only trailing pill. Callers pass both an icon and a word so the state never
     * depends on colour alone (UI PRD §5).
     */
    fun showTrailingPill(@DrawableRes iconRes: Int, text: CharSequence, tintColorRes: Int) {
        trailingView.visibility = VISIBLE
        trailingView.text = text
        trailingView.setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0)
        trailingView.setTextColor(ContextCompat.getColor(context, tintColorRes))
        trailingView.compoundDrawableTintList =
            ContextCompat.getColorStateList(context, tintColorRes)
    }

    fun hideTrailingPill() {
        trailingView.visibility = GONE
    }
}
