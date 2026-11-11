package com.videox.app.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `estimated size is unknown when the video size is unknown`() {
        // The user's screenshot (v2.6): 4K showed "Approx. 4.5 MB" —
        // that was only the audio stream. When the video size is
        // unknown the total is unknown (0), so HomeScreen shows no
        // size instead of a wrong one.
        val noVideoSize = listOf(
            fmt("313", 2160, ext = "webm", size = 0L, videoOnly = true),
            fmt("140", 0, ext = "m4a", size = 15_000_000, audioOnly = true)
        )
        assertEquals(0L, QualitySelector.estimatedSizeForHeight(noVideoSize, 2160))
        assertEquals(0L, QualitySelector.estimatedSizeForHeight(noVideoSize, 1080))
    }

    @Test
    fun `empty format list reports no video honestly`() {
        assertEquals("No video formats found", QualitySelector.maxQualityLabel(emptyList()))
        assertNull(QualitySelector.maxHeight(emptyList()))
        assertTrue(QualitySelector.availableHeights(emptyList()).isEmpty())
    }

    @Test
    fun `download attempts use defaults first and android only as last resort`() {
        // Verified against real yt-dlp 2025.11.12: forcing
        // player_client=android,web drops web_safari and the download
        // collapses to format 18 (360p); defaults select 401+251 (4K).
        val attempts = QualitySelector.downloadAttempts("https://youtu.be/GwNctZ8sIAs")
        assertEquals(2, attempts.size)
        assertNull(attempts[0]) // yt-dlp defaults (android, web_safari, web) — full quality
        assertEquals("youtube:player_client=android", attempts[1])
    }

    @Test
    fun `non-youtube urls get a single default attempt`() {
        val attempts = QualitySelector.downloadAttempts("https://example.com/video.mp4")
        assertEquals(listOf<String?>(null), attempts)
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

    @Test
    fun `isYouTubeUrl recognises youtube links only`() {
        assertTrue(QualitySelector.isYouTubeUrl("https://www.youtube.com/watch?v=abc"))
        assertTrue(QualitySelector.isYouTubeUrl("https://youtu.be/abc"))
        assertTrue(QualitySelector.isYouTubeUrl("https://music.youtube.com/watch?v=abc"))
        assertFalse(QualitySelector.isYouTubeUrl("https://vimeo.com/123"))
        assertFalse(QualitySelector.isYouTubeUrl("https://example.com/video.mp4"))
    }

    @Test
    fun `hasDirectMediaExtension accepts media files and ignores query strings`() {
        assertTrue(QualitySelector.hasDirectMediaExtension("https://example.com/video.mp4"))
        assertTrue(QualitySelector.hasDirectMediaExtension("https://example.com/clip.WEBM"))
        assertTrue(QualitySelector.hasDirectMediaExtension("https://example.com/a/b.mkv?token=xyz"))
        assertTrue(QualitySelector.hasDirectMediaExtension("https://example.com/song.mp3"))
        assertTrue(QualitySelector.hasDirectMediaExtension("https://example.com/v.ogv"))
        assertFalse(QualitySelector.hasDirectMediaExtension("https://example.com/watch?v=1"))
        assertFalse(QualitySelector.hasDirectMediaExtension("https://example.com/video.mp4.html"))
        assertFalse(QualitySelector.hasDirectMediaExtension("https://example.com/page"))
    }

    @Test
    fun `extensionForContentType maps media types`() {
        assertEquals("mp4", QualitySelector.extensionForContentType("video/mp4"))
        assertEquals("webm", QualitySelector.extensionForContentType("video/webm; charset=utf-8"))
        assertEquals("mov", QualitySelector.extensionForContentType("video/quicktime"))
        assertEquals("mkv", QualitySelector.extensionForContentType("video/x-matroska"))
        assertEquals("mp3", QualitySelector.extensionForContentType("audio/mpeg"))
        assertEquals("m4a", QualitySelector.extensionForContentType("audio/mp4"))
        assertNull(QualitySelector.extensionForContentType("text/html"))
        assertNull(QualitySelector.extensionForContentType(null))
    }

    @Test
    fun `maxQualityLabel says Original quality when resolutions are unknown`() {
        // Mirrors real yt-dlp output for a direct WebM / embedded video:
        // formats exist, but height is unknown (0).
        val direct = listOf(
            YtFormat("webm", "webm", "", 0, "Video • webm", 0L, false, false, "", ""),
            YtFormat("mp4", "mp4", "", 0, "Video • mp4", 0L, false, false, "", "")
        )
        assertEquals("Original quality", QualitySelector.maxQualityLabel(direct))
        assertTrue(QualitySelector.availableHeights(direct).isEmpty())
        assertNull(QualitySelector.maxHeight(direct))
        assertEquals("No video formats found", QualitySelector.maxQualityLabel(emptyList()))
        // A requested height with no known heights falls back to Best Quality.
        assertNull(QualitySelector.clampHeight(direct, 1080))
    }

    @Test
    fun `describeYtDlpError maps known failures to accurate messages`() {
        val bot = QualitySelector.describeYtDlpError("ERROR: [youtube] x: Sign in to confirm you're not a bot.")
        assertTrue(bot.contains("bot check"))
        assertTrue(bot.contains("never signs in"))
        val login = QualitySelector.describeYtDlpError("[vimeo] 1: The web client only works when logged-in.")
        assertTrue(login.contains("needs a login"))
        assertTrue(login.contains("never asks for your password"))
        val impersonate = QualitySelector.describeYtDlpError(
            "The extractor is attempting impersonation, but none of these impersonate targets are available: firefox."
        )
        assertTrue(impersonate.contains("browser check"))
        val forbidden = QualitySelector.describeYtDlpError(
            "ERROR: unable to download video data: HTTP Error 403: Forbidden"
        )
        assertTrue(forbidden.contains("403"))
        val unsupported = QualitySelector.describeYtDlpError("ERROR: Unsupported URL: https://example.com/")
        assertTrue(unsupported.contains("isn't a single video page"))
        val gone = QualitySelector.describeYtDlpError("ERROR: Video unavailable")
        assertTrue(gone.contains("unavailable"))
        val fmt = QualitySelector.describeYtDlpError("ERROR: Requested format is not available.")
        assertTrue(fmt.contains("isn't available for this video"))
        val net = QualitySelector.describeYtDlpError(
            "Unable to download webpage: [Errno -2] Name or service not known"
        )
        assertTrue(net.contains("internet connection"))
    }

    @Test
    fun `describeYtDlpError maps DNS failures to a clear message`() {
        // The exact error from the user's phone (v2.6, 11 Nov 23:56):
        // the download server's hostname did not resolve at all, after
        // 10 yt-dlp retries. Previously this fell through to the raw
        // text; now it explains it is a DNS problem and what to try.
        val dns = QualitySelector.describeYtDlpError(
            "ERROR: [download] Got error: [Errno 7] No address associated with hostname. Giving up after 10 retries"
        )
        assertTrue(dns.contains("DNS"))
        assertTrue(dns.contains("Retry"))
        val dns2 = QualitySelector.describeYtDlpError(
            "ERROR: [Errno -3] Temporary failure in name resolution"
        )
        assertTrue(dns2.contains("DNS"))
    }

    @Test
    fun `describeYtDlpError passes unknown errors through unchanged`() {
        val raw = "ERROR: something strange happened (code 42)"
        assertEquals(raw, QualitySelector.describeYtDlpError(raw))
        assertEquals("yt-dlp reported an error.", QualitySelector.describeYtDlpError(null))
        assertEquals("yt-dlp reported an error.", QualitySelector.describeYtDlpError("   "))
    }

    @Test
    fun `isAlreadyDownloaded recognises yt-dlp skip message`() {
        // The exact line from the user's phone (v2.5): the file was
        // already on disk, yt-dlp skipped it, the app called it a failure.
        assertTrue(
            QualitySelector.isAlreadyDownloaded(
                "[download] /storage/emulated/0/Download/VideoX/CID Season 2 _ Latest Episode.mp4 has already been downloaded"
            )
        )
        assertTrue(
            QualitySelector.isAlreadyDownloaded(
                "[download] Video by trendydr0.mp4 HAS ALREADY BEEN DOWNLOADED"
            )
        )
    }

    @Test
    fun `isAlreadyDownloaded is false for other output`() {
        assertFalse(QualitySelector.isAlreadyDownloaded("[download] 100% of 1.2GiB in 00:42"))
        assertFalse(QualitySelector.isAlreadyDownloaded(""))
        assertFalse(QualitySelector.isAlreadyDownloaded(null))
        assertFalse(QualitySelector.isAlreadyDownloaded("ERROR: unable to download video data: HTTP Error 403"))
    }

    @Test
    fun `describeYtDlpError maps already-downloaded to the Downloads tab`() {
        val msg = QualitySelector.describeYtDlpError(
            "[download] Video by trendydr0.mp4 has already been downloaded"
        )
        assertTrue(msg.contains("already downloaded"))
        assertTrue(msg.contains("Downloads tab"))
    }
}
