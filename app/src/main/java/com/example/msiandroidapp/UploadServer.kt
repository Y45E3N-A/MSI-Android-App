package com.example.msiandroidapp.service

import android.content.Context
import android.location.Geocoder
import android.util.Log
import com.example.msiandroidapp.data.AppDatabase
import com.example.msiandroidapp.data.Session
import com.example.msiandroidapp.util.UploadProgressBus
import com.google.android.gms.location.LocationServices
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Method
import kotlinx.coroutines.*
import java.io.BufferedInputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date
import java.util.UUID
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
 *  - PMFI ZIP (preferred): a ZIP for a single section/wavelength block (&mode=pmfi).
 *    We unzip the PNGs, merge them into that section’s row in Room
 *    (1 DB row per (runId, sectionIndex)), and broadcast progress.
 *  - PMFI PNG stream (optional/legacy): can still dribble frames, but ZIP is the main path.
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

    // runId -> (tempC, humidity, tsUtc) cached until the DB row exists
    private val pendingEnv = ConcurrentHashMap<String, Triple<Double?, Double?, String?>>()

    // Folder layout: <baseStorageDir>/Sessions/...
    private val sessionsRoot: File = run {
        val root = if (baseStorageDir.name.equals("Sessions", ignoreCase = true))
            baseStorageDir else File(baseStorageDir, "Sessions")
        root.mkdirs()
        Log.i(TAG, "Sessions root: ${root.absolutePath}")
        root
    }
    // prevent duplicate finalise/insert (AMSI safety)
    private val finalisedKeys = java.util.Collections.synchronizedSet(mutableSetOf<String>())

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

    // (kept for future use; not used to finalise implicitly)
    private val RUN_IDLE_SWEEP_MS = 30_000L

    private val IMAGES_PER_AMSI = 16
    private val SESSION_TIMEOUT_MS = 10 * 60 * 1000L     // clear trackers after 10 min idle
    private val LOCATION_TIMEOUT_MS = 2_000L             // location best-effort
    private val ZIP_SIGNATURES = arrayOf(
        byteArrayOf(0x50, 0x4B, 0x03, 0x04),
        byteArrayOf(0x50, 0x4B, 0x05, 0x06),
        byteArrayOf(0x50, 0x4B, 0x07, 0x08)
    )
    // --------------------------------------------------------------------------------------------
    // State / trackers
    // --------------------------------------------------------------------------------------------
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    // AMSI (and optional PMFI stream): sessionId -> imagePaths (absolute)
    private val amsiUploads = ConcurrentHashMap<String, MutableList<String>>()

    // last activity per logical key (sessionId / runId)
    private val lastSeenAt = ConcurrentHashMap<String, Long>()

    // prevent duplicate finalise/insert


    // prevent duplicate DB inserts (per logical key)
    private val dbInserted = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    // Access request params in helpers (populated per request)
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

    private fun handleGet(session: IHTTPSession): Response {
        return when (session.uri.orEmpty()) {
            "/health" -> ok("OK")
            "/debug"  -> debugSummary()
            else      -> notFound("Not found")
        }
    }

    private fun touchRun(run: RunTracker) {
        run.lastSeenAt = System.currentTimeMillis()
        scope.launch {
            delay(RUN_IDLE_SWEEP_MS)
            // no implicit finalise
        }
    }


    // --------------------------------------------------------------------------------------------
    // POST /upload
    // --------------------------------------------------------------------------------------------
    private fun handlePost(session: IHTTPSession): Response {
        return try {
            // Parse body to a temp file path
            val files = HashMap<String, String>()
            session.parseBody(files)

            val params = session.parms
            lastRequestParams.set(params)

            val tmpPath = files["file"] ?: files.values.firstOrNull()
            ?: return badRequest("missing file part 'file'")
            val tmpFile = File(tmpPath)

            val headers = session.headers ?: emptyMap()
            val contentType = headers["content-type"] ?: ""
            // Filename inference: header -> ?filename= -> form param "file" -> fallback
            val fileNameHint = extractFilename(headers["content-disposition"])
                ?: params["filename"]
                ?: params["file"]
                ?: "upload.bin"

            val mode = (params["mode"] ?: "amsi").lowercase(Locale.US)
            val sessionId = params["sessionId"] ?: params["sid"] ?: UUID.randomUUID().toString()
            val sectionTag = params["section"]

            // ── Log request envelope (useful when debugging uploads) ────────────────────────────────
            runCatching {
                Log.i(TAG, "POST ${session.uri} params=$params headers=$headers")
            }

            // ── 1) METADATA JSON (env + timestamps) ────────────────────────────────────────────────
            var looksLikeJson =
                contentType.contains("application/json", true) ||
                        fileNameHint.endsWith("_metadata.json", ignoreCase = true) ||
                        (fileNameHint.endsWith(".json", ignoreCase = true) &&
                                fileNameHint.contains("metadata", ignoreCase = true))
            if (!looksLikeJson) {
                // Lightweight sniff if headers/filename are unhelpful
                looksLikeJson = try {
                    BufferedInputStream(tmpFile.inputStream()).use { ins ->
                        val buf = ByteArray(64)
                        val n = ins.read(buf)
                        if (n > 0) {
                            val s = String(buf, 0, n).trimStart()
                            s.startsWith("{") || s.startsWith("[")
                        } else false
                    }
                } catch (_: Exception) { false }
            }
            if (looksLikeJson) {
                return handleEnvMetadataJson(
                    hintedRunId = params["sessionId"] ?: params["runId"],
                    tmpFile = tmpFile
                )
            }

            // ── 2) PMFI ZIP ────────────────────────────────────────────────────────────────────────
            if (mode == "pmfi" && looksLikeZip(fileNameHint, contentType, tmpFile)) {
                return handlePmfiZip(
                    sessionId = (params["runId"] ?: sessionId),
                    zipFilename = fileNameHint,
                    tmpFile = tmpFile,
                    sectionParam = sectionTag
                )
            }


            // ── 3) PMFI PNG stream (optional) ─────────────────────────────────────────────────────
            if (mode == "pmfi") {
                val uploadsRoot = File(context.filesDir, "uploads").apply { mkdirs() }
                val effectiveRunId = (params["runId"] ?: sessionId)
                val destDir = File(uploadsRoot, "pmfi/$effectiveRunId").apply { mkdirs() }
                val destFile = File(destDir, "upload_${System.currentTimeMillis()}.png")

                // Save file
                tmpFile.copyTo(destFile, overwrite = true)
                tmpFile.delete()

                // Index/insert one-by-one
                insertSessionFromUpload(
                    type = "PMFI",
                    title = sectionTag ?: "PMFI Section",
                    runId = effectiveRunId,
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

                cleanupStaleSessions()
                return ok("OK: saved ${destFile.name}")
            }
            // ── 3.5) CALIBRATION PNG stream  ─────────────────────────────────────────────
// Pi sends one PNG per LED channel with mode=cal, plus ?runId=cal_..., ?channel=idx
// We save them under Sessions/CAL/<runId>/CAL_image_XX.png
// and update/insert a CalibrationProfile row (or at least stash paths for it).
            if (mode == "cal") {
                val calRunId = params["runId"] ?: sessionId      // e.g. "cal_20251028...."
                val channelIdx = params["channel"]?.toIntOrNull() ?: -1
                val wavelengthNm = params["wavelength"] ?: ""

                val safeName = fileNameHint.ifBlank {
                    if (channelIdx >= 0) "CAL_image_%02d.png".format(channelIdx)
                    else "CAL_image_${System.currentTimeMillis()}.png"
                }

                val savedPath = handleCalPng(
                    calRunId = calRunId,
                    filename = safeName,
                    tmpFile = tmpFile,
                    channelIdx = channelIdx,
                    wavelengthNm = wavelengthNm
                )

                // Broadcast progress up to UI if you want (similar to UploadProgressBus)
                // We'll just log for now:
                Log.i(TAG, "CAL PNG saved: $savedPath (runId=$calRunId ch=$channelIdx λ=$wavelengthNm)")

                cleanupStaleSessions()
                return ok("CAL OK: $safeName")
            }


            // ── 4) AMSI PNGs (default) ────────────────────────────────────────────────────────────
            // Compute where the image will be saved and log it (full, real path)
            val sessionDir = File(sessionsRoot, sessionId).apply { mkdirs() }
            val safeName = fileNameHint.ifBlank { "image_${System.currentTimeMillis()}.png" }
            val plannedTarget = File(sessionDir, safeName)
            Log.i(TAG, "AMSI: saving PNG -> ${plannedTarget.absolutePath}")

            // Delegate to the existing PNG handler (does the actual move + progress + finalise)
            return handlePng(
                sessionId = sessionId,
                filename = safeName,
                tmpFile = tmpFile,
                isPmfi = false
            )
        } catch (e: Exception) {
            Log.e(TAG, "handlePost error: ${e.message}", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "error: ${e.message}")
        }
    }
    private fun handleCalPng(
        calRunId: String,
        filename: String,
        tmpFile: File,
        channelIdx: Int,
        wavelengthNm: String
    ): String {
        // On-disk layout for calibration:
        //   Sessions/CAL/<calRunId>/
        //       CAL_image_00.png
        //       CAL_image_01.png
        //       ...
        //
        // We keep them all together so the Gallery/Calibration screen can show them.
        val calRoot = File(sessionsRoot, "CAL").apply { mkdirs() }
        val calDir  = File(calRoot, calRunId).apply { mkdirs() }

        val target = File(calDir, filename)

        try {
            tmpFile.copyTo(target, overwrite = true)
        } finally {
            tmpFile.delete()
        }

        // Stash / merge this path into the DB for this calRunId.
        // We'll upsert a CalibrationProfile row with partial info
        // (images list grows as more channels arrive).
        upsertCalibrationProfileImages(
            calRunId = calRunId,
            imagePath = target.absolutePath,
            channelIdx = channelIdx,
            wavelengthNm = wavelengthNm
        )

        return target.absolutePath
    }
    private fun upsertCalibrationProfileImages(
        calRunId: String,
        imagePath: String,
        channelIdx: Int,
        wavelengthNm: String
    ) {
        scope.launch {
            try {
                val db = AppDatabase.getDatabase(context)
                val calDao = db.calibrationDao()

                // We assume CalibrationProfile looks something like:
                //  - runId: String (primary key or unique)
                //  - timestampStr / completedAtMillis
                //  - imagePaths: MutableList<String> or JSON string
                //  - ledNormsJson: String? (we'll fill later when metadata arrives)
                //  - notes like channel map, wavelengths, etc.
                //
                // We'll do a DAO call that either inserts new profile or adds this imagePath
                // to the existing profile for that runId.
                //
                // If you don't yet have such a DAO method, create one like:
                //   fun upsertCalibrationImage(runId: String,
                //                               timestampMillis: Long,
                //                               timestampStr: String,
                //                               newImagePath: String,
                //                               channelIdx: Int?,
                //                               wavelengthNm: String?)
                //
                val nowMillis = System.currentTimeMillis()
                val niceTs = SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.getDefault())
                    .format(Date(nowMillis))

                calDao.upsertCalibrationImage(
                    runId = calRunId,
                    timestampMillis = nowMillis,
                    timestampStr = niceTs,
                    newImagePath = imagePath,
                    channelIdx = channelIdx,
                    wavelengthNm = wavelengthNm
                )

            } catch (e: Exception) {
                Log.e(TAG, "upsertCalibrationProfileImages failed for $calRunId", e)
            }
        }
    }

    // --------------------------------------------------------------------------------------------
