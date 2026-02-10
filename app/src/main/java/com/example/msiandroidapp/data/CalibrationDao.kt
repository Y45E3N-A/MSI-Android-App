package com.example.msiandroidapp.data

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface CalibrationDao {

    // --- Basic queries for UI ---
    @Query("SELECT * FROM calibration_profiles ORDER BY completedAtMillis DESC")
    fun getAll(): LiveData<List<CalibrationProfile>>

    @Query("SELECT * FROM calibration_profiles WHERE runId = :runId LIMIT 1")
    fun getByRunIdLive(runId: String): LiveData<CalibrationProfile?>

    @Query("SELECT * FROM calibration_profiles WHERE runId = :runId LIMIT 1")
    suspend fun getByRunId(runId: String): CalibrationProfile?

    // --- Low-level upserts (insert/update) ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(profile: CalibrationProfile)

    @Update
    suspend fun update(profile: CalibrationProfile)

    @Query("DELETE FROM calibration_profiles WHERE runId = :runId")
    suspend fun deleteByRunId(runId: String)

    // --- High-level helpers that UploadServer will call ---

    /**
     * Append one new calibration image path (CAL_image_XX.png) to this run.
     *
     * If the row doesn't already exist, create it with this timestamp.
     *
     * If it DOES exist, we:
     *  - merge/append the image path
     *  - DO NOT touch completedAtMillis or timestampStr (keep original "when it was taken")
     */
    @Transaction
    suspend fun upsertCalibrationImage(
        runId: String,
        timestampMillis: Long,
        timestampStr: String,
        newImagePath: String,
        channelIdx: Int?,
        wavelengthNm: String?
    ) {
        val existing = getByRunId(runId)

        // Build merged path list
        val paths: MutableList<String> = mutableListOf()
        if (existing != null && existing.imagePathsJson.isNotBlank()) {
            paths.addAll(jsonArrayToMutableList(existing.imagePathsJson))
        }
        if (!paths.contains(newImagePath)) {
            paths.add(newImagePath)
        }

        // Build/refresh summary for UI
        val newSummary = buildString {
            append(paths.size).append(" images")
            if (channelIdx != null && channelIdx >= 0) {
                append(" | ch ").append(channelIdx)
            }
            if (!wavelengthNm.isNullOrBlank()) {
                append(" | ").append(wavelengthNm).append("nm")
            }
        }

        val updated = CalibrationProfile(
            id = existing?.id, // <-- carry forward if present (null if new)

            runId = runId,

            // Preserve original capture time if row already exists
            completedAtMillis = existing?.completedAtMillis ?: timestampMillis,
            timestampStr      = existing?.timestampStr      ?: timestampStr,

            imagePathsJson = mutableListToJsonArray(paths),

            ledNormsJson = existing?.ledNormsJson,
            calResultsJson = existing?.calResultsJson,
            targetDn     = existing?.targetDn,
            envTempC     = existing?.envTempC,
            envHumidity  = existing?.envHumidity,
            envTsUtc     = existing?.envTsUtc,
            tsUtcOverall = existing?.tsUtcOverall,

            summary = newSummary
        )

        insertOrReplace(updated)
    }


    /**
     * Merge metadata (LED norms, env snapshot, target DN) into an existing calibration row.
     *
     * If the row doesn't exist yet (race condition), we create a NEW row:
     *  - completedAtMillis / timestampStr become "now"
     *  - imagePathsJson starts as empty "[]"
     *
     * If the row DOES exist, we:
     *  - keep its completedAtMillis and timestampStr exactly as-is
     *  - update ledNorms/env/targetDn/etc
     *  - keep ALL previously stored imagePathsJson
     */
    @Transaction
    suspend fun upsertCalibrationMetadata(
        runId: String,
        ledNormsJson: String?,        // e.g. "[0.5,0.48,...]"
        calResultsJson: String?,      // per-channel results from metadata.json
        envTempC: Double?,
        envHumidity: Double?,
        envTsUtc: String?,
        targetDn: Double?,
        tsUtcOverall: String?
    ) {
        val existing = getByRunId(runId)

        val whenMillis = System.currentTimeMillis()
        val niceTs = timestampMillisToDisplayString(whenMillis)

        val pathsJson = existing?.imagePathsJson ?: "[]"

        val newSummary = buildString {
            append(jsonArrayToMutableList(pathsJson).size).append(" images")
            if (targetDn != null) {
                append(" | target ").append(targetDn.toInt())
            }
            if (envTempC != null && envHumidity != null) {
                append(" | ")
                append(String.format("%.1f°C", envTempC))
                append(", ")
                append(String.format("%.0f%% RH", envHumidity))
            }
        }

        val updated = CalibrationProfile(
            id = existing?.id, // <-- keep existing row id if we had one

            runId = runId,

            // keep original timestamps if we already had them
            completedAtMillis = existing?.completedAtMillis ?: whenMillis,
            timestampStr      = existing?.timestampStr      ?: niceTs,

            imagePathsJson = pathsJson,

            ledNormsJson = ledNormsJson ?: existing?.ledNormsJson,
            calResultsJson = calResultsJson ?: existing?.calResultsJson,
            targetDn     = targetDn     ?: existing?.targetDn,
            envTempC     = envTempC     ?: existing?.envTempC,
            envHumidity  = envHumidity  ?: existing?.envHumidity,
            envTsUtc     = envTsUtc     ?: existing?.envTsUtc,
            tsUtcOverall = tsUtcOverall ?: existing?.tsUtcOverall,

            summary = newSummary
        )

        insertOrReplace(updated)
    }


    // ----------------------------
    // Local helper utils
    // ----------------------------

    /**
     * Turn a JSON array string like '["/path/a.png","/path/b.png"]'
     * into a mutable list. If it's invalid, return empty list.
     */
    private fun jsonArrayToMutableList(json: String): MutableList<String> {
        return try {
            val arr = org.json.JSONArray(json)
            MutableList(arr.length()) { i -> arr.optString(i) }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    /**
     * Turn a list of file paths into a stable JSON array string.
     */
    private fun mutableListToJsonArray(list: List<String>): String {
        val arr = org.json.JSONArray()
        list.forEach { arr.put(it) }
        return arr.toString()
    }

    /**
     * Format millis -> "dd-MM-yyyy HH:mm:ss" (same style you're using elsewhere).
     */
    private fun timestampMillisToDisplayString(millis: Long): String {
        val sdf = java.text.SimpleDateFormat("dd-MM-yyyy HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(millis))
    }
}
