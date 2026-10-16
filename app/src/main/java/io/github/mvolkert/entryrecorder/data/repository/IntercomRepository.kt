package io.github.mvolkert.entryrecorder.data.repository

import io.github.mvolkert.entryrecorder.data.local.dao.ActiveServerRecordingDao
import io.github.mvolkert.entryrecorder.data.local.dao.AppSettingsDao
import io.github.mvolkert.entryrecorder.data.local.dao.DeviceDao
import io.github.mvolkert.entryrecorder.data.local.dao.RecordingDao
import io.github.mvolkert.entryrecorder.data.local.entity.ActiveServerRecordingEntity
import io.github.mvolkert.entryrecorder.data.local.entity.AppSettingsEntity
import io.github.mvolkert.entryrecorder.data.local.entity.RecordingEntity
import io.github.mvolkert.entryrecorder.data.mapper.toDomain
import io.github.mvolkert.entryrecorder.data.mapper.toEntity
import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.di.IoDispatcher
import io.github.mvolkert.entryrecorder.domain.model.AppSettings
import io.github.mvolkert.entryrecorder.domain.model.Device
import io.github.mvolkert.entryrecorder.domain.model.Recording
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The single source of truth for devices, recordings and settings; every row read or written goes through it.
 *
 * The DAOs keep speaking Room entities; this is the boundary where they become domain models, so nothing
 * above the data layer has to know about the storage format. The `*Entity` variants below exist only for
 * the backup restore, which round-trips whole stored rows (legacy columns included) and must not lose
 * fidelity to the mapping.
 */
