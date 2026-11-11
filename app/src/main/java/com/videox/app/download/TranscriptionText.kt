package com.videox.app.download

/**
 * Pure-Kotlin helpers for on-device speech transcription (v2.10).
 *
 * NO Android dependencies and NO Vosk imports on purpose, so this is
 * covered by plain JVM unit tests (see TranscriptionTextTest).
 */
object TranscriptionText {

    /**
     * Extract the recognised text from one Vosk result JSON string,
     * e.g. {"text": "hello world"}. Returns null when the JSON has no
     * usable "text" value (malformed input, missing key). JSON string
     * escapes (quote, backslash, slash, newline, tab, and backslash-u
     * four-hex-digit codes) are decoded.
     */
    fun textFromVoskJson(json: String?): String? {
        if (json.isNullOrBlank()) return null
        val keyIndex = json.indexOf("\"text\"")
        if (keyIndex < 0) return null
        val colonIndex = json.indexOf(':', keyIndex + 6)
        if (colonIndex < 0) return null
        var index = colonIndex + 1
        while (index < json.length && json[index].isWhitespace()) index++
        if (index >= json.length || json[index] != '"') return null
        index++
        val out = StringBuilder()
        while (index < json.length) {
            val ch = json[index]
            when {
                ch == '"' -> return out.toString()
                ch == '\\' -> {
                    if (index + 1 >= json.length) return null
                    when (val esc = json[index + 1]) {
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        '/' -> out.append('/')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'u' -> {
                            if (index + 5 >= json.length) return null
                            val hex = json.substring(index + 2, index + 6)
                            val code = hex.toIntOrNull(16) ?: return null
                            out.append(code.toChar())
                            index += 4
                        }
                        else -> return null
                    }
                    index += 2
                }
                else -> {
                    out.append(ch)
                    index++
                }
            }
        }
        return null // unterminated string
    }

    /**
     * Byte offset where the PCM audio data starts in a WAV file's
     * header bytes: the position just after the "data" chunk marker
     * and its 4-byte little-endian size. Returns null when no "data"
     * chunk marker is found (not a WAV file, or a truncated header).
     * Scanning starts after the 12-byte RIFF header.
     */
    fun pcmDataOffset(headerBytes: ByteArray): Int? {
        if (headerBytes.size < 20) return null
        var index = 12
        while (index + 8 <= headerBytes.size) {
            if (headerBytes[index] == 'd'.code.toByte() &&
                headerBytes[index + 1] == 'a'.code.toByte() &&
                headerBytes[index + 2] == 't'.code.toByte() &&
                headerBytes[index + 3] == 'a'.code.toByte()
            ) {
                return index + 8
            }
            index++
        }
        return null
    }
}
