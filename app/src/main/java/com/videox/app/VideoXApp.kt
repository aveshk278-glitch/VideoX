package com.videox.app

import android.app.Application
import com.videox.app.data.AppDatabase
import com.videox.app.data.DownloadRepository
import com.videox.app.download.YtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class VideoXApp : Application() {

    val database: AppDatabase by lazy {
        AppDatabase.getInstance(this)
    }

    val repository: DownloadRepository by lazy {
        DownloadRepository(database.downloadDao(), this)
    }

    override fun onCreate() {
        super.onCreate()
        // yt-dlp + FFmpeg take a few seconds to extract on first run — do it off the main thread.
        CoroutineScope(Dispatchers.IO).launch {
            YtDlpEngine.init(this@VideoXApp)
        }
    }
}
