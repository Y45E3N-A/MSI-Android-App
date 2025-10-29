package com.example.msiandroidapp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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

        // 1. Channel first
        createNotificationChannel()

        // 2. Immediately promote to foreground BEFORE doing any work
        val notif = buildNotification()
        startForeground(NOTIF_ID, notif)

        Log.i(TAG, "Service created, foreground started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 3. Now do the heavy work (safe to retry if service restarts)
        try {
            if (uploadServer == null) {
                val baseDir = getExternalFilesDir(null)!!
                val storageDir = File(baseDir, "Sessions").apply { mkdirs() }

                uploadServer = UploadServer(PORT, storageDir, this)
                uploadServer?.start(SOCKET_READ_TIMEOUT, false)

                Log.i(TAG, "UploadServer listening on :$PORT at ${storageDir.absolutePath}")
            } else {
                Log.i(TAG, "UploadServer already running")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start UploadServer", t)
            // If something goes wrong, don't crash the whole app:
            // we stay alive as a foreground service with a notification.
        }

        // 4. Sticky so Android can try to recreate if killed
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            uploadServer?.shutdown()
        } catch (_: Exception) { }
        uploadServer = null
        Log.i(TAG, "Service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MSI Upload Server",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }

        return builder
            .setSmallIcon(R.drawable.ic_stat_name) // make sure this icon exists in mipmap/drawable
            .setContentTitle("MSI Upload Server")
            .setContentText("Listening for incoming files on port $PORT")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "UploadForegroundService"
        private const val CHANNEL_ID = "UploadServerChannel"
        private const val NOTIF_ID = 101
        private const val SOCKET_READ_TIMEOUT = 30_000 // ms
    }
}
