package io.github.mvolkert.entryrecorder.testing

import io.github.mvolkert.entryrecorder.data.local.dao.ActiveServerRecordingDao
import io.github.mvolkert.entryrecorder.data.local.dao.AppSettingsDao
import io.github.mvolkert.entryrecorder.data.local.dao.DeviceDao
import io.github.mvolkert.entryrecorder.data.local.dao.RecordingDao
import io.github.mvolkert.entryrecorder.data.local.entity.ActiveServerRecordingEntity
import io.github.mvolkert.entryrecorder.data.local.entity.AppSettingsEntity
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.data.local.entity.RecordingEntity
import io.github.mvolkert.entryrecorder.data.model.EventType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory stand-ins for the four Room DAOs. Room DAOs are plain Kotlin interfaces, so the real
 * [io.github.mvolkert.entryrecorder.data.repository.IntercomRepository] can be constructed over these
 * in a JVM test — the repository's own logic (delete-with-orphans, settings-row creation, id assignment
 * on insert-or-update) is then exercised for real rather than faked.
 *
 * Insert semantics mirror Room's: `REPLACE` keyed by the primary key, and an auto-generated id (0) on
 * devices and recordings gets the next sequential id, which the insert call returns.
 */
class FakeDeviceDao : DeviceDao {
    private val store = MutableStateFlow<Map<Long, DeviceEntity>>(emptyMap())
    private var nextId = 1L

    /** Every row, in id order; the store is a StateFlow, so repository flows see every change. */
    val rows: List<DeviceEntity> get() = store.value.values.sortedBy { it.id }

    override fun getAllDevicesFlow(): Flow<List<DeviceEntity>> = store.map { it.values.sortedBy { d -> d.id } }

    override suspend fun getAllDevices(): List<DeviceEntity> = rows

    override suspend fun getEnabledDevices(): List<DeviceEntity> =
        rows.filter { it.isEnabled }

    override suspend fun getDeviceById(id: Long): DeviceEntity? = store.value[id]

    override fun getDeviceByIdFlow(id: Long): Flow<DeviceEntity?> = store.map { it[id] }

    override suspend fun insertDevice(device: DeviceEntity): Long {
        val row = if (device.id == 0L) device.copy(id = nextId++) else device
        store.update { it + (row.id to row) }
        return row.id
    }

    override suspend fun updateDevice(device: DeviceEntity) {
        store.update { it + (device.id to device) }
    }

    override suspend fun deleteDevice(device: DeviceEntity) {
        store.update { it - device.id }
    }

    override suspend fun deleteDeviceById(id: Long) {
        store.update { it - id }
    }
}

class FakeRecordingDao : RecordingDao {
    private val store = MutableStateFlow<Map<Long, RecordingEntity>>(emptyMap())
    private var nextId = 1L

    /** Every row in list order: newest timestamp first, insertion id as the tiebreaker. */
    val rows: List<RecordingEntity>
        get() = store.value.values.sortedWith(compareByDescending<RecordingEntity> { it.timestamp }.thenByDescending { it.id })

    override fun getAllRecordingsFlow(): Flow<List<RecordingEntity>> = store.map { snapshot ->
        snapshot.values.sortedWith(compareByDescending<RecordingEntity> { it.timestamp }.thenByDescending { it.id })
    }

    /** Mirrors the SQL filter: device, either trigger tag, and the timestamp window. */
    override fun searchRecordingsFlow(
        deviceId: Long?,
        eventType: EventType?,
        fromTimestamp: Long?,
        toTimestamp: Long?
    ): Flow<List<RecordingEntity>> = getAllRecordingsFlow().map { list ->
        list.filter { recording ->
            (deviceId == null || recording.deviceId == deviceId) &&
                (eventType == null || recording.triggerTypesForFakeMatch(eventType)) &&
                (fromTimestamp == null || recording.timestamp >= fromTimestamp) &&
                (toTimestamp == null || recording.timestamp <= toTimestamp)
        }
    }

    private fun RecordingEntity.triggerTypesForFakeMatch(eventType: EventType): Boolean =
        this.eventType == eventType ||
            alsoEventTypes.split(',').map { it.trim() }.contains(eventType.name)

    override suspend fun getRecordingById(id: Long): RecordingEntity? = store.value[id]

    override fun getRecordingByIdFlow(id: Long): Flow<RecordingEntity?> = store.map { it[id] }

    override suspend fun getExpiredUnprotectedRecordings(cutoffTimestamp: Long): List<RecordingEntity> =
        rows.filter { !it.isProtected && it.timestamp < cutoffTimestamp }.sortedBy { it.timestamp }

    override suspend fun getOldestUnprotectedRecordings(): List<RecordingEntity> =
        rows.filter { !it.isProtected }.sortedBy { it.timestamp }

    override suspend fun getTotalStorageUsageBytes(): Long? =
        store.value.takeIf { it.isNotEmpty() }?.values?.sumOf { it.fileSizeBytes }

    override suspend fun getProtectedFilePaths(): List<String> =
        rows.filter { it.isProtected }.map { it.filePath }

    override suspend fun getAllFilePaths(): List<String> = rows.map { it.filePath }

    override suspend fun getAllThumbnailPaths(): List<String> =
        rows.mapNotNull { it.thumbnailPath }

    override suspend fun insertRecording(recording: RecordingEntity): Long {
        val row = if (recording.id == 0L) recording.copy(id = nextId++) else recording
        store.update { it + (row.id to row) }
        return row.id
    }

    override suspend fun updateRecording(recording: RecordingEntity) {
        store.update { it + (recording.id to recording) }
    }

    override suspend fun deleteRecording(recording: RecordingEntity) {
        store.update { it - recording.id }
    }

    override suspend fun deleteRecordings(recordings: List<RecordingEntity>) {
        store.update { snap -> snap - recordings.map { it.id }.toSet() }
    }

    override suspend fun deleteRecordingById(id: Long) {
        store.update { it - id }
    }

    override suspend fun setProtected(id: Long, isProtected: Boolean) {
        store.value[id]?.let { store.update { snap -> snap + (id to it.copy(isProtected = isProtected)) } }
    }
}

class FakeAppSettingsDao : AppSettingsDao {
    /** Null until a row is written, exactly like an empty table; the id is always 1. */
    private val store = MutableStateFlow<AppSettingsEntity?>(null)

    val current: AppSettingsEntity? get() = store.value

    override fun getSettingsFlow(): Flow<AppSettingsEntity?> = store

    override suspend fun getSettings(): AppSettingsEntity? = store.value

    override suspend fun insertOrUpdate(settings: AppSettingsEntity) {
        store.value = settings.copy(id = 1)
    }
}

class FakeActiveServerRecordingDao : ActiveServerRecordingDao {
    private val store = MutableStateFlow<Map<Long, ActiveServerRecordingEntity>>(emptyMap())

    /** Every tracked job, as the reconcile pass and tests read it. */
    val rows: List<ActiveServerRecordingEntity> get() = store.value.values.toList()

    override suspend fun getAll(): List<ActiveServerRecordingEntity> = rows

    override suspend fun upsert(entity: ActiveServerRecordingEntity) {
        store.update { it + (entity.deviceId to entity) }
    }

    override suspend fun deleteByDeviceId(deviceId: Long) {
        store.update { it - deviceId }
    }
}

private fun <T> MutableStateFlow<T>.update(function: (T) -> T) {
    value = function(value)
}
