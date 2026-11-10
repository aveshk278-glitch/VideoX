package com.videox.app.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.URLUtil
import java.net.URL
import java.util.Locale

data class DownloadStatusInfo(
    val status: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val reason: Int
)

object DownloadManagerHelper {

    val SUPPORTED_EXTENSIONS = listOf("mp4", "webm", "mov", "m4v", "3gp")

    const val DOWNLOAD_FOLDER = "VideoX"

    /** True only for http(s) URLs whose path ends in a supported video extension. */
    fun isSupportedVideoUrl(rawUrl: String): Boolean {
        val url = rawUrl.trim()
        if (url.isEmpty()) return false
        if (!URLUtil.isValidUrl(url)) return false
        if (!url.startsWith("http://", ignoreCase = true) &&
            !url.startsWith("https://", ignoreCase = true)
        ) return false
        val path = try {
            URL(url).path.lowercase(Locale.ROOT)
        } catch (e: Exception) {
            url.substringBefore("?").lowercase(Locale.ROOT)
        }
        return SUPPORTED_EXTENSIONS.any { ext -> path.endsWith(".$ext") }
    }

    /** Human-readable validation error, or null when the URL looks usable. */
    fun validateUrl(rawUrl: String): String? {
        val url = rawUrl.trim()
        if (url.isEmpty()) return "Please enter a video URL."
        if (!URLUtil.isValidUrl(url)) return "That does not look like a valid URL. It should start with http:// or https://"
        if (!url.startsWith("http://", ignoreCase = true) &&
            !url.startsWith("https://", ignoreCase = true)
        ) return "Only http:// and https:// links are supported."
        if (!isSupportedVideoUrl(url)) {
            return "Only direct video file links are supported: MP4, WebM, MOV, M4V or 3GP. " +
                "This link does not end in one of those file types."
        }
        return null
    }

    fun fileNameFromUrl(rawUrl: String): String {
        val url = rawUrl.trim()
        val path = try {
            URL(url).path
        } catch (e: Exception) {
            url.substringBefore("?")
        }
        var name = path.substringAfterLast("/").substringBefore("?").trim()
        if (name.isEmpty() || !name.contains(".")) {
            name = "videox_${System.currentTimeMillis()}.mp4"
        }
        // DownloadManager / filesystem safety: strip characters that are not allowed.
        name = name.replace(Regex("[^A-Za-z0-9._\\- ]"), "_")
        return name
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
