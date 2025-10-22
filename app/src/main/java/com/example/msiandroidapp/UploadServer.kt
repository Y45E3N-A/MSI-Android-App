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
 * UploadServer (rewritten, compile-clean)
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
 *  - PMFI PNG stream (optional): accept + count; no auto-finalise unless enabled below.
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

    private data class RunTracker(
        val runId: String,
        var iniName: String = "unknown_ini",
        var lastSeenAt: Long = System.currentTimeMillis(),
        // sectionIndex -> PNG paths
        val sectionPngs: MutableMap<Int, MutableList<String>> = ConcurrentHashMap(),
        // Optional: expected frames per section (if provided by Pi)
        val expectedFramesPerSection: MutableMap<Int, Int> = ConcurrentHashMap()
    )

    private val pmfiRuns = ConcurrentHashMap<String, RunTracker>()
    // We will NOT auto-finalise on idle anymore (to avoid half sessions).
// Keep the constant if you want, but we won't use it for finalising.
    private val RUN_IDLE_SWEEP_MS = 30_000L

    private val IMAGES_PER_AMSI = 16
    private val SESSION_TIMEOUT_MS = 10 * 60 * 1000L     // clear trackers after 10 min idle
    private val LOCATION_TIMEOUT_MS = 2_000L             // location best-effort
    private val ZIP_SIGNATURES = arrayOf(
        byteArrayOf(0x50, 0x4B, 0x03, 0x04),
        byteArrayOf(0x50, 0x4B, 0x05, 0x06),
        byteArrayOf(0x50, 0x4B, 0x07, 0x08)
    )

    // Optional: if you ever stream PMFI PNGs, auto-finalise after idle period (disabled by default)
    private val AUTO_FINALISE_PMFI_STREAM = false
    private val PMFI_STREAM_IDLE_FINALISE_MS = 20_000L

    // --------------------------------------------------------------------------------------------
    // State / trackers
    // --------------------------------------------------------------------------------------------
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    // AMSI/PMFI-stream: sessionId -> imagePaths (absolute)
    private val amsiUploads = ConcurrentHashMap<String, MutableList<String>>()

    // last activity per logical key (sessionId or sectionKey)
    private val lastSeenAt = ConcurrentHashMap<String, Long>()

    // prevent duplicate finalise/insert
    private val finalisedKeys = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    // prevent duplicate DB inserts (per logical key)
    private val dbInserted = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    // If you want to access request params from helpers (optional)
    private val lastRequestParams = object : ThreadLocal<Map<String, String>>() {}

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
                Method.GET -> handleGet(session)
                Method.POST -> handlePost(session)
                else -> newFixedLengthResponse(
                    Response.Status.METHOD_NOT_ALLOWED,
                    MIME_PLAINTEXT,
                    "Method not allowed"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Serve error: ${e.message}", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Internal error")
        }
    }

    private fun touchRun(run: RunTracker) {
        run.lastSeenAt = System.currentTimeMillis()
        scope.launch {
            delay(RUN_IDLE_SWEEP_MS)
            // no-op: do not finalise on idle
        }
    }

    @Synchronized
    private fun finalizeRunMergeOrInsert(runId: String, run: RunTracker, reason: String) {
        // Build ordered PNG list
        val allPngs = run.sectionPngs.entries
            .sortedBy { it.key }
            .flatMap { e -> e.value.sorted() }

        if (allPngs.isEmpty()) {
            Log.w(TAG, "Finalize skipped for $runId (no PNGs).")
            return
        }

        val completedAt = System.currentTimeMillis()
        val tsStr = SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.getDefault()).format(Date(completedAt))

        Log.i(TAG, "Finalising PMFI run '$runId' (${allPngs.size} frames, reason=$reason)")

        scope.launch {
            try {
                val dao = AppDatabase.getDatabase(context).sessionDao()
                val existing = dao.findByRunId(runId)

                if (existing == null) {
                    // Insert new
                    dao.upsert(
                        Session(
                            createdAt = completedAt,
                            completedAtMillis = completedAt,
                            timestamp = tsStr,
                            location = "Unknown",  // if you prefer fused location, you can call insertSessionAsync instead
                            imagePaths = allPngs,
                            type = "PMFI",
                            label = "PMFI Run",
                            runId = runId,
                            iniName = run.iniName,
                            sectionIndex = null
                        )
                    )
                } else {
                    // Merge/append new images (ensure uniqueness + order)
                    val merged = (existing.imagePaths.orEmpty() + allPngs).distinct().sorted()
                    val updated = existing.copy(
                        completedAtMillis = completedAt,
                        timestamp = tsStr,
                        imagePaths = merged,
                        iniName = run.iniName ?: existing.iniName
                    )
                    dao.update(updated)
                }

                UploadProgressBus.uploadProgress.postValue(runId to allPngs.size)
            } catch (e: Exception) {
                Log.e(TAG, "DB merge/insert error for run=$runId: ${e.message}", e)
            } finally {
                pmfiRuns.remove(runId) // clear staging
            }
        }
    }


    private fun handleGet(session: IHTTPSession): Response {
        return when (session.uri.orEmpty()) {
            "/health" -> ok("OK")
            "/debug" -> debugSummary()
            else -> notFound("Not found")
        }
    }

    // --------------------------------------------------------------------------------------------
    // POST /upload
    // --------------------------------------------------------------------------------------------
    private fun handlePost(session: IHTTPSession): Response {
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)

            val params = session.parms
            lastRequestParams.set(params)

            val tempPath = files["file"] ?: files.values.firstOrNull()
            ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "missing file part 'file'")

            val mode = (params["mode"] ?: "amsi").lowercase(Locale.US)
            val sessionId = params["sessionId"] ?: params["sid"] ?: UUID.randomUUID().toString()
            val sectionTag = params["section"]

            val headers = session.headers ?: emptyMap()
            val contentType = headers["content-type"] ?: ""
            val fileNameHint = extractFilename(headers["content-disposition"]) ?: params["filename"] ?: "upload.bin"
            val tmpFile = File(tempPath)

            // --- PMFI ZIP fast-path ---
            if (mode == "pmfi" && looksLikeZip(fileNameHint, contentType, tmpFile)) {
                return handlePmfiZip(
                    sessionId = (params["runId"] ?: sessionId),
                    zipFilename = fileNameHint,
                    tmpFile = tmpFile,
                    sectionParam = sectionTag
                )
            }

            // --- PMFI PNG STREAM (optional legacy) ---
            if (mode == "pmfi") {
                // Keep your existing PMFI-stream behaviour if you still need it
                val uploadsRoot = File(context.filesDir, "uploads").apply { mkdirs() }
                val destFile = File(uploadsRoot, "pmfi/${params["runId"] ?: sessionId}")
                    .apply { mkdirs() }
                    .let { dir -> File(dir, "upload_${System.currentTimeMillis()}.png") }

                tmpFile.copyTo(destFile, overwrite = true)
                insertSessionFromUpload(
                    type = "PMFI",
                    title = sectionTag ?: "PMFI Section",
                    runId = params["runId"] ?: sessionId,
                    iniName = params["ini"],
                    sectionIndex = params["sectionIndex"]?.toIntOrNull(),
                    localPath = destFile.absolutePath,
                    extra = mapOf(
                        "part" to (params["part"] ?: "000"),
                        "framesPerSection" to params["framesPerSection"],
                        "totalFrames" to params["totalFrames"],
                        "totalSections" to params["totalSections"]
                    )
                )
                // This path is PMFI stream, not AMSI, so return as-is.
                return ok("OK: saved ${destFile.name}")
            }

            // --- AMSI: use the aggregator that counts to 16 and posts cumulative progress ---
            // IMPORTANT: pass the *tmpFile* so handlePng() owns the move; do NOT pre-copy here.
            return handlePng(
                sessionId = sessionId,
                filename = fileNameHint.ifBlank { "image_${System.currentTimeMillis()}.png" },
                tmpFile = tmpFile,
                isPmfi = false
            )

        } catch (e: Exception) {
            Log.e(TAG, "handlePost error: ${e.message}", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "error: ${e.message}")
        }
    }



    // --------------------------------------------------------------------------------------------
    // Legacy/aux handlers (ZIP & PNG streams)
    // --------------------------------------------------------------------------------------------
    // UploadServer.kt
    private fun handlePmfiZip(
        sessionId: String,
        zipFilename: String,
        tmpFile: File,
        sectionParam: String?
    ): Response {
        val q = lastRequestParams.get() ?: emptyMap()

        val rawRunId    = q["runId"] ?: q["x-run-id"] ?: sessionId
        val iniNameParam = q["ini"] ?: q["iniName"] ?: q["x-ini"]
        val sectionIndex = q["sectionIndex"]?.toIntOrNull() ?: 0
        val partParam    = q["part"] ?: ""                    // optional
        val finalFlag    = (q["final"] == "1")                // we also support auto-finalise by counts
        // Prefer Pi's section expectation under this key, fall back to older variants
        val sectionTotalFrames = q["sectionTotalFrames"]?.toIntOrNull()
            ?: q["sectionFrames"]?.toIntOrNull()
            ?: q["framesPerSection"]?.toIntOrNull()

        val iniName = iniNameParam?.let { File(it).name } ?: "unknown_ini"

        // -------- Guard: ensure new runs don't merge into an old row with the same runId ----------
        // -------- Guard: ensure new runs don't merge into an old row with the same runId ----------
        val dao = AppDatabase.getDatabase(context).sessionDao()
        val looksLikeRunStart = (sectionIndex == 0) && (partParam.isBlank() || partParam == "001" || partParam == "1")

        val runIdInUse = try {
            blockingIo { dao.findByRunId(rawRunId) } != null
        } catch (_: Exception) {
            false
        }


        val effectiveRunId = if (looksLikeRunStart && runIdInUse && !pmfiRuns.containsKey(rawRunId)) {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val newId = "${rawRunId}__$stamp"
            Log.w(TAG, "runId '$rawRunId' already used; starting a NEW run as '$newId'")
            newId
        } else {
            rawRunId
        }

        // -------- Staging filesystem --------
        val runFolderName = "${effectiveRunId}__${iniName}"
        val sectionName = (sectionParam?.ifBlank { null }
            ?: zipFilename.removeSuffix(".zip").removeSuffix(".ZIP")
                .substringAfter("${effectiveRunId}_", missingDelimiterValue = "section")
                .ifBlank { "section" })

        val pmfiRoot   = File(sessionsRoot, "PMFI").apply { mkdirs() }
        val runDir     = File(pmfiRoot, runFolderName).apply { mkdirs() }
        val sectionDir = File(runDir, "section_%03d__%s".format(sectionIndex, sectionName)).apply { mkdirs() }

        val savedZip = File(sectionDir, zipFilename)
        try {
            tmpFile.copyTo(savedZip, overwrite = true)
        } finally {
            tmpFile.delete()
        }

        val extractedPngs = unzipPngs(savedZip, sectionDir)
        Log.i(TAG, "PMFI ZIP -> run=$effectiveRunId ini=$iniName sec=$sectionIndex part=$partParam files=${extractedPngs.size}")

        // -------- In-memory aggregate for this run --------
        val run = pmfiRuns.getOrPut(effectiveRunId) { RunTracker(runId = effectiveRunId) }
        run.iniName = iniName
        sectionTotalFrames?.let { run.expectedFramesPerSection[sectionIndex] = it }

        val list = run.sectionPngs.getOrPut(sectionIndex) { synchronizedList(mutableListOf()) }
        list.addAll(extractedPngs)

        // Progress (total frames across sections so far)
        val totalSoFar = run.sectionPngs.values.sumOf { it.size }
        UploadProgressBus.uploadProgress.postValue(effectiveRunId to totalSoFar)

        touchRun(run)

        // Finalise on explicit flag OR when all sections met their expected counts
        if (finalFlag || allSectionsComplete(run)) {
            finalizeRunMergeOrInsert(
                runId = effectiveRunId,
                run = run,
                reason = if (finalFlag) "explicit_final_flag" else "all_sections_meet_expected"
            )
        }

        return ok("ZIP accepted (${extractedPngs.size} frames), run=$effectiveRunId section=$sectionIndex part=$partParam")
    }
    // Run a suspend block on IO and return the result, from non-suspend code.
    private fun <T> blockingIo(block: suspend () -> T): T =
        kotlinx.coroutines.runBlocking { withContext(Dispatchers.IO) { block() } }

    private fun allSectionsComplete(run: RunTracker): Boolean {
        if (run.expectedFramesPerSection.isEmpty()) return false
        // Every section that has an expectation must have >= that many PNGs
        return run.expectedFramesPerSection.all { (sec, expected) ->
            (run.sectionPngs[sec]?.size ?: 0) >= expected
        }
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

        val list = amsiUploads.getOrPut(sessionId) { synchronizedList(mutableListOf()) }
        list.add(target.absolutePath)

        lastSeenAt[sessionId] = System.currentTimeMillis()
        UploadProgressBus.uploadProgress.postValue(sessionId to list.size)
        Log.i(TAG, "PNG saved: '$filename' (session=$sessionId, count=${list.size}, pmfi=$isPmfi)")

        if (!isPmfi && list.size == IMAGES_PER_AMSI && finalisedKeys.add(sessionId)) {
            val completedAt = System.currentTimeMillis()
            insertSessionAsync(
                key = sessionId,
                imagePaths = list.toList(),
                type = "AMSI",
                label = null,
                completedAtMillis = completedAt
            )
            amsiUploads.remove(sessionId)
            lastSeenAt.remove(sessionId)
        }

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
        val byExt = name.lowercase(Locale.ROOT).endsWith(".zip")
        val byType = contentType.contains("application/zip")
        val bySig = isZipBySignature(tmpFile)
        return byExt || byType || bySig
    }

    private fun isZipBySignature(file: File): Boolean = try {
        BufferedInputStream(file.inputStream()).use { ins ->
            val sig = ByteArray(4)
            if (ins.read(sig) != 4) return false
            ZIP_SIGNATURES.any { z -> z[0] == sig[0] && z[1] == sig[1] && z[2] == sig[2] && z[3] == sig[3] }
        }
    } catch (_: Exception) {
        false
    }

    private fun extractFilename(contentDisposition: String?): String? {
        if (contentDisposition.isNullOrBlank()) return null
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
                if (finalisedKeys.add(key)) {
                    val paths = amsiUploads[key]?.toList().orEmpty()
                    if (paths.isNotEmpty()) {
                        val completedAt = System.currentTimeMillis()
                        insertSessionAsync(
                            key = key,
                            imagePaths = paths,
                            type = "PMFI",
                            label = "stream",
                            completedAtMillis = completedAt
                        )
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
    // (single, canonical inserter used by all paths)
    // --------------------------------------------------------------------------------------------
    private fun insertSessionAsync(
        key: String,
        imagePaths: List<String>,
        type: String,
        label: String?,
        completedAtMillis: Long = System.currentTimeMillis(),
        runId: String? = null,
        iniName: String? = null,
        sectionIndex: Int? = null
    ) {
        val tsStr = SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.getDefault())
            .format(Date(completedAtMillis))

        if (!dbInserted.add(key)) {
            Log.i(TAG, "Session '$key' already inserted; skipping.")
            return
        }

        val fused = LocationServices.getFusedLocationProviderClient(context)

        val fallbackJob = scope.launch {
            delay(LOCATION_TIMEOUT_MS)
            Log.w(TAG, "Location timeout; inserting '$key' with Unknown.")
            saveSessionToDb(
                completedAtMillis = completedAtMillis,
                timestampStr = tsStr,
                locationStr = "Unknown",
                imagePaths = imagePaths,
                type = type,
                label = label,
                runId = runId,
                iniName = iniName,
                sectionIndex = sectionIndex
            )
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
                                listOf(city, area, country).filter { s -> s.isNotBlank() }
                                    .joinToString(", ")
                                    .ifBlank { "${it.latitude},${it.longitude}" }
                            } else "${it.latitude},${it.longitude}"
                        } catch (_: Exception) {
                            "${it.latitude},${it.longitude}"
                        }
                    } ?: "Unknown"
                    saveSessionToDb(
                        completedAtMillis = completedAtMillis,
                        timestampStr = tsStr,
                        locationStr = locationStr,
                        imagePaths = imagePaths,
                        type = type,
                        label = label,
                        runId = runId,
                        iniName = iniName,
                        sectionIndex = sectionIndex
                    )
                }
                .addOnFailureListener { err ->
                    fallbackJob.cancel()
                    Log.w(TAG, "lastLocation failed: ${err.message}. Inserting Unknown for '$key'")
                    saveSessionToDb(
                        completedAtMillis = completedAtMillis,
                        timestampStr = tsStr,
                        locationStr = "Unknown",
                        imagePaths = imagePaths,
                        type = type,
                        label = label,
                        runId = runId,
                        iniName = iniName,
                        sectionIndex = sectionIndex
                    )
                }
        } catch (e: Exception) {
            fallbackJob.cancel()
            Log.w(TAG, "Location flow error: ${e.message}. Inserting Unknown for '$key'")
            saveSessionToDb(
                completedAtMillis = completedAtMillis,
                timestampStr = tsStr,
                locationStr = "Unknown",
                imagePaths = imagePaths,
                type = type,
                label = label,
                runId = runId,
                iniName = iniName,
                sectionIndex = sectionIndex
            )
        }
    }

    private fun saveSessionToDb(
        completedAtMillis: Long,
        timestampStr: String,
        locationStr: String,
        imagePaths: List<String>,
        type: String,
        label: String?,
        runId: String? = null,
        iniName: String? = null,
        sectionIndex: Int? = null
    ) {
        scope.launch {
            try {
                AppDatabase.getDatabase(context).sessionDao().upsert(
                    Session(
                        createdAt = completedAtMillis,
                        completedAtMillis = completedAtMillis,
                        timestamp = timestampStr,
                        location = locationStr,
                        imagePaths = imagePaths,
                        type = type,
                        label = label,
                        runId = runId,
                        iniName = iniName,
                        sectionIndex = sectionIndex
                    )
                )
                Log.i(
                    TAG,
                    "Inserted $type session (${imagePaths.size} images) run=$runId ini=$iniName label=$label idx=$sectionIndex"
                )
            } catch (e: Exception) {
                Log.e(TAG, "DB insert error: ${e.message}", e)
            }
        }
    }

    /**
     * Helper for single-file uploads (ZIP/PNG) that builds a unique key and delegates to insertSessionAsync(...)
     */
    private fun insertSessionFromUpload(
        type: String,
        title: String,
        runId: String?,
        iniName: String?,
        sectionIndex: Int?,
        localPath: String,
        extra: Map<String, String?> = emptyMap()
    ) {
        val part = extra["part"] ?: "000"
        val key = if (type.equals("PMFI", ignoreCase = true)) {
            "${(runId ?: UUID.randomUUID().toString())}__sec${sectionIndex ?: 0}__part$part"
        } else {
            runId ?: UUID.randomUUID().toString()
        }

        insertSessionAsync(
            key = key,
            imagePaths = listOf(localPath),
            type = type,
            label = title,
            completedAtMillis = System.currentTimeMillis(),
            runId = runId,
            iniName = iniName,
            sectionIndex = sectionIndex
        )
    }

    // --------------------------------------------------------------------------------------------
    // Responses & debug
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
            appendLine("PMFI runs in staging:")
            pmfiRuns.values.forEach { r ->
                val secList = r.sectionPngs.entries.sortedBy { it.key }
                    .joinToString { "sec${it.key}:${it.value.size}" }
                appendLine("  - ${r.runId} ini=${r.iniName} pngs=${r.sectionPngs.values.sumOf { it.size }} [$secList]")
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
