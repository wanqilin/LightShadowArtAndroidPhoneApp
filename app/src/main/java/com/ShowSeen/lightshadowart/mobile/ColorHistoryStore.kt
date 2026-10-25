package com.ShowSeen.lightshadowart.mobile

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color

/**
 * Recently used colours, so a colour the user picked before is one tap away.
 *
 * Stored as ints in a private preference file; the picker is opened for line colours, mat colours and
 * background colours, and one shared history is what users expect ("the red I used yesterday").
 */
object ColorHistoryStore {

    private const val PREFS = "light_shadow_color_history"
    private const val KEY_COLORS = "colors"

    /** How many colours are remembered */
    const val MAX = 8

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): List<Int> {
        val raw = prefs(context).getString(KEY_COLORS, null) ?: return emptyList()
        return raw.split(',')
            .mapNotNull { it.trim().toLongOrNull() }
            .map { it.toInt() }
            .filter { Color.alpha(it) == 255 }
            .distinct()
            .take(MAX)
    }

    /** Puts [color] in front; the newest colour always wins and the list stays short. */
    fun remember(context: Context, color: Int) {
        val opaque = Color.rgb(Color.red(color), Color.green(color), Color.blue(color))
        val next = (listOf(opaque) + load(context)).distinct().take(MAX)
        prefs(context).edit().putString(KEY_COLORS, next.joinToString(",")).apply()
    }
}
