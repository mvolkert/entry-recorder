package io.github.mvolkert.entryrecorder.data.backup

import io.github.mvolkert.entryrecorder.domain.model.AppSettings
import io.github.mvolkert.entryrecorder.domain.model.Device

/**
 * On-disk shape of a settings backup, serialized with Gson (see SettingsViewModel).
 * Captures the current [AppSettings] and the full [Device] list so a user can move their
 * configuration to another install. Recordings (large media) are intentionally excluded.
 *
 * It deliberately holds the domain models rather than the Room entities: the entities now keep device
 * HTTP/SIP passwords and the server API key encrypted at rest under a device-local Keystore key, and
 * ciphertext would be unreadable once restored on another install. The domain form carries the
 * plaintext the app works with, so a backup stays portable — treat exported files as sensitive.
 */
data class AppBackup(
    val version: Int = 1,
    val exportedAt: Long = 0L,
    val appSettings: AppSettings? = null,
    val devices: List<Device> = emptyList(),
)
