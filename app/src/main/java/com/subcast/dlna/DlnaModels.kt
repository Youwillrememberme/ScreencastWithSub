package com.subcast.dlna

/** Transport state reported by the renderer's AVTransport service. */
enum class PlaybackState { STOPPED, PLAYING, PAUSED, TRANSITIONING, NO_MEDIA_PRESENT, UNKNOWN }

/** A discovered DLNA MediaRenderer. [udn] is the stable lookup key. */
data class RendererDevice(val udn: String, val friendlyName: String)

/** Playback position + known duration, in milliseconds. */
data class PositionInfo(val positionMs: Long, val durationMs: Long)
