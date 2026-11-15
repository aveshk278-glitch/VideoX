package com.videox.app.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.videox.app.R

/**
 * Foreground service that keeps an AI upscale job alive while the app
 * is in the background. It shows a low-priority "Upscaling…" notification
 * and stops itself when [stop] is called after the job ends. The work
 * itself runs in the UpscalerViewModel's coroutine, mirroring
 * DownloadService.
 */
class UpscaleService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val channelId = "videox_upscale"
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "VideoX upscaling",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("VideoX is upscaling")
            .setContentText("AI upscaling keeps running in the background.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 1002
    }
}
