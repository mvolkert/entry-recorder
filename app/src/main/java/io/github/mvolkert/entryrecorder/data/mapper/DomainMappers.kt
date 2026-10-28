package io.github.mvolkert.entryrecorder.data.mapper

import io.github.mvolkert.entryrecorder.data.local.crypto.CredentialCipher
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
 *
 * The credential-bearing mappers ([DeviceEntity.toDomain], [Device.toEntity], [AppSettingsEntity.toDomain],
 * [AppSettings.toEntity]) take a [CredentialCipher] because a device password / SIP password / server API key
 * is stored as ciphertext at rest: it is decrypted here on the way out to the domain and encrypted on the way
 * in to storage, so the rest of the app only ever sees plaintext and the encryption boundary stays in one place.
 */

fun DeviceEntity.toDomain(cipher: CredentialCipher): Device = Device(
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
    password = cipher.decrypt(password),
    streamProtocol = streamProtocol,
    mjpegPath = mjpegPath,
    snapshotPath = snapshotPath,
    snapshotFps = snapshotFps,
    sipMode = sipMode,
    sipLocalPort = sipLocalPort,
    sipServerHost = sipServerHost,
    sipServerPort = sipServerPort,
    sipUser = sipUser,
    sipPassword = sipPassword?.let { cipher.decrypt(it) },
    recordOnMotion = recordOnMotion,
    recordOnRing = recordOnRing,
    recordOnNoise = recordOnNoise,
    recordOnMotionOnDevice = recordOnMotionOnDevice,
    motionSensitivity = motionSensitivity,
    motionPreRollFrames = motionPreRollFrames,
    motionPostRecordSeconds = motionPostRecordSeconds,
    noisePostRecordSeconds = noisePostRecordSeconds,
    ringRecordSeconds = ringRecordSeconds,
    motionMaxFrames = motionMaxFrames,
    noiseMaxFrames = noiseMaxFrames,
    ringMaxMinutes = ringMaxMinutes,
    wakeOnRing = wakeOnRing,
    soundOnRing = soundOnRing,
    vibrateOnRing = vibrateOnRing,
    wakeOnMotion = wakeOnMotion,
    wakeOnNoise = wakeOnNoise,
    keepLocksHeld = keepLocksHeld,
    isEnabled = isEnabled,
    serverDeviceId = serverDeviceId,
)

fun Device.toEntity(cipher: CredentialCipher): DeviceEntity = DeviceEntity(
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
    password = cipher.encrypt(password),
    streamProtocol = streamProtocol,
    mjpegPath = mjpegPath,
    snapshotPath = snapshotPath,
    snapshotFps = snapshotFps,
    sipMode = sipMode,
    sipLocalPort = sipLocalPort,
    sipServerHost = sipServerHost,
    sipServerPort = sipServerPort,
    sipUser = sipUser,
    sipPassword = sipPassword?.let { cipher.encrypt(it) },
    recordOnMotion = recordOnMotion,
    recordOnRing = recordOnRing,
    recordOnNoise = recordOnNoise,
    recordOnMotionOnDevice = recordOnMotionOnDevice,
    motionSensitivity = motionSensitivity,
    motionPreRollFrames = motionPreRollFrames,
    motionPostRecordSeconds = motionPostRecordSeconds,
    noisePostRecordSeconds = noisePostRecordSeconds,
    ringRecordSeconds = ringRecordSeconds,
    motionMaxFrames = motionMaxFrames,
    noiseMaxFrames = noiseMaxFrames,
    ringMaxMinutes = ringMaxMinutes,
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

fun AppSettingsEntity.toDomain(cipher: CredentialCipher): AppSettings = AppSettings(
    recordingMode = recordingMode,
    serverBaseUrl = serverBaseUrl,
    serverApiKey = cipher.decrypt(serverApiKey),
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
    motionAlertEnabled = motionAlertEnabled,
    motionAlertRecipient = motionAlertRecipient,
    loggingToFileEnabled = loggingToFileEnabled,
)

/** The entity's declared-but-unread legacy columns take their table defaults through the data-class defaults. */
fun AppSettings.toEntity(cipher: CredentialCipher): AppSettingsEntity = AppSettingsEntity(
    recordingMode = recordingMode,
    serverBaseUrl = serverBaseUrl,
    serverApiKey = cipher.encrypt(serverApiKey),
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
    motionAlertEnabled = motionAlertEnabled,
    motionAlertRecipient = motionAlertRecipient,
    loggingToFileEnabled = loggingToFileEnabled,
)
