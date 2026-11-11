package com.videox.app.data

import android.app.DownloadManager
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.videox.app.download.DownloadManagerHelper
import kotlinx.coroutines.flow.Flow

class DownloadRepository(
    private val dao: DownloadDao,
    private val context: Context
) {

    val allDownloads: Flow<List<DownloadItem>> = dao.getAll()

    private val prefs: SharedPreferences =
        context.getSharedPreferences("videox_settings", Context.MODE_PRIVATE)

    var wifiOnly: Boolean
        get() = prefs.getBoolean("wifi_only", false)
        set(value) { prefs.edit().putBoolean("wifi_only", value).apply() }

    var showNotifications: Boolean
        get() = prefs.getBoolean("show_notifications", true)
        set(value) { prefs.edit().putBoolean("show_notifications", value).apply() }

    var darkTheme: Boolean
        get() = prefs.getBoolean("dark_theme", true)
        set(value) { prefs.edit().putBoolean("dark_theme", value).apply() }

    var maxConcurrentDownloads: Int
        get() = prefs.getInt("max_concurrent", 2).coerceIn(1, 3)
        set(value) { prefs.edit().putInt("max_concurrent", value.coerceIn(1, 3)).apply() }

    fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Validates, enqueues with DownloadManager and records the download in history.
     * [fileName] overrides the name derived from the URL (used when a
     * content-type probe found the real filename). Returns the DownloadManager id on success.
     */
    suspend fun startDownload(rawUrl: String, fileName: String? = null): Result<Long> {
        val validationError = DownloadManagerHelper.validateUrl(rawUrl)
        if (validationError != null) return Result.failure(IllegalArgumentException(validationError))
        if (!isOnline()) {
            return Result.failure(IllegalStateException("No internet connection. Check your connection and try again."))
        }
        val url = rawUrl.trim()
        val name = fileName ?: DownloadManagerHelper.fileNameFromUrl(url)
        return try {
            val downloadId = DownloadManagerHelper.enqueue(context, url, name, wifiOnly)
            val item = DownloadItem(
                url = url,
                fileName = name,
                status = "QUEUED",
                progress = 0,
                totalBytes = 0L,
                downloadedBytes = 0L,
                downloadId = downloadId
            )
            dao.insert(item)
            Result.success(downloadId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Polls DownloadManager for every active download and writes progress/status to Room. */
    suspend fun refreshStatuses() {
        val active = dao.getActive()
        for (item in active) {
            if (item.downloadId < 0) continue
            val info = try {
                DownloadManagerHelper.query(context, item.downloadId)
            } catch (e: Exception) {
                null
            } ?: continue

            val (statusText, progress, error) = when (info.status) {
                DownloadManager.STATUS_SUCCESSFUL -> Triple("COMPLETED", 100, null as String?)
                DownloadManager.STATUS_FAILED -> Triple(
                    "FAILED",
                    if (info.totalBytes > 0) ((info.downloadedBytes * 100) / info.totalBytes).toInt() else 0,
                    DownloadManagerHelper.reasonText(info.reason, info.status)
                )
                DownloadManager.STATUS_PAUSED -> Triple(
                    "PAUSED",
                    if (info.totalBytes > 0) ((info.downloadedBytes * 100) / info.totalBytes).toInt() else 0,
                    DownloadManagerHelper.reasonText(info.reason, info.status)
                )
                DownloadManager.STATUS_RUNNING -> Triple(
                    "RUNNING",
                    if (info.totalBytes > 0) ((info.downloadedBytes * 100) / info.totalBytes).toInt() else 0,
                    null as String?
                )
                DownloadManager.STATUS_PENDING -> Triple("QUEUED", 0, null as String?)
                else -> Triple(item.status, item.progress, item.errorMessage)
            }
            dao.updateProgress(item.downloadId, statusText, progress, info.downloadedBytes, info.totalBytes, error)
        }
    }

    suspend fun clearHistory() {
        dao.clearAll()
    }

    fun openDownload(downloadId: Long) {
        DownloadManagerHelper.openDownloadedFile(context, downloadId)
    }

    // ---- yt-dlp downloads (Room rows updated by the ViewModel as progress arrives) ----

    suspend fun insertYtDownload(item: DownloadItem): Long = dao.insert(item)

    suspend fun updateYtProgress(id: Long, progress: Int, downloadedBytes: Long, totalBytes: Long) {
        dao.updateProgressById(id, "RUNNING", progress, downloadedBytes, totalBytes, null)
    }

    suspend fun markYtStatus(id: Long, status: String, error: String?) {
        dao.updateStatusById(id, status, if (status == "COMPLETED") 100 else 0, error)
    }

    /** Record the real filename after a download (extension may differ from the assumed one). */
    suspend fun updateYtFileName(id: Long, name: String) {
        dao.updateFileNameById(id, name)
    }

    /** Open a file downloaded by yt-dlp (or a direct download) from Downloads/VideoX. */
    fun openFile(fileName: String) {
        val file = java.io.File(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
            "${DownloadManagerHelper.DOWNLOAD_FOLDER}/$fileName"
        )
        if (!file.exists()) return
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val mime = when (file.extension.lowercase()) {
            "mp3", "m4a", "opus", "flac", "wav", "ogg" -> "audio/*"
            else -> "video/*"
        }
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** Share a downloaded file with other apps. */
    fun shareFile(fileName: String) {
        val file = java.io.File(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
            "${DownloadManagerHelper.DOWNLOAD_FOLDER}/$fileName"
        )
        if (!file.exists()) return
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val mime = when (file.extension.lowercase()) {
            "mp3", "m4a", "opus", "flac", "wav", "ogg" -> "audio/*"
            else -> "video/*"
        }
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = mime
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(
            android.content.Intent.createChooser(intent, "Share $fileName")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
