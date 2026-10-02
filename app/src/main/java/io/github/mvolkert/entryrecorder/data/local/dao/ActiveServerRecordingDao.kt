package io.github.mvolkert.entryrecorder.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.mvolkert.entryrecorder.data.local.entity.ActiveServerRecordingEntity

@Dao
interface ActiveServerRecordingDao {
    @Query("SELECT * FROM active_server_recordings")
    suspend fun getAll(): List<ActiveServerRecordingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ActiveServerRecordingEntity)

    @Query("DELETE FROM active_server_recordings WHERE deviceId = :deviceId")
    suspend fun deleteByDeviceId(deviceId: Long)
}
