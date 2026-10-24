package com.ShowSeen.lightshadowart.mobile

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import com.ShowSeen.lightshadowart.mobile.net.ConnectionManager

/**
 * Application entry point: loads the persisted session and forwards process foreground changes to
 * the connection state machine (PRD NW-5).
 *
 * The connection is owned by [ConnectionManager], not by a screen, so it must outlive the Activity
 * that opened it and must know when the whole app went to the background.
 */
class LightShadowApp : Application() {

    /**
     * Network availability is a recovery trigger, not just a status: when the phone rejoins Wi-Fi the
     * frame may be reachable again, and without this callback nothing would look for it (PRD 16.2 NW-15).
     */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            ConnectionManager.get(this@LightShadowApp).onNetworkAvailable()
        }
    }

    override fun onCreate() {
        super.onCreate()
        SessionStore.init(this)
        AppVisibility.install(this)
        AppVisibility.onChange = { foreground ->
            val manager = ConnectionManager.get(this)
            if (foreground) manager.onForeground() else manager.onBackground()
        }
        registerNetworkCallback()
    }

    private fun registerNetworkCallback() {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        // A device without ACCESS_NETWORK_STATE would throw; the app declares it, so this only guards
        // against a platform that refuses the callback
        runCatching { manager.registerDefaultNetworkCallback(networkCallback) }
    }
}
