package com.ShowSeen.lightshadowart.mobile.scan

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import com.journeyapps.barcodescanner.BarcodeView

/**
 * Scanner surface for the full-screen page (UI PRD §3.2 B).
 *
 * [BarcodeView] decodes only inside its framing rectangle, and the default one is a centered square
 * that is shorter than the preview. Returning the whole preview area makes the recognisable region
 * cover the screen, which is what the spec asks for; the page draws no framing box either.
 */
class ScanBarcodeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BarcodeView(context, attrs, defStyleAttr) {

    override fun calculateFramingRect(container: Rect, surface: Rect): Rect =
        Rect(container).also { full ->
            // When the preview surface does not overlap the container yet, keep the container.
            if (!full.intersect(surface)) return Rect(container)
        }
}
