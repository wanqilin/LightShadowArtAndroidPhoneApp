package com.ShowSeen.lightshadowart.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.ShowSeen.lightshadowart.mobile.net.QrPayloadParser
import com.ShowSeen.lightshadowart.mobile.scan.ScanActivity

/**
 * Bind Your Device (UI PRD §3.2): the second guide step, where the user is pointed at the QR code
 * on the frame and then opens the real scanner.
 *
 * Scanning itself is the existing zxing flow — this page only owns the camera permission states
 * (first request, denied, permanently denied) and the hand-off of a valid payload to the Dashboard.
 */
class BindDeviceActivity : AppCompatActivity() {

    /** The payload of the last successful scan, or null while the frame has not been paired yet. */
    private var scannedPayload: String? = null

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents.isNullOrBlank()) {
            // Cancelled: stay on the guide, no error — the user may simply have changed their mind
            Log.i(TAG, "scan cancelled")
            return@registerForActivityResult
        }
        QrPayloadParser.parse(contents)
            .onSuccess { endpoint ->
                // Pairing done: unlock Continue instead of jumping straight to the Dashboard, so the
                // page keeps the reference flow (scan, then continue) and the step reads as finished
                scannedPayload = contents
                findViewById<MaterialButton>(R.id.btn_bind_continue).isEnabled = true
                Log.i(TAG, "scan ok: ${endpoint.baseUrl}")
            }
            .onFailure {
                Log.w(TAG, "scan payload rejected: $contents")
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.dialog_error_title)
                    .setMessage(R.string.scan_invalid_qr)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            openScanner()
        } else {
            showCameraPermissionDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bind_device)

        findViewById<PageTopBarView>(R.id.bind_top_bar).apply {
            setTitle(getString(R.string.scan_bind_title))
            setOnBackClickListener { finish() }
        }

        findViewById<MaterialButton>(R.id.btn_bind_scan).setOnClickListener {
            if (hasCameraPermission()) {
                openScanner()
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        // Continue is gated on a successful scan: the frame has to be paired before the Dashboard can
        // show it, so the button stays disabled until the scan above has handed over a payload.
        findViewById<MaterialButton>(R.id.btn_bind_continue).apply {
            isEnabled = false
            setOnClickListener {
                val payload = scannedPayload ?: return@setOnClickListener
                startActivity(
                    Intent(this@BindDeviceActivity, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_SCANNED_ENDPOINT, payload)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun openScanner() {
        scanLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setCaptureActivity(ScanActivity::class.java)
                .setPrompt(getString(R.string.scan_prompt))
                .setBeepEnabled(false)
                .setOrientationLocked(true)
        )
    }

    /**
     * Denied — including "never ask again", which cannot be told apart reliably on every vendor
     * build, so the dialog always offers the way out through the system settings.
     */
    private fun showCameraPermissionDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.scan_permission_title)
            .setMessage(R.string.scan_permission_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.scan_open_settings) { _, _ -> openAppSettings() }
            .show()
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            )
        )
    }

    private companion object {
        const val TAG = "BindDeviceActivity"
    }
}
