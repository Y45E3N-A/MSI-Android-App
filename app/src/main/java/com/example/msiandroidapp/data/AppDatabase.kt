package com.example.msiandroidapp.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * AppDatabase
 *
 * This is the canonical local database for the MSI Android app.
 *
 * Baseline rules (very important):
 *
 * 1. version = 1 is the first "stable" schema.
 *    Everything before this (old dev versions like 4,5,6,12 etc.) is considered legacy
 *    and is NOT supported anymore.
 *
 * 2. All devices running this build will create a fresh database at version 1.
 *    If an older incompatible database exists, it will be destroyed and recreated.
 *    (See fallbackToDestructiveMigration below.)
 *
 * 3. From now on:
 *    - If you change schema (add/remove/rename columns), you MUST:
 *        a) bump `version` to 2, 3, ...,
 *        b) add a Migration(prevVersion, newVersion),
 *        c) register it in getDatabase() with .addMigrations(...),
 *        d) and REMOVE the destructive fallback in production builds.
 *
 * Entities:
 *  - Session:         represents an AMSI/PMFI run or section (images, timestamps, env data)
 *  - CalibrationProfile: represents a calibration capture set (LED norms, target DN, etc.)
 */

@Database(
    entities = [
        Session::class,
        CalibrationProfile::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao
    abstract fun calibrationDao(): CalibrationDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Get the singleton instance of the database.
         *
         * For this baseline (version = 1), we allow destructive migration.
         * That means:
         *  - If the on-device DB schema is incompatible, Room will wipe it and recreate it
         *    instead of crashing the app.
         *
         * After version 1 is deployed and we start caring about keeping data across updates,
         * we will:
         *   - bump @Database(...) version to 2,
         *   - add a proper MIGRATION_1_2 object,
         *   - register it using .addMigrations(MIGRATION_1_2),
         *   - REMOVE the destructive fallbacks.
         */
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance =
                    Room.databaseBuilder(
                        context.applicationContext,
                        AppDatabase::class.java,
                        "gallery_db"
                    )
                        // Destructive fallback:
                        //   This is intentional for version = 1.
                        //   It guarantees devices with weird old dev schemas (v4/v12/etc.)
                        //   don't crash the app. Instead, they get a clean fresh DB
                        //   matching the current entities.
                        //
                        //   IMPORTANT:
                        //   When you bump to version 2+ and start doing real migrations,
                        //   remove these two lines so user data persists.
                        .fallbackToDestructiveMigration()
                        .fallbackToDestructiveMigrationOnDowngrade()
                        .build()

                INSTANCE = instance
                instance
            }
        }
    }
}