// PMFI section upsert helper
// Takes everything we currently know for ONE (runId, sectionIndex) and writes/merges
// it into the DB via SessionDao.upsertPmfiSection(...).
// This is what actually makes "one card per wavelength section" show up in Gallery.
// --------------------------------------------------------------------------------------------
    private fun upsertPmfiSectionRow(
        runId: String,
        sectionIndex: Int,
        iniName: String,
        sectionLabel: String,
        allPngsForSection: List<String>
    ) {
        val completedAt = System.currentTimeMillis()
        val tsStr = SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.getDefault())
            .format(Date(completedAt))

        val (t, h, tsUtc) = pendingEnv[runId] ?: Triple(null, null, null)

        // instead of hardcoded "Unknown", resolve location first
        getBestEffortLocationString { locString ->

            scope.launch {
                try {
                    val dao = AppDatabase.getDatabase(context).sessionDao()

                    dao.upsertPmfiSection(
                        runId = runId,
                        sectionIndex = sectionIndex,
                        iniName = iniName,
                        newImagePaths = allPngsForSection,
                        completedAtMillis = completedAt,
                        timestampStr = tsStr,
                        locationStr = locString,          // <-- now real location / lat,lon
                        envTempC = t,
                        envHumidity = h,
                        envTsUtc = tsUtc,
                        label = sectionLabel
                    )

                    // keep pendingEnv for future sections of same runId
                } catch (e: Exception) {
                    Log.e(TAG, "upsertPmfiSectionRow failed for runId=$runId sec=$sectionIndex", e)
                }
            }
        }
    }



    private fun handleEnvMetadataJson(hintedRunId: String?, tmpFile: File): Response {
        return try {
            val txt = tmpFile.readText()
            tmpFile.delete()

            val root = org.json.JSONObject(txt)
            val modeFromJson = root.optString("mode").lowercase(Locale.ROOT)
            val ledNormsArr = if (root.has("led_norms")) root.optJSONArray("led_norms") else null
            val targetDn    = if (root.has("target_dn")) root.optDouble("target_dn") else Double.NaN

            // We'll serialise led_norms back to a JSON string so we can persist it.
            val ledNormsJsonStr = ledNormsArr?.toString() // e.g. "[0.5,0.48,...]"

            val sessionIdFromJson = root.optString("session_id").takeIf { it.isNotBlank() }
            val env = root.optJSONObject("env")

            val tempC = env?.optDouble("temp_c")?.let { if (it.isNaN()) null else it }
            val hum   = env?.optDouble("humidity")?.let { if (it.isNaN()) null else it }
            val tsUtc = env?.optString("ts_utc")?.takeIf { it.isNotBlank() }

            val runId = (hintedRunId ?: sessionIdFromJson)
                ?: return badRequest("metadata.json missing sessionId/runId")

            // Try to update now; if no row yet, cache and apply on insert
            scope.launch {
                // 1. Update env for normal sessions/PMFI (SessionDao)
                val dao = AppDatabase.getDatabase(context).sessionDao()
                val rows = runCatching { dao.updateEnvByRunId(runId, tempC, hum, tsUtc) }
                    .getOrDefault(0)

                if (rows == 0) {
                    // not in sessions yet => stash for later AMSI/PMFI
                    pendingEnv[runId] = Triple(tempC, hum, tsUtc)
                    Log.i(TAG, "Env metadata cached for runId=$runId (session not inserted yet).")
                } else {
                    Log.i(TAG, "Env metadata stored for runId=$runId (rows=$rows).")
                }

                // 2. If this was calibration metadata, also update CalibrationProfile
                if (modeFromJson == "cal") {
                    try {
                        val calDao = AppDatabase.getDatabase(context).calibrationDao()
                        calDao.upsertCalibrationMetadata(
                            runId = runId,
                            ledNormsJson = ledNormsJsonStr,
                            envTempC = tempC,
                            envHumidity = hum,
                            envTsUtc = tsUtc,
                            targetDn = if (targetDn.isNaN()) null else targetDn,
                            // we can also store ts_utc from root["ts_utc"] if we want
                            tsUtcOverall = root.optString("ts_utc")
                        )
                        Log.i(TAG, "Calibration metadata stored for runId=$runId")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to store calibration metadata for $runId", e)
                    }
                }
            }


            ok("metadata accepted for $runId")
        } catch (e: Exception) {
            Log.w(TAG, "metadata parse failed: ${e.message}", e)
            badRequest("invalid metadata json")
        }
    }

