package com.example.msiandroidapp.data

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface SessionDao {

    // Newest-first: prefer completedAtMillis, fall back to createdAt, then id for stability.
    @Query("""
        SELECT * FROM sessions
        ORDER BY 
            COALESCE(completedAtMillis, createdAt) DESC,
            createdAt DESC,
            id DESC
    """)
    fun getAllSessions(): LiveData<List<Session>>

    @Query("SELECT * FROM sessions WHERE id = :sessionId LIMIT 1")
    fun getSessionById(sessionId: Long): LiveData<Session?>

    // --- Primitive ops ---
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

    // --- Merge key for PMFI (one row per runId) ---
    @Query("SELECT * FROM sessions WHERE runId = :runId LIMIT 1")
    suspend fun findByRunId(runId: String): Session?

    /**
     * Merge-by-runId semantics:
     *  - If runId is NULL/blank -> insert a fresh row.
     *  - If runId is present -> update existing row with same runId; otherwise insert.
     */
    @Transaction
    suspend fun upsert(session: Session): Long {
        val key = session.runId?.takeIf { it.isNotBlank() }
        return if (key == null) {
            insert(session)
        } else {
            val existing = findByRunId(key)
            if (existing == null) {
                insert(session)
            } else {
                update(session.copy(id = existing.id))
                existing.id
            }
        }
    }

    // ---- Environment updates (metadata JSON) ----
    @Query("""
        UPDATE sessions
        SET envTempC = :tempC, envHumidity = :humidity, envTsUtc = :tsUtc
        WHERE runId = :runId
    """)
    suspend fun updateEnvByRunId(
        runId: String,
        tempC: Double?,
        humidity: Double?,
        tsUtc: String?
    ): Int

    /**
     * Bulk variant: applies the same merge semantics per item.
     */
    @Transaction
    suspend fun upsertAll(sessions: List<Session>) {
        for (s in sessions) upsert(s)
    }
}
