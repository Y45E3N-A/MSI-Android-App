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
     * newest first. "Newest" = completedAtMillis if set, else createdAt, then id.
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
    // Semantics:
    //  - If session.runId is null/blank, we ALWAYS insert a new row (new standalone session).
    //  - If session.runId is non-blank, we check if a row with that runId already exists.
    //      - If yes, we UPDATE that row.
    //      - If not, we INSERT a new row.
    //
    // NOTE: This is *not* used for PMFI sections anymore, because PMFI wants multiple rows per runId.
    //       PMFI uses upsertPmfiSection(...) below.
    // --------------------------------------------------------------------------------------------

    @Query("SELECT * FROM sessions WHERE runId = :runId LIMIT 1")
    suspend fun findByRunId(runId: String): Session?

    @Transaction
    suspend fun upsert(session: Session): Long {
        val key = session.runId?.takeIf { it.isNotBlank() }
        return if (key == null) {
            // No runId? Treat as brand new capture row.
            insert(session)
        } else {
            val existing = findByRunId(key)
            if (existing == null) {
                // First time we've seen this runId -> insert.
                insert(session)
            } else {
                // Row with this runId already exists -> update it in-place.
                update(session.copy(id = existing.id))
                existing.id
            }
        }
    }

    /**
     * Bulk helper: just loops upsert() for each element.
     */
    @Transaction
    suspend fun upsertAll(sessions: List<Session>) {
        for (s in sessions) upsert(s)
    }


    // --------------------------------------------------------------------------------------------
    // PMFI section-based storage
    //
    // Now we want ONE row PER SECTION of a PMFI run (e.g. each LED wavelength sweep).
    // Key is (runId, sectionIndex).
    //
    // Behaviour:
    //  - If there's no row yet for (runId, sectionIndex), insert a new Session row with type="PMFI".
    //  - If that row already exists, merge:
    //        * append any new PNG paths (dedup + sort),
    //        * refresh timestamps,
    //        * carry over env data if provided.
    //
    // This gets called every time we receive a new ZIP "part" for that section.
    // --------------------------------------------------------------------------------------------

    @Query("""
        SELECT * FROM sessions
        WHERE runId = :runId AND sectionIndex = :sectionIndex
        LIMIT 1
    """)
    suspend fun findByRunIdAndSection(
        runId: String,
        sectionIndex: Int
    ): Session?

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
            // First time seeing this (runId, sectionIndex): make a brand new row.
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
            // We've already got a row for this (runId, sectionIndex):
            // merge images and update metadata.
            val mergedPaths = (existing.imagePaths + newImagePaths)
                .distinct()
                .sorted()

            update(
                existing.copy(
                    completedAtMillis = completedAtMillis,
                    timestamp = timestampStr,
                    location = locationStr.ifBlank { existing.location },
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
    // The Pi sends a small JSON with temp/humidity/timestamp for a runId.
    // We want every PMFI section row for that runId to get those env values,
    // AND also any AMSI row that reused runId=sessionId.
    // This UPDATE hits all rows for that runId.
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
