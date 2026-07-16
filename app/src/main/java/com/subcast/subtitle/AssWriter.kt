package com.subcast.subtitle

import java.util.Locale

/**
 * Produces an ASS (`.ass`) document from a parsed [SubtitleTrack] + user
 * [SubtitleAdjustments]. This is the universal intermediate fed to FFmpeg's
 * `subtitles` filter, so that styling (font / size / color / position) and the
 * sync offset are honored on burn-in - regardless of the source format.
 *
 * ASS source tracks keep their inline override tags in cue text; the single
 * synthesized "Default" style carries the (possibly adjusted) base look.
 */
object AssWriter {

    fun render(track: SubtitleTrack, adj: SubtitleAdjustments = SubtitleAdjustments()): String {
        val base = track.defaultStyle
        val fontName = adj.fontName ?: base.fontName ?: "MiSans C"
        val fontSize = (base.fontSize * adj.fontSizeScale).toInt().coerceAtLeast(8)
        val primary = argbToAssColor(adj.colorArgb)
        val outline = base.outlineColor ?: "&H00000000&"
        val alignment = adj.position.assAlignment
        val marginV = base.marginV
        val bold = if (base.bold) -1 else 0
        val italic = if (base.italic) -1 else 0

        val sb = StringBuilder()
        sb.appendLine("[Script Info]")
        sb.appendLine("ScriptType: v4.00+")
        sb.appendLine("PlayResX: 1920")
        sb.appendLine("PlayResY: 1080")
        sb.appendLine("ScaledBorderAndShadow: yes")
        sb.appendLine("WrapStyle: 0")
        sb.appendLine()
        sb.appendLine("[V4+ Styles]")
        sb.appendLine("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding")
        sb.appendLine(
            "Style: Default,$fontName,$fontSize,$primary,&H000000FF,$outline,&H00000000," +
                "$bold,$italic,0,0,100,100,0,0,1,2,1,$alignment,40,40,$marginV,1"
        )
        sb.appendLine()
        sb.appendLine("[Events]")
        sb.appendLine("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text")

        for (cue in track.cues) {
            val start = (cue.startMs + adj.syncOffsetMs).coerceAtLeast(0L)
            val end = (cue.endMs + adj.syncOffsetMs).coerceAtLeast(start + 1)
            // Real newlines -> ASS line break; existing literal \N tags are preserved.
            val text = cue.text.replace("\n", "\\N")
            sb.appendLine("Dialogue: 0,${fmtTime(start)},${fmtTime(end)},Default,,0,0,0,,$text")
        }
        return sb.toString()
    }

    private fun fmtTime(ms: Long): String {
        val totalCs = ms / 10
        val cs = totalCs % 100
        val totalSec = totalCs / 100
        val sec = totalSec % 60
        val totalMin = totalSec / 60
        val min = totalMin % 60
        val h = totalMin / 60
        return String.format(Locale.US, "%d:%02d:%02d.%02d", h, min, sec, cs)
    }

    /** ARGB int -> ASS `&HAABBGGRR&` (ASS alpha: 00 = opaque). */
    fun argbToAssColor(argb: Int): String {
        val a = (argb ushr 24) and 0xFF
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        val assAlpha = 0xFF - a
        return String.format(Locale.US, "&H%02X%02X%02X%02X&", assAlpha, b, g, r)
    }
}
