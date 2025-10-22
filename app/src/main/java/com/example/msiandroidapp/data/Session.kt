package com.example.msiandroidapp.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per capture "session":
 *  - AMSI: the 16 PNGs (single-shot)
 *  - PMFI: all frames from a full INI execution (merged across ZIP parts/sections) keyed by runId
 */
@Entity(
    tableName = "sessions",
    indices = [
        // Ensures one logical row per PMFI run; multiple NULLs for AMSI are allowed.
        Index(value = ["runId"], unique = true)
    ]
)
data class Session(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,

    // Chronology
    val createdAt: Long = System.currentTimeMillis(),
    val completedAtMillis: Long? = null,            // prefer non-null when inserting

    // Legacy display timestamp (kept for now)
    val timestamp: String,

    // Context
    val location: String,

    // Files (Room uses your Converters to persist)
    val imagePaths: List<String>,

    // Type: "AMSI" | "PMFI" | future
    val type: String = "AMSI",

    // Optional display label (e.g., PMFI section tag)
    val label: String? = null,

    // ---- PMFI run grouping ----
    // Same value for all uploads belonging to a single INI execution (set by Pi as runId/session_id)
    val runId: String? = null,          // e.g., "pmfi_20251012_010203_1234"
    val iniName: String? = null,        // e.g., "quick_sweep.ini"
    val sectionIndex: Int? = null       // (when used) 0-based section index
)
