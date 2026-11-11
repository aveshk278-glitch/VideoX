package com.videox.app.download

import android.content.Context
import android.os.Environment
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import java.io.File

/**
 * yt-dlp engine, via the youtubedl-android library (the library Seal uses).
 *
 * Honest limits, by design:
 * - Only downloads what yt-dlp can technically fetch from a public URL.
 * - No DRM, paywall, login or platform-protection bypass. No cookies, no
 *   credentials, no account login. If yt-dlp reports an error (for example
 *   a site blocking the request), the error is shown to the user as-is.
 * - Direct MP4/WebM links do NOT go through this engine — they use the
 *   fast DownloadManager path in DownloadManagerHelper instead.
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
        return status.name
    }

    fun sanitizeFileName(name: String): String {
        val cleaned = name.trim().replace(Regex("[^A-Za-z0-9._\\- ]"), "_").trim()
        return if (cleaned.isEmpty()) "videox_${System.currentTimeMillis()}" else cleaned
    }

    /** Fetch title + available formats for a URL. Runs yt-dlp --dump-json. */
    fun getInfo(url: String): YtVideoInfo {
        if (!initialized) throw IllegalStateException(initError ?: "yt-dlp is not initialised yet.")
        val info = YoutubeDL.getInstance().getInfo(url)
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
                fileSize = f.fileSize,
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
     * [onProgress] receives percent (0-100) and ETA seconds.
     * Returns the YoutubeDLResponse when the process finishes.
     * Cancel with [cancel] using the same [processId].
     */
    fun download(
        url: String,
        formatSelector: String?,
        audioOnly: Boolean,
        audioFormat: String,
        customFileName: String?,
        processId: String,
        onProgress: (Float, Long) -> Unit
    ): YoutubeDLResponse {
        if (!initialized) throw IllegalStateException(initError ?: "yt-dlp is not initialised yet.")
        val dir = downloadDir()
        val baseName = if (customFileName.isNullOrBlank()) "%(title)s" else sanitizeFileName(customFileName)
        val request = YoutubeDLRequest(url)
        // Output template: custom name (or title) + real extension chosen by yt-dlp.
        request.addOption("-o", File(dir, "$baseName.%(ext)s").absolutePath)
        // Never overwrite without asking: yt-dlp resumes partial files by default.
        request.addOption("--no-overwrites")
        // YouTube SABR workaround (yt-dlp #12482): the Android player
        // client is not forced into SABR, so its formats keep real URLs.
        QualitySelector.extractorArgsFor(url)?.let {
            request.addOption("--extractor-args", it)
        }
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
