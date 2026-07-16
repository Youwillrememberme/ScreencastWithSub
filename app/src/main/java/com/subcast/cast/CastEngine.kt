package com.subcast.cast

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.subcast.data.ResumeDao
import com.subcast.data.ResumeRecord
import com.subcast.dlna.DlnaController
import com.subcast.dlna.RendererDevice
import com.subcast.media.MediaServer
import com.subcast.media.NetworkUtil
import com.subcast.subtitle.SubtitleAdjustments
import com.subcast.subtitle.SubtitleFileReader
import com.subcast.subtitle.SubtitleService
import com.subcast.transcode.MediaProbe
import com.subcast.transcode.TranscodeConfig
import com.subcast.transcode.TranscodeMode
import com.subcast.transcode.TranscodeProgress
import com.subcast.transcode.TranscodeResult
import com.subcast.transcode.Transcoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

private const val TAG = "SubCast/Cast"
private const val MIME_MP4 = "video/mp4"
private const val MIME_TS = "video/MP2T"

/**
 * Orchestrates the full cast pipeline on a caller-provided [CoroutineScope]
 * (typically viewModelScope):
 *
 *  video + subtitle → render ASS → FFmpeg transcode + burn-in → local HTTP URL
 *  → DLNA SetAVTransportURI + Play → poll position/state → persist resume.
 *
 * Two transcode modes (user-selectable): [TranscodeMode.PRE_TRANSCODE] waits for
 * the whole file before pushing; [TranscodeMode.STREAM] pushes the growing
 * fragmented mp4 as soon as the first bytes exist.
 */
