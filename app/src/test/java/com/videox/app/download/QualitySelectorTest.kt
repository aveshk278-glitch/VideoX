package com.videox.app.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the quality-selection logic (QualitySelector).
 * These run on the JVM via `./gradlew testDebugUnitTest` — no device,
 * no network, no yt-dlp needed. The format lists below mirror real
 * yt-dlp output for a typical YouTube video: combined formats up to
 * 720p, video-only adaptive formats up to 2160p, and audio-only streams.
 */
class QualitySelectorTest {

    private fun fmt(
        id: String,
        height: Int,
        ext: String = "mp4",
        size: Long = 0L,
        videoOnly: Boolean = false,
        audioOnly: Boolean = false
    ) = YtFormat(
        formatId = id,
        ext = ext,
        resolution = if (height > 0) "1920x$height" else "",
        height = height,
        label = id,
        fileSize = size,
        isAudioOnly = audioOnly,
        isVideoOnly = videoOnly,
        vcodec = if (audioOnly) "none" else "avc1",
        acodec = if (videoOnly) "none" else "mp4a"
    )

    /** Typical YouTube-style list: combined to 720p, adaptive to 2160p. */
    private fun youtubeFormats() = listOf(
        fmt("18", 360, size = 40_000_000),
        fmt("22", 720, size = 120_000_000),
        fmt("137", 1080, size = 300_000_000, videoOnly = true),
        fmt("248", 1080, ext = "webm", size = 280_000_000, videoOnly = true),
        fmt("271", 1440, ext = "webm", size = 500_000_000, videoOnly = true),
        fmt("313", 2160, ext = "webm", size = 900_000_000, videoOnly = true),
        fmt("140", 0, ext = "m4a", size = 15_000_000, audioOnly = true),
        fmt("251", 0, ext = "webm", size = 12_000_000, audioOnly = true)
    )

    @Test
    fun `availableHeights lists every real resolution highest first`() {
        assertEquals(listOf(2160, 1440, 1080, 720, 360), QualitySelector.availableHeights(youtubeFormats()))
    }

    @Test
    fun `maxHeight is the true maximum not the combined-format cap`() {
        // The old bug: only combined formats were shown, capping at 720p.
        assertEquals(2160, QualitySelector.maxHeight(youtubeFormats()))
    }

    @Test
    fun `maxQualityLabel names the real maximum`() {
        assertEquals("Max available: 2160p 4K", QualitySelector.maxQualityLabel(youtubeFormats()))
    }

    @Test
    fun `maxQualityLabel handles a 720p-only source honestly`() {
        val low = listOf(fmt("18", 360), fmt("22", 720), fmt("140", 0, audioOnly = true))
        assertEquals("Max available: 720p HD", QualitySelector.maxQualityLabel(low))
    }

    @Test
    fun `qualityLabel names every required quality`() {
        assertEquals("144p", QualitySelector.qualityLabel(144))
        assertEquals("240p", QualitySelector.qualityLabel(240))
        assertEquals("360p", QualitySelector.qualityLabel(360))
        assertEquals("480p", QualitySelector.qualityLabel(480))
        assertEquals("720p HD", QualitySelector.qualityLabel(720))
        assertEquals("1080p Full HD", QualitySelector.qualityLabel(1080))
        assertEquals("1440p 2K", QualitySelector.qualityLabel(1440))
        assertEquals("2160p 4K", QualitySelector.qualityLabel(2160))
    }

    @Test
    fun `best quality selector pairs best video with best audio`() {
        // Must contain +bestaudio: a bare video-only id downloads silent.
        assertEquals("bestvideo+bestaudio/best", QualitySelector.buildFormatSelector(null))
        assertTrue(QualitySelector.buildFormatSelector(null).contains("bestaudio"))
    }

    @Test
    fun `height selector pairs that height with best audio`() {
        assertEquals(
            "bestvideo[height<=1080]+bestaudio/best",
            QualitySelector.buildFormatSelector(1080)
        )
        assertEquals(
            "bestvideo[height<=720]+bestaudio/best",
            QualitySelector.buildFormatSelector(720)
        )
    }

    @Test
    fun `requested height above the maximum is clamped never upscaled`() {
        assertEquals(2160, QualitySelector.clampHeight(youtubeFormats(), 4320))
        assertEquals(1080, QualitySelector.clampHeight(youtubeFormats(), 1080))
        assertNull(QualitySelector.clampHeight(youtubeFormats(), null))
    }

    @Test
    fun `bestVideoFormat picks the highest height`() {
        assertEquals("313", QualitySelector.bestVideoFormat(youtubeFormats())?.formatId)
    }

    @Test
    fun `bestVideoFormat prefers combined on a height tie`() {
        val tied = listOf(
            fmt("137", 1080, videoOnly = true),
            fmt("22", 720),
            fmt("999", 1080) // combined 1080p
        )
        assertEquals("999", QualitySelector.bestVideoFormat(tied)?.formatId)
    }

    @Test
    fun `estimated size adds video and audio for video-only heights`() {
        // 1080p video-only (300 MB) + best audio (15 MB) = 315 MB.
        assertEquals(315_000_000L, QualitySelector.estimatedSizeForHeight(youtubeFormats(), 1080))
    }

    @Test
    fun `empty format list reports no video honestly`() {
        assertEquals("No video formats found", QualitySelector.maxQualityLabel(emptyList()))
        assertNull(QualitySelector.maxHeight(emptyList()))
        assertTrue(QualitySelector.availableHeights(emptyList()).isEmpty())
    }

    @Test
    fun `youtube urls get the android player client workaround`() {
        // YouTube SABR 403 workaround (yt-dlp #12482): the Android player
        // client is not forced into SABR, so its formats keep real URLs.
        assertEquals(
            "youtube:player_client=android,web",
            QualitySelector.extractorArgsFor("https://www.youtube.com/watch?v=GwNctZ8sIAs")
        )
        assertEquals(
            "youtube:player_client=android,web",
            QualitySelector.extractorArgsFor("https://youtu.be/GwNctZ8sIAs")
        )
        assertEquals(
            "youtube:player_client=android,web",
            QualitySelector.extractorArgsFor("https://music.youtube.com/watch?v=abc123")
        )
    }

    @Test
    fun `non-youtube urls get no extractor args`() {
        assertNull(QualitySelector.extractorArgsFor("https://example.com/video.mp4"))
        assertNull(QualitySelector.extractorArgsFor("https://vimeo.com/123456"))
        assertNull(QualitySelector.extractorArgsFor("https://www.facebook.com/watch?v=1"))
    }

    @Test
    fun `sanitizeFileName keeps only safe characters`() {
        assertEquals("My Video 2024", QualitySelector.sanitizeFileName("My Video 2024"))
        assertEquals("a_b_c", QualitySelector.sanitizeFileName("a/b:c"))
        assertEquals("______", QualitySelector.sanitizeFileName("हिन्दी"))
    }

    @Test
    fun `resolveOutputBaseName prefers the custom name and falls back to the title`() {
        assertEquals("my_clip", QualitySelector.resolveOutputBaseName("Some Title!", "my_clip"))
        assertEquals("Some Title_", QualitySelector.resolveOutputBaseName("Some Title!", ""))
        assertEquals("Some Title_", QualitySelector.resolveOutputBaseName("Some Title!", null))
    }
}
