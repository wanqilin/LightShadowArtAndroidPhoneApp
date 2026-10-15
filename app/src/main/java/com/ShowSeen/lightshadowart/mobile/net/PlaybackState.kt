package com.ShowSeen.lightshadowart.mobile.net

/**
 * Device playback state read from `GET /api/v1/playback` (mirrors the device-side data class).
 *
 * The phone never derives a state locally: it renders whatever the device reports, and a missing
 * answer (offline / session gone) is shown as "unknown" rather than assumed.
 */
data class PlaybackState(
    /** True while the device is showing a photo or video full screen. */
    val playbackActive: Boolean = false,
    /** True while the device is advancing through the playlist on its own. */
    val autoLoop: Boolean = false,
    /** Device aspect mode; photos and videos always play in AUTO (3). */
    val screenMode: Int = DEFAULT_SCREEN_MODE_AUTO,
    /** Device browse view: 0 = by date, 1 = immersive, 2 = favourite. */
    val browserMode: Int = DEFAULT_BROWSER_MODE_IMMERSIVE,
    /** Path of the media currently on screen; empty when nothing has played yet. */
    val lastFile: String = ""
) {
    /** The three states the Space page can show, derived from the two device-side booleans. */
    val mode: PlaybackMode
        get() = when {
            !playbackActive -> PlaybackMode.BROWSE
            autoLoop -> PlaybackMode.AUTO
            else -> PlaybackMode.STATIC
        }

    companion object {
        /** Matches device-side `Const.SCREEN_MODE_AUTO`. */
        const val DEFAULT_SCREEN_MODE_AUTO = 3

        /** Matches device-side `PlayStateStore.BROWSER_MODE_IMMERSIVE`. */
        const val DEFAULT_BROWSER_MODE_IMMERSIVE = 1
    }
}

/** Browse mode / static playback (paused) / auto play. */
enum class PlaybackMode { BROWSE, STATIC, AUTO }
