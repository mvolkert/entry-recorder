package io.github.mvolkert.entryrecorder.data.local.dao

import androidx.room.*
import io.github.mvolkert.entryrecorder.data.local.entity.RecordingEntity
import io.github.mvolkert.entryrecorder.data.model.EventType
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY timestamp DESC")
    fun getAllRecordingsFlow(): Flow<List<RecordingEntity>>

    /**
     * A type filter matches either tag of a clip: the trigger it was filed under or one folded in while the
     * recording was still running (stored as comma-separated names, hence the padded LIKE).
     */
    @Query("""
        SELECT * FROM recordings 
        WHERE (:deviceId IS NULL OR deviceId = :deviceId)
          AND (:eventType IS NULL OR eventType = :eventType
            OR (',' || alsoEventTypes || ',') LIKE ('%,' || :eventType || ',%'))
          AND (:fromTimestamp IS NULL OR timestamp >= :fromTimestamp)
          AND (:toTimestamp IS NULL OR timestamp <= :toTimestamp)
        ORDER BY timestamp DESC
    """)
    fun searchRecordingsFlow(
        deviceId: Long? = null,
        eventType: EventType? = null,
        fromTimestamp: Long? = null,
        toTimestamp: Long? = null
    ): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE id = :id LIMIT 1")
    suspend fun getRecordingById(id: Long): RecordingEntity?

    @Query("SELECT * FROM recordings WHERE id = :id LIMIT 1")
    fun getRecordingByIdFlow(id: Long): Flow<RecordingEntity?>

    @Query("SELECT * FROM recordings WHERE isProtected = 0 AND timestamp < :cutoffTimestamp ORDER BY timestamp ASC")
    suspend fun getExpiredUnprotectedRecordings(cutoffTimestamp: Long): List<RecordingEntity>

    @Query("SELECT * FROM recordings WHERE isProtected = 0 ORDER BY timestamp ASC")
    suspend fun getOldestUnprotectedRecordings(): List<RecordingEntity>

    @Query("SELECT SUM(fileSizeBytes) FROM recordings")
    suspend fun getTotalStorageUsageBytes(): Long?

    /**
     * File paths of the clips the user protected. The external cleanup needs the names, not the rows: the
     * mirror in the export folder is matched by file name, and a protected clip whose row still exists must
     * never lose its exported copy.
     */
    @Query("SELECT filePath FROM recordings WHERE isProtected = 1")
    suspend fun getProtectedFilePaths(): List<String>

    /**
     * Every video path the archive still owns. The orphan sweep compares a directory listing against all of
     * them, so it needs the whole set rather than the protected subset the external pass asks for.
     */
    @Query("SELECT filePath FROM recordings")
    suspend fun getAllFilePaths(): List<String>

    /** Every thumbnail path the archive still owns, for the same sweep over the thumbnail directory. */
    @Query("SELECT thumbnailPath FROM recordings WHERE thumbnailPath IS NOT NULL")
    suspend fun getAllThumbnailPaths(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecording(recording: RecordingEntity): Long

    @Update
    suspend fun updateRecording(recording: RecordingEntity)

    @Delete
    suspend fun deleteRecording(recording: RecordingEntity)

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun deleteRecordingById(id: Long)

    @Query("UPDATE recordings SET isProtected = :isProtected WHERE id = :id")
    suspend fun setProtected(id: Long, isProtected: Boolean)
}
