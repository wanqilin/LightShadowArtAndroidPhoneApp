package com.ShowSeen.lightshadowart.mobile.net

/**
 * Frame parameters (requirement 3.3.1 / T-13, geometry extended per F-6), mirroring the device-side
 * `network/FrameParams.kt` one-to-one.
 *
 * The device-side `GET /frame` response is authoritative; the defaults here are only a parsing fallback
 * and must match the device-side `DeviceConfig.FRAME_*` (Constitution §9.5: same default values on both ends).
 */
data class FrameParams(
    /** Current frame style id; an empty string means the device-side default style */
    val styleId: String = "",
    /**
     * Frame master switch (F-6, third round): off = the device draws no frame and black-bar gaussian blur returns outside the photo.
     * The device uses a dedicated field rather than `style_id = "none"` because "off" also requires the background to fall back.
     */
    val frameEnabled: Boolean = DEFAULT_FRAME_ENABLED,
    /** Frame line color, 0xAARRGGBB */
    val lineColor: Int = DEFAULT_LINE_COLOR,
    /** Width of a single line (dp) */
    val lineWidthDp: Float = DEFAULT_LINE_WIDTH_DP,
    /** Background solid color, 0xAARRGGBB (base color filling the screen outside the photo) */
    val paperColor: Int = DEFAULT_PAPER_COLOR,
    /** Background solid color switch: off = this layer falls back to black-bar gaussian blur on the device */
    val paperEnabled: Boolean = DEFAULT_PAPER_ENABLED,
    /** Whether the background uses a user-defined image (F-6, third round): image takes precedence over solid color */
    val backgroundImage: Boolean = DEFAULT_BACKGROUND_IMAGE,
    /** Whether the device already stores a user-defined background image (read-only; used to render and preview the background color row) */
    val hasBackground: Boolean = false,
    /**
     * Distance between the inner border and the photo (dp), F-6 ①.
     * `null` = the field is not sent this time and the device keeps its current value (`POST /frame` supports partial updates);
     * a value parsed from `GET /frame` is always non-null.
     */
    val paddingDp: Float? = null,
    /**
     * Gap between the inner and outer border (dp), F-6 ⑤; same semantics as [paddingDp].
     * It merges the device's former `line_gap_dp` (double-frame gap, the same quantity); the old field is gone from the protocol.
     */
    val borderGapDp: Float? = null,
    /** Distance from the outer border to the screen edge (dp), F-6 ②; same semantics as [paddingDp] */
    val insetDp: Float? = null
) {
    companion object {
        /** Matches the device-side `DeviceConfig.FRAME_ENABLED` (off by default at compile time) */
        const val DEFAULT_FRAME_ENABLED: Boolean = false
        /** Matches the device-side `DeviceConfig.FRAME_LINE_COLOR` (red) */
        const val DEFAULT_LINE_COLOR: Int = 0xFFD32F2F.toInt()
        /** Matches the device-side `DeviceConfig.FRAME_LINE_WIDTH_DP` */
        const val DEFAULT_LINE_WIDTH_DP: Float = 2f
        /** Matches the device-side `DeviceConfig.FRAME_PAPER_COLOR` (pure black, since 2026-10-15) */
        const val DEFAULT_PAPER_COLOR: Int = 0xFF000000.toInt()
        /** Matches the device-side `DeviceConfig.FRAME_PAPER_ENABLED` */
        const val DEFAULT_PAPER_ENABLED: Boolean = true
        /** Matches the device-side `DeviceConfig.FRAME_BACKGROUND_IMAGE` */
        const val DEFAULT_BACKGROUND_IMAGE: Boolean = false
        /** Matches the device-side `DeviceConfig.FRAME_PADDING_DP` (F-6 ①) */
        const val DEFAULT_PADDING_DP: Float = 24f
        /** Matches the device-side `DeviceConfig.FRAME_BORDER_GAP_DP` (F-6 ⑤) */
        const val DEFAULT_BORDER_GAP_DP: Float = 8f
        /** Matches the device-side `DeviceConfig.FRAME_INSET_DP` (F-6 ②) */
        const val DEFAULT_INSET_DP: Float = 24f
    }
}

/**
 * **Partial update** payload for `POST /frame`: a `null` field is not sent this time and the device keeps its current value.
 *
 * Why a separate class instead of reusing [FrameParams]: `FrameParams` is "a complete device-side state"
 * with every field set, while a patch must carry **only the fields the user really changed** — the previous
 * "style" page sent `FrameParams(styleId = "custom")`, overwriting the untouched line color / line width /
 * background paper on the device with defaults (a real defect). Distinguishing "complete state" from
 * "incremental patch" in the type system makes this class of mistake unwritable.
 */
data class FramePatch(
    val styleId: String? = null,
    val frameEnabled: Boolean? = null,
    val lineColor: Int? = null,
    val lineWidthDp: Float? = null,
    val paperColor: Int? = null,
    val paperEnabled: Boolean? = null,
    val backgroundImage: Boolean? = null,
    val paddingDp: Float? = null,
    val borderGapDp: Float? = null,
    val insetDp: Float? = null
)

/**
 * Frame style entry (id + display name).
 *
 * Sent by the device; the mobile side only displays it — the style set is a device-side fact, and
 * hard-coded style names on the client go stale as soon as the device adds a style.
 */
data class FrameStyleInfo(
    val id: String,
    val name: String
)

/** `GET /frame` and `POST /frame` response: current parameters + optional device-side style list */
data class FrameConfig(
    val params: FrameParams,
    val styles: List<FrameStyleInfo>
)
