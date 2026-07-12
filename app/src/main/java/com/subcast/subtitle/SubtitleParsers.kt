package com.subcast.subtitle

import java.util.Locale

/**
 * Dispatches parsing by format and hosts the concrete SRT / VTT / ASS parsers.
 *
 * Parsers are deliberately tolerant (skip malformed blocks) rather than failing
 * the whole file on one bad cue, since real-world subtitle files are messy.
 */
object SubtitleParser {

    fun parse(text: String, format: SubtitleFormat): SubtitleTrack = when (format) {
        SubtitleFormat.SRT -> SrtParser.parse(text)
        SubtitleFormat.VTT -> VttParser.parse(text)
        SubtitleFormat.ASS, SubtitleFormat.SSA -> AssParser.parse(text)
        SubtitleFormat.UNKNOWN -> error("Unknown subtitle format")
    }

    fun formatForExtension(name: String): SubtitleFormat {
        val lower = name.lowercase(Locale.US)
        return when {
            lower.endsWith(".srt") -> SubtitleFormat.SRT
            lower.endsWith(".vtt") -> SubtitleFormat.VTT
            lower.endsWith(".ass") -> SubtitleFormat.ASS
            lower.endsWith(".ssa") -> SubtitleFormat.SSA
            else -> SubtitleFormat.UNKNOWN
        }
    }
}

object SrtParser {
    private val TIME = Regex("""(\d{1,2}:\d{2}:\d{2},\d{3})\s*-->\s*(\d{1,2}:\d{2}:\d{2},\d{3})""")

    fun parse(text: String): SubtitleTrack {
        val cues = mutableListOf<SubtitleCue>()
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val blocks = normalized.split(Regex("\n[ \t]*\n"))
        for (block in blocks) {
            val lines = block.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            var idx = 0
            if (lines[0].toIntOrNull() != null) idx = 1   // optional sequence number
            if (idx >= lines.size) continue
            val match = TIME.find(lines[idx]) ?: continue
            val start = parseTime(match.groupValues[1])
            val end = parseTime(match.groupValues[2])
            val body = lines.drop(idx + 1).joinToString("\n")
            if (body.isBlank()) continue
            cues += SubtitleCue(start, end, body)
        }
        return SubtitleTrack(cues, SubtitleFormat.SRT)
    }

    private fun parseTime(s: String): Long {
        val ms = s.substringAfter(',').toLong()
        val parts = s.substringBefore(',').split(':')
        val h = parts[0].toLong()
        val m = parts[1].toLong()
        val sec = parts[2].toLong()
        return ((h * 60 + m) * 60 + sec) * 1000 + ms
    }
}

object VttParser {
    private val TIME = Regex("""(\d{1,2}:\d{2}:\d{2}\.\d{3}|\d{1,2}:\d{2}\.\d{3}|\d{1,2}\.\d{3})\s*-->\s*(\S+)""")

    fun parse(text: String): SubtitleTrack {
        val cues = mutableListOf<SubtitleCue>()
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val blocks = normalized.split(Regex("\n[ \t]*\n"))
        for (block in blocks) {
            val lines = block.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            if (lines[0].startsWith("WEBVTT", ignoreCase = true) || lines[0].startsWith("NOTE", ignoreCase = true)) continue
            var idx = 0
            if (!lines[0].contains("-->")) idx = 1   // optional cue identifier
            if (idx >= lines.size) continue
            val match = TIME.find(lines[idx]) ?: continue
            val start = parseTime(match.groupValues[1])
            val end = parseTime(match.groupValues[2])
            val body = lines.drop(idx + 1).joinToString("\n")
            if (body.isBlank()) continue
            cues += SubtitleCue(start, end, body)
        }
        return SubtitleTrack(cues, SubtitleFormat.VTT)
    }

    private fun parseTime(s: String): Long {
        val parts = s.split(':', '.')
        return when (parts.size) {
            4 -> ((parts[0].toLong() * 60 + parts[1].toLong()) * 60 + parts[2].toLong()) * 1000 + parts[3].toLong()
            3 -> (parts[0].toLong() * 60 + parts[1].toLong()) * 60 * 1000 + parts[2].toLong()
            2 -> parts[0].toLong() * 1000 + parts[1].toLong()
            else -> 0L
        }
    }
}

object AssParser {

