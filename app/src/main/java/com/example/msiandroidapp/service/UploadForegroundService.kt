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

        // Use app-scoped "Sessions" directory instead of public MSI_App
        val baseDir = getExternalFilesDir(null)!!
        val storageDir = File(baseDir, "Sessions").apply { mkdirs() }

        uploadServer = UploadServer(PORT, storageDir, this)
        uploadServer?.start(SOCKET_READ_TIMEOUT, false)

        startForeground(101, buildNotification())
        Log.i("UploadForegroundService", "UploadServer listening on :$PORT at ${storageDir.absolutePath}")
    }


    override fun onDestroy() {
        try { uploadServer?.shutdown() } catch (_: Exception) {}
        uploadServer = null
        Log.i("UploadForegroundService", "Service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "UploadServerChannel",
                "MSI Upload Server",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "UploadServerChannel")
        } else {
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle("MSI Upload Server")
            .setContentText("Listening for incoming files on port $PORT")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val SOCKET_READ_TIMEOUT = 30_000 // ms
    }
}
