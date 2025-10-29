package com.example.msiandroidapp.data

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface SessionDao {

    // --------------------------------------------------------------------------------------------
    // LiveData queries for UI
    // --------------------------------------------------------------------------------------------

    /**
     * All sessions (AMSI, PMFI sections, calibration pseudo-sessions if any),
     * newest first.
     *
     * "Newest" = completedAtMillis if set, else createdAt, then id.
     */
    @Query("""
        SELECT * FROM sessions
        ORDER BY 
            COALESCE(completedAtMillis, createdAt) DESC,
            createdAt DESC,
            id DESC
    """)
    fun getAllSessions(): LiveData<List<Session>>

    /**
     * Observe a single session row by DB ID.
     * Used by SessionDetailActivity.
     */
    @Query("SELECT * FROM sessions WHERE id = :sessionId LIMIT 1")
    fun getSessionById(sessionId: Long): LiveData<Session?>


    // --------------------------------------------------------------------------------------------
    // Non-LiveData fetch helpers (for internal logic / background work)
    // --------------------------------------------------------------------------------------------

    /**
     * Get the single most recent session row (AMSI or PMFI),
     * using the exact same recency ordering as getAllSessions().
     *
     * This is useful after an AMSI run completes, so we can
     * locate its folder on disk and total the bytes for the toast.
     */
    @Query("""
        SELECT * FROM sessions
        ORDER BY 
            COALESCE(completedAtMillis, createdAt) DESC,
            createdAt DESC,
            id DESC
        LIMIT 1
    """)
    suspend fun getMostRecentSession(): Session?

    /**
     * Look up a row by runId (used for AMSI, where runId == sessionId from the Pi).
     */
    @Query("SELECT * FROM sessions WHERE runId = :runId LIMIT 1")
    suspend fun findByRunId(runId: String): Session?

    /**
     * Look up a row by (runId, sectionIndex) pair (used for PMFI sections).
     */
    @Query("""
        SELECT * FROM sessions
        WHERE runId = :runId AND sectionIndex = :sectionIndex
        LIMIT 1
    """)
    suspend fun findByRunIdAndSection(
        runId: String,
        sectionIndex: Int
    ): Session?


    // --------------------------------------------------------------------------------------------
    // Primitive CRUD
    // --------------------------------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: Session): Long

    @Update
    suspend fun update(session: Session)

    @Delete
    suspend fun delete(session: Session)

    @Query("DELETE FROM sessions WHERE id = :sessionId")
    suspend fun deleteById(sessionId: Long)

    @Query("DELETE FROM sessions")
    suspend fun deleteAll()


    // --------------------------------------------------------------------------------------------
    // AMSI / generic single-row upsert
    //
    // For AMSI we currently set runId = sessionId from the Pi, and sectionIndex is usually null.
    //
    // Behaviour:
    //  - If session.runId is null/blank, always INSERT a new row (treated as standalone session).
    //  - If session.runId is non-blank:
    //      * If a row with that runId exists, UPDATE/merge it.
    //      * Else, INSERT as new.
    //
    // NOTE: PMFI does NOT use this. PMFI wants multiple rows per runId,
    //       so it uses upsertPmfiSection(...) instead.
    // --------------------------------------------------------------------------------------------

    @Transaction
    suspend fun upsert(session: Session): Long {
        val key = session.runId?.takeIf { it.isNotBlank() }

        // No runId? Always insert new row (can't merge reliably)
        if (key == null) {
            return insert(session)
        }

        val existing = findByRunId(key)
        return if (existing == null) {
            // First time we've seen this runId -> insert as-is.
            insert(session)
        } else {
            // Merge WITHOUT wiping original timestamps.
            val merged = existing.copy(
                // keep the original id and oldest timestamps where possible
                createdAt = if (existing.createdAt > 0) existing.createdAt else session.createdAt,
                completedAtMillis = existing.completedAtMillis ?: session.completedAtMillis,

                // keep the first pretty timestamp string if we already had one
                timestamp = if (existing.timestamp.isNotBlank()) {
                    existing.timestamp
                } else {
                    session.timestamp
                },

                // update live / mutable fields
                location = if (session.location.isNotBlank()) {
                    session.location
                } else {
                    existing.location
                },

                imagePaths = if (session.imagePaths.isNotEmpty()) {
                    session.imagePaths
                } else {
                    existing.imagePaths
                },

                type = if (session.type.isNotBlank()) {
                    session.type
                } else {
                    existing.type
                },

                label = session.label ?: existing.label,
                runId = existing.runId ?: session.runId,
                iniName = session.iniName ?: existing.iniName,
                sectionIndex = session.sectionIndex ?: existing.sectionIndex,

                envTempC = session.envTempC ?: existing.envTempC,
                envHumidity = session.envHumidity ?: existing.envHumidity,
                envTsUtc = session.envTsUtc ?: existing.envTsUtc
            )

            update(merged)
            existing.id
        }
    }

    /**
     * Bulk helper: loops upsert() for each.
     */
    @Transaction
    suspend fun upsertAll(sessions: List<Session>) {
        for (s in sessions) upsert(s)
    }


    // --------------------------------------------------------------------------------------------
    // PMFI section-based storage
    //
    // We want ONE row PER SECTION of a PMFI run (e.g. each LED sweep or frequency sweep).
    // Key is (runId, sectionIndex).
    //
    // Behaviour:
    //  - If we have not seen (runId, sectionIndex), INSERT a new row type="PMFI".
    //  - If we have seen it, UPDATE that row:
    //        * merge/append new image paths, dedup, sort,
    //        * refresh timestamps and env data.
    //
    // This runs every time we receive a new ZIP part for that section.
    // --------------------------------------------------------------------------------------------

    @Transaction
    suspend fun upsertPmfiSection(
        runId: String,
        sectionIndex: Int,
        iniName: String?,
        newImagePaths: List<String>,
        completedAtMillis: Long,
        timestampStr: String,
        locationStr: String,
        envTempC: Double?,
        envHumidity: Double?,
        envTsUtc: String?,
        label: String? // e.g. "Section 003 (660nm)"
    ): Long {

        val existing = findByRunIdAndSection(runId, sectionIndex)

        return if (existing == null) {
            // First time seeing this (runId, sectionIndex): brand new row.
            insert(
                Session(
                    createdAt = completedAtMillis,
                    completedAtMillis = completedAtMillis,
                    timestamp = timestampStr,
                    location = locationStr,
                    imagePaths = newImagePaths.distinct(),
                    type = "PMFI",
                    label = label,
                    runId = runId,
                    iniName = iniName,
                    sectionIndex = sectionIndex,
                    envTempC = envTempC,
                    envHumidity = envHumidity,
                    envTsUtc = envTsUtc
                )
            )
        } else {
            // Merge onto existing row.
            val mergedPaths = (existing.imagePaths + newImagePaths)
                .distinct()
                .sorted()

            update(
                existing.copy(
                    completedAtMillis = completedAtMillis,
                    timestamp = timestampStr,
                    location = if (locationStr.isNotBlank()) {
                        locationStr
                    } else {
                        existing.location
                    },
                    imagePaths = mergedPaths,
                    iniName = iniName ?: existing.iniName,
                    envTempC = envTempC ?: existing.envTempC,
                    envHumidity = envHumidity ?: existing.envHumidity,
                    envTsUtc = envTsUtc ?: existing.envTsUtc,
                    label = label ?: existing.label
                )
            )
            existing.id
        }
    }


    // --------------------------------------------------------------------------------------------
    // Environment metadata updates
    //
    // The Pi sends JSON with temp/humidity/timestamp tagged with a runId.
    // We want every row with that runId (AMSI row or all PMFI section rows)
    // to inherit those env values.
    // --------------------------------------------------------------------------------------------

    @Query("""
        UPDATE sessions
        SET envTempC = :tempC,
            envHumidity = :humidity,
            envTsUtc = :tsUtc
        WHERE runId = :runId
    """)
    suspend fun updateEnvByRunId(
        runId: String,
        tempC: Double?,
        humidity: Double?,
        tsUtc: String?
    ): Int
}
