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

    /**
     * Honest one-line summary of the best quality on offer. When the
     * source offers formats but no resolution is known (direct media
     * files, embedded page videos), that is "Original quality" — never
     * "No video formats found", which would be a lie about a file that
     * IS downloadable.
     */
    fun maxQualityLabel(formats: List<YtFormat>): String {
        val max = maxHeight(formats)
        if (max != null) return "Max available: ${qualityLabel(max)}"
        return if (formats.isNotEmpty()) "Original quality" else "No video formats found"
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

    /** True for YouTube watch / Shorts / youtu.be links. */
    fun isYouTubeUrl(rawUrl: String): Boolean {
        val lower = rawUrl.lowercase()
        return lower.contains("youtube.com") || lower.contains("youtu.be")
    }

    /** Direct media file extensions VideoX can save as-is (video + audio). */
    val DIRECT_MEDIA_EXTENSIONS = listOf(
        "mp4", "webm", "mov", "m4v", "3gp", "mkv", "ogv",
        "m4a", "mp3", "wav", "ogg", "flac"
    )

    /**
     * True when the URL path ends in a direct media extension
     * (query strings and fragments ignored, case-insensitive).
     * This is a FAST-PATH hint only — it never decides whether a link
     * is downloadable. Extension-less direct files are detected by
     * content-type probing instead, and every other link goes to yt-dlp.
     */
    fun hasDirectMediaExtension(rawUrl: String): Boolean {
        val path = rawUrl.trim().substringBefore("?").substringBefore("#").lowercase()
        return DIRECT_MEDIA_EXTENSIONS.any { path.endsWith(".$it") }
    }

    /** File extension for a media content-type, or null when unknown. */
    fun extensionForContentType(contentType: String?): String? {
        val ct = contentType?.trim()?.lowercase()?.substringBefore(";")?.trim() ?: return null
        return when (ct) {
            "video/mp4" -> "mp4"
            "video/webm" -> "webm"
            "video/quicktime" -> "mov"
            "video/x-matroska", "video/matroska" -> "mkv"
            "video/3gpp", "video/3gpp2" -> "3gp"
            "video/ogg", "video/ogv" -> "ogv"
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/mp4", "audio/x-m4a" -> "m4a"
            "audio/ogg" -> "ogg"
            "audio/wav", "audio/x-wav" -> "wav"
            "audio/flac", "audio/x-flac" -> "flac"
            "audio/webm" -> "webm"
            else -> null
        }
    }

    /**
     * The Android-player-client fallback, used ONLY when the user
     * explicitly chooses it after full quality was blocked. The Android
     * client offers only format 18 (360p, combined) for most videos —
     * verified against real yt-dlp — so this is honestly a 360p
     * fallback, never a silent downgrade.
     */
    const val ANDROID_FALLBACK_ARGS = "youtube:player_client=android"
    const val ANDROID_FALLBACK_SELECTOR = "best"

    /**
     * True when yt-dlp reports that the file is already on disk.
     * With "--no-overwrites" yt-dlp skips a complete file and prints
     * "[download] ... has already been downloaded" — the file is real
     * and finished, so that is a SUCCESS, not a failure. (v2.5 treated
     * it as a failure because no file changed during the attempt.)
     */
    fun isAlreadyDownloaded(output: String?): Boolean {
        return output?.contains("has already been downloaded", ignoreCase = true) == true
    }

    /**
     * Turn a raw yt-dlp / engine error into an accurate, user-facing
     * explanation. Never a generic "this link is not supported": each
     * known failure says what actually happened, and an unknown error
     * is shown as-is instead of being replaced by a restriction message.
     */
    fun describeYtDlpError(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return "yt-dlp reported an error."
        val lower = text.lowercase()
        return when {
            lower.contains("has already been downloaded") ->
                "This video is already downloaded — check the Downloads tab."
            lower.contains("not a bot") || lower.contains("sign in to confirm") ->
                "YouTube is asking for a bot check (sign-in) for this video. " +
                    "VideoX never signs in or uses your account, so this video can't be downloaded right now. " +
                    "Try again later, or try a different video."
            lower.contains("impersonat") ->
                "This site needs a browser check this app can't perform, so it can't be downloaded right now."
            lower.contains("logged-in") || lower.contains("login required") ||
                lower.contains("log in to") || lower.contains("private video") ||
                lower.contains("this video is private") ->
                "This video needs a login or is private. VideoX never asks for your password or account, " +
                    "so it can't download this one."
            lower.contains("403") ->
                "The site refused this request (HTTP 403). YouTube changes its delivery often — " +
                    "updating the yt-dlp engine in Settings sometimes fixes it. Try again later if it persists."
            lower.contains("unsupported url") ->
                "This link isn't a single video page VideoX recognises. Copy the link to the video itself " +
                    "(not a channel, playlist or search page) and try again."
            lower.contains("video unavailable") || lower.contains("has been removed") ||
                lower.contains("has been deleted") || lower.contains("404") ||
                lower.contains("not found") ->
                "This video is unavailable, removed or deleted."
            lower.contains("requested format is not available") || lower.contains("format is not available") ->
                "The quality you picked isn't available for this video. Pick another quality from the list."
            lower.contains("unable to download webpage") || lower.contains("name or service not known") ||
                lower.contains("connection") || lower.contains("timed out") ||
                lower.contains("network is unreachable") || lower.contains("no route to host") ->
                "Couldn't reach the site — check your internet connection and try again."
            lower.contains("no video formats") ->
                "No downloadable video was found on this page."
            else -> text
        }
    }

    /**
     * Download attempts for a video, in order. Each entry is the value
     * for yt-dlp's `--extractor-args`, or null for yt-dlp's DEFAULT
     * YouTube clients.
     *
     * Verified by running real yt-dlp 2025.11.12 against the user's own
     * video (2026-11-11):
     * - Default clients (android, web_safari, web) select 401+251 = 4K.
     * - Forcing `player_client=android,web` drops web_safari from the
     *   list; the web formats then have no URLs (SABR) and only format
     *   18 (360p) survives — that was the v2.2/v2.3 bug: 4K shown, 360p
     *   downloaded. The Android client on its own offers ONLY 360p.
     * So: default clients first (full quality), Android client only as
     * an EXPLICIT last resort the user chooses (360p, honestly labelled).
     */
    fun downloadAttempts(url: String): List<String?> {
        return if (isYouTubeUrl(url)) {
            listOf(
                null, // yt-dlp defaults: android, web_safari, web — full quality
                ANDROID_FALLBACK_ARGS // explicit fallback only: 360p
            )
        } else {
            listOf(null) // other sites: one attempt, defaults only
        }
    }

    /**
     * Make a filename safe for disk: only ASCII letters, digits, dot,
     * dash, underscore and space survive; everything else becomes "_".
     * Used for BOTH the name the app expects AND the name yt-dlp is told
     * to write, so the two can never disagree (v2.2 bug: the app expected
     * a sanitised title while yt-dlp wrote the raw %(title)s, so a
     * downloaded file was reported as "failed" when its title contained
     * characters the two sides handled differently).
     */
    fun sanitizeFileName(name: String): String {
        val cleaned = name.trim().replace(Regex("[^A-Za-z0-9._\\- ]"), "_").trim()
        return if (cleaned.isEmpty()) "videox_${System.currentTimeMillis()}" else cleaned
    }

    /** The exact base name (no extension) the download will be saved as. */
    fun resolveOutputBaseName(title: String, customFileName: String?): String =
        if (customFileName.isNullOrBlank()) sanitizeFileName(title) else sanitizeFileName(customFileName)
}
