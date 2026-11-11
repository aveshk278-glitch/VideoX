package com.videox.app.download

import android.content.Context
import android.os.Environment
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import com.yausername.youtubedl_android.mapper.VideoInfo
import java.io.File

/**
 * yt-dlp engine, via the youtubedl-android library (the library Seal uses).
 *
 * What it handles: any public video page yt-dlp supports (1000+ sites:
 * YouTube, Vimeo, Dailymotion, Reddit, Pinterest, X, Facebook, TikTok,
 * web pages with embedded video via the generic/HTML5 extractors, and
 * direct media files), plus direct MP4/WebM links through the generic
 * extractor. Direct links also have a fast DownloadManager path — see
 * [DownloadManagerHelper]; the engine is the fallback for links the
 * fast path cannot recognise (no file extension, redirects, pages).
 *
 * Honest limits, by design:
 * - Only downloads what yt-dlp can technically fetch from a public URL.
 * - No DRM, paywall, login or platform-protection bypass. No cookies, no
 *   credentials, no account login. If yt-dlp reports an error (for example
 *   a site blocking the request), the error is shown to the user as-is.
 */
data class YtFormat(
    val formatId: String,
    val ext: String,
    val resolution: String,
    val height: Int,
    val label: String,
    val fileSize: Long,
    val isAudioOnly: Boolean,
    val isVideoOnly: Boolean,
    val vcodec: String,
    val acodec: String
)

data class YtVideoInfo(
    val title: String,
    val uploader: String,
    val durationSeconds: Long,
    val formats: List<YtFormat>
)

object YtDlpEngine {

    @Volatile
    var initialized: Boolean = false
        private set

    @Volatile
    var initError: String? = null
        private set

    /** Initialise yt-dlp + FFmpeg once, at app start. Safe to call repeatedly. */
    fun init(context: Context) {
        if (initialized) return
        try {
            YoutubeDL.getInstance().init(context.applicationContext)
            FFmpeg.getInstance().init(context.applicationContext)
            initialized = true
            initError = null
        } catch (e: Exception) {
            initialized = false
            initError = e.message ?: "yt-dlp failed to initialise."
        }
    }

    fun downloadDir(): File {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(downloads, DownloadManagerHelper.DOWNLOAD_FOLDER).apply { mkdirs() }
    }

