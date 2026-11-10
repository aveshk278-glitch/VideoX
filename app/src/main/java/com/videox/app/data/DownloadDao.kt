package com.videox.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun getAll(): Flow<List<DownloadItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: DownloadItem): Long

    @Update
    suspend fun update(item: DownloadItem)

    @Query(
        "UPDATE downloads SET status = :status, progress = :progress, " +
            "downloadedBytes = :downloaded, totalBytes = :total, errorMessage = :error " +
            "WHERE downloadId = :downloadId"
    )
    suspend fun updateProgress(
        downloadId: Long,
        status: String,
        progress: Int,
        downloaded: Long,
        total: Long,
        error: String?
    )

    @Query("SELECT * FROM downloads WHERE downloadId = :downloadId LIMIT 1")
    suspend fun getByDownloadId(downloadId: Long): DownloadItem?

    @Query("SELECT * FROM downloads WHERE status IN ('QUEUED', 'RUNNING', 'PAUSED')")
    suspend fun getActive(): List<DownloadItem>

    @Query("DELETE FROM downloads")
    suspend fun clearAll()
}
