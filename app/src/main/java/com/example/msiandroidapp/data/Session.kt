package com.example.msiandroidapp.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per capture "session":
 *  - AMSI: the 16 PNGs (single-shot); we set runId = sessionId from the Pi
 *  - PMFI: all frames from a full INI execution (merged across ZIP parts/sections) keyed by runId
 */
@Entity(
    tableName = "sessions",
    indices = [
        // Enforce a single logical row per PMFI runId.
        // (SQLite allows multiple NULLs, so AMSI rows without runId would still be allowed,
        // but we set runId=sessionId for AMSI so metadata can match.)
        Index(value = ["runId"], unique = true)
    ]
)
data class Session(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,

    // Chronology
    val createdAt: Long = System.currentTimeMillis(),
    val completedAtMillis: Long? = null,   // prefer non-null when inserting

    // Legacy display timestamp (kept for UI formatting)
    val timestamp: String,

    // Context
    val location: String,

    // Files (persisted via your existing TypeConverters)
    val imagePaths: List<String>,

    // Type: "AMSI" | "PMFI" | future
    val type: String = "AMSI",

    // Optional display label (e.g., PMFI section tag)
    val label: String? = null,

    // ---- PMFI/AMSI grouping ----
    // For PMFI: ID of the run from the Pi.
    // For AMSI: set runId = sessionId so metadata JSON can update the row.
    val runId: String? = null,      // e.g., "pmfi_20251012_010203_1234"
    val iniName: String? = null,    // e.g., "quick_sweep.ini"
    val sectionIndex: Int? = null,  // when used: 0-based section index

    // ---- Environment snapshot (from *_metadata.json) ----
    val envTempC: Double? = null,
    val envHumidity: Double? = null,
    val envTsUtc: String? = null
)
