package com.example.msiandroidapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "calibration_profiles")
data class CalibrationProfile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    // Friendly label you can show in a list, e.g. "Cal • 2025-09-09 15:10"
    val name: String,

    // For your instrument identifier (e.g., "FB1")
    val machine: String,

    // Unix epoch millis
    val timestamp: Long = System.currentTimeMillis(),

    // ROI used during that calibration
    val roiX: Double,
    val roiY: Double,
    val roiW: Double,
    val roiH: Double,

    // Target average intensity used by the routine
    val targetIntensity: Double,

    // 16 per-channel normalisation factors returned by the Pi
    val ledNorms: List<Double>,

    // Optional notes or server message
    val notes: String? = null
)
