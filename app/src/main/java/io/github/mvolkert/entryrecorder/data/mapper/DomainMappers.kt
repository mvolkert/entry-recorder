package io.github.mvolkert.entryrecorder.data.mapper

import io.github.mvolkert.entryrecorder.data.local.crypto.EncryptedString
import io.github.mvolkert.entryrecorder.data.local.entity.AppSettingsEntity
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.data.local.entity.RecordingEntity
import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.domain.model.AppSettings
import io.github.mvolkert.entryrecorder.domain.model.Device
import io.github.mvolkert.entryrecorder.domain.model.Recording

/**
 * Entity ↔ domain translation at the data boundary. Room DAOs keep speaking entities internally;
 * everything above the repository consumes the domain models through these mappers.
 */

fun DeviceEntity.toDomain(): Device = Device(
    id = id,
    name = name,
    deviceType = deviceType,
    ipAddress = ipAddress,
    httpPort = httpPort,
    httpsPort = httpsPort,
    useHttps = useHttps,
    rtspPort = rtspPort,
    rtspPath = rtspPath,
    username = username,
    password = password.value,
    streamProtocol = streamProtocol,
    mjpegPath = mjpegPath,
    snapshotPath = snapshotPath,
    snapshotFps = snapshotFps,
    sipMode = sipMode,
    sipLocalPort = sipLocalPort,
    sipServerHost = sipServerHost,
    sipServerPort = sipServerPort,
    sipUser = sipUser,
    sipPassword = sipPassword?.value,
    recordOnMotion = recordOnMotion,
    recordOnRing = recordOnRing,
    recordOnNoise = recordOnNoise,
    recordOnMotionOnDevice = recordOnMotionOnDevice,
    motionSensitivity = motionSensitivity,
    motionPreRollFrames = motionPreRollFrames,
    motionPostRecordSeconds = motionPostRecordSeconds,
    noisePostRecordSeconds = noisePostRecordSeconds,
    ringRecordSeconds = ringRecordSeconds,
    wakeOnRing = wakeOnRing,
    soundOnRing = soundOnRing,
    vibrateOnRing = vibrateOnRing,
    wakeOnMotion = wakeOnMotion,
    wakeOnNoise = wakeOnNoise,
    keepLocksHeld = keepLocksHeld,
    isEnabled = isEnabled,
    serverDeviceId = serverDeviceId,
)

fun Device.toEntity(): DeviceEntity = DeviceEntity(
    id = id,
    name = name,
    deviceType = deviceType,
    ipAddress = ipAddress,
    httpPort = httpPort,
    httpsPort = httpsPort,
    useHttps = useHttps,
    rtspPort = rtspPort,
    rtspPath = rtspPath,
    username = username,
    password = EncryptedString(password),
    streamProtocol = streamProtocol,
    mjpegPath = mjpegPath,
    snapshotPath = snapshotPath,
    snapshotFps = snapshotFps,
    sipMode = sipMode,
    sipLocalPort = sipLocalPort,
    sipServerHost = sipServerHost,
    sipServerPort = sipServerPort,
    sipUser = sipUser,
    sipPassword = sipPassword?.let(::EncryptedString),
    recordOnMotion = recordOnMotion,
    recordOnRing = recordOnRing,
    recordOnNoise = recordOnNoise,
    recordOnMotionOnDevice = recordOnMotionOnDevice,
    motionSensitivity = motionSensitivity,
    motionPreRollFrames = motionPreRollFrames,
    motionPostRecordSeconds = motionPostRecordSeconds,
    noisePostRecordSeconds = noisePostRecordSeconds,
    ringRecordSeconds = ringRecordSeconds,
    wakeOnRing = wakeOnRing,
    soundOnRing = soundOnRing,
    vibrateOnRing = vibrateOnRing,
    wakeOnMotion = wakeOnMotion,
    wakeOnNoise = wakeOnNoise,
    keepLocksHeld = keepLocksHeld,
    isEnabled = isEnabled,
    serverDeviceId = serverDeviceId,
)

fun RecordingEntity.toDomain(): Recording = Recording(
    id = id,
    deviceId = deviceId,
    deviceName = deviceName,
    eventType = eventType,
    alsoEventTypes = parseAlsoEventTypes(alsoEventTypes),
    timestamp = timestamp,
    durationSeconds = durationSeconds,
    filePath = filePath,
    fileSizeBytes = fileSizeBytes,
    thumbnailPath = thumbnailPath,
    isProtected = isProtected,
    note = note,
)

fun Recording.toEntity(): RecordingEntity = RecordingEntity(
    id = id,
    deviceId = deviceId,
    deviceName = deviceName,
    eventType = eventType,
    alsoEventTypes = alsoEventTypes.joinToString(","),
    timestamp = timestamp,
    durationSeconds = durationSeconds,
    filePath = filePath,
    fileSizeBytes = fileSizeBytes,
    thumbnailPath = thumbnailPath,
    isProtected = isProtected,
    note = note,
)

/** Names in the stored CSV; unreadable names are skipped rather than dropping the row. */
private fun parseAlsoEventTypes(raw: String): List<EventType> =
    raw.split(',').mapNotNull { name ->
        name.trim().takeIf { it.isNotEmpty() }?.let { runCatching { EventType.valueOf(it) }.getOrNull() }
    }

fun AppSettingsEntity.toDomain(): AppSettings = AppSettings(
    recordingMode = recordingMode,
    serverBaseUrl = serverBaseUrl,
    serverApiKey = serverApiKey.value,
    retentionDays = retentionDays,
    transcodeOnExport = transcodeOnExport,
    exportFolderUri = exportFolderUri,
    autoExportOnFinalize = autoExportOnFinalize,
    internalQuotaMb = internalQuotaMb,
    internalCleanupEnabled = internalCleanupEnabled,
    externalQuotaMb = externalQuotaMb,
    externalCleanupEnabled = externalCleanupEnabled,
    themePrimaryIndex = themePrimaryIndex,
    themeSecondaryIndex = themeSecondaryIndex,
    themeTertiaryIndex = themeTertiaryIndex,
    themeMode = themeMode,
    themeUseDynamicColor = themeUseDynamicColor,
    autoBootEnabled = autoBootEnabled,
)

/** The entity's declared-but-unread legacy columns take their table defaults through the data-class defaults. */
fun AppSettings.toEntity(): AppSettingsEntity = AppSettingsEntity(
    recordingMode = recordingMode,
    serverBaseUrl = serverBaseUrl,
    serverApiKey = EncryptedString(serverApiKey),
    retentionDays = retentionDays,
    transcodeOnExport = transcodeOnExport,
    exportFolderUri = exportFolderUri,
    autoExportOnFinalize = autoExportOnFinalize,
    internalQuotaMb = internalQuotaMb,
    internalCleanupEnabled = internalCleanupEnabled,
    externalQuotaMb = externalQuotaMb,
    externalCleanupEnabled = externalCleanupEnabled,
    themePrimaryIndex = themePrimaryIndex,
    themeSecondaryIndex = themeSecondaryIndex,
    themeTertiaryIndex = themeTertiaryIndex,
    themeMode = themeMode,
    themeUseDynamicColor = themeUseDynamicColor,
    autoBootEnabled = autoBootEnabled,
)
