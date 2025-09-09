package com.example.msiandroidapp.data

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface CalibrationDao {
    @Query("SELECT * FROM calibration_profiles ORDER BY id DESC")
    fun getAll(): LiveData<List<CalibrationProfile>>

    @Query("SELECT * FROM calibration_profiles WHERE id = :id")
    fun getById(id: Long): LiveData<CalibrationProfile?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: CalibrationProfile): Long

    @Delete
    suspend fun delete(profile: CalibrationProfile)
}
