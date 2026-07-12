package com.subcast.subtitle

/**
 * High-level subtitle handling: bytes → encoding detection → parse.
 *
 * File I/O (Uri → bytes) is supplied by [SubtitleFileReader] so the parsing
 * core stays free of Android dependencies and easy to reason about.
 */
object SubtitleService {

    /** Decode + parse a subtitle file given its raw bytes and name. */
    fun load(bytes: ByteArray, fileName: String): SubtitleTrack {
        val charset = EncodingDetector.detect(bytes)
        val text = String(bytes, charset)
        val format = SubtitleParser.formatForExtension(fileName).let {
            if (it == SubtitleFormat.UNKNOWN) sniffFormat(text) else it
        }
        return SubtitleParser.parse(text, format)
    }

    /** Render a track + adjustments to an ASS document ready for burn-in. */
    fun renderAss(track: SubtitleTrack, adj: SubtitleAdjustments = SubtitleAdjustments()): String =
        AssWriter.render(track, adj)

    /**
     * Merges two subtitle tracks into a single bilingual track: time is split at every
     * cue boundary of either track, and each segment carries the active primary + active
     * secondary text joined with "\N" (an ASS hard line break). Contiguous segments with
     * identical text are coalesced. Result renders as two stacked lines via [AssWriter].
     */
    fun mergeBilingual(primary: SubtitleTrack, secondary: SubtitleTrack): SubtitleTrack {
        val bounds = sortedSetOf<Long>().apply {
            add(0L)
            primary.cues.forEach { add(it.startMs.coerceAtLeast(0L)); add(it.endMs.coerceAtLeast(0L)) }
            secondary.cues.forEach { add(it.startMs.coerceAtLeast(0L)); add(it.endMs.coerceAtLeast(0L)) }
        }.toList()

        val raw = mutableListOf<SubtitleCue>()
        for (i in 0 until bounds.size - 1) {
            val t0 = bounds[i]
            val t1 = bounds[i + 1]
            if (t0 >= t1) continue
            val p = primary.cues.firstOrNull { it.startMs <= t0 && t0 < it.endMs }?.text
            val s = secondary.cues.firstOrNull { it.startMs <= t0 && t0 < it.endMs }?.text
            val parts = listOf(p, s).filterNotNull().filter { it.isNotBlank() }
            if (parts.isEmpty()) continue
            raw += SubtitleCue(t0, t1, parts.joinToString("\\N"))
        }

        // Coalesce contiguous segments with identical text.
        val coalesced = mutableListOf<SubtitleCue>()
        for (c in raw) {
            val last = coalesced.lastOrNull()
            if (last != null && last.text == c.text && last.endMs == c.startMs) {
                coalesced[coalesced.lastIndex] = last.copy(endMs = c.endMs)
            } else {
                coalesced += c
            }
        }
        return SubtitleTrack(coalesced, SubtitleFormat.ASS)
    }

    private fun sniffFormat(text: String): SubtitleFormat {
        val head = text.take(64).trim()
        return when {
            head.startsWith("WEBVTT", ignoreCase = true) -> SubtitleFormat.VTT
            head.startsWith("[Script Info", ignoreCase = true) -> SubtitleFormat.ASS
            else -> SubtitleFormat.SRT
        }
    }
}
