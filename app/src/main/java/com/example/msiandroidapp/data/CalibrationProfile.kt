package com.example.msiandroidapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "calibration_profiles")
data class CalibrationProfile(
    @PrimaryKey(autoGenerate = true)
    val id: Long? = null,  // <-- nullable now

    val runId: String,
    val completedAtMillis: Long,
    val timestampStr: String,
    val imagePathsJson: String,
    val ledNormsJson: String?,
    val targetDn: Double?,
    val envTempC: Double?,
    val envHumidity: Double?,
    val envTsUtc: String?,
    val tsUtcOverall: String?,
    val summary: String?
)
