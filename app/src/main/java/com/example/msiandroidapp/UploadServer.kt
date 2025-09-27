package com.example.msiandroidapp.service

import android.content.Context
import android.location.Geocoder
import android.util.Log
import com.example.msiandroidapp.data.AppDatabase
import com.example.msiandroidapp.data.Session
import com.example.msiandroidapp.util.UploadProgressBus
import com.google.android.gms.location.LocationServices
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.Collections.synchronizedList
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * Unified UploadServer
 * - AMSI: POST /upload?sessionId=UUID  (16 PNGs -> finalise once)
 * - PMFI (ZIP): POST /upload?sessionId=UUID&mode=pmfi  (filename.zip -> extract + finalise section)
 * - PMFI (PNG stream, optional): POST /upload?sessionId=UUID&mode=pmfi  (PNGs stored; no auto-finalise)
 *
 * Notes:
 * - We ONLY infer PMFI from explicit "mode=pmfi" (or header x-mode: pmfi). No filename heuristics.
 * - Accepts /upload and /upload/ to avoid 404s from trailing slashes.
 * - Does NOT override start(); start it from your ForegroundService once.
 */
class UploadServer(
    port: Int,
    baseStorageDir: File,
    private val context: Context
) : NanoHTTPD(port) {

    private val TAG = "UploadServer"
    // Thread-safe: tracks sessions already inserted into DB
    private val dbInserted = Collections.synchronizedSet(mutableSetOf<String>())

    // Root storage: if caller passed ".../Sessions", reuse; otherwise append "Sessions"
    private val sessionsRoot: File = if (baseStorageDir.name.equals("Sessions", true)) {
        baseStorageDir
    } else {
        File(baseStorageDir, "Sessions")
    }.apply { mkdirs() }

    // ---- AMSI constants/trackers ----
    private val imagesPerAmsi = 16
    private val sessionTimeoutMillis = 10 * 60 * 1000L // 10 minutes

    // Thread-safe trackers
    private val amsiUploads = ConcurrentHashMap<String, MutableList<String>>() // sessionId -> image paths
    private val lastSeenAt = ConcurrentHashMap<String, Long>()                 // sessionId -> last timestamp
    private val finalisedKeys = Collections.synchronizedSet(mutableSetOf<String>()) // keys we've finalised

    // Coroutines for background tasks
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    fun shutdown() {
        try { closeAllConnections() } catch (_: Exception) {}
        job.cancel()
        stop()
        Log.i(TAG, "UploadServer stopped.")
    }

    // ------------------------------------------------------------------------
    // HTTP router
    // ------------------------------------------------------------------------
    override fun serve(session: IHTTPSession): Response {
        return try {
            when (session.method) {
                Method.GET  -> handleGet(session)
                Method.POST -> handlePost(session)
                else -> newFixedLengthResponse(
                    Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Method not allowed"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Serve error: ${e.message}", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Internal error")
        }
    }

    private fun handleGet(session: IHTTPSession): Response {
        return when (session.uri.orEmpty()) {
            "/health" -> newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")
            else      -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun handlePost(session: IHTTPSession): Response {
        val uri = session.uri.orEmpty()
        if (!(uri == "/upload" || uri == "/upload/")) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Unknown endpoint")
        }

        val files = HashMap<String, String>()
        session.parseBody(files) // NanoHTTPD writes multipart file to a temp path

        // ---- Session Id ----
        val sessionId = session.parms["sessionId"]
            ?: session.headers["x-session-id"]
            ?: return badRequest("No sessionId provided")

        // ---- Mode ----
        val isPmfi = session.parms["mode"]?.equals("pmfi", true) == true ||
                session.headers["x-mode"]?.equals("pmfi", true) == true

        val tmpPath = files.values.firstOrNull() ?: return badRequest("No file received")
        val incomingName = extractFilename(session.headers["content-disposition"])
            ?: session.parms["filename"]
            ?: "upload_${System.currentTimeMillis()}"
        val safeName = File(incomingName).name // strip any paths

        lastSeenAt[sessionId] = System.currentTimeMillis()

        // ZIPs are treated as PMFI sections (explicit mode only strongly recommended)
        val isZip = safeName.lowercase(Locale.ROOT).endsWith(".zip")
        val sectionParam = session.parms["section"]

        return if (isZip) {
            handlePmfiZip(sessionId, safeName, File(tmpPath), sectionParam)  // <-- pass it
        } else {
            handlePng(sessionId, safeName, File(tmpPath), isPmfi)
        }
    }

    // ------------------------------------------------------------------------
    // PMFI ZIP handler (explicit section finalisation)
    // ------------------------------------------------------------------------
    private fun handlePmfiZip(sessionId: String, zipFilename: String, tmpFile: File,sectionParam: String?): Response {
        // Prefer explicit section query (server sends &section=NAME)
        val base = zipFilename.removeSuffix(".zip")
        val inferred = base.substringAfter("${sessionId}_", missingDelimiterValue = base)
        val section = (sectionParam ?: inferred).ifBlank { "section" }  // <-- use param
        val sectionKey = "${sessionId}_$section"

        val sectionDir = File(sessionsRoot, sectionKey).apply { mkdirs() }
        val savedZip = File(sectionDir, zipFilename)
        try { tmpFile.copyTo(savedZip, overwrite = true) } finally { tmpFile.delete() }

        val extracted = unzipPngs(savedZip, sectionDir)
        UploadProgressBus.uploadProgress.postValue(sectionKey to extracted.size)
        Log.i(TAG, "PMFI ZIP saved $zipFilename -> ${extracted.size} frames (section=$section)")

        insertSessionAsync(
            sessionId = sectionKey,
            imagePaths = extracted,
            type = "PMFI",
            label = section
        )


        finalisedKeys.add(sectionKey)
        cleanupStaleSessions()
        return ok("ZIP saved: $zipFilename (${extracted.size} frames)")
    }


    // ------------------------------------------------------------------------
    // PNG handler (AMSI or PMFI stream)
    // ------------------------------------------------------------------------
    private fun handlePng(sessionId: String, filename: String, tmpFile: File, isPmfi: Boolean): Response {
        val sessionDir = File(sessionsRoot, sessionId).apply { mkdirs() }
        val target = File(sessionDir, filename)

        try {
            tmpFile.copyTo(target, overwrite = true)
        } finally {
            tmpFile.delete()
        }

        // Share one tracker, but AMSI is the only mode that auto-finalises here
        val list = amsiUploads.getOrPut(sessionId) { synchronizedList(mutableListOf()) }
        list.add(target.absolutePath)

        UploadProgressBus.uploadProgress.postValue(sessionId to list.size)
        Log.i(TAG, "PNG saved: $filename (session=$sessionId, count=${list.size}, pmfi=$isPmfi)")

        cleanupStaleSessions()

        if (!isPmfi) {
            // ---- AMSI: finalise exactly at 16, once ----
            if (list.size == imagesPerAmsi && finalisedKeys.add(sessionId)) {
                insertSessionAsync(sessionId = sessionId, imagePaths = list.toList())
                amsiUploads.remove(sessionId)
                lastSeenAt.remove(sessionId)
            }
        } else {
            // ---- PMFI PNG stream (accepted + progress only) ----
            // Intentionally do not finalise automatically to avoid repeated DB churn.
            // If you want to finalise on timeout or min-count, you can add logic here later.
        }

        return ok("File saved: $filename")
    }

    // ------------------------------------------------------------------------
    // Utilities
    // ------------------------------------------------------------------------
    private fun extractFilename(contentDisposition: String?): String? {
        if (contentDisposition.isNullOrBlank()) return null
        // e.g. Content-Disposition: form-data; name="file"; filename="image_00.png"
        return contentDisposition.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("filename=", ignoreCase = true) }
            ?.substringAfter("=", "")
            ?.trim('"')
    }

    private fun unzipPngs(zipFile: File, destDir: File): List<String> {
        val out = mutableListOf<String>()
        ZipFile(zipFile).use { zf ->
            val entries = zf.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                if (e.isDirectory) continue
                val name = File(e.name).name
                if (!name.lowercase(Locale.ROOT).endsWith(".png")) continue
                val target = File(destDir, name)
                zf.getInputStream(e).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                out.add(target.absolutePath)
            }
        }
        return out.sorted()
    }

    private fun cleanupStaleSessions() {
        val now = System.currentTimeMillis()
        val stale = mutableListOf<String>()
        for ((id, ts) in lastSeenAt.entries) {
            if (now - ts > sessionTimeoutMillis) stale.add(id)
        }
        stale.forEach { id ->
            Log.i(TAG, "Session $id timed out; clearing trackers.")
            amsiUploads.remove(id)
            lastSeenAt.remove(id)
            finalisedKeys.remove(id)
        }
    }

    // ------------------------------------------------------------------------
    // DB insert (no schema changes required)
    // ------------------------------------------------------------------------
    private fun insertSessionAsync(sessionId: String, imagePaths: List<String>, type: String = "AMSI", label: String? = null) {
        val timestamp = SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.getDefault()).format(Date())
        val fused = LocationServices.getFusedLocationProviderClient(context)

        // If we already inserted this session, bail early
        if (!dbInserted.add(sessionId)) {
            Log.i(TAG, "Session $sessionId already inserted; skipping duplicate.")
            return
        }

        // Schedule a fallback insert in case fused-location never returns
        val fallbackJob = scope.launch {
            delay(2000)
            // Only insert if nobody else has done it (dbInserted already reserved our spot)
            Log.w(TAG, "Location timeout; inserting session $sessionId with Unknown location.")
            saveSessionToDb(timestamp, "Unknown", imagePaths, type, label)

        }

        try {
            fused.lastLocation
                .addOnSuccessListener { loc ->
                    // If we reached here, cancel the fallback and insert with best location
                    fallbackJob.cancel()

                    val locationStr = loc?.let {
                        var place: String? = null
                        try {
                            val geocoder = Geocoder(context, Locale.getDefault())
                            val list = geocoder.getFromLocation(it.latitude, it.longitude, 1)
                            if (!list.isNullOrEmpty()) {
                                val a = list[0]
                                val city = a.locality ?: ""
                                val area = a.subAdminArea ?: ""
                                val country = a.countryName ?: ""
                                place = listOf(city, area, country)
                                    .filter { s -> s.isNotBlank() }
                                    .joinToString(", ")
                            }
                        } catch (ge: Exception) {
                            Log.w(TAG, "Geocoder failed: ${ge.message}")
                        }
                        place ?: "${it.latitude},${it.longitude}"
                    } ?: "Unknown"

                    saveSessionToDb(timestamp, locationStr, imagePaths, type, label)
                }
                .addOnFailureListener { err ->
                    // Cancel fallback; we’ll insert Unknown now (single insert)
                    fallbackJob.cancel()
                    Log.w(TAG, "lastLocation failed: ${err.message}. Inserting Unknown for $sessionId")
                    saveSessionToDb(timestamp, "Unknown", imagePaths, type, label)
                }
        } catch (e: Exception) {
            // Cancel fallback; insert Unknown now (single insert)
            fallbackJob.cancel()
            Log.w(TAG, "Location flow threw: ${e.message}. Inserting Unknown for $sessionId")
            saveSessionToDb(timestamp, "Unknown", imagePaths, type, label)
        }
    }


    private fun saveSessionToDb(
        timestamp: String,
        locationStr: String,
        imagePaths: List<String>,
        type: String = "AMSI",
        label: String? = null
    ) {
        scope.launch {
            try {
                AppDatabase.getDatabase(context).sessionDao().insert(
                    Session(
                        timestamp = timestamp,
                        location = locationStr,
                        imagePaths = imagePaths,
                        type = type,
                        label = label
                    )
                )
                Log.i(TAG, "Inserted $type session (${imagePaths.size} images) @ $locationStr label=$label")
            } catch (e: Exception) {
                Log.e(TAG, "DB insert error: ${e.message}", e)
            }
        }
    }


    // ------------------------------------------------------------------------
    // Response helpers
    // ------------------------------------------------------------------------
    private fun ok(msg: String) =
        newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, msg)

    private fun badRequest(msg: String) =
        newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, msg)
}
