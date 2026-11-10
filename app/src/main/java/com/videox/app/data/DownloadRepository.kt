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

    fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Validates, enqueues with DownloadManager and records the download in history.
     * Returns the DownloadManager id on success.
     */
    suspend fun startDownload(rawUrl: String): Result<Long> {
        val validationError = DownloadManagerHelper.validateUrl(rawUrl)
        if (validationError != null) return Result.failure(IllegalArgumentException(validationError))
        if (!isOnline()) {
            return Result.failure(IllegalStateException("No internet connection. Check your connection and try again."))
        }
        val url = rawUrl.trim()
        val fileName = DownloadManagerHelper.fileNameFromUrl(url)
        return try {
            val downloadId = DownloadManagerHelper.enqueue(context, url, fileName, wifiOnly)
            val item = DownloadItem(
                url = url,
                fileName = fileName,
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
}
