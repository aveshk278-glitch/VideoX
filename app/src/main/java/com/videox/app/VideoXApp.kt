package com.videox.app

import android.app.Application
import com.videox.app.data.AppDatabase
import com.videox.app.data.DownloadRepository

class VideoXApp : Application() {

    val database: AppDatabase by lazy {
        AppDatabase.getInstance(this)
    }

    val repository: DownloadRepository by lazy {
        DownloadRepository(database.downloadDao(), this)
    }

    override fun onCreate() {
        super.onCreate()
    }
}
