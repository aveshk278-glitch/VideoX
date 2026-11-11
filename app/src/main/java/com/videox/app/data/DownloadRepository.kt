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

    // ---- v2.9 settings ----

    /** Warn before downloading on a metered connection (mobile data). */
    var warnMetered: Boolean
        get() = prefs.getBoolean("warn_metered", true)
        set(value) { prefs.edit().putBoolean("warn_metered", value).apply() }

    /** Optional proxy URL for yt-dlp (empty = direct). Only a valid URL is ever used. */
    var proxyUrl: String
        get() = prefs.getString("proxy_url", "") ?: ""
        set(value) { prefs.edit().putString("proxy_url", value).apply() }

    /** Embed subtitles into downloaded videos when the video has them. */
    var subtitlesEnabled: Boolean
        get() = prefs.getBoolean("subtitles_enabled", false)
        set(value) { prefs.edit().putBoolean("subtitles_enabled", value).apply() }

    /** Subtitle languages for yt-dlp, e.g. "en", "hi" or "all". */
    var subtitleLangs: String
        get() = prefs.getString("subtitle_langs", "en") ?: "en"
        set(value) { prefs.edit().putString("subtitle_langs", value).apply() }

    /** Embed chapter markers into downloaded files when the video has chapters. */
    var embedChapters: Boolean
        get() = prefs.getBoolean("embed_chapters", false)
        set(value) { prefs.edit().putBoolean("embed_chapters", value).apply() }

    /** Transcribe speech on the phone when a video has no subtitles to extract. */
    var transcribeFallback: Boolean
        get() = prefs.getBoolean("transcribe_fallback", true)
        set(value) { prefs.edit().putBoolean("transcribe_fallback", value).apply() }

    fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** True when the active network is metered (typically mobile data). */
    fun isNetworkMetered(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return try {
            cm.isActiveNetworkMetered
        } catch (_: Exception) {
            false
        }
    }

    // ---- Settings backup and restore (v2.9) ----

    private fun backupFile(): java.io.File {
        val dir = java.io.File(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
            DownloadManagerHelper.DOWNLOAD_FOLDER
        )
        dir.mkdirs()
        return java.io.File(dir, "videox-settings-backup.json")
    }

    /** Write all settings to Downloads/VideoX/videox-settings-backup.json. */
    fun backupSettings(): Result<java.io.File> {
        return try {
            val values = mapOf(
                "wifi_only" to wifiOnly.toString(),
                "show_notifications" to showNotifications.toString(),
                "dark_theme" to darkTheme.toString(),
                "max_concurrent" to maxConcurrentDownloads.toString(),
                "warn_metered" to warnMetered.toString(),
                "proxy_url" to proxyUrl,
                "subtitles_enabled" to subtitlesEnabled.toString(),
                "subtitle_langs" to subtitleLangs,
                "embed_chapters" to embedChapters.toString(),
                "transcribe_fallback" to transcribeFallback.toString()
            )
            val file = backupFile()
            file.writeText(com.videox.app.download.SettingsBackup.encode(values))
            Result.success(file)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Read the backup file and apply its settings. Every value is
     * validated before it is written: a damaged file restores nothing
     * and reports the failure, and an invalid value (bad proxy URL,
     * out-of-range number) is skipped instead of corrupting settings.
     * Returns the number of settings actually applied.
     */
    fun restoreSettings(): Result<Int> {
        return try {
            val file = backupFile()
            if (!file.exists()) {
                return Result.failure(
                    IllegalStateException("No backup file found in Downloads/VideoX. Back up your settings first.")
                )
            }
            val values = com.videox.app.download.SettingsBackup.decode(file.readText())
                ?: return Result.failure(
                    IllegalStateException("The backup file is damaged and could not be read. Nothing was changed.")
                )
            var applied = 0
            values["wifi_only"]?.toBooleanStrictOrNull()?.let { wifiOnly = it; applied++ }
            values["show_notifications"]?.toBooleanStrictOrNull()?.let { showNotifications = it; applied++ }
            values["dark_theme"]?.toBooleanStrictOrNull()?.let { darkTheme = it; applied++ }
            values["max_concurrent"]?.toIntOrNull()?.let {
                maxConcurrentDownloads = it
                applied++
            }
            values["warn_metered"]?.toBooleanStrictOrNull()?.let { warnMetered = it; applied++ }
            values["proxy_url"]?.let { url ->
                if (url.isBlank() || com.videox.app.download.QualitySelector.isValidProxyUrl(url)) {
                    proxyUrl = url
                    applied++
                }
            }
            values["subtitles_enabled"]?.toBooleanStrictOrNull()?.let { subtitlesEnabled = it; applied++ }
            values["subtitle_langs"]?.let {
                subtitleLangs = com.videox.app.download.QualitySelector.sanitizeSubtitleLangs(it)
                applied++
            }
            values["embed_chapters"]?.toBooleanStrictOrNull()?.let { embedChapters = it; applied++ }
            values["transcribe_fallback"]?.toBooleanStrictOrNull()?.let { transcribeFallback = it; applied++ }
            Result.success(applied)
        } catch (e: Exception) {
            Result.failure(e)
        }
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
            "txt" -> "text/plain"
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
            "txt" -> "text/plain"
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
