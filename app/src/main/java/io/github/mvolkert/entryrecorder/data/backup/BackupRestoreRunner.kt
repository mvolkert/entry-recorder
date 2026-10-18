package io.github.mvolkert.entryrecorder.data.backup

import android.util.Log
import com.google.gson.Gson
import io.github.mvolkert.entryrecorder.data.local.crypto.CredentialCipher
import io.github.mvolkert.entryrecorder.data.local.entity.normalizedForRestore
import io.github.mvolkert.entryrecorder.data.mapper.toEntity
import io.github.mvolkert.entryrecorder.data.repository.IntercomRepository
import io.github.mvolkert.entryrecorder.di.IoDispatcher
import io.github.mvolkert.entryrecorder.ui.theme.ThemeModeStore
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Result of a backup write or a restore read. [Exported] and [Restored] carry the number of devices the
 * document held, which is what both success messages interpolate. [Invalid] is the file the picker handed
 * back parsing to nothing at all — a different fact from [Failed], which is a provider or database error —
 * and the caller turns either into snack-bar text; this type carries no resource ids.
 */
sealed interface BackupRestoreOutcome {
    data class Exported(val deviceCount: Int) : BackupRestoreOutcome
    data class Restored(val deviceCount: Int) : BackupRestoreOutcome
    data object Invalid : BackupRestoreOutcome
    data class Failed(val cause: Throwable) : BackupRestoreOutcome
}

/**
 * The whole backup document pipeline: assemble [AppBackup] from the repository, serialize it and hand it to
 * the document store; or read a document, apply it, and leave the messaging to the caller.
 *
 * Extracted from the settings ViewModel so the ViewModel keeps only the outcome → message mapping. Two rules
 * live here because they are what a restore is *for*: a restored row is re-encrypted under the local Keystore
 * key rather than the one that wrote the file, and `serverDeviceId` is dropped so devices re-register against
 * whichever server is configured now instead of the one the backup was taken against.
 */
class BackupRestoreRunner @Inject constructor(
    private val repository: IntercomRepository,
    private val backupStore: BackupDocumentStore,
    private val gson: Gson,
    private val credentialCipher: CredentialCipher,
    private val themeModeStore: ThemeModeStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val tag = "BackupRestoreRunner"

    /** Writes a JSON backup of app settings + all devices to the user-chosen [documentUri]. */
    suspend fun export(documentUri: String): BackupRestoreOutcome = try {
        val backup = AppBackup(
            exportedAt = System.currentTimeMillis(),
            appSettings = repository.getSettings(),
            devices = repository.getAllDevicesList()
        )
        val json = gson.toJson(backup)
        withContext(ioDispatcher) {
            backupStore.writeText(documentUri, json)
        }
        BackupRestoreOutcome.Exported(backup.devices.size)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(tag, "Backup export failed", e)
        BackupRestoreOutcome.Failed(e)
    }

    /**
     * Restores settings + devices from a backup JSON at [documentUri]. Devices are upserted by id (so their
     * existing recordings keep working); devices not present in the file are left untouched.
     */
    suspend fun restore(documentUri: String): BackupRestoreOutcome = try {
        val json = withContext(ioDispatcher) { backupStore.readText(documentUri) }
        val backup = gson.fromJson(json, AppBackup::class.java)
        if (backup == null) {
            // Gson answers null for a document that is empty or not JSON at all; the caller has the words for it.
            Log.w(tag, "Backup restore parsed no document from $documentUri")
            BackupRestoreOutcome.Invalid
        } else {
            backup.appSettings?.let {
                themeModeStore.setThemeMode(it.themeMode)
                repository.insertOrUpdateSettingsEntity(it.toEntity(credentialCipher).normalizedForRestore())
            }
            // Reset serverDeviceId: a backup may have been taken against a different server, so the
            // restored devices re-register lazily against whichever server is configured now.
            backup.devices.forEach { repository.upsertDevice(it.copy(serverDeviceId = null)) }
            BackupRestoreOutcome.Restored(backup.devices.size)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(tag, "Backup restore failed", e)
        BackupRestoreOutcome.Failed(e)
    }
}
