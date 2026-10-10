package io.github.mvolkert.entryrecorder.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import io.github.mvolkert.entryrecorder.data.local.dao.ActiveServerRecordingDao
import io.github.mvolkert.entryrecorder.data.local.dao.AppSettingsDao
import io.github.mvolkert.entryrecorder.data.local.dao.DeviceDao
import io.github.mvolkert.entryrecorder.data.local.dao.RecordingDao
import io.github.mvolkert.entryrecorder.data.local.entity.ActiveServerRecordingEntity
import io.github.mvolkert.entryrecorder.data.local.entity.AppSettingsEntity
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.data.local.entity.RecordingEntity

@Database(
    entities = [
        DeviceEntity::class,
        RecordingEntity::class,
        AppSettingsEntity::class,
        ActiveServerRecordingEntity::class
    ],
    version = 17,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
    abstract fun recordingDao(): RecordingDao
    abstract fun appSettingsDao(): AppSettingsDao
    abstract fun activeServerRecordingDao(): ActiveServerRecordingDao
}
