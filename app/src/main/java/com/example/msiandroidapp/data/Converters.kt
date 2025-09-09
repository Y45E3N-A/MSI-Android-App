package com.example.msiandroidapp.data

import androidx.room.TypeConverter

class Converters {

    // --- Existing converters for List<String> (e.g., image paths) ---
    @TypeConverter
    fun fromString(value: String): List<String> =
        if (value.isEmpty()) emptyList() else value.split("||")

    @TypeConverter
    fun listToString(list: List<String>): String =
        list.joinToString("||")

    // --- NEW converters for List<Double> (LED norms, etc.) ---
    // Stored as CSV: "0.98,1.02,..."
    @TypeConverter
    fun fromDoubleList(list: List<Double>?): String? =
        list?.joinToString(",")

    @TypeConverter
    fun toDoubleList(csv: String?): List<Double> =
        csv?.split(",")
            ?.mapNotNull { it.trim().toDoubleOrNull() }
            ?: emptyList()
}
