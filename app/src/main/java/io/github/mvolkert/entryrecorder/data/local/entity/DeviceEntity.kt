package io.github.mvolkert.entryrecorder.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.mvolkert.entryrecorder.data.model.DeviceType
import io.github.mvolkert.entryrecorder.data.model.MotionSensitivity
import io.github.mvolkert.entryrecorder.data.model.SipMode
import io.github.mvolkert.entryrecorder.data.model.StreamProtocol

@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val deviceType: DeviceType = DeviceType.TWO_N_VERSO,
    val ipAddress: String,
    val httpPort: Int = 80,
    val httpsPort: Int = 443,
    val useHttps: Boolean = false,
    val rtspPort: Int = 554,
    val rtspPath: String = "/live.sdp",
    val username: String = "admin",
    // Held at rest as `enc:v1:` ciphertext; the repository's DomainMappers encrypt on write and decrypt on
    // read. The default is only a fallback (a fresh row is always built through `Device.toEntity(cipher)`).
    val password: String = "2n",

    // Streaming Protocol & Endpoints
    val streamProtocol: StreamProtocol = StreamProtocol.AUTO,
    val mjpegPath: String = "/api/camera/mjpeg",
    val snapshotPath: String = "/api/camera/snapshot",
    val snapshotFps: Int = 5,
    
    // SIP Configuration
    val sipMode: SipMode = SipMode.PEER_TO_PEER,
    val sipLocalPort: Int = 5060,
    val sipServerHost: String? = null,
    val sipServerPort: Int? = 5060,
    val sipUser: String? = null,
    val sipPassword: String? = null,
    
    // Recording & Trigger Configuration
    val recordOnMotion: Boolean = true,
    val recordOnRing: Boolean = true,
    val recordOnNoise: Boolean = true,
    // Motion is analyzed locally in-app from the video stream instead of relying on the device's own detection
    val recordOnMotionOnDevice: Boolean = false,
    // Sensitivity of that in-app analyzer; only consulted while recordOnMotionOnDevice is true.
    val motionSensitivity: MotionSensitivity = MotionSensitivity.BALANCED,
    // How many recent frames the analyzer's pre-roll ring keeps and prepends when a motion clip starts.
    // Default reproduces the fixed constant shipped before this setting existed.
    val motionPreRollFrames: Int = DEFAULT_MOTION_PRE_ROLL_FRAMES,
    val motionPostRecordSeconds: Int = 20,
    val noisePostRecordSeconds: Int = 20,
    val ringRecordSeconds: Int = 60,
    // Hard per-trigger recording ceilings. 0 is "no limit", which is also the migration default, so an
    // upgraded device keeps the clip lengths it had before the settings existed.
    val motionMaxFrames: Int = DEFAULT_MAX_RECORD_LIMIT,
    val noiseMaxFrames: Int = DEFAULT_MAX_RECORD_LIMIT,
    val ringMaxMinutes: Int = DEFAULT_MAX_RECORD_LIMIT,

    // Per-device alert & lockscreen behavior (was global app settings). Defaults true to match the
    // previous global behavior; the Room migration seeds them from the old app_settings row.
    /**
     * Doorbell ring: take the call over the screen (full-screen intent plus a direct activity launch).
     * False keeps the ring as a notification only — still audible and tappable, but the lockscreen stays
     * up. The notification itself is always posted, so this is about visibility, not about being told.
     */
    val wakeOnRing: Boolean = true,
    val soundOnRing: Boolean = true,
    val vibrateOnRing: Boolean = true,
    val wakeOnMotion: Boolean = true,
    val wakeOnNoise: Boolean = true,
    /**
     * Keep the CPU and Wi-Fi locks held for the whole time this device is monitored, not just during an
     * active recording or ring. Off by default: idle monitoring then holds no lock and pays the battery
     * price only while something is actually being captured. On is the detection-first deployment — a
     * dedicated device that must never delay a wake because the radio was asleep.
     */
    val keepLocksHeld: Boolean = false,
    val isEnabled: Boolean = true,
    /**
     * Row id of this device on the Python server, assigned when the app registers it via
     * `POST /api/devices` (Phase S device sync). Null until registered, or in local-only mode. All
     * server calls (record/stop/reconcile, live) address the device by this id, never by the local [id],
     * which belongs to a different id space. Preserved across edits because the edit form copies from the
     * existing row rather than rebuilding it.
     */
    val serverDeviceId: Long? = null
) {
    companion object {
        // Sanity bounds behind the derived getters — the URL/clamping rules themselves live in
        // io.github.mvolkert.entryrecorder.domain.model.DeviceEndpointResolver.
        const val SNAPSHOT_FPS_HARD_MAX = 30

        const val DEFAULT_MOTION_PRE_ROLL_FRAMES = 6
        const val MOTION_PRE_ROLL_FRAMES_MIN = 1
        const val MOTION_PRE_ROLL_FRAMES_MAX = 30

        const val DEFAULT_MAX_RECORD_LIMIT = 0
        const val MOTION_MAX_FRAMES_MIN = 0
        const val MOTION_MAX_FRAMES_MAX = 3000
        const val NOISE_MAX_FRAMES_MIN = 0
        const val NOISE_MAX_FRAMES_MAX = 3000
        const val RING_MAX_MINUTES_MIN = 0
        const val RING_MAX_MINUTES_MAX = 60
    }
}
