package com.subcast.cast

import android.net.Uri
import com.subcast.dlna.PlaybackState
import com.subcast.dlna.RendererDevice
import com.subcast.subtitle.SubtitleAdjustments
import com.subcast.transcode.TranscodeMode

data class SelectedVideo(val uri: Uri, val name: String, val durationMs: Long?)
data class SelectedSubtitle(val uri: Uri, val name: String)

enum class CastPhase { IDLE, PREPARING, TRANSCODING, CASTING, ERROR }

/** Single source of truth for the cast UI. */
data class CastUiState(
    val phase: CastPhase = CastPhase.IDLE,
    val progress: Float = 0f,
    val message: String? = null,
    val video: SelectedVideo? = null,
    val subtitle: SelectedSubtitle? = null,
    val secondarySubtitle: SelectedSubtitle? = null,
    val mode: TranscodeMode = TranscodeMode.PRE_TRANSCODE,
    val adjustments: SubtitleAdjustments = SubtitleAdjustments(),
    val devices: List<RendererDevice> = emptyList(),
    val selectedDevice: RendererDevice? = null,
    val playbackState: PlaybackState = PlaybackState.UNKNOWN,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val volume: Int = 0,
    val pendingResumeMs: Long? = null,
) {
    val canStart: Boolean
        get() = video != null && selectedDevice != null &&
            (phase == CastPhase.IDLE || phase == CastPhase.ERROR)
}
