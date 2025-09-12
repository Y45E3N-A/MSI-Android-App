package com.example.msiandroidapp.service

import android.content.Context
import android.location.Geocoder
import android.util.Log
import com.example.msiandroidapp.data.AppDatabase
import com.example.msiandroidapp.data.Session
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import com.google.android.gms.location.LocationServices
import com.example.msiandroidapp.util.UploadProgressBus

class UploadServer(
    private val port: Int,
    private val storageDir: File,
    private val context: Context
) : NanoHTTPD(port) {

    private val sessionUploads = mutableMapOf<String, MutableList<String>>() // sessionId -> image paths
    private val sessionTimestamps = mutableMapOf<String, Long>()
    private val sessionInserted = mutableSetOf<String>() // Track completed sessions (avoid duplicates)
    private val imagesPerSession = 16
    private val sessionTimeoutMillis = 10 * 60 * 1000 // 10 minutes

    override fun serve(session: IHTTPSession): Response {
        return when (session.method) {
            Method.POST -> handlePost(session)
            else -> newFixedLengthResponse(
                Response.Status.METHOD_NOT_ALLOWED,
                MIME_PLAINTEXT,
                "Only POST allowed"
            )
        }
    }

    private fun handlePost(session: IHTTPSession): Response {
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)

            // Always use Pi-provided sessionId! (e.g. ?sessionId=123456 or header x-session-id)
            val sessionId = session.parms["sessionId"]
                ?: session.headers["x-session-id"]
                ?: run {
                    Log.e("UploadServer", "No sessionId provided! Rejecting upload.")
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "No sessionId provided")
                }

            val filename = session.headers["content-disposition"]?.let { extractFilename(it) }
                ?: "upload_${System.currentTimeMillis()}.png"

            val fileKey = files.values.firstOrNull()
            if (fileKey == null) {
                Log.e("UploadServer", "No file received")
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "No file received")
            }

            val uploadedFile = File(fileKey)
            val targetFile = File(storageDir, filename)
            uploadedFile.copyTo(targetFile, overwrite = true)
            uploadedFile.delete()

            val now = System.currentTimeMillis()

            // Track file and timestamp
            val imagePaths = sessionUploads.getOrPut(sessionId) { mutableListOf() }
            imagePaths.add(targetFile.absolutePath)
            sessionTimestamps[sessionId] = now

            Log.i("UploadServer", "Saved: ${targetFile.absolutePath} (session: $sessionId, count: ${imagePaths.size})")

            // ------- REPORT PROGRESS TO UI -------
            UploadProgressBus.uploadProgress.postValue(Pair(sessionId, imagePaths.size))
            // --------------------------------------

            // Clean up old sessions
            cleanupOldSessions(now)

            // Only insert session ONCE
            if (imagePaths.size == imagesPerSession && !sessionInserted.contains(sessionId)) {
                createAndInsertSession(sessionId, imagePaths.toList())
                sessionInserted.add(sessionId)
                sessionUploads.remove(sessionId)
                sessionTimestamps.remove(sessionId)
            }

            newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "File saved: ${targetFile.name}")
        } catch (e: Exception) {
            Log.e("UploadServer", "Upload error: ${e.message}", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    private fun extractFilename(contentDisposition: String): String? {
        val parts = contentDisposition.split(";")
        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.startsWith("filename=")) {
                return trimmed.substringAfter("filename=").trim('"')
            }
        }
        return null
    }

    // Remove old session batches after timeout to avoid memory leaks and confusion
    private fun cleanupOldSessions(now: Long) {
        val iterator = sessionTimestamps.iterator()
        while (iterator.hasNext()) {
            val (id, timestamp) = iterator.next()
            if (now - timestamp > sessionTimeoutMillis) {
                Log.i("UploadServer", "Session $id timed out and is being cleared.")
                sessionUploads.remove(id)
                iterator.remove()
                sessionInserted.remove(id)
            }
        }
    }

    private fun createAndInsertSession(sessionId: String, imagePaths: List<String>) {
        val timestamp = SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.getDefault()).format(Date())

        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                val locationStr = if (location != null) {
                    // Use Geocoder to reverse geocode coordinates into a place string
                    var placeString: String? = null
                    try {
                        val geocoder = Geocoder(context, Locale.getDefault())
                        val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                        if (!addresses.isNullOrEmpty()) {
                            val address = addresses[0]
                            val city = address.locality ?: ""
                            val area = address.subAdminArea ?: ""
                            val country = address.countryName ?: ""
                            placeString = listOf(city, area, country)
                                .filter { it.isNotBlank() }
                                .joinToString(", ")
                        }
                    } catch (e: Exception) {
                        Log.w("UploadServer", "Geocoder failed: ${e.message}")
                    }
                    placeString ?: "${location.latitude},${location.longitude}"
                } else {
                    "Unknown"
                }
                saveSessionToDb(timestamp, locationStr, imagePaths, sessionId)
            }.addOnFailureListener {
                saveSessionToDb(timestamp, "Unknown", imagePaths, sessionId)
            }

            // Fallback in case location doesn't return (after 2 seconds, insert anyway)
            GlobalScope.launch {
                delay(2000)
                if (!sessionInserted.contains(sessionId)) {
                    saveSessionToDb(timestamp, "Unknown", imagePaths, sessionId)
                    sessionInserted.add(sessionId)
                }
            }
        } catch (e: Exception) {
            saveSessionToDb(timestamp, "Unknown", imagePaths, sessionId)
        }
    }

    private fun saveSessionToDb(timestamp: String, locationStr: String, imagePaths: List<String>, sessionId: String) {
        GlobalScope.launch {
            try {
                AppDatabase.getDatabase(context).sessionDao().insert(
                    Session(
                        timestamp = timestamp,
                        location = locationStr,
                        imagePaths = imagePaths
                    )
                )
                Log.i("UploadServer", "Inserted session $sessionId with location $locationStr and ${imagePaths.size} images")
            } catch (e: Exception) {
                Log.e("UploadServer", "Error inserting session $sessionId: ${e.message}", e)
            }
        }
    }
}
