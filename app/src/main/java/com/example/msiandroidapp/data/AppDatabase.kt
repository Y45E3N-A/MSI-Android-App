package com.example.msiandroidapp.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Session::class, CalibrationProfile::class],
    version = 10,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao
    abstract fun calibrationDao(): CalibrationDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        /**
         * v4 -> v5:
         * - Add sessions.createdAt (epoch millis) to preserve chronological ordering.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!columnExists(db, "sessions", "createdAt")) {
                    db.execSQL(
                        """
                        ALTER TABLE sessions
                        ADD COLUMN createdAt INTEGER NOT NULL
                        DEFAULT (strftime('%s','now')*1000)
                        """.trimIndent()
                    )
                }
            }
        }

        /**
         * v5 -> v6:
         * - Add sessions.completedAtMillis, type, label, location, imagePaths
         * - Backfill completedAtMillis = createdAt for stable ordering
         * - Add createdAt/updatedAtMillis to calibration table if missing
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (tableExists(db, "sessions")) {
                    if (!columnExists(db, "sessions", "completedAtMillis")) {
                        db.execSQL("ALTER TABLE sessions ADD COLUMN completedAtMillis INTEGER")
                        db.execSQL(
                            """
                            UPDATE sessions
                            SET completedAtMillis = createdAt
                            WHERE completedAtMillis IS NULL
                            """.trimIndent()
                        )
                    }
                    if (!columnExists(db, "sessions", "type")) {
                        db.execSQL("ALTER TABLE sessions ADD COLUMN type TEXT")
                    }
                    if (!columnExists(db, "sessions", "label")) {
                        db.execSQL("ALTER TABLE sessions ADD COLUMN label TEXT")
                    }
                    if (!columnExists(db, "sessions", "location")) {
                        db.execSQL("ALTER TABLE sessions ADD COLUMN location TEXT")
                    }
                    if (!columnExists(db, "sessions", "imagePaths")) {
                        db.execSQL("ALTER TABLE sessions ADD COLUMN imagePaths TEXT")
                    }
                }

                val calibTable = when {
                    tableExists(db, "calibration_profiles") -> "calibration_profiles"
                    tableExists(db, "CalibrationProfile")    -> "CalibrationProfile"
                    else -> null
                }
                calibTable?.let { table ->
                    if (!columnExists(db, table, "createdAt")) {
                        db.execSQL(
                            """
                            ALTER TABLE $table
                            ADD COLUMN createdAt INTEGER NOT NULL
                            DEFAULT (strftime('%s','now')*1000)
                            """.trimIndent()
                        )
                    }
                    if (!columnExists(db, table, "updatedAtMillis")) {
                        db.execSQL("ALTER TABLE $table ADD COLUMN updatedAtMillis INTEGER")
                    }
                }
            }
        }

        /**
         * v6 -> v7:
         * - Add PMFI run grouping columns: runId, iniName, sectionIndex
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (tableExists(db, "sessions")) {
                    if (!columnExists(db, "sessions", "runId")) {
                        db.execSQL("ALTER TABLE sessions ADD COLUMN runId TEXT")
                    }
                    if (!columnExists(db, "sessions", "iniName")) {
                        db.execSQL("ALTER TABLE sessions ADD COLUMN iniName TEXT")
                    }
                    if (!columnExists(db, "sessions", "sectionIndex")) {
                        db.execSQL("ALTER TABLE sessions ADD COLUMN sectionIndex INTEGER")
                    }
                }
            }
        }

        /**
         * v7 -> v8:
         * - De-dupe any duplicate non-null runIds (keep newest row per runId)
         * - Create UNIQUE index on sessions(runId)
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!tableExists(db, "sessions")) return

                // 1. Drop legacy unique index on runId if it exists.
                // SQLite doesn't have "DROP INDEX IF EXISTS" in older APIs via SupportSQLiteDatabase,
                // but execSQL will just throw if it doesn't exist, so wrap in runCatching.
                runCatching {
                    db.execSQL("DROP INDEX IF EXISTS index_sessions_runId")
                }

                // 2. Create the new composite unique index.
                // Note: sectionIndex can be NULL for AMSI rows. SQLite allows multiple NULLs in a UNIQUE index,
                // so AMSI rows won't collide with each other even though runId=sessionId there.
                db.execSQL(
                    """
            CREATE UNIQUE INDEX IF NOT EXISTS index_sessions_runId_sectionIndex
            ON sessions(runId, sectionIndex)
            """.trimIndent()
                )
            }
        }

        /**
         * v8 -> v9:
         * - Add environment columns: envTempC, envHumidity, envTsUtc
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (tableExists(db, "sessions")) {
                    if (!columnExists(db, "sessions", "envTempC")) {
                        db.execSQL("ALTER TABLE sessions ADD COLUMN envTempC REAL")
                    }
                    if (!columnExists(db, "sessions", "envHumidity")) {
                        db.execSQL("ALTER TABLE sessions ADD COLUMN envHumidity REAL")
                    }
                    if (!columnExists(db, "sessions", "envTsUtc")) {
                        db.execSQL("ALTER TABLE sessions ADD COLUMN envTsUtc TEXT")
                    }
                }
            }
        }
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!tableExists(db, "sessions")) return

                // 1. Drop legacy unique index on runId alone.
                // Some devices (older schema) might still have index_sessions_runId.
                // "DROP INDEX IF EXISTS" is valid SQL, but SupportSQLiteDatabase.execSQL
                // may still throw on some OEM sqlite builds, so we runCatching it.
                runCatching {
                    db.execSQL("DROP INDEX IF EXISTS index_sessions_runId")
                }

                // 2. Create composite unique index (runId, sectionIndex)
                // Safeguarded with IF NOT EXISTS in case we already created it in an earlier hotfix.
                db.execSQL(
                    """
            CREATE UNIQUE INDEX IF NOT EXISTS index_sessions_runId_sectionIndex
            ON sessions(runId, sectionIndex)
            """.trimIndent()
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "gallery_db"
                )
                    .addMigrations(
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10
                    )
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                    .also { INSTANCE = it }
            }

        // ---------- Helpers ----------
        private fun tableExists(db: SupportSQLiteDatabase, table: String): Boolean =
            db.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                arrayOf(table)
            ).use { it.moveToFirst() }

        private fun columnExists(db: SupportSQLiteDatabase, table: String, column: String): Boolean =
            db.query("PRAGMA table_info($table)").use { cursor ->
                val nameIdx = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIdx).equals(column, ignoreCase = true)) return true
                }
                false
            }
    }
}
