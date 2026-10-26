package com.ShowSeen.lightshadowart.mobile.net

/**
 * Frame protocol DTOs (PRD 6.9), mirroring the device-side `network/FrameParams.kt`.
 *
 * Parameters follow the style: the device tells us which parameters the selected style reads, and the
 * editor builds its rows from that — no more five global knobs that only one style honours.
 */

/** One tunable parameter of a style */
data class FrameParamInfo(
    val id: String,
    val kind: String,
    val minDp: Float = 0f,
    val maxDp: Float = 0f,
    val defaultDp: Float = 0f,
    val defaultColor: Int = 0,
    /** Advanced parameters sit behind the collapsed group */
    val advanced: Boolean = false,
    /** Display unit of a length parameter: `dp`, `%` or `°` (empty for colours) */
    val unit: String = "dp"
) {
    val isLength: Boolean get() = kind == KIND_LENGTH

    /** Default value in the shape the editor stores (Float for lengths, Int for colours) */
    val defaultValue: Any get() = if (isLength) defaultDp else defaultColor

    companion object {
        const val KIND_LENGTH = "length"
        const val KIND_COLOR = "color"
    }
}

/** A device-side style plus the parameters it reads */
data class FrameStyleInfo(
    val id: String,
    val name: String,
    val params: List<FrameParamInfo> = emptyList()
)

/** Background: one mode per image, plus the colour the solid mode uses */
data class FrameBackground(
    val mode: Int = MODE_SOLID,
    val color: Int = DEFAULT_COLOR,
    /** Whether the device already stores the shared user image (read-only) */
    val hasImage: Boolean = false
) {
    companion object {
        const val MODE_BLUR = 0
        const val MODE_SOLID = 1
        const val MODE_IMAGE = 2

        /** Matches the device-side `DeviceConfig.FRAME_PAPER_COLOR` */
        const val DEFAULT_COLOR: Int = 0xFF000000.toInt()
    }
}

/** Panel metrics: the live preview must use the real aspect ratio (3:2 on MT9681) */
data class PanelInfo(
    val widthPx: Int = 1920,
    val heightPx: Int = 1280,
    val density: Float = 2f
) {
    val aspect: Float get() = if (widthPx > 0) heightPx.toFloat() / widthPx else 0.75f
}

/**
 * Resolved frame state of one scope.
 *
 * [values] holds `Float` (dp) for length parameters and `Int` (0xAARRGGBB) for colour parameters.
 */
data class FrameState(
    val styleId: String = "",
    val values: Map<String, Any> = emptyMap(),
    val background: FrameBackground = FrameBackground(),
    val frameEnabled: Boolean = false,
    /** True when the image's own override is in play (the editor shows "this picture" instead of "default") */
    val fromImage: Boolean = false
)

/** `GET/POST /frame` reply: resolved state + every style with its parameters + panel metrics */
data class FrameConfig(
    val state: FrameState,
    val styles: List<FrameStyleInfo> = emptyList(),
    val panel: PanelInfo = PanelInfo()
) {
    /** The style the state refers to (falls back to the first style the device offers) */
    fun style(): FrameStyleInfo? =
        styles.firstOrNull { it.id == state.styleId } ?: styles.firstOrNull()
}
