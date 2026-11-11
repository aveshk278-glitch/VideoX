package com.videox.app.download

/**
 * Pure-Kotlin quality selection logic for VideoX.
 *
 * This module has NO Android dependencies on purpose, so it is covered by
 * plain JVM unit tests (see QualitySelectorTest).
 *
 * Root cause it fixes (v2.0 bug):
 * - The old UI listed raw yt-dlp formats, combined (video+audio) formats
 *   first, truncated to 12. On YouTube combined formats stop at 720p, so
 *   1080p/1440p/4K never showed up — users only ever saw low quality.
 * - Picking a high-resolution format downloaded it with `-f <id>` alone.
 *   Those formats are video-ONLY (no audio track), so the result was a
 *   silent video that looked broken.
 *
 * The fix: select by HEIGHT, not by raw format id, and always pair the
 * chosen video stream with the best audio stream so yt-dlp merges them
 * (FFmpeg) into one file with sound:
 *   Best Quality : bestvideo+bestaudio/best
 *   1080p        : bestvideo[height<=1080]+bestaudio/best
 * Never upscale: a requested height above the real maximum is clamped
 * down to the actual maximum, and the UI shows that maximum honestly.
 */
object QualitySelector {

    /** Height in pixels of a format, 0 when it has no video track. */
    fun heightOf(format: YtFormat): Int = format.height

    /** Human label for a height, e.g. 1080 -> "1080p Full HD". */
    fun qualityLabel(height: Int): String = when (height) {
        2160 -> "2160p 4K"
        1440 -> "1440p 2K"
        1080 -> "1080p Full HD"
        720 -> "720p HD"
        480 -> "480p"
        360 -> "360p"
        240 -> "240p"
        144 -> "144p"
        else -> if (height > 0) "${height}p" else "Audio"
    }

    /** Distinct video heights available, highest first. Audio-only ignored. */
    fun availableHeights(formats: List<YtFormat>): List<Int> =
        formats.filter { it.height > 0 }
            .map { it.height }
            .distinct()
            .sortedDescending()

    /** The highest genuinely available video height, or null if none. */
    fun maxHeight(formats: List<YtFormat>): Int? = availableHeights(formats).firstOrNull()

    /** Honest one-line summary of the best quality on offer. */
    fun maxQualityLabel(formats: List<YtFormat>): String {
        val max = maxHeight(formats) ?: return "No video formats found"
        return "Max available: ${qualityLabel(max)}"
    }

    /**
     * Clamp a requested height to what actually exists. A request above
     * the real maximum returns the maximum — never an upscale, never a
     * format that does not exist.
     */
    fun clampHeight(formats: List<YtFormat>, requestedHeight: Int?): Int? {
        if (requestedHeight == null) return null // null = Best Quality
        val max = maxHeight(formats) ?: return null
        return minOf(requestedHeight, max)
    }

    /**
     * yt-dlp format selector for a chosen height.
     * - null height (Best Quality): highest video + best audio, merged.
     * - a height: best video at or below that height + best audio, merged.
     * The `+bestaudio/best` part is what guarantees sound: without it a
     * high-resolution video-only stream would download silently.
     */
    fun buildFormatSelector(requestedHeight: Int?): String =
        if (requestedHeight == null) {
            "bestvideo+bestaudio/best"
        } else {
            "bestvideo[height<=$requestedHeight]+bestaudio/best"
        }

    /**
     * The single best video format: highest height; on a tie prefer a
     * combined (video+audio) format, then mp4. Used for labels/sizes only —
     * downloads always go through [buildFormatSelector].
     */
    fun bestVideoFormat(formats: List<YtFormat>): YtFormat? =
        formats.filter { it.height > 0 }
            .sortedWith(
                compareByDescending<YtFormat> { it.height }
                    .thenByDescending { !it.isVideoOnly }
                    .thenByDescending { it.ext == "mp4" }
            )
            .firstOrNull()

    /**
     * Best-effort size estimate for a quality: best video at/below the
     * height plus best audio. Returns 0 when sizes are unknown.
     */
    fun estimatedSizeForHeight(formats: List<YtFormat>, height: Int): Long {
        val video = formats.filter { it.height > 0 && it.height <= height }
            .maxByOrNull { it.height }
        val audio = formats.filter { it.isAudioOnly }.maxByOrNull { it.fileSize }
        val videoSize = video?.fileSize ?: 0L
        val audioSize = if (video?.isVideoOnly == true) audio?.fileSize ?: 0L else 0L
        return videoSize + audioSize
    }

    /**
     * yt-dlp extractor args for a URL, or null when none are needed.
     *
     * YouTube now forces SABR streaming on its web client, which makes
     * the bundled yt-dlp's web-client formats lose their URLs and fail
     * with HTTP 403 (yt-dlp issue #12482). The Android player client is
     * not forced into SABR, so asking for `android,web` gives working
     * formats again. Only YouTube-family URLs get this flag; every other
     * site is left untouched.
     */
    fun extractorArgsFor(url: String): String? {
        val lower = url.lowercase()
        return if (lower.contains("youtube.com") || lower.contains("youtu.be")) {
            "youtube:player_client=android,web"
        } else {
            null
        }
    }
}
