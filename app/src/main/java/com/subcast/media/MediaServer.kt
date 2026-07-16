package com.subcast.media

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile

private const val TAG = "SubCast/Media"

/**
 * Local HTTP server that exposes the transcoded media to the DLNA renderer.
 *
 * Serves a single "current" file under a random token URL:
 *   http://<lan-ip>:<port>/<token>
 *
 * Two serving modes, keyed off [MediaRef.live]:
 *  - PRE_TRANSCODE (live=false): a complete, static file -> standard HTTP Range
 *    serving with a real Content-Length (TV can seek).
 *  - STREAM (live=true): a file that grows as ffmpeg writes it -> a *continuous*
 *    chunked response with no Content-Length. A blocking InputStream tails the
 *    growing file, blocking for fresh bytes while the transcode runs and returning
 *    EOF only once the transcode completes (CastEngine flips live to false on the
 *    same token). This is what lets the TV play a live transcode -- the previous
 *    snapshot response (Content-Length = current file size) delivered only ~2s of
 *    video then closed, because the TV read the finite Content-Length as
 *    "stream ended" and never came back for more.
 *
 * The served [MediaRef.mimeType] matches the container the transcoder wrote
 * (mp4 for PRE_TRANSCODE, MPEG-TS / video/MP2T for STREAM) -- the TV's DLNA
 * player keys playback off the HTTP Content-Type, so a mismatch reads as
 * "playback failed" on the TV even though the bytes are correct.
 */
class MediaServer(port: Int = 0) : NanoHTTPD(port) {

    data class MediaRef(val file: File, val token: String, val live: Boolean, val mimeType: String)

    @Volatile
    private var media: MediaRef? = null

    fun setMedia(file: File, token: String, live: Boolean, mimeType: String) {
        media = MediaRef(file, token, live, mimeType)
    }

    /** Drop the current media. Used to unblock any in-flight live stream when a
     *  transcode fails, so its connection thread doesn't block forever. */
    fun clear() {
        media = null
    }

    fun urlFor(ip: String, token: String): String = "http://$ip:$listeningPort/$token"

    override fun serve(session: IHTTPSession): Response {
        val ref = media
        if (ref == null) {
            Log.w(TAG, "request but no media set: ${session.uri}")
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "no media")
        }
        val uri = session.uri.trimStart('/')
        if (uri != ref.token) {
            Log.w(TAG, "request for unknown token '${session.uri}' (have '${ref.token}')")
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "no media")
        }
        return if (ref.live) serveLive(session, ref) else serveRange(session, ref)
    }

    /**
     * Live (STREAM) path: serve the growing file as a continuous chunked stream.
     * The TV reads bytes as ffmpeg produces them; EOF only when the transcode
     * finishes. No Content-Length, so the TV never mistakes a snapshot size for
     * the end of the stream.
     */
    private fun serveLive(session: IHTTPSession, ref: MediaRef): Response {
        val rangeHeader = session.header("range")
        var start0 = rangeStart(rangeHeader)

        // Wait (bounded) for the requested start byte to exist on the growing file.
        var length = ref.file.length()
        val deadline = System.currentTimeMillis() + 25_000L
        while (length <= start0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(200)
            length = ref.file.length()
        }
        if (length <= 0) {
            Log.w(TAG, "serve live ${ref.mimeType}: file not ready (len=$length)")
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "not ready")
        }
        start0 = start0.coerceIn(0L, length - 1)

        val stream = LiveMediaStream(ref.file, ref.token, start0)
        val resp = newChunkedResponse(Response.Status.OK, ref.mimeType, stream)
        // A live stream can't be range-served; tell the TV not to seek ahead.
        resp.addHeader("Accept-Ranges", "none")
        Log.i(TAG, "serve live ${ref.mimeType} range='${rangeHeader ?: "none"}' -> OK chunked start=$start0 len=$length")
        return resp
    }

    /** Static (PRE_TRANSCODE) path: standard Range serving of a complete file. */
    private fun serveRange(session: IHTTPSession, ref: MediaRef): Response {
        val rangeHeader = session.header("range")
        val file = ref.file
        val length = file.length()

        if (length <= 0) {
            Log.w(TAG, "serve ${ref.mimeType}: file empty (len=$length)")
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "not ready")
        }

        val start = rangeStart(rangeHeader).coerceIn(0L, length - 1)
        val end = length - 1
        val contentLength = end - start + 1

        val fis = FileInputStream(file)
        val skipped = fis.skip(start)
        if (skipped < start) {
            fis.close()
            Log.w(TAG, "serve ${ref.mimeType}: seek error start=$start skipped=$skipped")
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "seek error")
        }

        val status = if (rangeHeader != null) Response.Status.PARTIAL_CONTENT else Response.Status.OK
        val resp = newFixedLengthResponse(status, ref.mimeType, fis, contentLength)
        resp.addHeader("Accept-Ranges", "bytes")
        resp.addHeader("Content-Range", "bytes $start-$end/$length")
        Log.i(TAG, "serve ${ref.mimeType} range='${rangeHeader ?: "none"}' -> $status bytes=$start-$end cl=$contentLength len=$length")
        return resp
    }

    /**
     * InputStream that tails a file ffmpeg is still writing. Reads return the
     * bytes available so far; on reaching the current EOF it blocks (polling) as
     * long as [MediaServer.media] still points at [token] with live=true. EOF is
     * returned only once the transcode completes (CastEngine flips live to false
     * on the same token) or a new cast replaces the media / [clear] is called --
     * all make [isLive] false, draining the stream and freeing the connection.
     */
    private inner class LiveMediaStream(
        file: File,
        private val token: String,
        start: Long,
    ) : InputStream() {
        private val raf = RandomAccessFile(file, "r")
        private var pos: Long = start

        init { raf.seek(start) }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (true) {
                val avail = raf.length() - pos
                if (avail > 0) {
                    raf.seek(pos)
                    val n = raf.read(b, off, len)
                    if (n > 0) { pos += n; return n }
                }
                // At EOF: keep tailing only while the producer is still live for our token.
                if (!isLive(token)) return -1
                try { Thread.sleep(50) } catch (_: InterruptedException) { return -1 }
            }
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun close() {
            runCatching { raf.close() }
        }
    }

    private fun isLive(token: String): Boolean =
        media?.let { it.token == token && it.live } == true

    private fun rangeStart(range: String?): Long {
        if (range == null) return 0L
        val m = Regex("bytes=(\\d+)").find(range) ?: return 0L
        return m.groupValues[1].toLong()
    }

    private fun IHTTPSession.header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
