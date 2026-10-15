package com.ShowSeen.lightshadowart.mobile

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Network Connection page (UI PRD §3.4).
 *
 * The single place to read the connection state and to act on it: reconnect, disconnect,
 * configure Wi-Fi and read the diagnostics. Values come from the live session only — an unknown
 * state says so rather than showing a fabricated connection.
 */
class NetworkConnectionActivity : DeviceActivity() {

    private lateinit var topBar: PageTopBarView
    private lateinit var stateDot: View
    private lateinit var stateIcon: ImageView
    private lateinit var tvState: TextView
    private lateinit var tvNetworkName: TextView
    private lateinit var tvAddress: TextView
    private lateinit var tvNote: TextView
    private lateinit var tvDiagnostics: TextView
    private lateinit var ivDiagnostics: ImageView
    private lateinit var btnReconnect: MaterialButton
    private lateinit var btnDisconnect: MaterialButton
    private lateinit var btnBind: MaterialButton

    private var diagnosticsExpanded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_network)

        topBar = findViewById(R.id.network_top_bar)
        stateDot = findViewById(R.id.view_network_dot)
        stateIcon = findViewById(R.id.iv_network_state_icon)
        tvState = findViewById(R.id.tv_network_state)
        tvNetworkName = findViewById(R.id.tv_network_name)
        tvAddress = findViewById(R.id.tv_network_address)
        tvNote = findViewById(R.id.tv_network_note)
        tvDiagnostics = findViewById(R.id.tv_network_diagnostics)
        ivDiagnostics = findViewById(R.id.iv_network_diagnostics)
        btnReconnect = findViewById(R.id.btn_network_reconnect)
        btnDisconnect = findViewById(R.id.btn_network_disconnect)
        btnBind = findViewById(R.id.btn_network_bind)

        topBar.setTitle(getString(R.string.network_title))
        topBar.setOnBackClickListener { finish() }

        btnReconnect.setOnClickListener { if (!connecting) refresh() }
        btnDisconnect.setOnClickListener { confirmDisconnect() }
        btnBind.setOnClickListener { startActivity(Intent(this, BindDeviceActivity::class.java)) }
        findViewById<View>(R.id.row_network_wifi).setOnClickListener { openWifiConfig() }
        findViewById<View>(R.id.row_network_diagnostics).setOnClickListener { toggleDiagnostics() }
    }

    override fun onResume() {
        super.onResume()
        onSessionChanged()
    }

    /** Reconnection is a user action on this page; the heartbeat owns LAN migration. */
    override fun canMigrateToLan(): Boolean = false

    override fun onSessionChanged() {
        val endpoint = SessionStore.endpoint
        val active = endpoint != null && SessionStore.isActive

        when {
            connecting -> {
                stateDot.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary_blue))
                stateIcon.setImageResource(R.drawable.ic_hourglass)
                stateIcon.imageTintList =
                    ContextCompat.getColorStateList(this, R.color.primary_blue)
                tvState.setText(R.string.network_state_connecting)
            }

            active -> {
                stateDot.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_connected))
                stateIcon.setImageResource(R.drawable.ic_check_circle)
                stateIcon.imageTintList =
                    ContextCompat.getColorStateList(this, R.color.status_connected)
                tvState.setText(R.string.network_state_connected)
            }

            else -> {
                stateDot.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.status_destructive))
                stateIcon.setImageResource(R.drawable.ic_warning)
                stateIcon.imageTintList =
                    ContextCompat.getColorStateList(this, R.color.status_destructive)
                tvState.setText(R.string.network_state_disconnected)
            }
        }

        // Network name: the Wi-Fi the device joined, or the device hotspot we are attached to
        val ssid = SessionStore.lastWifiSsid
        tvNetworkName.text = ssid.ifEmpty { getString(R.string.network_value_unknown) }

        // Device address, straight from the live endpoint
        tvAddress.text = endpoint?.let { "${it.host}:${it.port}" }
            ?: getString(R.string.network_value_unknown)

        tvNote.isVisible = true
        tvNote.text = when {
            connecting -> getString(R.string.network_note_connecting)
            active -> getString(
                R.string.state_session_ready,
                getString(endpoint!!.modeNameRes),
                formatRemaining(SessionStore.remainingMs)
            )

            hasReconnectTarget() -> getString(R.string.network_note_can_reconnect)
            else -> getString(R.string.network_note_no_target)
        }

        // Reconnect three states: connected (disabled) / connecting (disabled) / idle
        when {
            active -> {
                btnReconnect.setText(R.string.action_refresh_connected)
                btnReconnect.isEnabled = false
            }

            connecting -> {
                btnReconnect.setText(R.string.action_refresh_connecting)
                btnReconnect.isEnabled = false
            }

            else -> {
                btnReconnect.setText(R.string.action_refresh)
                // No reconnect target at all (fresh install): do not run a pointless discovery
                btnReconnect.isEnabled = hasReconnectTarget()
            }
        }

        // Binding is only offered while there is no session; it is the way back from a reset device
        btnBind.isVisible = !active && !connecting
        btnDisconnect.isEnabled = active || hotspotConnector.isConnected ||
            SessionStore.lastDeviceToken.isNotEmpty()

        renderDiagnostics()
    }

    private fun confirmDisconnect() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.network_disconnect_confirm_title)
            .setMessage(R.string.network_disconnect_confirm_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.network_disconnect) { _, _ -> disconnect() }
            .show()
    }

    private fun toggleDiagnostics() {
        diagnosticsExpanded = !diagnosticsExpanded
        tvDiagnostics.isVisible = diagnosticsExpanded
        ivDiagnostics.rotation = if (diagnosticsExpanded) 90f else 0f
    }

    /** Diagnostics are rendered from the live endpoint; without one only the local picture shows. */
    private fun renderDiagnostics() {
        val endpoint = SessionStore.endpoint
        tvDiagnostics.text = if (endpoint == null) {
            getString(R.string.network_note_no_target)
        } else {
            diagnostics(endpoint)
        }
    }
}