@Singleton
class IntercomRepository @Inject constructor(
    private val deviceDao: DeviceDao,
    private val recordingDao: RecordingDao,
    private val appSettingsDao: AppSettingsDao,
    private val activeServerRecordingDao: ActiveServerRecordingDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    // Devices
    val allDevices: Flow<List<Device>> = deviceDao.getAllDevicesFlow().map { rows -> rows.map { it.toDomain() } }

    suspend fun getAllDevicesList(): List<Device> = deviceDao.getAllDevices().map { it.toDomain() }

    suspend fun getEnabledDevices(): List<Device> = deviceDao.getEnabledDevices().map { it.toDomain() }

    suspend fun getDeviceById(id: Long): Device? = deviceDao.getDeviceById(id)?.toDomain()

    fun getDeviceByIdFlow(id: Long): Flow<Device?> = deviceDao.getDeviceByIdFlow(id).map { it?.toDomain() }

    suspend fun saveDevice(device: Device): Long {
        val entity = device.toEntity()
        return if (device.id == 0L) {
            deviceDao.insertDevice(entity)
        } else {
            deviceDao.updateDevice(entity)
            device.id
        }
    }

    suspend fun deleteDevice(device: Device) {
        deviceDao.deleteDevice(device.toEntity())
    }

    suspend fun deleteDeviceById(id: Long) {
        deviceDao.deleteDeviceById(id)
    }

    /**
     * Inserts [device] preserving its existing id (Room REPLACE on conflict). Used by backup
     * restore so re-imported devices keep the ids their recordings still reference.
     */
    suspend fun upsertDevice(device: Device): Long = deviceDao.insertDevice(device.toEntity())

    /** Entity-level settings write for backup restore; the domain mapping drops declared legacy columns. */
    suspend fun insertOrUpdateSettingsEntity(entity: AppSettingsEntity) = appSettingsDao.insertOrUpdate(entity)

    // Recordings
    val allRecordings: Flow<List<Recording>> =
        recordingDao.getAllRecordingsFlow().map { rows -> rows.map { it.toDomain() } }

    fun searchRecordings(
        deviceId: Long? = null,
        eventType: EventType? = null,
        fromTimestamp: Long? = null,
        toTimestamp: Long? = null
    ): Flow<List<Recording>> =
        recordingDao.searchRecordingsFlow(deviceId, eventType, fromTimestamp, toTimestamp)
            .map { rows -> rows.map { it.toDomain() } }

    suspend fun getRecordingById(id: Long): Recording? = recordingDao.getRecordingById(id)?.toDomain()

    fun getRecordingByIdFlow(id: Long): Flow<Recording?> = recordingDao.getRecordingByIdFlow(id).map { it?.toDomain() }

    suspend fun insertRecording(recording: Recording): Long = recordingDao.insertRecording(recording.toEntity())

    suspend fun updateRecording(recording: Recording) = recordingDao.updateRecording(recording.toEntity())

    /**
     * Deletes [recording]'s row and the files it owned, reporting what actually went. The row goes whatever
     * the files do — a file that survives is an orphan the caller has to know about, because space no row
     * references is space no quota counts either.
     */
    suspend fun deleteRecording(recording: Recording): RecordingDeleteResult {
        val orphans = listOfNotNull(
            deleteFile(recording.filePath),
            recording.thumbnailPath?.let { deleteFile(it) },
        )
        recordingDao.deleteRecording(recording.toEntity())
        return if (orphans.isEmpty()) RecordingDeleteResult.Deleted
        else RecordingDeleteResult.DeletedWithOrphans(orphans)
    }

    /**
     * Bulk variant used by multi-select delete. The files go first (each independently, so an undeletable
     * handle on one clip still lets the rest be reclaimed), then every row drops in a single transaction so
     * the batch is atomic — no listed clip is left behind by a half-applied delete.
     */
    suspend fun deleteRecordings(recordings: List<Recording>): RecordingDeleteReport {
        val orphanPaths = recordings.flatMap { recording ->
            listOfNotNull(
                deleteFile(recording.filePath),
                recording.thumbnailPath?.let { deleteFile(it) },
            )
        }
        recordingDao.deleteRecordings(recordings.map { it.toEntity() })
        return RecordingDeleteReport(
            deleted = recordings.size,
            orphanPaths = orphanPaths,
        )
    }

    /**
     * Removes one file on the IO dispatcher, retrying once: a MediaStore or player handle can still be open
     * for a moment after a clip stops playing, and a second attempt covers that without pretending the bytes
     * are gone. Null means nothing is left at [path], the path itself means it is now an orphan.
     */
    private suspend fun deleteFile(path: String): String? = withContext(ioDispatcher) {
        val file = File(path)
        if (!file.exists()) return@withContext null
        if (file.delete() || !file.exists()) return@withContext null
        if (file.delete() || !file.exists()) null else path
    }

    suspend fun setRecordingProtected(id: Long, isProtected: Boolean) {
        recordingDao.setProtected(id, isProtected)
    }

    suspend fun getExpiredRecordings(retentionDays: Int): List<Recording> {
        if (retentionDays <= 0) return emptyList()
        val cutoffMs = System.currentTimeMillis() - (retentionDays * 24L * 60L * 60L * 1000L)
        return recordingDao.getExpiredUnprotectedRecordings(cutoffMs).map { it.toDomain() }
    }

    suspend fun getOldestUnprotectedRecordings(): List<Recording> =
        recordingDao.getOldestUnprotectedRecordings().map { it.toDomain() }

    suspend fun getTotalStorageBytes(): Long {
        return recordingDao.getTotalStorageUsageBytes() ?: 0L
    }

    /** File paths of protected clips, used to keep their exported copies out of an external cleanup pass. */
    suspend fun getProtectedFilePaths(): List<String> = recordingDao.getProtectedFilePaths()

    /**
     * Every path any row still owns, videos and thumbnails together. The orphan sweep decides what to delete
     * from this set, so it has to cover both kinds or it would reclaim the thumbnail of a listed clip.
     */
    suspend fun getAllReferencedFilePaths(): List<String> =
        recordingDao.getAllFilePaths() + recordingDao.getAllThumbnailPaths()

    // Settings
    val settingsFlow: Flow<AppSettings?> = appSettingsDao.getSettingsFlow().map { it?.toDomain() }

    suspend fun getSettings(): AppSettings {
        return appSettingsDao.getSettings()?.toDomain() ?: AppSettings().also {
            appSettingsDao.insertOrUpdate(it.toEntity())
        }
    }

    suspend fun updateSettings(settings: AppSettings) {
        appSettingsDao.insertOrUpdate(settings.toEntity())
    }

    // Active server recordings: transient rows tracking a job the server runs, so a process restart can
    // resume reconciling and auto-stopping it. A row lives only while the app is watching the job.
    suspend fun getActiveServerRecordings(): List<ActiveServerRecordingEntity> =
        activeServerRecordingDao.getAll()

    suspend fun saveActiveServerRecording(entity: ActiveServerRecordingEntity) =
        activeServerRecordingDao.upsert(entity)

    suspend fun deleteActiveServerRecording(deviceId: Long) =
        activeServerRecordingDao.deleteByDeviceId(deviceId)
}
