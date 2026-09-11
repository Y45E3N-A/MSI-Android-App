package com.example.msiandroidapp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.content.Context
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import android.util.Log
import com.example.msiandroidapp.R
import java.io.File

class UploadForegroundService : Service() {

    private var uploadServer: UploadServer? = null
    private val PORT = 8080
    private val handler = Handler(Looper.getMainLooper())
    private val monitor = object : Runnable {
        override fun run() {
            ensureServerRunning()
            handler.postDelayed(this, 3000)
        }
    }

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
        handler.removeCallbacks(monitor)
        monitor.run()
        return START_STICKY
    }

    private fun ensureServerRunning() {
        // 3. Now do the heavy work (safe to retry if service restarts)
        try {
            if (uploadServer?.isAlive != true) {
                receiverReady = false
                uploadServer?.shutdown()
                uploadServer = null
                val baseDir = getExternalFilesDir(null)!!
                val storageDir = File(baseDir, "Sessions").apply { mkdirs() }

                uploadServer = UploadServer(PORT, storageDir, this)
                uploadServer!!.start(SOCKET_READ_TIMEOUT, false)
                check(uploadServer!!.isAlive) { "Upload receiver did not start" }

                Log.i(TAG, "UploadServer listening on :$PORT at ${storageDir.absolutePath}")
            }
            receiverReady = true
        } catch (t: Throwable) {
            receiverReady = false
            runCatching { uploadServer?.shutdown() }
            uploadServer = null
            Log.e(TAG, "Failed to start UploadServer", t)
        }
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    override fun onDestroy() {
        handler.removeCallbacks(monitor)
        receiverReady = false
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
            .setContentText(if (receiverReady) "Ready to receive captures" else "Starting upload receiver; retrying if needed")
            .setOngoing(true)
            .build()
    }

    companion object {
        @Volatile private var receiverReady = false

        suspend fun awaitReady(context: Context) {
            receiverReady = false
            ContextCompat.startForegroundService(context, Intent(context, UploadForegroundService::class.java))
            repeat(40) {
                if (receiverReady) return
                delay(200)
            }
            error("Phone upload receiver is unavailable. Reopen the app and try again.")
        }
        private const val TAG = "UploadForegroundService"
        private const val CHANNEL_ID = "UploadServerChannel"
        private const val NOTIF_ID = 101
        private const val SOCKET_READ_TIMEOUT = 600_000 // ms; allow large AMSI/PMFI ZIP uploads
    }
}