// PMFI ZIP handler
//
// Pi sends us (mode=pmfi) + a ZIP chunk. That ZIP = frames for ONE sectionIndex,
// possibly just part of that section (e.g. part=002).
//
// After we unzip the frames, we:
//   1. Track them in pmfiRuns[runId].sectionPngs[sectionIndex] (so we remember everything so far).
//   2. Call upsertPmfiSectionRow(...) so Room has/updates a row for this specific section.
//      -> That row shows up as its own card in Gallery.
//   3. Post progress via UploadProgressBus.
//
// We do NOT create a single "whole PMFI run" row anymore.
// --------------------------------------------------------------------------------------------
    private fun handlePmfiZip(
        sessionId: String,
        zipFilename: String,
        tmpFile: File,
        sectionParam: String?
    ): Response {

        val q = lastRequestParams.get() ?: emptyMap()

        // The Pi's logical run identifier. May repeat across sections.
        val rawRunId     = q["runId"] ?: q["x-run-id"] ?: sessionId

        // Name of the INI file or recipe used on Pi
        val iniNameParam = q["ini"] ?: q["iniName"] ?: q["x-ini"]
        val iniName = iniNameParam?.let { File(it).name } ?: "unknown_ini"

        // Which "wavelength block" / LED set is this ZIP for?
        val sectionIndex = q["sectionIndex"]?.toIntOrNull() ?: 0

        // Pi may split a section into multiple parts (part=001, 002, ...).
        // We don't really *need* part for DB keys now, but it's nice info for logs.
        val partParam    = q["part"] ?: ""

        // Optional hint from Pi telling us how many frames we expect in this section total.
        val sectionTotalFrames = q["sectionTotalFrames"]?.toIntOrNull()
            ?: q["sectionFrames"]?.toIntOrNull()
            ?: q["framesPerSection"]?.toIntOrNull()

        // --- handle runId collisions across days/runs ---
        //
        // If the Pi accidentally reuses a runId that we already stored historically,
        // and this *looks* like the start of a "new" scan (sectionIndex=0, first part),
        // we mint a new effectiveRunId with a timestamp suffix, so we don't merge old data.
        //
        val looksLikeRunStart = (sectionIndex == 0) &&
                (partParam.isBlank() || partParam == "001" || partParam == "1")

        val dao = AppDatabase.getDatabase(context).sessionDao()
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

        // If we renamed the runId, move any cached env metadata across so future sections still get it.
        if (effectiveRunId != rawRunId) {
            pendingEnv[rawRunId]?.let { triple ->
                pendingEnv[effectiveRunId] = triple
                pendingEnv.remove(rawRunId)
                Log.i(TAG, "Moved pending env rawRunId=$rawRunId -> effectiveRunId=$effectiveRunId")
            }
        }

        // --- Work out a human-readable section label.
        //
        // Example incoming names:
        //   zipFilename: "run123_section002_part001.zip"
        //   sectionParam query: "660nm"
        //
        // We prefer the provided &section= name, else derive something from filename.
        //
        val sectionNameGuess = sectionParam?.ifBlank { null }
            ?: zipFilename
                .removeSuffix(".zip")
                .removeSuffix(".ZIP")
                .substringAfter("${effectiveRunId}_", missingDelimiterValue = "section")
                .ifBlank { "section" }

        val sectionLabel = String.format(
            Locale.US,
            "Section %03d (%s)",
            sectionIndex,
            sectionNameGuess
        )

        // --- Create folders on disk to hold extracted images for this run/section.
        //
        // On-disk layout (per device, not DB):
        //   Sessions/PMFI/<runId>__<iniName>/section_003__660nm/<actual PNGs...>
        //
        val pmfiRoot   = File(sessionsRoot, "PMFI").apply { mkdirs() }
        val runDir     = File(pmfiRoot, "${effectiveRunId}__${iniName}").apply { mkdirs() }
        val sectionDir = File(
            runDir,
            String.format(Locale.US, "section_%03d__%s", sectionIndex, sectionNameGuess)
        ).apply { mkdirs() }

        // Move the uploaded ZIP into that section folder, then unzip.
        val savedZip = File(sectionDir, zipFilename)
        try {
            Log.i(TAG, "PMFI ZIP: saving -> ${savedZip.absolutePath}")
            tmpFile.copyTo(savedZip, overwrite = true)
        } finally {
            tmpFile.delete()
        }

        val extractedPngs = unzipPngs(savedZip, sectionDir)
        Log.i(
            TAG,
            "PMFI ZIP -> run=$effectiveRunId ini=$iniName sec=$sectionIndex part=$partParam files=${extractedPngs.size}"
        )

        // --- Update in-memory tracker for progress accounting.
        //
        // pmfiRuns tracks everything we've received for this runId so far, grouped by sectionIndex.
        val runTracker = pmfiRuns.getOrPut(effectiveRunId) { RunTracker(runId = effectiveRunId) }
        runTracker.iniName = iniName
        sectionTotalFrames?.let { runTracker.expectedFramesPerSection[sectionIndex] = it }
        val sectionList = runTracker.sectionPngs.getOrPut(sectionIndex) {
            synchronizedList(mutableListOf())
        }
        sectionList.addAll(extractedPngs)
        touchRun(runTracker)

        // --- Write/update DB row for THIS section right now.
        //
        // This is the important part: we now persist ONE row per (runId, sectionIndex).
        // Room will merge imagePaths across parts of the same section.
        upsertPmfiSectionRow(
            runId = effectiveRunId,
            sectionIndex = sectionIndex,
            iniName = iniName,
            sectionLabel = sectionLabel,
            allPngsForSection = sectionList.toList() // full list so far for this section
        )

        // --- Progress broadcast (for UI live progress bars etc.).
        //
        // totalSoFar = total frames across ALL sections we've seen for this run so far.
        val totalSoFar = runTracker.sectionPngs.values.sumOf { it.size }
        UploadProgressBus.uploadProgress.postValue(effectiveRunId to totalSoFar)

        // NOTE: We intentionally DO NOT "finalise" the run as a single row.
        // Every section is already in the DB as its own record where Gallery can see it.
        // We just acknowledge receipt.
        return ok(
            "ZIP accepted (${extractedPngs.size} frames), " +
                    "run=$effectiveRunId section=$sectionIndex part=$partParam"
        )
    }


    private fun <T> blockingIo(block: suspend () -> T): T =
        kotlinx.coroutines.runBlocking { withContext(Dispatchers.IO) { block() } }


    private fun handlePng(
        sessionId: String,
        filename: String,
        tmpFile: File,
        isPmfi: Boolean
    ): Response {
        val sessionDir = File(sessionsRoot, sessionId).apply { mkdirs() }
        val target = File(sessionDir, filename)

        try {
            Log.i(TAG, "AMSI: saving PNG -> ${target.absolutePath}")
            tmpFile.copyTo(target, overwrite = true)
        } finally {
            tmpFile.delete()
        }

        val list = amsiUploads.getOrPut(sessionId) { synchronizedList(mutableListOf()) }
        list.add(target.absolutePath)

        lastSeenAt[sessionId] = System.currentTimeMillis()
        UploadProgressBus.uploadProgress.postValue(sessionId to list.size)
        Log.i(TAG, "PNG saved: '$filename' (session=$sessionId, count=${list.size}, pmfi=$isPmfi)")

        // ✅ ONLY finalize AMSI bursts (16-frame captures)
        if (!isPmfi && list.size == IMAGES_PER_AMSI && finalisedKeys.add(sessionId)) {
            val completedAt = System.currentTimeMillis()
            insertSessionAsync(
                key = sessionId,
                imagePaths = list.toList(),
                type = "AMSI",
                label = null,
                completedAtMillis = completedAt,
                runId = sessionId // we store runId=sessionId for AMSI
            )
            amsiUploads.remove(sessionId)
            lastSeenAt.remove(sessionId)
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
                Log.i(TAG, "PMFI ZIP: extracted PNG -> ${target.absolutePath}")
                out.add(target.absolutePath)
            }
        }
        return out.sorted()
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
        }
    }

    // --------------------------------------------------------------------------------------------
    // DB insert with best-effort location (duplicate-safe)
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

        // if we can't resolve location fast, we still insert after timeout
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

                    val locString = if (loc != null) {
                        // Always have at least lat,lon
                        val basic = String.format(
                            Locale.US,
                            "%.5f,%.5f",
                            loc.latitude,
                            loc.longitude
                        )

                        // Try reverse geocode, but if it fails we still keep basic
                        val pretty = try {
                            val geocoder = Geocoder(context, Locale.getDefault())
                            val list = geocoder.getFromLocation(loc.latitude, loc.longitude, 1)
                            if (!list.isNullOrEmpty()) {
                                val a = list[0]
                                val city = a.locality ?: ""
                                val area = a.subAdminArea ?: ""
                                val country = a.countryName ?: ""
                                listOf(city, area, country)
                                    .filter { s -> s.isNotBlank() }
                                    .joinToString(", ")
                                    .ifBlank { basic }
                            } else {
                                basic
                            }
                        } catch (geocodeErr: Exception) {
                            Log.w(TAG, "Geocoder failed: ${geocodeErr.message}")
                            basic
                        }

                        pretty
                    } else {
                        "Unknown"
                    }

                    saveSessionToDb(
                        completedAtMillis = completedAtMillis,
                        timestampStr = tsStr,
                        locationStr = locString,
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
    private fun getBestEffortLocationString(
        onResult: (String) -> Unit
    ) {
        val fused = LocationServices.getFusedLocationProviderClient(context)

        // We'll race fused.lastLocation against a timeout, just like AMSI does
        scope.launch {
            // timeout job
            val timeout = launch {
                delay(LOCATION_TIMEOUT_MS)
                onResult("Unknown")
            }

            try {
                fused.lastLocation
                    .addOnSuccessListener { loc ->
                        if (!timeout.isActive) return@addOnSuccessListener
                        timeout.cancel()

                        if (loc == null) {
                            onResult("Unknown")
                            return@addOnSuccessListener
                        }

                        val basic = String.format(
                            Locale.US,
                            "%.5f,%.5f",
                            loc.latitude,
                            loc.longitude
                        )

                        // Try reverse geocode to make it pretty, fallback to lat,lon
                        val pretty = try {
                            val geocoder = Geocoder(context, Locale.getDefault())
                            val list = geocoder.getFromLocation(loc.latitude, loc.longitude, 1)
                            if (!list.isNullOrEmpty()) {
                                val a = list[0]
                                val city = a.locality ?: ""
                                val area = a.subAdminArea ?: ""
                                val country = a.countryName ?: ""
                                listOf(city, area, country)
                                    .filter { s -> s.isNotBlank() }
                                    .joinToString(", ")
                                    .ifBlank { basic }
                            } else {
                                basic
                            }
                        } catch (geocodeErr: Exception) {
                            Log.w(TAG, "Geocoder failed in PMFI row: ${geocodeErr.message}")
                            basic
                        }

                        onResult(pretty)
                    }
                    .addOnFailureListener { err ->
                        if (!timeout.isActive) return@addOnFailureListener
                        timeout.cancel()
                        Log.w(TAG, "lastLocation failed in PMFI row: ${err.message}")
                        onResult("Unknown")
                    }
            } catch (e: Exception) {
                if (timeout.isActive) {
                    timeout.cancel()
                    Log.w(TAG, "Location flow threw in PMFI row: ${e.message}")
                    onResult("Unknown")
                }
            }
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
                val dao = AppDatabase.getDatabase(context).sessionDao()
                dao.upsert(
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
                // If we got metadata earlier, apply it now
                runId?.let { id ->
                    pendingEnv.remove(id)?.let { (t, h, ts) ->
                        try {
                            dao.updateEnvByRunId(id, t, h, ts)
                            Log.i(TAG, "Applied pending env to runId=$id (T=$t, RH=$h, ts=$ts)")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to apply pending env for runId=$id: ${e.message}")
                        }
                    }
                }

                Log.i(TAG, "Inserted $type session (${imagePaths.size} images) run=$runId ini=$iniName label=$label idx=$sectionIndex")
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
            appendLine("  dbInserted : ${dbInserted.size}")
        }
        return ok(b.toString())
    }
}
