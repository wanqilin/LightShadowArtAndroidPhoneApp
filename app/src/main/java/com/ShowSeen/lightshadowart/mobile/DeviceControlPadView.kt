package com.ShowSeen.lightshadowart.mobile

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Floating, collapsible device control pad (UI PRD §3.6 E).
 *
 * Collapsed by default: the gallery keeps its space and only a small round entry button is left.
 * Expanded: a translucent cross pad with Up / Down / Left / Right and a centre key whose meaning
 * follows the real playback state.
 *
 * The pad can be dragged anywhere on screen; the entry button and the wheel share one offset, so
 * the pad always reopens where the user dropped it.
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

    /** Direction presses: the value is the device control action name. */
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

    /** Touch travel below this stays a click, above it becomes a drag */
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    /** Anchor geometry, read from dimens so the drag clamp matches the layout exactly */
    private val entrySize = resources.getDimensionPixelSize(R.dimen.control_pad_entry_size)
    private val panelSize = resources.getDimensionPixelSize(R.dimen.control_pad_diameter)
    private val endMargin = resources.getDimensionPixelSize(R.dimen.page_horizontal_padding)
    private val entryBottomMargin = resources.getDimensionPixelSize(R.dimen.control_pad_entry_bottom_margin)
    private val panelBottomMargin = resources.getDimensionPixelSize(R.dimen.control_pad_panel_bottom_margin)

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
            findViewById(R.id.pad_right)
        )

        entry.setOnClickListener { setExpanded(!isExpanded) }
        scrim.setOnClickListener { setExpanded(false) }

        val actions = mapOf(
            R.id.pad_up to "UP",
            R.id.pad_down to "DOWN",
            R.id.pad_left to "LEFT",
            R.id.pad_right to "RIGHT"
        )
        actions.forEach { (id, action) ->
            findViewById<ImageButton>(id).setOnClickListener { onAction?.invoke(action) }
        }
        center.setOnClickListener { onCenterClick?.invoke() }

        // The whole pad is a drag handle: the entry button, the wheel background and every key
        attachDrag(entry)
        attachDrag(panel)
        keys.forEach { attachDrag(it) }
        attachDrag(center)

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

    /**
     * Drags the pad with one finger. Travel below the touch slop still counts as a click, so the
     * entry button and the wheel keys keep working while the pad is movable.
     *
     * Every anchor starts from the offset stored on [entry], not from its own translation: the keys
     * sit inside the panel and carry no offset of their own.
     */
    private fun attachDrag(target: View) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0f
        var startY = 0f
        var dragging = false
        target.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = entry.translationX
                    startY = entry.translationY
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging) dragging = abs(dx) > touchSlop || abs(dy) > touchSlop
                    if (dragging) movePad(startX + dx, startY + dy)
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!dragging) view.performClick()
                    true
                }

                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    /** Moves both anchors by one shared offset, clamped so neither leaves the parent bounds. */
    private fun movePad(tx: Float, ty: Float) {
        if (width == 0 || height == 0) return
        val minX = max(
            -(width - entrySize - endMargin).toFloat(),
            -(width - panelSize - endMargin).toFloat()
        )
        val minY = max(
            -(height - entrySize - entryBottomMargin).toFloat(),
            -(height - panelSize - panelBottomMargin).toFloat()
        )
        val x = tx.coerceIn(minX, endMargin.toFloat())
        val y = ty.coerceIn(minY, min(entryBottomMargin, panelBottomMargin).toFloat())
        entry.translationX = x
        entry.translationY = y
        panel.translationX = x
        panel.translationY = y
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
