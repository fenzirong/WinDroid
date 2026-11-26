package com.windroid.app.core.model

import kotlinx.serialization.Serializable

/**
 * Resolution advertised to the Wine/X11 side of a container.
 *
 * Immutable and directly serializable so a container's display settings persist
 * alongside the rest of its [ContainerConfig].
 *
 * @property width horizontal resolution in pixels
 * @property height vertical resolution in pixels
 * @property dpi logical density reported to X11 / Wine
 * @property refreshRate target refresh rate in Hz
 */
@Serializable
data class DisplaySpec(
    val width: Int = 1280,
    val height: Int = 720,
    val dpi: Int = 96,
    val refreshRate: Int = 60,
) {
    /** Human readable "1280x720" label used by settings screens. */
    val resolutionLabel: String
        get() = "${width}x$height"

    companion object {
        val PRESET_SVGA: DisplaySpec = DisplaySpec(width = 800, height = 600)
        val PRESET_XGA: DisplaySpec = DisplaySpec(width = 1024, height = 768)
        val PRESET_720P: DisplaySpec = DisplaySpec(width = 1280, height = 720)
        val PRESET_900P: DisplaySpec = DisplaySpec(width = 1600, height = 900)
        val PRESET_1080P: DisplaySpec = DisplaySpec(width = 1920, height = 1080)

        /** Presets in ascending resolution order, for pickers. */
        val ALL_PRESETS: List<DisplaySpec> = listOf(
            PRESET_SVGA,
            PRESET_XGA,
            PRESET_720P,
            PRESET_900P,
            PRESET_1080P,
        )
    }
}