    /** Current bundled yt-dlp version (e.g. "2025.11.12"), or null if unknown. */
    fun ytDlpVersion(context: Context): String? {
        return try {
            YoutubeDL.getInstance().version(context)?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Download the latest stable yt-dlp from GitHub (the library's
     * built-in self-update) and switch the engine to it.
     * Returns "DONE" when a newer version was installed,
     * "ALREADY_UP_TO_DATE" when there was nothing newer.
     * Throws on network failure. Safe to call repeatedly; call it on a
     * background thread — it downloads several MB on first update.
     */
    fun updateEngine(context: Context): String {
        val status = YoutubeDL.getInstance().updateYoutubeDL(context)
        return status?.name ?: "UNKNOWN"
    }

    /** Fetch title + available formats for a URL. Runs yt-dlp --dump-json. */
    fun getInfo(url: String, proxyUrl: String? = null): YtVideoInfo {
        if (!initialized) throw IllegalStateException(initError ?: "yt-dlp is not initialised yet.")
        val request = YoutubeDLRequest(url)
        proxyUrl?.takeIf { it.isNotBlank() }?.let { request.addOption("--proxy", it) }
        return mapVideoInfo(YoutubeDL.getInstance().getInfo(request), url)
    }

    /**
     * Same as [getInfo] but with yt-dlp extractor args (e.g. the
     * Android-player-client fallback for YouTube). Uses the
     * getInfo(YoutubeDLRequest) overload — verified to exist in
     * youtubedl-android 0.18.1 via javap on the real AAR.
     */
    fun getInfoWithArgs(url: String, extractorArgs: String, proxyUrl: String? = null): YtVideoInfo {
        if (!initialized) throw IllegalStateException(initError ?: "yt-dlp is not initialised yet.")
        val request = YoutubeDLRequest(url)
        request.addOption("--extractor-args", extractorArgs)
        proxyUrl?.takeIf { it.isNotBlank() }?.let { request.addOption("--proxy", it) }
        return mapVideoInfo(YoutubeDL.getInstance().getInfo(request), url)
    }

    /**
     * Check whether the Android-player-client (360p) fallback would
     * download this video, WITHOUT downloading anything: runs yt-dlp in
     * simulate mode. True means the user can be offered the 360p
     * download as an explicit choice; false means there is no fallback.
     */
    fun probeFallback(url: String, proxyUrl: String? = null): Boolean {
        if (!initialized) return false
        return try {
            val request = YoutubeDLRequest(url)
            request.addOption("--simulate")
            request.addOption("-f", QualitySelector.ANDROID_FALLBACK_SELECTOR)
            request.addOption("--extractor-args", QualitySelector.ANDROID_FALLBACK_ARGS)
            proxyUrl?.takeIf { it.isNotBlank() }?.let { request.addOption("--proxy", it) }
            YoutubeDL.getInstance().execute(request, "videox_probe_${System.currentTimeMillis()}")
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun mapVideoInfo(info: VideoInfo, url: String): YtVideoInfo {
        val formats = (info.formats ?: emptyList()).mapNotNull { f ->
            val formatId = f.formatId ?: return@mapNotNull null
            val ext = f.ext ?: ""
            val width = f.width
            val height = f.height
            val resolution = if (width > 0 && height > 0) "${width}x$height" else ""
            val vcodec = f.vcodec ?: ""
            val acodec = f.acodec ?: ""
            val isAudioOnly = vcodec == "none" && acodec != "none"
            val isVideoOnly = acodec == "none" && vcodec != "none"
            val label = buildString {
                append(if (isAudioOnly) "Audio" else if (isVideoOnly) "Video only" else "Video")
                if (resolution.isNotEmpty()) append(" • $resolution")
                if (ext.isNotEmpty()) append(" • $ext")
                val note = f.formatNote
                if (!note.isNullOrBlank()) append(" • $note")
            }
            YtFormat(
                formatId = formatId,
                ext = ext,
                resolution = resolution,
                height = height,
                label = label,
                // YouTube often reports no exact filesize for DASH formats
                // but does give an approximate one — use it, so quality
                // sizes are honest instead of showing 0 (verified:
                // VideoFormat has getFileSizeApproximate() in the real
                // 0.18.1 AAR).
                fileSize = if (f.fileSize > 0) f.fileSize else f.fileSizeApproximate,
                isAudioOnly = isAudioOnly,
                isVideoOnly = isVideoOnly,
                vcodec = vcodec,
                acodec = acodec
            )
        }
        // Highest resolution first, so the best quality is never buried
        // below the low-quality combined formats.
        val sorted = formats.sortedWith(
            compareByDescending<YtFormat> { it.height }
                .thenByDescending { !it.isVideoOnly }
        )
        return YtVideoInfo(
            title = info.title ?: url,
            uploader = info.uploader ?: "",
            durationSeconds = info.duration.toLong(),
            formats = sorted
        )
    }

    /**
     * Download with yt-dlp. [formatSelector] is a yt-dlp format string
     * built by [QualitySelector] (e.g. "bestvideo+bestaudio/best" or
     * "bestvideo[height<=1080]+bestaudio/best"). It always pairs video
     * with the best audio stream so the merged file has sound — a raw
     * video-only format id must NEVER be passed here on its own.
     * [proxyUrl] is the user's proxy setting, already validated by the
     * caller (blank means no proxy). [extraOptions] are the subtitle
     * and chapter options built by [QualitySelector.extraDownloadOptions].
     * [onProgress] receives percent (0-100) and ETA seconds.
     * Returns the YoutubeDLResponse when the process finishes.
     * Cancel with [cancel] using the same [processId].
     */
    fun download(
        url: String,
        formatSelector: String?,
        audioOnly: Boolean,
        audioFormat: String,
        outputBaseName: String,
        extractorArgs: String?,
        processId: String,
        proxyUrl: String? = null,
        extraOptions: List<Pair<String, String?>> = emptyList(),
        onProgress: (Float, Long) -> Unit
    ): YoutubeDLResponse {
        if (!initialized) throw IllegalStateException(initError ?: "yt-dlp is not initialised yet.")
        val dir = downloadDir()
        val request = YoutubeDLRequest(url)
        // Output template: the EXACT base name the app expects (already
        // sanitised by the caller). Never %(title)s — yt-dlp would write
        // the raw title and the app would look for a different name.
        request.addOption("-o", File(dir, "$outputBaseName.%(ext)s").absolutePath)
        // Never overwrite without asking: yt-dlp resumes partial files by default.
        request.addOption("--no-overwrites")
        // User's proxy, when one is set in Settings. The caller only
        // passes a valid proxy URL, so this never breaks downloads.
        proxyUrl?.takeIf { it.isNotBlank() }?.let { request.addOption("--proxy", it) }
        // Extractor args override yt-dlp's default YouTube clients — only
        // pass them for the last-resort (Android-only, 360p) attempt.
        // Null means "use yt-dlp's defaults" (android, web_safari, web),
        // which is what selects full quality. NEVER force
        // player_client=android,web here: it drops web_safari and the
        // download collapses to 360p (verified, v2.2/v2.3 bug).
        extractorArgs?.let { request.addOption("--extractor-args", it) }
        if (audioOnly) {
            request.addOption("-f", "bestaudio/best")
            request.addOption("--extract-audio")
            request.addOption("--audio-format", audioFormat)
        } else {
            // Video + best audio, merged by FFmpeg. Never a bare video-only id.
            request.addOption("-f", formatSelector ?: "bestvideo+bestaudio/best")
        }
        // merge-output-format needs FFmpeg (bundled ffmpeg module).
        request.addOption("--merge-output-format", "mp4")
        // Subtitle and chapter options from Settings. A null value is a
        // flag-only option (e.g. --embed-subs); otherwise option + value.
        extraOptions.forEach { (option, value) ->
            if (value == null) request.addOption(option) else request.addOption(option, value)
        }
        // NOTE: in youtubedl-android 0.18.1 the signature is
        // execute(request, processId, callback) and the callback takes
        // (progressPercent, etaSeconds, statusText).
        return YoutubeDL.getInstance().execute(request, processId) { progress, etaInSeconds, _ ->
            onProgress(progress, etaInSeconds)
        }
    }

    fun cancel(processId: String) {
        try {
            YoutubeDL.getInstance().destroyProcessById(processId)
        } catch (_: Exception) {
            // Process already finished or never started — nothing to cancel.
        }
    }
}
