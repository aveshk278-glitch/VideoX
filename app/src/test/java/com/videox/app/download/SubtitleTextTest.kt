package com.videox.app.download

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for video-to-text conversion (SubtitleText).
 * These run on the JVM via `./gradlew testDebugUnitTest` — no device,
 * no network, no yt-dlp needed. The samples below mirror real subtitle
 * files downloaded by yt-dlp 2026.10.26 (SRT with --sub-format srt,
 * and native WebVTT without it).
 */
class SubtitleTextTest {

    @Test
    fun `srt converts to one line per cue with multi-line cues joined`() {
        val srt = """
            1
            00:00:01,360 --> 00:00:03,040
            [♪♪♪]

            2
            00:00:18,640 --> 00:00:21,880
            ♪ We're no strangers to love ♪

            3
            00:00:22,640 --> 00:00:26,960
            ♪ You know the rules
            and so do I ♪
        """.trimIndent()
        assertEquals(
            "[♪♪♪]\n♪ We're no strangers to love ♪\n♪ You know the rules and so do I ♪",
            SubtitleText.toPlainText(srt)
        )
    }

    @Test
    fun `webvtt headers and cue identifiers are dropped`() {
        val vtt = """
            WEBVTT
            Kind: captions
            Language: en

            cue-1
            00:00:01.360 --> 00:00:03.040
            [♪♪♪]

            00:00:18.640 --> 00:00:21.880
            Hello world
        """.trimIndent()
        assertEquals(
            "[♪♪♪]\nHello world",
            SubtitleText.toPlainText(vtt)
        )
    }

    @Test
    fun `formatting tags are stripped but the words stay`() {
        val srt = """
            1
            00:00:01,000 --> 00:00:02,000
            <i>Emphasised</i> and <c.yellow>coloured</c.yellow> words

            2
            00:00:03,000 --> 00:00:04,000
            Plain line
        """.trimIndent()
        assertEquals(
            "Emphasised and coloured words\nPlain line",
            SubtitleText.toPlainText(srt)
        )
    }

    @Test
    fun `empty and timestamp-only input yields empty text`() {
        assertEquals("", SubtitleText.toPlainText(""))
        assertEquals("", SubtitleText.toPlainText("WEBVTT\nKind: captions\n"))
        assertEquals(
            "",
            SubtitleText.toPlainText("1\n00:00:01,000 --> 00:00:02,000\n")
        )
    }

    @Test
    fun `repeated lines are kept as the video says them`() {
        val srt = """
            1
            00:00:01,000 --> 00:00:02,000
            Never gonna give you up

            2
            00:00:03,000 --> 00:00:04,000
            Never gonna give you up
        """.trimIndent()
        assertEquals(
            "Never gonna give you up\nNever gonna give you up",
            SubtitleText.toPlainText(srt)
        )
    }
}
