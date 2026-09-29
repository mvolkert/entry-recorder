package io.github.mvolkert.entryrecorder.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mvolkert.entryrecorder.data.local.entity.DeviceEntity
import io.github.mvolkert.entryrecorder.data.model.DeviceType
import io.github.mvolkert.entryrecorder.data.model.SipMode
import io.github.mvolkert.entryrecorder.data.model.StreamProtocol

/**
 * Editable values behind [DeviceEditDialog], seeded from an existing device or from the built-in
 * defaults for a new one.
 *
 * Numeric fields are deliberately kept as the strings the text fields show, so typing into a port
 * is never re-parsed mid-edit; the parse-with-fallback only happens in [buildDevice] and
 * [buildTestCandidate]. The whole initial-value table lives here on purpose — it is the part of the
 * form that has to stay in sync with [DeviceEntity]'s own defaults.
 */
class DeviceFormState(
    private val initial: DeviceEntity?,
    defaultName: String
) {
    var name by mutableStateOf(initial?.name ?: defaultName)
    var deviceType by mutableStateOf(initial?.deviceType ?: DeviceType.TWO_N_VERSO)
    var ipAddress by mutableStateOf(initial?.ipAddress ?: "192.168.1.100")
    var httpPort by mutableStateOf(initial?.httpPort?.toString() ?: "80")
    var rtspPort by mutableStateOf(initial?.rtspPort?.toString() ?: "554")
    var rtspPath by mutableStateOf(initial?.rtspPath ?: "/live.sdp")
    var username by mutableStateOf(initial?.username ?: "admin")
    var password by mutableStateOf(initial?.password ?: "2n")

    var streamProtocol by mutableStateOf(initial?.streamProtocol ?: StreamProtocol.AUTO)
    var mjpegPath by mutableStateOf(initial?.mjpegPath ?: "/api/camera/mjpeg")
    var snapshotPath by mutableStateOf(initial?.snapshotPath ?: "/api/camera/snapshot")
    var snapshotFps by mutableStateOf(initial?.snapshotFps?.toString() ?: "5")

    var sipMode by mutableStateOf(initial?.sipMode ?: SipMode.PEER_TO_PEER)
    var sipLocalPort by mutableStateOf(initial?.sipLocalPort?.toString() ?: "5060")
    var sipServerHost by mutableStateOf(initial?.sipServerHost ?: "")
    var sipServerPort by mutableStateOf(initial?.sipServerPort?.toString() ?: "5060")
    var sipUser by mutableStateOf(initial?.sipUser ?: "")
    var sipPassword by mutableStateOf(initial?.sipPassword ?: "")

    var recordOnMotion by mutableStateOf(initial?.recordOnMotion ?: true)
    var recordOnRing by mutableStateOf(initial?.recordOnRing ?: true)
    var recordOnNoise by mutableStateOf(initial?.recordOnNoise ?: true)
    var recordOnMotionOnDevice by mutableStateOf(initial?.recordOnMotionOnDevice ?: false)

    var useHttps by mutableStateOf(initial?.useHttps ?: false)
    var httpsPort by mutableStateOf(initial?.httpsPort?.toString() ?: "443")

    var ringRecordSeconds by mutableStateOf((initial?.ringRecordSeconds ?: 60).toString())
    var motionPostRecordSeconds by mutableStateOf((initial?.motionPostRecordSeconds ?: 20).toString())
    var noisePostRecordSeconds by mutableStateOf((initial?.noisePostRecordSeconds ?: 20).toString())

    var isEnabled by mutableStateOf(initial?.isEnabled ?: true)

    /** Save is refused while either of the two identifying fields is empty. */
    val isValid: Boolean
        get() = name.isNotBlank() && ipAddress.isNotBlank()

    /**
     * The connection probe sees exactly what was typed, including a still-empty or untrimmed port,
     * and only carries the fields [io.github.mvolkert.entryrecorder.data.device.IntercomDeviceFactory]
     * needs to build a device.
     */
    fun buildTestCandidate(): DeviceEntity = DeviceEntity(
        id = initial?.id ?: 0,
        name = name,
        deviceType = deviceType,
        ipAddress = ipAddress,
        httpPort = httpPort.toIntOrNull() ?: 80,
        rtspPort = rtspPort.toIntOrNull() ?: 554,
        rtspPath = rtspPath,
        username = username,
        password = password,
        streamProtocol = streamProtocol,
        mjpegPath = mjpegPath,
        snapshotPath = snapshotPath,
        snapshotFps = snapshotFps.toIntOrNull() ?: 5
    )

    /**
     * Builds the device to persist. Starts from the edited device — or a fresh entity when creating —
     * and only overwrites the fields this dialog exposes, so anything the form does not show keeps its
     * current value instead of being reset to an entity default.
     */
    fun buildDevice(): DeviceEntity {
        val trimmedName = name.trim()
        val trimmedIp = ipAddress.trim()
        val base = initial ?: DeviceEntity(name = trimmedName, ipAddress = trimmedIp)
        return base.copy(
            name = trimmedName,
            deviceType = deviceType,
            ipAddress = trimmedIp,
            httpPort = httpPort.toIntOrNull() ?: 80,
            useHttps = useHttps,
            httpsPort = httpsPort.toIntOrNull() ?: 443,
            rtspPort = rtspPort.toIntOrNull() ?: 554,
            rtspPath = rtspPath.trim(),
            username = username.trim(),
            password = password.trim(),
            streamProtocol = streamProtocol,
            mjpegPath = mjpegPath.trim(),
            snapshotPath = snapshotPath.trim(),
            snapshotFps = snapshotFps.toIntOrNull() ?: 5,
            sipMode = sipMode,
            sipLocalPort = sipLocalPort.toIntOrNull() ?: 5060,
            sipServerHost = if (sipServerHost.isNotBlank()) sipServerHost.trim() else null,
            sipServerPort = sipServerPort.toIntOrNull() ?: 5060,
            sipUser = if (sipUser.isNotBlank()) sipUser.trim() else null,
            sipPassword = if (sipPassword.isNotBlank()) sipPassword.trim() else null,
            recordOnMotion = recordOnMotion,
            recordOnRing = recordOnRing,
            recordOnNoise = recordOnNoise,
            recordOnMotionOnDevice = recordOnMotionOnDevice,
            ringRecordSeconds = ringRecordSeconds.toIntOrNull() ?: 60,
            motionPostRecordSeconds = motionPostRecordSeconds.toIntOrNull() ?: 20,
            noisePostRecordSeconds = noisePostRecordSeconds.toIntOrNull() ?: 20,
            isEnabled = isEnabled
        )
    }
}
