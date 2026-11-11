package com.videox.app.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.URLUtil
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class DownloadStatusInfo(
    val status: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val reason: Int
)

/** What a content-type probe found: a real media file behind the URL. */
data class MediaProbe(
    val contentType: String,
    val fileName: String,
    val contentLength: Long
)

object DownloadManagerHelper {

    /** Direct media extensions for the fast path — see QualitySelector. */
    val SUPPORTED_EXTENSIONS: List<String> = QualitySelector.DIRECT_MEDIA_EXTENSIONS

    const val DOWNLOAD_FOLDER = "VideoX"

    /** True only for http(s) URLs whose path ends in a supported media extension. */
    fun isSupportedVideoUrl(rawUrl: String): Boolean {
        val url = rawUrl.trim()
        if (url.isEmpty()) return false
        if (!URLUtil.isValidUrl(url)) return false
        if (!url.startsWith("http://", ignoreCase = true) &&
            !url.startsWith("https://", ignoreCase = true)
        ) return false
        return QualitySelector.hasDirectMediaExtension(url)
    }

    /**
     * Human-readable validation error, or null when the URL looks usable.
     * This checks ONLY that the text is a usable http(s) URL. It never
     * rejects a link for its file extension or host: extension-less
     * direct files are detected by content-type probing, and every other
     * link is handed to yt-dlp, which supports 1000+ sites.
     */
    fun validateUrl(rawUrl: String): String? {
        val url = rawUrl.trim()
        if (url.isEmpty()) return "Please enter a video URL."
        if (!URLUtil.isValidUrl(url)) return "That does not look like a valid URL. It should start with http:// or https://"
        if (!url.startsWith("http://", ignoreCase = true) &&
            !url.startsWith("https://", ignoreCase = true)
        ) return "Only http:// and https:// links are supported."
        return null
    }

    /**
     * Ask the server what a URL actually is (HEAD, with a Range-GET
     * fallback for servers that refuse HEAD). Follows redirects.
     * Returns a probe only when the response is a real media file
     * (video/* or audio/*) — this is how extension-less direct links
     * (CDN / signed URLs) are detected. Returns null for web pages,
     * errors, or anything that is not media. Call on a background thread.
     */
    fun probeDirectMedia(rawUrl: String): MediaProbe? {
        val url = rawUrl.trim()
        if (url.isEmpty()) return null
        if (!url.startsWith("http://", ignoreCase = true) &&
            !url.startsWith("https://", ignoreCase = true)
        ) return null
        return try {
            probeOnce(url, useRangeGet = false)
                ?: probeOnce(url, useRangeGet = true)
        } catch (_: Exception) {
            null
        }
    }

    private fun probeOnce(url: String, useRangeGet: Boolean): MediaProbe? {
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (_: Exception) {
            return null
        }
        conn.apply {
            requestMethod = if (useRangeGet) "GET" else "HEAD"
            if (useRangeGet) setRequestProperty("Range", "bytes=0-0")
            instanceFollowRedirects = true
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
            )
        }
        return try {
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299) return null
            parseProbe(url, conn)
        } catch (_: IOException) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun parseProbe(url: String, conn: HttpURLConnection): MediaProbe? {
        val contentType = conn.contentType?.substringBefore(";")?.trim()?.lowercase()
            ?: return null
        if (!contentType.startsWith("video/") && !contentType.startsWith("audio/")) return null
        val length = conn.contentLengthLong.takeIf { it > 0 } ?: 0L
        val name = fileNameFromProbe(url, conn.getHeaderField("Content-Disposition"), contentType)
        return MediaProbe(contentType, name, length)
    }

    private fun fileNameFromProbe(url: String, disposition: String?, contentType: String): String {
        // Content-Disposition: attachment; filename="x.mp4" or filename*=UTF-8''x.mp4
        val fromHeader = disposition?.let { d ->
            Regex("filename\\*?=(?:UTF-8''|\"?)([^\";]+)")
                .find(d)?.groupValues?.getOrNull(1)?.trim()?.trim('"')
                ?.takeIf { it.isNotEmpty() }
        }
        val fromPath = try {
            URL(url).path.substringAfterLast("/").substringBefore("?")
        } catch (_: Exception) {
            ""
        }
        val name = fromHeader
            ?: fromPath.takeIf { it.contains(".") }
            ?: "videox_${System.currentTimeMillis()}.${QualitySelector.extensionForContentType(contentType) ?: "mp4"}"
        return QualitySelector.sanitizeFileName(name)
    }

    fun fileNameFromUrl(rawUrl: String, contentType: String? = null): String {
        val url = rawUrl.trim()
        val path = try {
            URL(url).path
        } catch (e: Exception) {
            url.substringBefore("?")
        }
        var name = path.substringAfterLast("/").substringBefore("?").trim()
        if (name.isEmpty() || !name.contains(".")) {
            val ext = QualitySelector.extensionForContentType(contentType) ?: "mp4"
            name = "videox_${System.currentTimeMillis()}.$ext"
        }
        // DownloadManager / filesystem safety: strip characters that are not allowed.
        return QualitySelector.sanitizeFileName(name)
    }

    @Suppress("DEPRECATION")
    fun enqueue(
        context: Context,
        url: String,
        fileName: String,
        wifiOnly: Boolean
    ): Long {
        val request = DownloadManager.Request(Uri.parse(url.trim()))
            .setTitle(fileName)
            .setDescription("Downloading with VideoX")
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            .setAllowedOverRoaming(true)
            .setAllowedOverMetered(!wifiOnly)
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                "$DOWNLOAD_FOLDER/$fileName"
            )
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return manager.enqueue(request)
    }

    fun query(context: Context, downloadId: Long): DownloadStatusInfo? {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(downloadId)
        manager.query(query)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val bytesIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val totalIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val reasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
            if (statusIndex < 0 || bytesIndex < 0 || totalIndex < 0) return null
            return DownloadStatusInfo(
                status = cursor.getInt(statusIndex),
                downloadedBytes = cursor.getLong(bytesIndex),
                totalBytes = cursor.getLong(totalIndex),
                reason = if (reasonIndex >= 0) cursor.getInt(reasonIndex) else 0
            )
        }
        return null
    }

    fun openDownloadedFile(context: Context, downloadId: Long) {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = manager.getUriForDownloadedFile(downloadId) ?: return
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // No video player found — leave the file in Downloads/VideoX for the user.
        }
    }

    fun reasonText(reason: Int, status: Int): String {
        return when (status) {
            DownloadManager.STATUS_FAILED -> when (reason) {
                DownloadManager.ERROR_FILE_ERROR -> "Download failed: file error."
                DownloadManager.ERROR_HTTP_DATA_ERROR -> "Download failed: server error (HTTP)."
                DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Download failed: not enough storage space."
                DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "Download failed: too many redirects."
                DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "Download failed: server returned an unsupported response."
                DownloadManager.ERROR_UNKNOWN -> "Download failed: unknown error. Check your connection and try again."
                else -> "Download failed (code $reason). Check your connection and try again."
            }
            DownloadManager.STATUS_PAUSED -> when (reason) {
                DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Paused: waiting for Wi-Fi."
                DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Paused: waiting for network."
                DownloadManager.PAUSED_WAITING_TO_RETRY -> "Paused: will retry automatically."
                else -> "Paused."
            }
            else -> ""
        }
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return "—"
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            bytes >= gb -> String.format(Locale.US, "%.2f GB", bytes / gb)
            bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
            bytes >= kb -> String.format(Locale.US, "%.0f KB", bytes / kb)
            else -> "$bytes B"
        }
    }
}
