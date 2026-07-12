package com.subcast.transcode

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics

/**
 * Burns subtitles into a video and re-encodes to a TV-friendly H.264/AAC mp4
 * in a single FFmpeg pass. Two modes share the same pipeline:
 *  - [TranscodeMode.PRE_TRANSCODE]: standard mp4 + faststart, served after completion.
 *  - [TranscodeMode.STREAM]: fragmented mp4 (zerolatency), written to [outputPath]
 *    while the HTTP server serves the growing file (best-effort realtime cast).
 *
 * Callbacks fire on ffmpeg-kit's internal threads; callers switch to the UI
 * thread as needed.
 */
class Transcoder(private val config: TranscodeConfig) {

    @Volatile
    private var session: FFmpegSession? = null

    fun start(
        onProgress: (TranscodeProgress) -> Unit,
        onComplete: (TranscodeResult) -> Unit,
        onLog: ((String) -> Unit)? = null
    ) {
        val command = buildCommand()
        session = FFmpegKit.executeAsync(
            command,
            { s ->
                val rc = s.returnCode
                val ok = ReturnCode.isSuccess(rc)
                onComplete(TranscodeResult(ok, if (ok) null else "ffmpeg rc=$rc"))
            },
            { log -> onLog?.invoke(log.message) },
            { stats: Statistics ->
                val time = stats.time.toLong()
                val pct = config.sourceDurationMs
                    ?.let { d -> if (d > 0) (time.toFloat() / d).coerceIn(0f, 1f) else 0f }
                    ?: 0f
                onProgress(TranscodeProgress(pct, time))
            }
        )
    }

    fun cancel() {
        session?.let { FFmpegKit.cancel(it.sessionId) }
    }

    private val streaming: Boolean get() = config.mode == TranscodeMode.STREAM

    private fun buildCommand(): String {
        val args = mutableListOf("-y", "-i", config.videoInput)

        config.subtitleAssPath?.let { subs ->
            // libass subtitles filter; escapes path for the filtergraph parser.
            args += listOf("-vf", "subtitles=${escapeFilterPath(subs)}")
        }

        args += listOf("-c:v", "libx264", "-preset", if (streaming) "ultrafast" else "veryfast")
        if (streaming) args += listOf("-tune", "zerolatency")
        args += listOf("-c:a", "aac", "-b:a", "192k")

        args += if (streaming) {
            // Fragmented mp4: writeable without knowing total duration up front,
            // so the file is playable while still being produced.
            listOf("-movflags", "+frag_keyframe+empty_moov+default_base_moof+omit_tfhd")
        } else {
            listOf("-movflags", "+faststart")
        }

        args += listOf("-f", "mp4", config.outputPath)
        return args.joinToString(" ") { shellQuote(it) }
    }

    /** Escape a filesystem path for the ffmpeg filtergraph (subtitles=...). */
    private fun escapeFilterPath(path: String): String {
        val escaped = path
            .replace("\\", "\\\\")
            .replace(":", "\\:")
            .replace("'", "\\'")
        return "'$escaped'"
    }

    /** Quote an argument containing whitespace for ffmpeg-kit's command parser. */
    private fun shellQuote(s: String): String =
        if (s.any { it.isWhitespace() }) "\"${s.replace("\"", "\\\"")}\"" else s
}
