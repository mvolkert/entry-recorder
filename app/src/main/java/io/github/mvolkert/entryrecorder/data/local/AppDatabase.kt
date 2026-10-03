package io.github.mvolkert.entryrecorder.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.mvolkert.entryrecorder.data.local.dao.ActiveServerRecordingDao
import io.github.mvolkert.entryrecorder.data.local.dao.AppSettingsDao
import io.github.mvolkert.entryrecorder.data.local.dao.DeviceDao
import io.github.mvolkert.entryrecorder.data.local.dao.RecordingDao
import io.github.mvolkert.entryrecorder.data.local.entity.ActiveServerRecordingEntity
import io.github.mvolkert.entryrecorder.data.local.entity.AppSettingsEntity
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.data.local.entity.RecordingEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        DeviceEntity::class,
        RecordingEntity::class,
        AppSettingsEntity::class,
        ActiveServerRecordingEntity::class
    ],
    version = 13,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
    abstract fun recordingDao(): RecordingDao
    abstract fun appSettingsDao(): AppSettingsDao
    abstract fun activeServerRecordingDao(): ActiveServerRecordingDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // v4 -> v5: add the transcode-on-export setting. Kept as a real migration because
        // destructive migration is disabled; a default of 1 preserves the previous (always-transcode)
        // behavior for existing installs.
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE app_settings ADD COLUMN transcodeOnExport INTEGER NOT NULL DEFAULT 1"
                )
            }
        }

        // v5 -> v6: add the SAF export-folder tree URI. Empty string means no folder chosen yet.
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE app_settings ADD COLUMN exportFolderUri TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        // v6 -> v7: add the opt-in "auto-export finished recordings" mirror flag. Default 0 keeps
        // existing installs on the previous behavior (no background writes to the SAF folder).
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE app_settings ADD COLUMN autoExportOnFinalize INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        // v7 -> v8: add the selectable accent-color preset index. Default 0 maps to the baseline
        // Material 3 palette, preserving the previous look for existing installs.
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE app_settings ADD COLUMN themeAccentIndex INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        // v8 -> v9: per-role accent selection. Three preset-index columns, each seeded from the old
        // single themeAccentIndex so an existing install renders exactly as before. The legacy column
        // is kept (still mapped by the entity) instead of rebuilding app_settings.
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE app_settings ADD COLUMN themePrimaryIndex INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE app_settings ADD COLUMN themeSecondaryIndex INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE app_settings ADD COLUMN themeTertiaryIndex INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "UPDATE app_settings SET themePrimaryIndex = themeAccentIndex, " +
                        "themeSecondaryIndex = themeAccentIndex, themeTertiaryIndex = themeAccentIndex"
                )
            }
        }

        // v9 -> v10: alert & lockscreen behavior moves from global app_settings onto each device. The
        // five new columns default to 1, then every existing device is seeded from the current global
        // app_settings row so an install keeps the behavior the user already chose.
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE devices ADD COLUMN wakeOnRing INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE devices ADD COLUMN soundOnRing INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE devices ADD COLUMN vibrateOnRing INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE devices ADD COLUMN wakeOnMotion INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE devices ADD COLUMN wakeOnNoise INTEGER NOT NULL DEFAULT 1")
                db.execSQL(
                    "UPDATE devices SET " +
                        "wakeOnRing = (SELECT wakeOnRing FROM app_settings WHERE id = 1), " +
                        "soundOnRing = (SELECT soundOnRing FROM app_settings WHERE id = 1), " +
                        "vibrateOnRing = (SELECT vibrateOnRing FROM app_settings WHERE id = 1), " +
                        "wakeOnMotion = (SELECT wakeOnMotion FROM app_settings WHERE id = 1), " +
                        "wakeOnNoise = (SELECT wakeOnNoise FROM app_settings WHERE id = 1)"
                )
            }
        }

        // v10 -> v11: per-device motion sensitivity for the in-app analyzer. Default 'BALANCED' reproduces
        // the analyzer's original fixed constants, so every existing device keeps behaving exactly as before.
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE devices ADD COLUMN motionSensitivity TEXT NOT NULL DEFAULT 'BALANCED'"
                )
            }
        }

        // v11 -> v12: persisted in-flight server recordings, so a process restart can resume reconciling
        // and auto-stopping a job the server is still running. Transient table: rows live only while the
        // app tracks the job. eventType uses Room's enum-as-TEXT mapping (the enum name), like the
        // other enum columns already in this database.
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS active_server_recordings (" +
                        "deviceId INTEGER NOT NULL PRIMARY KEY, " +
                        "recordingId INTEGER, " +
                        "eventType TEXT NOT NULL, " +
                        "maxDurationSeconds INTEGER NOT NULL, " +
                        "startedByThisRequest INTEGER NOT NULL, " +
                        "startedAtMs INTEGER NOT NULL)"
                )
            }
        }

        // v12 -> v13: Phase S device sync. Each app device can now carry the row id the server assigned it
        // when the app registered it via POST /api/devices. Nullable (no default): an existing device is
        // simply unregistered until the app pushes it once in PYTHON_SERVER mode.
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE devices ADD COLUMN serverDeviceId INTEGER")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "entry_recorder_database"
                )
                    .fallbackToDestructiveMigration(false)
                    .addMigrations(
                        MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                        MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13
                    )
                    .addCallback(object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            // Initialize default app settings
                            CoroutineScope(Dispatchers.IO).launch {
                                val database = getDatabase(context)
                                database.appSettingsDao().insertOrUpdate(AppSettingsEntity())
                            }
                        }
                    })
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
