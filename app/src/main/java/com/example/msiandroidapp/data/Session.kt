package com.example.msiandroidapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class Session(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: String,
    val location: String,
    val imagePaths: List<String>
)
