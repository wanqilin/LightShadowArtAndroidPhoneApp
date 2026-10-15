package com.ShowSeen.lightshadowart.mobile

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton

/**
 * Floating, collapsible device control pad (UI PRD §3.6 E).
 *
 * Collapsed by default: the gallery keeps its space and only a small round entry button is left.
 * Expanded: a translucent cross pad with Up / Down / Left / Right and a centre key whose meaning
 * follows the real playback state, plus an Exit key kept outside the wheel.
 *
 * The view never decides what a key means for the device; it reports the press and lets the page
 * map it (the centre key is `CONFIRM` in browse mode, start-auto in static playback, pause in auto
 * play). It also greys the whole pad out when the session is not usable.
 */
class DeviceControlPadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Centre-key labels: the word matches the state so the key is never guessed from colour. */
    enum class CenterState(@StringRes val labelRes: Int) {
        OK(R.string.space_pad_center_ok),
        PLAY(R.string.space_pad_center_play),
        PAUSE(R.string.space_pad_center_pause)
    }

    /** Direction / exit presses: the value is the device control action name. */
    var onAction: ((String) -> Unit)? = null

    /** Centre key press; the page decides between play / auto play / pause from the live state. */
    var onCenterClick: (() -> Unit)? = null

    /** Expanded state changed, so the page can add bottom padding for the gallery content. */
    var onExpandedChanged: ((Boolean) -> Unit)? = null

    private val scrim: View
    private val panel: View
    private val entry: ImageButton
    private val center: TextView
    private val keys: List<ImageButton>

    var isExpanded: Boolean = false
        private set

    private var padEnabled = false

    init {
        View.inflate(context, R.layout.view_device_control_pad, this)
        scrim = findViewById(R.id.pad_scrim)
        panel = findViewById(R.id.pad_panel)
        entry = findViewById(R.id.pad_entry)
        center = findViewById(R.id.pad_center)
        keys = listOf(
            findViewById(R.id.pad_up),
            findViewById(R.id.pad_down),
            findViewById(R.id.pad_left),
            findViewById(R.id.pad_right),
            findViewById(R.id.pad_exit)
        )

        entry.setOnClickListener { setExpanded(!isExpanded) }
        scrim.setOnClickListener { setExpanded(false) }

        val actions = mapOf(
            R.id.pad_up to "UP",
            R.id.pad_down to "DOWN",
            R.id.pad_left to "LEFT",
            R.id.pad_right to "RIGHT",
            R.id.pad_exit to "EXIT"
        )
        actions.forEach { (id, action) ->
            findViewById<ImageButton>(id).setOnClickListener { onAction?.invoke(action) }
        }
        center.setOnClickListener { onCenterClick?.invoke() }

        setCenterState(CenterState.OK)
        setPadEnabled(false)
    }

    /** Expand or collapse the pad. Collapsing also drops the scrim. */
    fun setExpanded(expanded: Boolean) {
        if (!padEnabled && expanded) return
        isExpanded = expanded
        panel.isVisible = expanded
        scrim.isVisible = expanded
        entry.isVisible = !expanded
        onExpandedChanged?.invoke(expanded)
    }

    /** Label for the centre key, kept in sync with the playback state capsule. */
    fun setCenterState(state: CenterState) {
        center.setText(state.labelRes)
    }

    /** Session usable? Disabled greys the whole pad out and collapses it. */
    fun setPadEnabled(enabled: Boolean) {
        if (padEnabled == enabled) return
        padEnabled = enabled
        if (!enabled) setExpanded(false)
        entry.isEnabled = enabled
        entry.alpha = if (enabled) 1f else DISABLED_ALPHA
        keys.forEach {
            it.isEnabled = enabled
            it.alpha = if (enabled) 1f else DISABLED_ALPHA
        }
        center.isEnabled = enabled
        center.alpha = if (enabled) 1f else DISABLED_ALPHA
    }

    /** Preserved across page switches inside the same session, so it does not need re-expanding. */
    fun restoreExpanded(remembered: Boolean) {
        if (remembered) setExpanded(true)
    }

    private companion object {
        const val DISABLED_ALPHA = 0.4f
    }
}
