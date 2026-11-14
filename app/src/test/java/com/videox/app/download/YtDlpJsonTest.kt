package com.videox.app.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for YtDlpJson (v2.14): parsing yt-dlp
 * --dump-single-json output for Auto Detect. These run on the JVM
 * via `./gradlew testDebugUnitTest` — no device, no network, no
 * yt-dlp needed. The samples mirror the real shape of yt-dlp
 * 2026.10.26 output, including the traps: format objects carry
 * their own nested "language" fields, and chapters/thumbnails carry
 * nested objects that must never be mistaken for top-level fields.
 */
class YtDlpJsonTest {

    /** Hindi video: manual Hindi subs, auto English captions, nested traps. */
    private val hindiVideoJson = """
        {
          "id": "abc123",
          "title": "Hindi video with a } brace and \"quoted\" text in the title",
          "language": "hi",
          "formats": [
            {"format_id": "18", "language": "en", "ext": "mp4"},
            {"format_id": "22", "language": null, "ext": "mp4"}
          ],
          "subtitles": {
            "hi": [{"ext": "vtt", "url": "https://example.com/hi.vtt"}],
            "en": [{"ext": "vtt", "url": "https://example.com/en.vtt"}]
          },
          "automatic_captions": {
            "en": [{"ext": "vtt", "url": "https://example.com/auto-en.vtt"}],
            "hi": [{"ext": "vtt", "url": "https://example.com/auto-hi.vtt"}]
          },
          "chapters": [{"title": "Intro", "start_time": 0, "end_time": 10}]
        }
    """.trimIndent()

    @Test
    fun `parse reads the video language and both subtitle maps`() {
        val probe = YtDlpJson.parse(hindiVideoJson)
        assertEquals("hi", probe.videoLanguage)
        assertEquals(setOf("hi", "en"), probe.manualSubLangs)
        assertEquals(setOf("en", "hi"), probe.autoSubLangs)
    }

    @Test
    fun `parse ignores nested language fields inside formats`() {
        // The formats above claim "en"; the video language must stay "hi".
        val probe = YtDlpJson.parse(hindiVideoJson)
        assertEquals("hi", probe.videoLanguage)
    }

    @Test
    fun `auto detect prefers the video language manual track`() {
        val probe = YtDlpJson.parse(hindiVideoJson)
        assertEquals("hi", YtDlpJson.chooseSubtitleLanguage(probe, "auto"))
        assertEquals("hi", YtDlpJson.chooseSubtitleLanguage(probe, "original"))
    }

    @Test
    fun `auto detect falls back to any manual track when the video language has none`() {
        val probe = YtDlpJson.Probe(
            videoLanguage = "ur",
            manualSubLangs = setOf("en", "hi"),
            autoSubLangs = setOf("ur")
        )
        // First alphabetically, so the choice is deterministic.
        assertEquals("en", YtDlpJson.chooseSubtitleLanguage(probe, "auto"))
    }

    @Test
    fun `auto detect uses auto captions when there are no manual subtitles`() {
        val probe = YtDlpJson.Probe(
            videoLanguage = "hi",
            manualSubLangs = emptySet(),
            autoSubLangs = setOf("en", "hi")
        )
        assertEquals("hi", YtDlpJson.chooseSubtitleLanguage(probe, "auto"))
    }

    @Test
    fun `auto detect returns any auto caption when the video language has none`() {
        val probe = YtDlpJson.Probe(
            videoLanguage = "mr",
            manualSubLangs = emptySet(),
            autoSubLangs = setOf("en", "es")
        )
        assertEquals("en", YtDlpJson.chooseSubtitleLanguage(probe, "auto"))
    }

    @Test
    fun `auto detect returns null when the video has no tracks at all`() {
        val probe = YtDlpJson.Probe("hi", emptySet(), emptySet())
        assertNull(YtDlpJson.chooseSubtitleLanguage(probe, "auto"))
        assertNull(YtDlpJson.chooseSubtitleLanguage(probe, "original"))
    }

