package com.subcast.transcode

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics

private const val TAG = "SubCast/Transcode"

/**
 * Burns subtitles into a video and re-encodes to a TV-friendly H.264/AAC mp4
 * in a single FFmpeg pass. Two modes share the same pipeline:
 *  - [TranscodeMode.PRE_TRANSCODE]: standard mp4 + faststart, served after completion.
 *  - [TranscodeMode.STREAM]: MPEG-TS (zerolatency), written to [outputPath] while the
 *    HTTP server serves the growing file (best-effort realtime cast). TS is the DLNA
 *    live-stream container -- every TV renderer accepts it, unlike fragmented mp4.
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
        // Surface the exact command + every ffmpeg log line to logcat so a non-zero
        // rc (the UI only shows "ffmpeg rc=N") can be diagnosed from the stderr.
        Log.d(TAG, "cmd: $command")
        session = FFmpegKit.executeAsync(
            command,
            { s ->
                val rc = s.returnCode
                val ok = ReturnCode.isSuccess(rc)
                Log.i(TAG, "rc=$rc ok=$ok")
                onComplete(TranscodeResult(ok, if (ok) null else "ffmpeg rc=$rc"))
            },
            { log ->
                Log.d(TAG, log.message)
                onLog?.invoke(log.message)
            },
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
            // fontsdir points fontconfig at Android's system fonts (Roboto + Noto
            // CJK) -- without it libass finds NO fonts on the device and renders
            // nothing ("fontselect: failed to find any fallback with glyph 0x0").
            args += listOf("-vf", "subtitles=${escapeFilterPath(subs)}:fontsdir=/system/fonts")
        }

        args += listOf("-c:v", "libx264", "-preset", if (streaming) "ultrafast" else "veryfast")
        if (streaming) args += listOf("-tune", "zerolatency")
        args += listOf("-c:a", "aac", "-b:a", "192k")

        if (streaming) {
            // MPEG-TS: the DLNA live-stream container. Streamable by design (no
            // moov/sample-table to write up front), so the file is playable while
            // still being produced -- AND every TV DLNA renderer understands it.
            // The previous fragmented-mp4 (empty_moov) was rejected by the renderer
            // ("playback failed"): many TV demuxers can't parse fMP4. libx264 emits
            // annex-B natively, so no h264_mp4toannexb bitstream filter is needed.
            args += listOf("-f", "mpegts")
        } else {
            args += listOf("-movflags", "+faststart", "-f", "mp4")
        }
        args += config.outputPath
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
