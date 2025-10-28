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
    version = 12,
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

                runCatching {
                    db.execSQL("DROP INDEX IF EXISTS index_sessions_runId")
                }

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

        /**
         * v9 -> v10:
         * - Ensure composite unique index (runId, sectionIndex)
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!tableExists(db, "sessions")) return

                runCatching {
                    db.execSQL("DROP INDEX IF EXISTS index_sessions_runId")
                }

                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS index_sessions_runId_sectionIndex
                    ON sessions(runId, sectionIndex)
                    """.trimIndent()
                )
            }
        }

        /**
         * v10 -> v11:
         * - (old version) Recreate calibration_profiles without numeric id
         */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Drop old table if it exists
                if (tableExists(db, "calibration_profiles")) {
                    db.execSQL("DROP TABLE calibration_profiles")
                }

                // Recreate with runId as PRIMARY KEY (legacy v11 schema)
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS calibration_profiles (
                        runId TEXT NOT NULL PRIMARY KEY,
                        completedAtMillis INTEGER NOT NULL,
                        timestampStr TEXT NOT NULL,
                        imagePathsJson TEXT NOT NULL,
                        ledNormsJson TEXT,
                        targetDn REAL,
                        envTempC REAL,
                        envHumidity REAL,
                        envTsUtc TEXT,
                        tsUtcOverall TEXT,
                        summary TEXT
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * v11 -> v12:
         * - Add numeric auto-increment id to calibration_profiles for display/selection.
         * - We can't just ALTER TABLE to add "INTEGER PRIMARY KEY AUTOINCREMENT"
         *   to an existing table, so we:
         *      1. CREATE a new table with the final schema.
         *      2. COPY data from old table.
         *      3. DROP old table.
         *      4. RENAME new -> calibration_profiles.
         *
         *   New schema:
         *      id INTEGER PRIMARY KEY AUTOINCREMENT
         *      runId TEXT NOT NULL UNIQUE
         *      ...rest of columns same as before...
         */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {

                // 1. Create a temp table with the new schema.
                db.execSQL(
                    """
                    CREATE TABLE calibration_profiles_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        runId TEXT NOT NULL UNIQUE,
                        completedAtMillis INTEGER NOT NULL,
                        timestampStr TEXT NOT NULL,
                        imagePathsJson TEXT NOT NULL,
                        ledNormsJson TEXT,
                        targetDn REAL,
                        envTempC REAL,
                        envHumidity REAL,
                        envTsUtc TEXT,
                        tsUtcOverall TEXT,
                        summary TEXT
                    )
                    """.trimIndent()
                )

                // 2. Copy rows from old table into new table.
                // id will autogenerate here.
                db.execSQL(
                    """
                    INSERT INTO calibration_profiles_new (
                        runId,
                        completedAtMillis,
                        timestampStr,
                        imagePathsJson,
                        ledNormsJson,
                        targetDn,
                        envTempC,
                        envHumidity,
                        envTsUtc,
                        tsUtcOverall,
                        summary
                    )
                    SELECT
                        runId,
                        completedAtMillis,
                        timestampStr,
                        imagePathsJson,
                        ledNormsJson,
                        targetDn,
                        envTempC,
                        envHumidity,
                        envTsUtc,
                        tsUtcOverall,
                        summary
                    FROM calibration_profiles
                    """.trimIndent()
                )

                // 3. Drop the old table.
                db.execSQL("DROP TABLE calibration_profiles")

                // 4. Rename the new table to the final name.
                db.execSQL(
                    """
                    ALTER TABLE calibration_profiles_new
                    RENAME TO calibration_profiles
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
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12
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

        private fun columnExists(
            db: SupportSQLiteDatabase,
            table: String,
            column: String
        ): Boolean =
            db.query("PRAGMA table_info($table)").use { cursor ->
                val nameIdx = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIdx).equals(column, ignoreCase = true)) return true
                }
                false
            }
    }
}