class CastEngine(
    private val context: Context,
    private val dao: ResumeDao,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(CastUiState())
    val state: StateFlow<CastUiState> = _state

    private val mediaServer = MediaServer()
    private val dlna = DlnaController(context)

    private var activeTranscoder: Transcoder? = null
    private var pollJob: Job? = null

    init {
        runCatching { mediaServer.start(30_000) }
            .onSuccess { Log.i(TAG, "MediaServer started, listeningPort=${mediaServer.listeningPort}") }
            .onFailure { Log.e(TAG, "MediaServer start FAILED", it) }
    }

    // ---------------- lifecycle ----------------

    fun startServices() {
        dlna.bind()
        scope.launch {
            dlna.devices.collect { list -> update { it.copy(devices = list) } }
        }
    }

    fun destroy() {
        pollJob?.cancel()
        activeTranscoder?.cancel()
        runCatching { dlna.unbind() }
        runCatching { mediaServer.stop() }
    }

    // ---------------- selections ----------------

    fun setVideo(uri: Uri, name: String) {
        scope.launch {
            // MediaMetadataRetriever reads content:// directly and seeks natively,
            // so it gets the duration even for mp4s whose moov atom is at the tail
            // (where FFprobeKit over FFmpegKit's non-seekable saf:// stream can't).
            val dur = runCatching {
                val mmr = MediaMetadataRetriever()
                try {
                    mmr.setDataSource(context, uri)
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                } finally {
                    mmr.release()
                }
            }.getOrNull()
            val resume = dao.get(uri.toString())
            update { it.copy(video = SelectedVideo(uri, name, dur), pendingResumeMs = resume?.positionMs) }
        }
    }

    fun setSubtitle(uri: Uri, name: String) {
        update { it.copy(subtitle = SelectedSubtitle(uri, name)) }
    }

    fun setSecondarySubtitle(uri: Uri, name: String) {
        update { it.copy(secondarySubtitle = SelectedSubtitle(uri, name)) }
    }

    fun clearSubtitle() = update { it.copy(subtitle = null) }
    fun clearSecondarySubtitle() = update { it.copy(secondarySubtitle = null) }

    fun setMode(mode: TranscodeMode) = update { it.copy(mode = mode) }
    fun selectDevice(device: RendererDevice) = update { it.copy(selectedDevice = device) }
    fun setAdjustments(adj: SubtitleAdjustments) = update { it.copy(adjustments = adj) }
    fun refreshDevices() = dlna.search()

    // ---------------- cast entry (on-demand device pick) ----------------

    /** Start button: open the device picker instead of requiring a pre-selection. */
    fun onStartCastClicked() {
        val s = _state.value
        if (s.phase != CastPhase.IDLE && s.phase != CastPhase.ERROR) return
        if (s.video == null) return
        refreshDevices()              // refresh the list so the picker is current
        update { it.copy(showDevicePicker = true) }
    }

    fun dismissDevicePicker() = update { it.copy(showDevicePicker = false) }

    /** A device was tapped in the picker: lock it in and kick off the pipeline. */
    fun pickDeviceAndCast(device: RendererDevice) {
        // Guard against a double-tap / double-fire from the device picker: once a
        // cast is committed we must not start a second pipeline. A second pick used
        // to orphan the first transcoder, whose cancel (rc=255) then surfaced as a
        // spurious "ffmpeg rc=255" error while a second transcode ran on in the
        // background. Setting phase synchronously here makes a concurrent pick see
        // we're past IDLE and bail.
        val s = _state.value
        if (s.phase != CastPhase.IDLE && s.phase != CastPhase.ERROR) return
        update { it.copy(selectedDevice = device, showDevicePicker = false, phase = CastPhase.PREPARING, message = "正在准备…") }
        scope.launch { startCast() }
    }

    // ---------------- cast ----------------

    suspend fun startCast() {
        val s = _state.value
        val video = s.video ?: return
        val device = s.selectedDevice ?: return

        val ip = NetworkUtil.lanIpAddress()
        if (ip == null) { setError("未找到局域网 IP，请确认手机已连接 WiFi"); return }

        // 1. Copy the picked video to a real cache file and feed ffmpeg its path.
        //    FFmpegKit's saf:// protocol hands ffmpeg a non-seekable stream; an mp4
        //    whose moov atom lives at the tail then fails with "moov atom not found"
        //    (the demuxer can't seek back to the start / to the end). A plain file is
        //    fully seekable, sidestepping the saf path entirely.
        update { it.copy(message = "正在准备视频文件…") }
        val ext = video.name.substringAfterLast('.', "mp4")
        val cachedInput = File(context.cacheDir, "input_${System.nanoTime()}.$ext")
        val input: String? = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(video.uri)?.use { src ->
                    cachedInput.outputStream().use { out -> src.copyTo(out) }
                } ?: error("openInputStream returned null")
                cachedInput.absolutePath
            }.getOrElse { setError("无法读取视频文件: ${it.message}"); null }
        }
        if (input == null) return

        val duration = MediaProbe.durationMs(input) ?: video.durationMs

        // 2. Render subtitle → ASS file (burn-in path). null ⇒ video transcode only.
        val assPath = s.subtitle?.let { renderAssFile(it, s.secondarySubtitle, s.adjustments) }

        // 3. Output + URL. STREAM produces MPEG-TS (video/MP2T), PRE_TRANSCODE a
        //    faststart mp4 -- the container and the DLNA protocolInfo mime must match
        //    what the renderer can play (fragmented mp4 was rejected; TS is universal).
        val token = "media_${System.nanoTime()}"
        val outFile = File(context.cacheDir, "$token.mp4")
        val config = TranscodeConfig(s.mode, input, assPath, outFile.absolutePath, duration)
        val url = mediaServer.urlFor(ip, token)
        val mime = if (s.mode == TranscodeMode.STREAM) MIME_TS else MIME_MP4

        // Cancel any transcode still running from a previous (failed/retried) cast
        // before starting a new one. STREAM's start() is non-blocking, so without this
        // each retry stacked a concurrent ffmpeg (seen: 3x transcodes racing at 0.4x,
        // cooking the phone) -- the overwritten reference leaked the previous session.
        activeTranscoder?.cancel()
        activeTranscoder = null

        // 4. Transcode + push, per mode.
        when (s.mode) {
            TranscodeMode.PRE_TRANSCODE -> {
                update { it.copy(phase = CastPhase.TRANSCODING, progress = 0f, message = "正在烧录字幕，请稍候…") }
                val transcoder = Transcoder(config)
                activeTranscoder = transcoder
                val result = transcoder.runSuspend { p -> update { it.copy(progress = p.percent) } }
                activeTranscoder = null
                if (!result.success) { setError(result.message ?: "转码失败"); return }
                mediaServer.setMedia(outFile, token, live = false, mimeType = mime)
            }
            TranscodeMode.STREAM -> {
                mediaServer.setMedia(outFile, token, live = true, mimeType = mime)
                update { it.copy(phase = CastPhase.TRANSCODING, progress = 0f, message = "正在实时烧录并推送…") }
                val transcoder = Transcoder(config)
                activeTranscoder = transcoder
                transcoder.start(
                    onProgress = { p -> update { it.copy(progress = p.percent) } },
                    onComplete = { r ->
                        if (r.success) {
                            mediaServer.setMedia(outFile, token, live = false, mimeType = mime)
                        } else {
                            // Unblock any in-flight live stream so its connection thread
                            // closes -- otherwise it tails a file that stopped growing forever.
                            mediaServer.clear()
                            setError(r.message ?: "转码失败")
                        }
                    }
                )
                // Wait for first bytes before pushing so the TV gets a valid stream.
                val deadline = System.currentTimeMillis() + 30_000
                while (outFile.length() == 0L && System.currentTimeMillis() < deadline) delay(300)
                if (outFile.length() == 0L) { setError("转码未产生数据"); return }
            }
        }

        // 5. Push to renderer.
        update { it.copy(phase = CastPhase.PREPARING, message = "正在推送到电视…") }
        Log.i(TAG, "push to ${device.friendlyName}: $url ($mime)")
        val ok = dlna.cast(device, url, video.name, mime)
        Log.i(TAG, "push result ok=$ok")
        if (!ok) { setError("投屏失败：电视可能不支持该格式或未就绪"); return }

        update { it.copy(phase = CastPhase.CASTING, message = null) }

        // 6. Resume from last position, then poll playback.
        s.pendingResumeMs?.let { resumeMs ->
            if (resumeMs > 5_000) {
                delay(1200)
                dlna.seek(device, resumeMs)
            }
        }
        startPolling(device)
    }

    private suspend fun renderAssFile(
        primary: SelectedSubtitle,
        secondary: SelectedSubtitle?,
        adj: SubtitleAdjustments
    ): String? =
        runCatching {
            val pTrack = SubtitleService.load(SubtitleFileReader.readBytes(context, primary.uri), primary.name)
            val track = secondary?.let {
                val sTrack = SubtitleService.load(SubtitleFileReader.readBytes(context, it.uri), it.name)
                SubtitleService.mergeBilingual(pTrack, sTrack)
            } ?: pTrack
            val assText = SubtitleService.renderAss(track, adj)
            val file = File(context.cacheDir, "subs_${System.nanoTime()}.ass")
            file.writeText(assText)
            file.absolutePath
        }.getOrElse {
            setError("字幕解析失败: ${it.message}")
            null
        }

    private fun startPolling(device: RendererDevice) {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                val pos = dlna.position(device)
                val st = dlna.state(device)
                val vol = dlna.getVolume(device)
                Log.d(TAG, "poll: state=$st pos=${pos.positionMs}/${pos.durationMs} vol=$vol")
                update {
                    // The TV returns HTTP 500 for SOAP queries while playing a live
                    // MPEG-TS stream; on query failure keep the last known state so
                    // the UI doesn't flip to "unknown".
                    val newState = if (st == com.subcast.dlna.PlaybackState.UNKNOWN) it.playbackState else st
                    it.copy(
                        playbackState = newState,
                        positionMs = if (pos.positionMs > 0) pos.positionMs else it.positionMs,
                        durationMs = if (pos.durationMs > 0) pos.durationMs else it.durationMs,
                        volume = if (vol > 0) vol else it.volume
                    )
                }
                delay(1000)
            }
        }
    }

    // ---------------- transport ----------------

    fun play() = scope.launch { _state.value.selectedDevice?.let { dlna.play(it) } }
    fun pause() = scope.launch { _state.value.selectedDevice?.let { dlna.pause(it) } }

    fun seekTo(ms: Long) = scope.launch {
        _state.value.selectedDevice?.let {
            dlna.seek(it, ms)
            update { s -> s.copy(positionMs = ms) }
        }
    }

    fun setVolume(volume: Int) = scope.launch {
        _state.value.selectedDevice?.let {
            dlna.setVolume(it, volume)
            update { s -> s.copy(volume = volume) }
        }
    }

    fun stop() {
        activeTranscoder?.cancel()
        activeTranscoder = null
        pollJob?.cancel()
        scope.launch {
            _state.value.selectedDevice?.let { dlna.stop(it) }
            _state.value.video?.let { vid ->
                dao.upsert(
                    ResumeRecord(
                        videoUri = vid.uri.toString(),
                        videoName = vid.name,
                        positionMs = _state.value.positionMs,
                        durationMs = _state.value.durationMs,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
            mediaServer.clear()
            update { it.copy(phase = CastPhase.IDLE, playbackState = com.subcast.dlna.PlaybackState.UNKNOWN, positionMs = 0, message = null) }
        }
    }

    // ---------------- helpers ----------------

    private fun update(transform: (CastUiState) -> CastUiState) = _state.update(transform)

    private fun setError(message: String) =
        update { it.copy(phase = CastPhase.ERROR, message = message) }

    /** Bridges the callback-based [Transcoder] into a suspending call. */
    private suspend fun Transcoder.runSuspend(onProgress: (TranscodeProgress) -> Unit): TranscodeResult =
        suspendCancellableCoroutine { cont ->
            start(
                onProgress = onProgress,
                onComplete = { r -> if (cont.isActive) cont.resume(r) }
            )
            cont.invokeOnCancellation { cancel() }
        }
}
