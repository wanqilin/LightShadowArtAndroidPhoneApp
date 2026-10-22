package com.ShowSeen.lightshadowart.mobile

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Process-wide foreground tracking (PRD 16.5).
 *
 * Counts resumed activities instead of using one Activity's lifecycle: the Dashboard stays resumed
 * behind the Network Connection page, so a per-activity flag would call that "background" and drop
 * the session while the user is looking at the app.
 *
 * A screen the app starts itself (system photo picker, permission dialog, the Wi-Fi panel) is not
 * "the user left": those hold a keep-alive, so the background grace only starts for Home, recents
 * or the lock screen.
 */
object AppVisibility : Application.ActivityLifecycleCallbacks {

    private var resumed = 0
    private var keepAlive = 0
    private var lastForeground: Boolean? = null

    /** Called when the process crosses between foreground and background */
    var onChange: ((Boolean) -> Unit)? = null

    val isForeground: Boolean get() = resumed > 0 || keepAlive > 0

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(this)
    }

    /** Hold while an app-initiated external screen is open (photo picker, system panel, upload) */
    fun acquire() {
        keepAlive++
        notifyIfChanged()
    }

    fun release() {
        if (keepAlive > 0) keepAlive--
        notifyIfChanged()
    }

    override fun onActivityResumed(activity: Activity) {
        resumed++
        notifyIfChanged()
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed > 0) resumed--
        notifyIfChanged()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    private fun notifyIfChanged() {
        val foreground = isForeground
        if (foreground == lastForeground) return
        lastForeground = foreground
        onChange?.invoke(foreground)
    }
}
