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
import java.io.BufferedInputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.Collections.synchronizedList
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * UploadServer (rewritten)
 *
 * Endpoints:
 *   GET  /health                     -> "OK"
 *   GET  /debug                      -> text summary of sessions on disk (dev aid)
 *   POST /upload[?sessionId=...]
 *          [&mode=pmfi]
 *          [&section=sectionName]    -> accepts AMSI (PNG stream) or PMFI ZIP/PNG
 *
 * Modes:
 *  - AMSI (default): expects 16 PNGs per sessionId. Auto-finalises at 16.
 *  - PMFI ZIP (preferred): a ZIP per section (with &mode=pmfi). We detect ZIP by file signature,
 *    Content-Type, or filename extension. We unzip PNGs and finalise a *section* record immediately.
 *    Each section is stored as its own Session row with id key "<sessionId>_<section>".
 *  - PMFI PNG stream (optional): if Pi streams PNGs with &mode=pmfi, we accept + count them,
 *    but *do not automatically finalise* by default (to avoid churn). You can enable timeout-based
 *    auto-finalise if desired (see AUTO_FINALISE_PMFI_STREAM below).
 */
class UploadServer(
    port: Int,
    baseStorageDir: File,
    private val context: Context
) : NanoHTTPD(port) {

    // --------------------------------------------------------------------------------------------
    // Config
    // --------------------------------------------------------------------------------------------
    private val TAG = "UploadServer"

    // Folder layout: <baseStorageDir>/Sessions/...
    private val sessionsRoot: File = run {
        val root = if (baseStorageDir.name.equals("Sessions", ignoreCase = true))
            baseStorageDir else File(baseStorageDir, "Sessions")
        root.mkdirs()
        root
    }

    private val IMAGES_PER_AMSI = 16
    private val SESSION_TIMEOUT_MS = 10 * 60 * 1000L     // clear trackers after 10 min idle
    private val LOCATION_TIMEOUT_MS = 2_000L             // location best-effort
    private val ZIP_SIGNATURES = arrayOf(
        byteArrayOf(0x50, 0x4B, 0x03, 0x04),            // PK.. (local file header)
        byteArrayOf(0x50, 0x4B, 0x05, 0x06),            // PK.. (empty archive)
        byteArrayOf(0x50, 0x4B, 0x07, 0x08)             // PK.. (spanned archive)
    )

    // Optional: if you ever stream PMFI PNGs, auto-finalise after idle period (disabled by default)
    private val AUTO_FINALISE_PMFI_STREAM = false
    private val PMFI_STREAM_IDLE_FINALISE_MS = 20_000L   // 20s of silence => finalise PMFI stream

    // --------------------------------------------------------------------------------------------
    // State / trackers
    // --------------------------------------------------------------------------------------------
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    // AMSI: sessionId -> imagePaths (absolute)
    private val amsiUploads = ConcurrentHashMap<String, MutableList<String>>()

    // last activity per logical key (sessionId or sectionKey)
    private val lastSeenAt = ConcurrentHashMap<String, Long>()

    // prevent duplicate finalise/insert
    private val finalisedKeys = Collections.synchronizedSet(mutableSetOf<String>())

    // prevent duplicate DB inserts (per logical key)
    private val dbInserted = Collections.synchronizedSet(mutableSetOf<String>())

    // --------------------------------------------------------------------------------------------
    // Lifecycle
    // --------------------------------------------------------------------------------------------
    fun shutdown() {
        runCatching { closeAllConnections() }
        job.cancel()
        stop()
        Log.i(TAG, "UploadServer stopped.")
    }

    // --------------------------------------------------------------------------------------------
    // HTTP router
    // --------------------------------------------------------------------------------------------
    override fun serve(session: IHTTPSession): Response {
        return try {
            when (session.method) {
                Method.GET  -> handleGet(session)
                Method.POST -> handlePost(session)
                else -> newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Method not allowed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Serve error: ${e.message}", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Internal error")
        }
    }

    private fun handleGet(session: IHTTPSession): Response {
        return when (session.uri.orEmpty()) {
            "/health" -> ok("OK")
            "/debug"  -> debugSummary()
            else      -> notFound("Not found")
        }
    }

    private fun handlePost(session: IHTTPSession): Response {
        val uri = session.uri.orEmpty()
        if (uri != "/upload" && uri != "/upload/") return notFound("Unknown endpoint")

        val files = HashMap<String, String>()
        session.parseBody(files) // multipart will be written to temp file(s)

        // sessionId
        val sessionId = session.parms["sessionId"]
            ?: session.headers["x-session-id"]
            ?: return badRequest("Missing sessionId")

        // mode
        val isPmfi = session.parms["mode"]?.equals("pmfi", true) == true ||
                session.headers["x-mode"]?.equals("pmfi", true) == true

        val tmpPath = files.values.firstOrNull() ?: return badRequest("No file payload")
        val tmpFile = File(tmpPath)

        val incomingName = extractFilename(session.headers["content-disposition"])
            ?: session.parms["filename"]
            ?: "upload_${System.currentTimeMillis()}"
        val safeName = File(incomingName).name

        lastSeenAt[sessionId] = System.currentTimeMillis()

        val contentType = (session.headers["content-type"] ?: "").lowercase(Locale.ROOT)
        val looksZip = looksLikeZip(safeName, contentType, tmpFile)

        // For PMFI ZIP, carry section if provided
        val sectionParam = session.parms["section"]

        return if (looksZip) {
            // Treat as PMFI ZIP (regardless of explicit mode) — it's the only sensible meaning
            handlePmfiZip(sessionId, safeName, tmpFile, sectionParam)
        } else {
            // PNG path: AMSI or PMFI stream
            handlePng(sessionId, safeName, tmpFile, isPmfi)
        }
    }

    // --------------------------------------------------------------------------------------------
    // Handlers
    // --------------------------------------------------------------------------------------------
    private fun handlePmfiZip(
        sessionId: String,
        zipFilename: String,
        tmpFile: File,
        sectionParam: String?
    ): Response {
        // Derive a section label:
        // Priority: explicit &section=, else try "<session>_<section>.zip", else "section"
        val base = zipFilename.removeSuffix(".zip").removeSuffix(".ZIP")
        val inferred = base.substringAfter("${sessionId}_", missingDelimiterValue = base)
        val section = (sectionParam ?: inferred).ifBlank { "section" }
        val sectionKey = "${sessionId}_$section"

        val sectionDir = File(sessionsRoot, sectionKey).apply { mkdirs() }
        val savedZip   = File(sectionDir, zipFilename)

        try {
            tmpFile.copyTo(savedZip, overwrite = true)
        } finally {
            tmpFile.delete()
        }

        // Unzip all PNGs to sectionDir
        val extracted = unzipPngs(savedZip, sectionDir)
        UploadProgressBus.uploadProgress.postValue(sectionKey to extracted.size)
        Log.i(TAG, "PMFI ZIP saved '$zipFilename' -> ${extracted.size} PNG(s), section='$section'")

        // Finalise the section as its own session row
        if (finalisedKeys.add(sectionKey)) {
            insertSessionAsync(
                key = sectionKey,
                imagePaths = extracted,
                type = "PMFI",
                label = section
            )
        }

        cleanupStaleSessions()
        return ok("ZIP saved: $zipFilename (${extracted.size} frames)")
    }

    private fun handlePng(
        sessionId: String,
        filename: String,
        tmpFile: File,
        isPmfi: Boolean
    ): Response {
        val sessionDir = File(sessionsRoot, sessionId).apply { mkdirs() }
        val target = File(sessionDir, filename)

        try {
            tmpFile.copyTo(target, overwrite = true)
        } finally {
            tmpFile.delete()
        }

        // Track PNG count for this logical key
        val list = amsiUploads.getOrPut(sessionId) { synchronizedList(mutableListOf()) }
        list.add(target.absolutePath)

        UploadProgressBus.uploadProgress.postValue(sessionId to list.size)
        Log.i(TAG, "PNG saved: '$filename' (session=$sessionId, count=${list.size}, pmfi=$isPmfi)")

        // AMSI: finalise when exactly 16 files arrive (once)
        if (!isPmfi && list.size == IMAGES_PER_AMSI && finalisedKeys.add(sessionId)) {
            insertSessionAsync(key = sessionId, imagePaths = list.toList(), type = "AMSI", label = null)
            amsiUploads.remove(sessionId)
            lastSeenAt.remove(sessionId)
        }

        // Optional: PMFI stream auto-finalise after idle timeout (disabled by default)
        if (isPmfi && AUTO_FINALISE_PMFI_STREAM) {
            schedulePmfiStreamIdleCheck(sessionId)
        }

        cleanupStaleSessions()
        return ok("File saved: $filename")
    }

    // --------------------------------------------------------------------------------------------
    // Helpers
    // --------------------------------------------------------------------------------------------
    private fun looksLikeZip(name: String, contentType: String, tmpFile: File): Boolean {
        val byExt  = name.lowercase(Locale.ROOT).endsWith(".zip")
        val byType = contentType.contains("application/zip")
        val bySig  = isZipBySignature(tmpFile)
        return byExt || byType || bySig
    }

    private fun isZipBySignature(file: File): Boolean = try {
        BufferedInputStream(file.inputStream()).use { ins ->
            val sig = ByteArray(4)
            if (ins.read(sig) != 4) return false
            ZIP_SIGNATURES.any { z -> z[0] == sig[0] && z[1] == sig[1] && z[2] == sig[2] && z[3] == sig[3] }
        }
    } catch (_: Exception) { false }

    private fun extractFilename(contentDisposition: String?): String? {
        if (contentDisposition.isNullOrBlank()) return null
        // Content-Disposition: form-data; name="file"; filename="foo.png"
        return contentDisposition.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("filename=", ignoreCase = true) }
            ?.substringAfter("=")
            ?.trim('"')
    }

    private fun unzipPngs(zipFile: File, destDir: File): List<String> {
        val out = mutableListOf<String>()
        ZipFile(zipFile).use { zf ->
            val entries = zf.entries()
            while (entries.hasMoreElements()) {
                val e: ZipEntry = entries.nextElement()
                if (e.isDirectory) continue
                val onlyName = File(e.name).name
                if (!onlyName.lowercase(Locale.ROOT).endsWith(".png")) continue
                val target = File(destDir, onlyName)
                zf.getInputStream(e).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                out.add(target.absolutePath)
            }
        }
        return out.sorted()
    }

    private fun schedulePmfiStreamIdleCheck(sessionId: String) {
        val key = sessionId
        lastSeenAt[key] = System.currentTimeMillis()
        scope.launch {
            delay(PMFI_STREAM_IDLE_FINALISE_MS)
            val last = lastSeenAt[key] ?: return@launch
            if (System.currentTimeMillis() - last >= PMFI_STREAM_IDLE_FINALISE_MS) {
                // Finalise PMFI stream session if not yet inserted
                if (finalisedKeys.add(key)) {
                    val paths = amsiUploads[key]?.toList().orEmpty()
                    if (paths.isNotEmpty()) {
                        insertSessionAsync(key = key, imagePaths = paths, type = "PMFI", label = "stream")
                    }
                    amsiUploads.remove(key)
                    lastSeenAt.remove(key)
                }
            }
        }
    }

    private fun cleanupStaleSessions() {
        val now = System.currentTimeMillis()
        val stale = mutableListOf<String>()
        for ((id, ts) in lastSeenAt.entries) {
            if (now - ts > SESSION_TIMEOUT_MS) stale.add(id)
        }
        stale.forEach { id ->
            Log.i(TAG, "Session '$id' timed out; clearing trackers.")
            amsiUploads.remove(id)
            lastSeenAt.remove(id)
            finalisedKeys.remove(id)
        }
    }

    // --------------------------------------------------------------------------------------------
    // DB insert with best-effort location (duplicate-safe)
    // --------------------------------------------------------------------------------------------
    private fun insertSessionAsync(
        key: String,                        // logical key: sessionId or "<sessionId>_<section>"
        imagePaths: List<String>,
        type: String,
        label: String?
    ) {
        val timestamp = SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.getDefault()).format(Date())

        // Skip if already inserted
        if (!dbInserted.add(key)) {
            Log.i(TAG, "Session '$key' already inserted; skipping.")
            return
        }

        val fused = LocationServices.getFusedLocationProviderClient(context)

        val fallbackJob = scope.launch {
            delay(LOCATION_TIMEOUT_MS)
            Log.w(TAG, "Location timeout; inserting '$key' with Unknown.")
            saveSessionToDb(timestamp, "Unknown", imagePaths, type, label)
        }

        try {
            fused.lastLocation
                .addOnSuccessListener { loc ->
                    fallbackJob.cancel()
                    val locationStr = loc?.let {
                        try {
                            val geocoder = Geocoder(context, Locale.getDefault())
                            val list = geocoder.getFromLocation(it.latitude, it.longitude, 1)
                            if (!list.isNullOrEmpty()) {
                                val a = list[0]
                                val city = a.locality ?: ""
                                val area = a.subAdminArea ?: ""
                                val country = a.countryName ?: ""
                                val place = listOf(city, area, country).filter { s -> s.isNotBlank() }.joinToString(", ")
                                if (place.isNotBlank()) place else "${it.latitude},${it.longitude}"
                            } else "${it.latitude},${it.longitude}"
                        } catch (_: Exception) {
                            "${it.latitude},${it.longitude}"
                        }
                    } ?: "Unknown"
                    saveSessionToDb(timestamp, locationStr, imagePaths, type, label)
                }
                .addOnFailureListener { err ->
                    fallbackJob.cancel()
                    Log.w(TAG, "lastLocation failed: ${err.message}. Inserting Unknown for '$key'")
                    saveSessionToDb(timestamp, "Unknown", imagePaths, type, label)
                }
        } catch (e: Exception) {
            fallbackJob.cancel()
            Log.w(TAG, "Location flow error: ${e.message}. Inserting Unknown for '$key'")
            saveSessionToDb(timestamp, "Unknown", imagePaths, type, label)
        }
    }

    private fun saveSessionToDb(
        timestamp: String,
        locationStr: String,
        imagePaths: List<String>,
        type: String,
        label: String?
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

    // --------------------------------------------------------------------------------------------
    // Responses
    // --------------------------------------------------------------------------------------------
    private fun ok(msg: String) =
        newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, msg)

    private fun badRequest(msg: String) =
        newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, msg)

    private fun notFound(msg: String) =
        newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, msg)

    private fun debugSummary(): Response {
        val b = StringBuilder().apply {
            appendLine("UploadServer Debug")
            appendLine("Root: ${sessionsRoot.absolutePath}")
            appendLine()
            sessionsRoot.listFiles()?.sortedBy { it.name }?.forEach { dir ->
                if (dir.isDirectory) {
                    appendLine("• ${dir.name}")
                    dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.take(5)?.forEach { f ->
                        appendLine("   - ${f.name}  (${f.length()} bytes)")
                    }
                    val more = (dir.listFiles()?.size ?: 0) - 5
                    if (more > 0) appendLine("   - ... +$more more")
                }
            }
            appendLine()
            appendLine("Trackers:")
            appendLine("  amsiUploads: ${amsiUploads.keys}")
            appendLine("  lastSeenAt : ${lastSeenAt.keys}")
            appendLine("  finalised  : ${finalisedKeys.size}")
            appendLine("  dbInserted : ${dbInserted.size}")
        }
        return ok(b.toString())
    }
}
