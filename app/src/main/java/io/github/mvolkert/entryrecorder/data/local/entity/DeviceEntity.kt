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

    /**
     * MJPEG stream URL. The 2N Verso has no dedicated MJPEG endpoint: its firmware serves a
     * `multipart/x-mixed-replace` server push from the same snapshot URL with an added `fps` param
     * (2N Streaming manual), so it is derived from [snapshotUrl] rather than read from the stored
     * [mjpegPath]. Deriving also self-heals devices saved before this whose `/api/camera/mjpeg`
     * path answers HTTP 200 + JSON and read as a dead camera. Other device types keep their raw path.
     */
    val mjpegUrl: String
        get() {
            if (deviceType == DeviceType.TWO_N_VERSO) {
                val fps = effectiveSnapshotFps.coerceIn(MJPEG_FPS_MIN, MJPEG_FPS_MAX)
                val separator = if (snapshotUrl.contains("?")) "&" else "?"
                return "$snapshotUrl${separator}fps=$fps"
            }
            var path = mjpegPath.trim()
            if (!path.startsWith("/")) path = "/$path"
            return "$httpBaseUrl$path"
        }

    /**
     * Snapshot polling rate actually worth asking for: [snapshotFps] clamped to [SNAPSHOT_FPS_HARD_MAX].
     * The ceiling used to be a per-device-type guess (6 for the 2N), which fought real cameras that can
     * serve more — the device form now measures each endpoint directly ("Get FPS"), so only this sanity
     * bound remains: past it the poll interval falls below a realistic HTTP round trip.
     */
    val effectiveSnapshotFps: Int
        get() = snapshotFps.coerceIn(1, maxSnapshotFps)

    /** Upper bound [effectiveSnapshotFps] clamps to, surfaced to the user wherever the rate is edited. */
    val maxSnapshotFps: Int
        get() = SNAPSHOT_FPS_HARD_MAX

    companion object {
        // Single sanity ceiling for every device: beyond it the frame interval drops below a realistic
        // HTTP round trip, so no snapshot endpoint can deliver it. Per-camera capability is measured in
        // the device form rather than guessed from the type (a 2N once forced this down to a static 6).
        const val SNAPSHOT_FPS_HARD_MAX = 30

        // The 2N MJPEG server push accepts an fps param in 1–10 (2N Streaming manual); the configured
        // snapshot rate is clamped into it when the stream URL is derived.
        private const val MJPEG_FPS_MIN = 1
        private const val MJPEG_FPS_MAX = 10
    }
}
