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
            // Best-effort self-update: YouTube changes often break old
            // yt-dlp versions (e.g. SABR 403s). If a newer stable yt-dlp
            // exists on GitHub, switch to it now so the first download
            // already uses it. Any failure here is silent — the bundled
            // version keeps working, and Settings has a manual update.
            try {
                YtDlpEngine.updateEngine(this@VideoXApp)
            } catch (_: Exception) {
                // No network or update failed — ignore, keep bundled version.
            }
        }
    }
}