    @Test
    fun `parse handles a video with no subtitles key at all`() {
        val probe = YtDlpJson.parse("""{"id": "x", "title": "No subs", "language": "en"}""")
        assertEquals("en", probe.videoLanguage)
        assertTrue(probe.manualSubLangs.isEmpty())
        assertTrue(probe.autoSubLangs.isEmpty())
        assertNull(YtDlpJson.chooseSubtitleLanguage(probe, "auto"))
    }

    @Test
    fun `parse handles a video with no language key`() {
        val probe = YtDlpJson.parse(
            """{"id": "x", "subtitles": {"fr": [{"ext": "vtt"}]}}"""
        )
        assertNull(probe.videoLanguage)
        assertEquals(setOf("fr"), probe.manualSubLangs)
        assertEquals("fr", YtDlpJson.chooseSubtitleLanguage(probe, "auto"))
    }

    @Test
    fun `parse handles a null language value`() {
        val probe = YtDlpJson.parse(
            """{"id": "x", "language": null, "subtitles": {"de": []}}"""
        )
        assertNull(probe.videoLanguage)
        assertEquals(setOf("de"), probe.manualSubLangs)
    }

    @Test
    fun `parse never crashes on malformed input`() {
        for (bad in listOf("", "   ", "not json", "{", "{\"language\":", "{\"language\": \"hi\"", null)) {
            val probe = YtDlpJson.parse(bad)
            assertTrue(probe.manualSubLangs.isEmpty())
            assertTrue(probe.autoSubLangs.isEmpty())
        }
        // Unterminated string and trailing garbage: still no crash.
        YtDlpJson.parse("{\"language\": \"hi")
        YtDlpJson.parse("{\"language\": \"hi\"} garbage")
    }

    @Test
    fun `parse ignores a nested subtitles key inside formats`() {
        val probe = YtDlpJson.parse(
            """{"id": "x", "formats": [{"subtitles": {"zz": []}}], "language": "en"}"""
        )
        assertTrue(probe.manualSubLangs.isEmpty())
        assertEquals("en", probe.videoLanguage)
    }

    @Test
    fun `explicit code matches its track, manual first`() {
        val probe = YtDlpJson.parse(hindiVideoJson)
        assertEquals("en", YtDlpJson.chooseSubtitleLanguage(probe, "en"))
        assertEquals("hi", YtDlpJson.chooseSubtitleLanguage(probe, "hi"))
    }

    @Test
    fun `explicit code returns null when the video has no such track`() {
        val probe = YtDlpJson.parse(hindiVideoJson)
        assertNull(YtDlpJson.chooseSubtitleLanguage(probe, "ur"))
        assertNull(YtDlpJson.chooseSubtitleLanguage(probe, "bn"))
    }

    @Test
    fun `explicit code matches by base language and returns the real track key`() {
        val probe = YtDlpJson.Probe(
            videoLanguage = "en",
            manualSubLangs = setOf("en-US", "pt-BR"),
            autoSubLangs = emptySet()
        )
        assertEquals("en-US", YtDlpJson.chooseSubtitleLanguage(probe, "en"))
        assertEquals("en-US", YtDlpJson.chooseSubtitleLanguage(probe, "en-in"))
        assertEquals("pt-BR", YtDlpJson.chooseSubtitleLanguage(probe, "pt"))
    }

    @Test
    fun `explicit code matching is case-insensitive`() {
        val probe = YtDlpJson.Probe("hi", setOf("hi"), emptySet())
        assertEquals("hi", YtDlpJson.chooseSubtitleLanguage(probe, "HI"))
    }

    @Test
    fun `all returns null because the caller requests every track`() {
        val probe = YtDlpJson.parse(hindiVideoJson)
        assertNull(YtDlpJson.chooseSubtitleLanguage(probe, "all"))
    }

    @Test
    fun `auto captions only sample parses correctly`() {
        val probe = YtDlpJson.parse(
            """{"id": "y", "language": "ta", "automatic_captions": {"ta": [{"ext": "vtt"}], "en": [{"ext": "vtt"}]}}"""
        )
        assertEquals("ta", probe.videoLanguage)
        assertTrue(probe.manualSubLangs.isEmpty())
        assertEquals(setOf("ta", "en"), probe.autoSubLangs)
        assertEquals("ta", YtDlpJson.chooseSubtitleLanguage(probe, "auto"))
    }
}
