package com.example.msiandroidapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class Session(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: String,
    val location: String,
    val imagePaths: List<String>,
    val type: String = "AMSI",          // "AMSI" or "PMFI"
    val label: String? = null // e.g. "smfi1" section name for PMFI
)
