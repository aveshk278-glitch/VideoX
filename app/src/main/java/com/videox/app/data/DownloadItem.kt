package com.videox.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row in the download history.
 *
 * status is one of: QUEUED, RUNNING, PAUSED, COMPLETED, FAILED
 */
@Entity(tableName = "downloads")
data class DownloadItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val url: String,
    val fileName: String,
    val status: String,
    val progress: Int = 0,
    val totalBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val downloadId: Long = -1L,
    val createdAt: Long = System.currentTimeMillis(),
    val errorMessage: String? = null,
    /** VIDEO, AUDIO or TEXT (extracted subtitles). */
    val mode: String = "VIDEO",
    /** Human-readable format label chosen by the user, e.g. "Video • 720p • mp4". */
    val formatLabel: String? = null,
    /** yt-dlp process id, used to cancel a running download. Null for direct downloads. */
    val processId: String? = null,
    /** "DIRECT" (DownloadManager) or "YTDLP". */
    val engine: String = "DIRECT"
) {
    val isRunning: Boolean
        get() = status == "QUEUED" || status == "RUNNING" || status == "PAUSED"

    val isCompleted: Boolean
        get() = status == "COMPLETED"

    val isFailed: Boolean
        get() = status == "FAILED"
}
