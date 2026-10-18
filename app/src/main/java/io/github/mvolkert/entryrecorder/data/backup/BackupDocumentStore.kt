package io.github.mvolkert.entryrecorder.data.backup

import android.content.Context
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.util.UiStrings
import javax.inject.Inject

/**
 * Reading and writing the backup JSON document the user picks through SAF, as a JVM-only contract:
 * the backup runner builds and parses [AppBackup] itself, so only the document stream needs the
 * ContentResolver — and, exactly like [io.github.mvolkert.entryrecorder.util.ExportGateway], the document
 * travels as the String the settings row already stores. That keeps the whole path constructible from an
 * ordinary unit test, where no `android.net.Uri` instance can exist.
 *
 * Both methods block on provider round trips; callers schedule them on their IO dispatcher.
 */
interface BackupDocumentStore {

    /**
     * Writes [text] to the document at [documentUri], replacing its content.
     * @throws IllegalStateException when the provider refuses the stream for writing.
     */
    fun writeText(documentUri: String, text: String)

    /**
     * Reads the whole document at [documentUri] as UTF-8 text.
     * @throws IllegalStateException when the provider refuses the stream for reading.
     */
    fun readText(documentUri: String): String
}

/** The device-backed implementation: ContentResolver streams through the application context. */
class AndroidBackupDocumentStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val strings: UiStrings,
) : BackupDocumentStore {

    override fun writeText(documentUri: String, text: String) {
        context.contentResolver.openOutputStream(documentUri.toUri())
            ?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: throw IllegalStateException(strings(R.string.settings_backup_open_write_failed))
    }

    override fun readText(documentUri: String): String =
        context.contentResolver.openInputStream(documentUri.toUri())?.use {
            String(it.readBytes(), Charsets.UTF_8)
        } ?: throw IllegalStateException(strings(R.string.settings_backup_open_read_failed))
}
