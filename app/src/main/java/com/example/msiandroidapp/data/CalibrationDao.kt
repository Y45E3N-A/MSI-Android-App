package com.example.msiandroidapp.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CalibrationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: CalibrationProfile): Long

    @Query("SELECT * FROM calibration_profiles ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<CalibrationProfile>>

    @Query("SELECT * FROM calibration_profiles ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatest(): CalibrationProfile?

    @Query("SELECT * FROM calibration_profiles WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): CalibrationProfile?

    @Delete
    suspend fun delete(profile: CalibrationProfile)
}
