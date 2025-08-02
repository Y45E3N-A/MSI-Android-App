package com.example.msiandroidapp.service

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.example.msiandroidapp.R
import java.io.File

class UploadForegroundService : Service() {

    private var uploadServer: UploadServer? = null
    private val PORT = 8080

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val storageDir = File(getExternalFilesDir(null), "MSI_Sessions")
        if (!storageDir.exists()) storageDir.mkdirs()



        // Pass galleryViewModel to UploadServer
        uploadServer = UploadServer(PORT, storageDir, this)
        uploadServer?.start()

        val notification = buildNotification()
        startForeground(101, notification)

        Log.i("UploadForegroundService", "Service started on port $PORT")
    }

    override fun onDestroy() {
        uploadServer?.stop()
        Log.i("UploadForegroundService", "Service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "UploadServerChannel",
                "Upload Server",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "UploadServerChannel")
        } else {
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("MSI Upload Server")
            .setContentText("Listening for incoming files on port $PORT")
            .setOngoing(true)
            .build()
    }
}
