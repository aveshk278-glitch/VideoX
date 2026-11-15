package com.videox.app.download

import java.io.ByteArrayOutputStream

/**
 * Splits FFmpeg's image2pipe MJPEG byte stream into individual JPEG
 * frames. A JPEG starts with SOI (0xFF 0xD8) and ends with EOI
 * (0xFF 0xD9); inside the entropy-coded data every 0xFF byte is
 * stuffed as 0xFF 0x00, so a raw 0xFF 0xD9 only ever marks the true
 * end of a frame. Feed arbitrary chunks, get whole frames back.
 * Pure JVM, unit-tested.
 */
class JpegStreamSplitter {

    private val pending = ByteArrayOutputStream()
    private var previousByte: Int = -1
    private var insideFrame = false

    /**
     * Feed [bytes] ([offset], [length]) from the stream. Returns
     * every complete JPEG frame found in this chunk, in order.
     * A partial frame stays buffered until its EOI arrives.
     */
    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        var i = offset
        val end = offset + length
        while (i < end) {
            val b = bytes[i].toInt() and 0xFF
            if (!insideFrame) {
                if (previousByte == 0xFF && b == 0xD8) {
                    insideFrame = true
                    pending.write(0xFF)
                    pending.write(0xD8)
                }
            } else {
                pending.write(b)
                if (previousByte == 0xFF && b == 0xD9) {
                    frames += pending.toByteArray()
                    pending.reset()
                    insideFrame = false
                }
            }
            previousByte = b
            i++
        }
        return frames
    }

    /** Any bytes of an unfinished frame at end of stream (null when clean). */
    fun finish(): ByteArray? {
        if (!insideFrame || pending.size() == 0) return null
        val partial = pending.toByteArray()
        pending.reset()
        insideFrame = false
        return partial
    }
}
