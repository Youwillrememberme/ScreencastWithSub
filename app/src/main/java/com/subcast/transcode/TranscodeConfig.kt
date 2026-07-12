package com.subcast.transcode

enum class TranscodeMode { PRE_TRANSCODE, STREAM }

/**
 * @param videoInput resolved ffmpeg input spec — either a real filesystem path
 *   or a SAF read parameter produced by FFmpegKitConfig (lets us read a
 *   content:// Uri without copying large video files to cache).
 * @param subtitleAssPath path to a rendered ASS file, or null to transcode
 *   the video only (no burn-in).
 * @param outputPath where the transcoded mp4 is written (app cache dir).
 * @param sourceDurationMs optional source duration to compute progress percent.
 */
data class TranscodeConfig(
    val mode: TranscodeMode,
    val videoInput: String,
    val subtitleAssPath: String?,
    val outputPath: String,
    val sourceDurationMs: Long? = null,
)

data class TranscodeProgress(val percent: Float, val timeMs: Long)

data class TranscodeResult(val success: Boolean, val message: String?)
