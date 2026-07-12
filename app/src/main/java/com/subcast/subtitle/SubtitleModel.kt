package com.subcast.subtitle

/** Supported subtitle container formats. */
enum class SubtitleFormat { SRT, VTT, ASS, SSA, UNKNOWN }

/**
 * Visual styling applied when rendering / burning. Field names mirror the ASS
 * Style row so an [AssWriter] can emit them directly.
 */
data class SubtitleStyle(
    val fontName: String? = null,
    val fontSize: Int = 48,
    val primaryColor: String? = null,   // ASS color &HAABBGGRR&; null = writer default
    val outlineColor: String? = null,
    val alignment: Int = 2,             // ASS numpad alignment (2 = bottom center)
    val marginV: Int = 60,
    val bold: Boolean = false,
    val italic: Boolean = false,
)

/** A single timed subtitle entry. [text] may contain ASS override tags. */
data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val styleName: String = "Default",
)

/** Parsed subtitle track, format-agnostic. */
data class SubtitleTrack(
    val cues: List<SubtitleCue>,
    val format: SubtitleFormat,
    val styles: Map<String, SubtitleStyle> = emptyMap(),
    val defaultStyleName: String = "Default",
) {
    val defaultStyle: SubtitleStyle
        get() = styles[defaultStyleName] ?: SubtitleStyle()
}

/** User-facing vertical placement, mapped to the ASS numpad alignment. */
enum class SubtitlePosition(val assAlignment: Int) {
    BOTTOM_CENTER(2),
    BOTTOM_LEFT(1),
    BOTTOM_RIGHT(3),
    CENTER(5),
    TOP_CENTER(8),
}

/** Runtime adjustments applied before burning (sync + style overrides). */
data class SubtitleAdjustments(
    val syncOffsetMs: Long = 0L,
    val fontSizeScale: Float = 1f,
    val position: SubtitlePosition = SubtitlePosition.BOTTOM_CENTER,
    /** ARGB int (e.g. 0xFFFFFF00 = opaque yellow). */
    val colorArgb: Int = 0xFFFFFF00.toInt(),
    val fontName: String? = null,
)
