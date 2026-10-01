package io.github.mvolkert.entryrecorder.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.mvolkert.entryrecorder.data.model.DeviceType
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
    val motionPostRecordSeconds: Int = 20,
    val noisePostRecordSeconds: Int = 20,
    val ringRecordSeconds: Int = 60,

    // Per-device alert & lockscreen behavior (was global app settings). Defaults true to match the
    // previous global behavior; the Room migration seeds them from the old app_settings row.
    val wakeOnRing: Boolean = true,
    val soundOnRing: Boolean = true,
    val vibrateOnRing: Boolean = true,
    val wakeOnMotion: Boolean = true,
    val wakeOnNoise: Boolean = true,
    val isEnabled: Boolean = true
) {
    val httpBaseUrl: String
        get() {
            val scheme = if (useHttps) "https" else "http"
            val port = if (useHttps) httpsPort else httpPort
            return "$scheme://$ipAddress:$port"
        }

    val rtspStreamUrl: String
        get() {
            val authPart = if (username.isNotBlank() && password.isNotBlank()) {
                "$username:$password@"
            } else ""
            
            // Sanitize path: remove repeated IP addresses or hostnames that users often paste accidentally
            var sanitizedPath = rtspPath.trim()
            if (sanitizedPath.contains(ipAddress)) {
                sanitizedPath = sanitizedPath.replace(ipAddress, "").replace("//", "/")
            }
            
            val cleanPath = when {
                sanitizedPath.isBlank() || sanitizedPath == "/" -> "/live.sdp" // Default for 2N
                sanitizedPath.startsWith("/") -> sanitizedPath
                else -> "/$sanitizedPath"
            }
            return "rtsp://$authPart$ipAddress:$rtspPort$cleanPath"
        }

    val snapshotUrl: String
        get() {
            var path = snapshotPath.trim()
            if (!path.startsWith("/")) path = "/$path"
            val separator = if (path.contains("?")) "&" else "?"
            return if (path.contains("width=") || path.contains("height=")) {
                "$httpBaseUrl$path"
            } else {
                "$httpBaseUrl$path${separator}width=1280&height=720"
            }
        }

    val mjpegUrl: String
        get() {
            var path = mjpegPath.trim()
            if (!path.startsWith("/")) path = "/$path"
            return "$httpBaseUrl$path"
        }

    /**
     * Snapshot polling rate actually worth asking for: [snapshotFps] clamped to what this device type
     * can serve. The 2N serialises snapshot encoding (~5.9 req/s measured on this LAN), so a higher
     * configured value does not deliver more frames — it only makes the setting lie about the result.
     */
    val effectiveSnapshotFps: Int
        get() = snapshotFps.coerceIn(1, maxSnapshotFps)

    /** Upper bound [effectiveSnapshotFps] clamps to, surfaced to the user wherever the rate is edited. */
    val maxSnapshotFps: Int
        get() = maxSnapshotFpsFor(deviceType)

    companion object {
        // Ceiling measured on the deployed 2N IP Verso firmware (≈5.9 req/s sustained over one
        // keep-alive connection); polling faster just queues requests behind the encoder.
        const val TWO_N_SNAPSHOT_FPS_CEILING = 6

        // Ceiling for devices with no measured endpoint limit: the frame interval still has to stay
        // above a realistic HTTP round trip, so nothing above this is achievable through a snapshot.
        const val SNAPSHOT_FPS_HARD_MAX = 30

        /** The snapshot rate ceiling for a device type, also what the edit form warns about. */
        fun maxSnapshotFpsFor(deviceType: DeviceType): Int = when (deviceType) {
            DeviceType.TWO_N_VERSO -> TWO_N_SNAPSHOT_FPS_CEILING
            DeviceType.GENERIC_RTSP_ONVIF -> SNAPSHOT_FPS_HARD_MAX
        }
    }
}
