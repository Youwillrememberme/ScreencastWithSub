package com.subcast.media

import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream

/**
 * Local HTTP server that exposes the transcoded media to the DLNA renderer.
 *
 * Serves a single "current" file under a random token URL:
 *   http://<lan-ip>:<port>/<token>
 *
 * Supports HTTP Range requests so the TV can seek. For [live] (streaming)
 * files the file grows while being served; a requested range ahead of the
 * write head blocks briefly until bytes arrive, and the Content-Range total
 * is reported as `*` (unknown) since the final size isn't known until the
 * transcode completes.
 */
class MediaServer(port: Int = 8080) : NanoHTTPD(port) {

    data class MediaRef(val file: File, val token: String, val live: Boolean)

    @Volatile
    private var media: MediaRef? = null

    fun setMedia(file: File, token: String, live: Boolean) {
        media = MediaRef(file, token, live)
    }

    fun clear() {
        media = null
    }

    fun urlFor(ip: String, token: String): String = "http://$ip:$listeningPort/$token"

    override fun serve(session: IHTTPSession): Response {
        val ref = media ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "no media")
        val uri = session.uri.trimStart('/')
        if (uri != ref.token) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "no media")
        return serveRange(session, ref)
    }

    private fun serveRange(session: IHTTPSession, ref: MediaRef): Response {
        val rangeHeader = session.header("range")
        val file = ref.file
        var length = file.length()

        val start0 = rangeStart(rangeHeader)
        if (ref.live) {
            // Wait (bounded) for the requested start byte to exist on the growing file.
            val deadline = System.currentTimeMillis() + 25_000L
            while (length <= start0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(200)
                length = file.length()
            }
        }

        if (length <= 0) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "not ready")
        }

        val start = start0.coerceIn(0L, length - 1)
        val end = length - 1
        val contentLength = end - start + 1

        val fis = FileInputStream(file)
        val skipped = fis.skip(start)
        if (skipped < start) {
            fis.close()
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "seek error")
        }

        val status = if (rangeHeader != null) Response.Status.PARTIAL_CONTENT else Response.Status.OK
        val resp = newFixedLengthResponse(status, "video/mp4", fis, contentLength)
        resp.addHeader("Accept-Ranges", "bytes")
        resp.addHeader("Content-Range", "bytes $start-$end/${if (ref.live) "*" else length.toString()}")
        return resp
    }

    private fun rangeStart(range: String?): Long {
        if (range == null) return 0L
        val m = Regex("bytes=(\\d+)").find(range) ?: return 0L
        return m.groupValues[1].toLong()
    }

    private fun IHTTPSession.header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
