package com.subcast.subtitle

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Detects a subtitle file's text encoding.
 *
 * Strategy: honor a UTF-8 / UTF-16 BOM if present; otherwise attempt a *strict*
 * UTF-8 decode — if it succeeds, treat as UTF-8; on failure fall back to
 * GB18030, which is a superset of GBK/GB2312 (the common non-UTF-8 encodings
 * for Chinese subtitles). This covers the overwhelming majority of real-world
 * files without pulling in a dedicated detection library.
 */
object EncodingDetector {

    private val GB18030: Charset = Charset.forName("GB18030")

    fun detect(bytes: ByteArray): Charset {
        // BOM checks
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return Charsets.UTF_8
        }
        if (bytes.size >= 2) {
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return Charsets.UTF_16LE
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return Charsets.UTF_16BE
        }
        // Strict UTF-8 trial: if every byte is valid UTF-8, assume UTF-8.
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes))
            Charsets.UTF_8
        } catch (e: Exception) {
            GB18030
        }
    }
}
