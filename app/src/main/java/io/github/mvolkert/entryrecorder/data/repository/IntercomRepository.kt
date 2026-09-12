package io.github.mvolkert.entryrecorder.data.repository

import io.github.mvolkert.entryrecorder.data.local.dao.AppSettingsDao
import io.github.mvolkert.entryrecorder.data.local.dao.DeviceDao
import io.github.mvolkert.entryrecorder.data.local.dao.RecordingDao
import io.github.mvolkert.entryrecorder.data.local.entity.AppSettingsEntity
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.data.local.entity.RecordingEntity
import io.github.mvolkert.entryrecorder.data.model.EventType
import kotlinx.coroutines.flow.Flow
import java.io.File

class IntercomRepository(
    private val deviceDao: DeviceDao,
    private val recordingDao: RecordingDao,
    private val appSettingsDao: AppSettingsDao
) {
    // Devices
    val allDevices: Flow<List<DeviceEntity>> = deviceDao.getAllDevicesFlow()

    suspend fun getAllDevicesList(): List<DeviceEntity> = deviceDao.getAllDevices()

    suspend fun getEnabledDevices(): List<DeviceEntity> = deviceDao.getEnabledDevices()

    suspend fun getDeviceById(id: Long): DeviceEntity? = deviceDao.getDeviceById(id)

    fun getDeviceByIdFlow(id: Long): Flow<DeviceEntity?> = deviceDao.getDeviceByIdFlow(id)

    suspend fun saveDevice(device: DeviceEntity): Long {
        return if (device.id == 0L) {
            deviceDao.insertDevice(device)
        } else {
            deviceDao.updateDevice(device)
            device.id
        }
    }

    suspend fun deleteDevice(device: DeviceEntity) {
        deviceDao.deleteDevice(device)
    }

    suspend fun deleteDeviceById(id: Long) {
        deviceDao.deleteDeviceById(id)
    }

    // Recordings
    val allRecordings: Flow<List<RecordingEntity>> = recordingDao.getAllRecordingsFlow()

    fun searchRecordings(
        deviceId: Long? = null,
        eventType: EventType? = null,
        fromTimestamp: Long? = null,
        toTimestamp: Long? = null
    ): Flow<List<RecordingEntity>> {
        return recordingDao.searchRecordingsFlow(deviceId, eventType, fromTimestamp, toTimestamp)
    }

    suspend fun getRecordingById(id: Long): RecordingEntity? = recordingDao.getRecordingById(id)

    fun getRecordingByIdFlow(id: Long): Flow<RecordingEntity?> = recordingDao.getRecordingByIdFlow(id)

    suspend fun insertRecording(recording: RecordingEntity): Long = recordingDao.insertRecording(recording)

    suspend fun updateRecording(recording: RecordingEntity) = recordingDao.updateRecording(recording)

    suspend fun deleteRecording(recording: RecordingEntity) {
        // Remove physical files
        try {
            val videoFile = File(recording.filePath)
            if (videoFile.exists()) videoFile.delete()
            recording.thumbnailPath?.let { thumb ->
                val thumbFile = File(thumb)
                if (thumbFile.exists()) thumbFile.delete()
            }
        } catch (_: Exception) {}
        recordingDao.deleteRecording(recording)
    }

    suspend fun setRecordingProtected(id: Long, isProtected: Boolean) {
        recordingDao.setProtected(id, isProtected)
    }

    suspend fun getExpiredRecordings(retentionDays: Int): List<RecordingEntity> {
        if (retentionDays <= 0) return emptyList()
        val cutoffMs = System.currentTimeMillis() - (retentionDays * 24L * 60L * 60L * 1000L)
        return recordingDao.getExpiredUnprotectedRecordings(cutoffMs)
    }

    suspend fun getOldestUnprotectedRecordings(): List<RecordingEntity> {
        return recordingDao.getOldestUnprotectedRecordings()
    }

    suspend fun getTotalStorageBytes(): Long {
        return recordingDao.getTotalStorageUsageBytes() ?: 0L
    }

    // Settings
    val settingsFlow: Flow<AppSettingsEntity?> = appSettingsDao.getSettingsFlow()

    suspend fun getSettings(): AppSettingsEntity {
        return appSettingsDao.getSettings() ?: AppSettingsEntity().also {
            appSettingsDao.insertOrUpdate(it)
        }
    }

    suspend fun updateSettings(settings: AppSettingsEntity) {
        appSettingsDao.insertOrUpdate(settings)
    }
}
