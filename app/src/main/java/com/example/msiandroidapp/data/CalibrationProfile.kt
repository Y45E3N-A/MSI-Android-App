package com.example.msiandroidapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single calibration output/profile saved by the app.
 * previewPath can point to a generated chart/image if you have one (optional).
 */
@Entity(tableName = "calibration_profiles")
data class CalibrationProfile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val updatedAtMillis: Long? = null,
    val name: String,             // e.g. "White-balance", "Radiometric v1"
    val summary: String?,         // small text like "ΔE=1.8, gain=1.12"
    val previewPath: String?,      // optional image path for thumbnail"
    val ledNorms: List<Double> = emptyList()
)