    private val DEFAULT_STYLE_FORMAT = listOf(
        "Name", "Fontname", "Fontsize", "PrimaryColour", "SecondaryColour", "OutlineColour", "BackColour",
        "Bold", "Italic", "Underline", "StrikeOut", "ScaleX", "ScaleY", "Spacing", "Angle",
        "BorderStyle", "Outline", "Shadow", "Alignment", "MarginL", "MarginR", "MarginV", "Encoding"
    )
    private val DEFAULT_EVENT_FORMAT = listOf(
        "Layer", "Start", "End", "Style", "Name", "MarginL", "MarginR", "MarginV", "Effect", "Text"
    )
    private val TIME = Regex("""(\d+):(\d+):(\d+)\.(\d+)""")

    fun parse(text: String): SubtitleTrack {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')

        var section = ""
        var styleFormat = DEFAULT_STYLE_FORMAT
        var eventFormat = DEFAULT_EVENT_FORMAT
        val styles = mutableMapOf<String, SubtitleStyle>()
        val cues = mutableListOf<SubtitleCue>()

        for (raw in lines) {
            val line = raw.trim()
            if (line.startsWith('[') && line.endsWith(']')) {
                section = line.substring(1, line.length - 1).trim()
                continue
            }
            if (line.isEmpty()) continue
            when (section.lowercase(Locale.US)) {
                "v4+ styles", "v4 styles" -> {
                    when {
                        line.startsWith("Format:", ignoreCase = true) ->
                            styleFormat = line.substringAfter(":").split(',').map { it.trim() }
                        line.startsWith("Style:", ignoreCase = true) -> {
                            val map = styleFormat.zip(splitFields(line.substringAfter(":"), styleFormat.size)).toMap()
                            styles[map["Name"] ?: "Default"] = SubtitleStyle(
                                fontName = map["Fontname"],
                                fontSize = map["Fontsize"]?.toIntOrNull() ?: 48,
                                primaryColor = map["PrimaryColour"],
                                outlineColor = map["OutlineColour"],
                                alignment = map["Alignment"]?.toIntOrNull() ?: 2,
                                marginV = map["MarginV"]?.toIntOrNull() ?: 60,
                                bold = map["Bold"]?.let { it == "-1" || it == "1" } ?: false,
                                italic = map["Italic"]?.let { it == "-1" || it == "1" } ?: false,
                            )
                        }
                    }
                }
                "events" -> {
                    when {
                        line.startsWith("Format:", ignoreCase = true) ->
                            eventFormat = line.substringAfter(":").split(',').map { it.trim() }
                        line.startsWith("Dialogue:", ignoreCase = true) -> {
                            val map = eventFormat.zip(splitFields(line.substringAfter(":"), eventFormat.size)).toMap()
                            val start = parseTime(map["Start"] ?: "0:00:00.00")
                            val end = parseTime(map["End"] ?: "0:00:00.00")
                            val body = map["Text"] ?: ""
                            if (body.isNotBlank() && end > start) {
                                cues += SubtitleCue(start, end, body, map["Style"] ?: "Default")
                            }
                        }
                    }
                }
            }
        }
        val defaultName = when {
            styles.containsKey("Default") -> "Default"
            styles.isNotEmpty() -> styles.keys.first()
            else -> "Default"
        }
        return SubtitleTrack(cues, SubtitleFormat.ASS, styles, defaultName)
    }

    /**
     * Split ASS fields honoring the format's field count: everything past the
     * last named field (the Text field, which may legitimately contain commas)
     * is rejoined and kept whole.
     */
    private fun splitFields(payload: String, fieldCount: Int): List<String> {
        val parts = payload.split(',')
        if (parts.size <= fieldCount) return parts.map { it.trim() }
        val head = parts.subList(0, fieldCount - 1).map { it.trim() }
        val tail = parts.subList(fieldCount - 1, parts.size).joinToString(",")
        return head + tail
    }

    private fun parseTime(s: String): Long {
        val m = TIME.find(s) ?: return 0L
        val h = m.groupValues[1].toLong()
        val min = m.groupValues[2].toLong()
        val sec = m.groupValues[3].toLong()
        val cs = m.groupValues[4].padEnd(2, '0').substring(0, 2).toLong()
        return ((h * 60 + min) * 60 + sec) * 1000 + cs * 10
    }
}
