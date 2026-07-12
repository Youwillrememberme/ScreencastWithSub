package com.subcast.transcode

import com.arthenica.ffmpegkit.FFprobeKit

/** Probes source media for duration (used to report transcode progress). */
object MediaProbe {

    fun durationMs(input: String): Long? {
        val session = FFprobeKit.getMediaInformation(input) ?: return null
        val info = session.mediaInformation ?: return null
        val seconds = info.duration?.toDoubleOrNull() ?: return null
        return (seconds * 1000).toLong()
    }
}
