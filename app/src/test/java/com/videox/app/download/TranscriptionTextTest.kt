package com.videox.app.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the transcription helpers (TranscriptionText).
 * These run on the JVM via `./gradlew testDebugUnitTest` — no device,
 * no Vosk native library, no network needed.
 */
class TranscriptionTextTest {

    @Test
    fun `textFromVoskJson extracts the text field`() {
        assertEquals(
            "hello world",
            TranscriptionText.textFromVoskJson("{\"text\": \"hello world\"}")
        )
        assertEquals(
            "",
            TranscriptionText.textFromVoskJson("{\"text\": \"\"}")
        )
    }

    @Test
    fun `textFromVoskJson decodes escapes and unicode`() {
        assertEquals(
            "say \"hi\"\nnew line",
            TranscriptionText.textFromVoskJson("{\"text\": \"say \\\"hi\\\"\\nnew line\"}")
        )
        assertEquals(
            "caf\u00e9",
            TranscriptionText.textFromVoskJson("{\"text\": \"caf\\u00e9\"}")
        )
        assertEquals(
            "a/b\\c",
            TranscriptionText.textFromVoskJson("{\"text\": \"a\\/b\\\\c\"}")
        )
    }

    @Test
    fun `textFromVoskJson ignores other fields`() {
        assertEquals(
            "never gonna give you up",
            TranscriptionText.textFromVoskJson(
                "{\"result\": [{\"word\": \"never\"}], \"text\": \"never gonna give you up\"}"
            )
        )
    }

    @Test
    fun `textFromVoskJson returns null for malformed input`() {
        assertNull(TranscriptionText.textFromVoskJson(null))
        assertNull(TranscriptionText.textFromVoskJson(""))
        assertNull(TranscriptionText.textFromVoskJson("{\"partial\": \"hello\"}"))
        assertNull(TranscriptionText.textFromVoskJson("{\"text\": 42}"))
        assertNull(TranscriptionText.textFromVoskJson("{\"text\": \"unterminated}"))
        assertNull(TranscriptionText.textFromVoskJson("not json"))
    }

    @Test
    fun `pcmDataOffset finds the data chunk in a standard WAV header`() {
        val header = ByteArray(44)
        "RIFF".toByteArray().copyInto(header, 0)
        "WAVE".toByteArray().copyInto(header, 8)
        "fmt ".toByteArray().copyInto(header, 12)
        "data".toByteArray().copyInto(header, 36)
        assertEquals(44, TranscriptionText.pcmDataOffset(header))
    }

    @Test
    fun `pcmDataOffset finds a data chunk after extra chunks`() {
        val header = ByteArray(60)
        "RIFF".toByteArray().copyInto(header, 0)
        "WAVE".toByteArray().copyInto(header, 8)
        "LIST".toByteArray().copyInto(header, 12)
        "data".toByteArray().copyInto(header, 52)
        assertEquals(60, TranscriptionText.pcmDataOffset(header))
    }

    @Test
    fun `pcmDataOffset returns null without a data chunk`() {
        assertNull(TranscriptionText.pcmDataOffset(ByteArray(0)))
        assertNull(TranscriptionText.pcmDataOffset(ByteArray(44)))
        assertNull(TranscriptionText.pcmDataOffset("not a wav file at all........".toByteArray()))
    }
}
