package com.example.msiandroidapp.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * AppDatabase (stable baseline v2).
 *
 * Entities:
 *  - Session: AMSI/PMFI run/section (images + metadata)
 *  - CalibrationProfile: device calibration (LED norms etc.)
 *
 * Policy in dev:
 *  - We use destructive migration for ANY version change (up or down).
 *  - We ALSO switch to a new on-disk filename ("gallery_db_v2") so stale v1 files don't block startup.
 *  - When you care about preserving user data, remove the fallbacks and add proper Migration(...) objects.
 */
@Database(
    entities = [
        Session::class,
        CalibrationProfile::class
    ],
    version = 2,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao
    abstract fun calibrationDao(): CalibrationDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private const val DB_NAME = "gallery_db_v2" // new file name; guarantees a clean open after schema changes

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                )
                    // DEV-SAFE: accept ANY upgrade/downgrade by wiping + recreating.
                    .fallbackToDestructiveMigration()                 // on upgrade (e.g., 1 -> 2)
                    .fallbackToDestructiveMigrationOnDowngrade()      // on downgrade (e.g., 3 -> 2)
                    // If you *really* need to be explicit, keep this too:
                    .fallbackToDestructiveMigrationFrom(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
                    .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                    .build()
                INSTANCE = instance
                instance
            }
        }

        /** Optional helper to hard-reset the DB file (useful in debug settings). */
        fun nuke(context: Context) {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
                context.deleteDatabase(DB_NAME)
            }
        }
    }
}
