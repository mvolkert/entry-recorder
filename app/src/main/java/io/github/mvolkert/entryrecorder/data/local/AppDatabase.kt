package io.github.mvolkert.entryrecorder.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.mvolkert.entryrecorder.data.local.dao.AppSettingsDao
import io.github.mvolkert.entryrecorder.data.local.dao.DeviceDao
import io.github.mvolkert.entryrecorder.data.local.dao.RecordingDao
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
        AppSettingsEntity::class
    ],
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
    abstract fun recordingDao(): RecordingDao
    abstract fun appSettingsDao(): AppSettingsDao

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

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "entry_recorder_database"
                )
                    .fallbackToDestructiveMigration(false)
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
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
