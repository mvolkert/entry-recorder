package io.github.mvolkert.entryrecorder.data.backup

import io.github.mvolkert.entryrecorder.data.local.entity.AppSettingsEntity
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity

/**
 * On-disk shape of a settings backup, serialized with Gson (see SettingsViewModel).
 * Captures the single [AppSettingsEntity] row and the full [DeviceEntity] list so a user can move
 * their configuration to another install. Recordings (large media) are intentionally excluded.
 *
 * NOTE: device HTTP/SIP credentials are stored plaintext in Room today, so a backup file contains
 * them in plaintext too — treat exported files as sensitive.
 */
data class AppBackup(
    val version: Int = 1,
    val exportedAt: Long = 0L,
    val appSettings: AppSettingsEntity? = null,
    val devices: List<DeviceEntity> = emptyList()
)
