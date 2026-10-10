package io.github.mvolkert.entryrecorder.data.backup

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.util.UiStrings
import javax.inject.Inject

/**
 * Reading and writing the backup JSON document the user picks through SAF, as a JVM-only contract:
 * the settings ViewModel builds and parses [AppBackup] itself, so only the document stream needs the
 * ContentResolver — and only the ViewModel's caller-supplied `Uri` reaches this interface, keeping the
 * ViewModel itself free of a `Context`.
 *
 * Both methods block on provider round trips; callers schedule them on their IO dispatcher.
 */
interface BackupDocumentStore {

    /**
     * Writes [text] to the document at [uri], replacing its content.
     * @throws IllegalStateException when the provider refuses the stream for writing.
     */
    fun writeText(uri: Uri, text: String)

    /**
     * Reads the whole document at [uri] as UTF-8 text.
     * @throws IllegalStateException when the provider refuses the stream for reading.
     */
    fun readText(uri: Uri): String
}

/** The device-backed implementation: ContentResolver streams through the application context. */
class AndroidBackupDocumentStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val strings: UiStrings,
) : BackupDocumentStore {

    override fun writeText(uri: Uri, text: String) {
        context.contentResolver.openOutputStream(uri)
            ?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: throw IllegalStateException(strings(R.string.settings_backup_open_write_failed))
    }

    override fun readText(uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use {
            String(it.readBytes(), Charsets.UTF_8)
        } ?: throw IllegalStateException(strings(R.string.settings_backup_open_read_failed))
}
