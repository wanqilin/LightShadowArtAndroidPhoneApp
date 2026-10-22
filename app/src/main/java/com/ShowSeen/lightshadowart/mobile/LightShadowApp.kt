package com.ShowSeen.lightshadowart.mobile

import android.app.Application
import com.ShowSeen.lightshadowart.mobile.net.ConnectionManager

/**
 * Application entry point: loads the persisted session and forwards process foreground changes to
 * the connection state machine (PRD NW-5).
 *
 * The connection is owned by [ConnectionManager], not by a screen, so it must outlive the Activity
 * that opened it and must know when the whole app went to the background.
 */
class LightShadowApp : Application() {

    override fun onCreate() {
        super.onCreate()
        SessionStore.init(this)
        AppVisibility.install(this)
        AppVisibility.onChange = { foreground ->
            val manager = ConnectionManager.get(this)
            if (foreground) manager.onForeground() else manager.onBackground()
        }
    }
}
