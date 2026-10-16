package io.github.mvolkert.entryrecorder.domain.model

import io.github.mvolkert.entryrecorder.data.model.DeviceType

/**
 * Every URL and clamped rate a [Device] can serve, derived once from its stored configuration. A value
 * object of plain strings and ints so the derivation is testable without Room, Context or a network.
 */
data class DeviceEndpoints(
    val httpBaseUrl: String,
    val rtspStreamUri: String,
    val snapshotUrl: String,
    val mjpegUrl: String,
    val effectiveSnapshotFps: Int,
    val maxSnapshotFps: Int,
    val effectiveMotionPreRollFrames: Int,
)

/**
 * The single place device endpoint URLs are built and configured rates are clamped; the rules moved out
 * of the Room entity so they are pure and unit-testable.
 *
 * The MJPEG derivation reproduces the stored-path behaviour of every device type that came before it: a
 * path pasted with the IP in it is stripped, a blank or bare `/` falls back to the 2N default, and a path
 * without a leading slash gets one.
 */
object DeviceEndpointResolver {

    // Single sanity ceiling for every device: beyond it the frame interval drops below a realistic
    // HTTP round trip, so no snapshot endpoint can deliver it. Per-camera capability is measured in
    // the device form rather than guessed from the type (a 2N once forced this down to a static 6).
    const val SNAPSHOT_FPS_HARD_MAX = 30

    // Pre-roll ring: the default is the analyzer's original fixed size, so devices saved before the
    // setting existed keep capturing exactly as before. The ceiling bounds the heap the 24/7
    // analyzer loop holds in full-res JPEGs (each frame is a camera snapshot).
    const val DEFAULT_MOTION_PRE_ROLL_FRAMES = 6
    const val MOTION_PRE_ROLL_FRAMES_MIN = 1
    const val MOTION_PRE_ROLL_FRAMES_MAX = 30

    // The 2N MJPEG server push accepts an fps param in 1–10 (2N Streaming manual); the configured
    // snapshot rate is clamped into it when the stream URL is derived.
    private const val MJPEG_FPS_MIN = 1
    private const val MJPEG_FPS_MAX = 10

    private const val DEFAULT_RTSP_PATH = "/live.sdp"

    fun resolve(device: Device): DeviceEndpoints {
        val httpBaseUrl = buildHttpBaseUrl(device)
        val snapshotUrl = buildSnapshotUrl(device, httpBaseUrl)
        return DeviceEndpoints(
            httpBaseUrl = httpBaseUrl,
            rtspStreamUri = buildRtspStreamUri(device),
            snapshotUrl = snapshotUrl,
            mjpegUrl = buildMjpegUrl(device, snapshotUrl),
            effectiveSnapshotFps = device.snapshotFps.coerceIn(1, SNAPSHOT_FPS_HARD_MAX),
            maxSnapshotFps = SNAPSHOT_FPS_HARD_MAX,
            effectiveMotionPreRollFrames = device.motionPreRollFrames
                .coerceIn(MOTION_PRE_ROLL_FRAMES_MIN, MOTION_PRE_ROLL_FRAMES_MAX),
        )
    }

    private fun buildHttpBaseUrl(device: Device): String {
        val scheme = if (device.useHttps) "https" else "http"
        val port = if (device.useHttps) device.httpsPort else device.httpPort
        return "$scheme://${device.ipAddress}:$port"
    }

    private fun buildRtspStreamUri(device: Device): String {
        val authPart = if (device.username.isNotBlank() && device.password.isNotBlank()) {
            "${device.username}:${device.password}@"
        } else {
            ""
        }

        // Sanitize path: remove repeated IP addresses or hostnames that users often paste accidentally
        var sanitizedPath = device.rtspPath.trim()
        if (sanitizedPath.contains(device.ipAddress)) {
            sanitizedPath = sanitizedPath.replace(device.ipAddress, "").replace("//", "/")
        }

        val cleanPath = when {
            sanitizedPath.isBlank() || sanitizedPath == "/" -> DEFAULT_RTSP_PATH // Default for 2N
            sanitizedPath.startsWith("/") -> sanitizedPath
            else -> "/$sanitizedPath"
        }
        return "rtsp://$authPart${device.ipAddress}:${device.rtspPort}$cleanPath"
    }

    private fun buildSnapshotUrl(device: Device, httpBaseUrl: String): String {
        var path = device.snapshotPath.trim()
        if (!path.startsWith("/")) path = "/$path"
        val separator = if (path.contains("?")) "&" else "?"
        return if (path.contains("width=") || path.contains("height=")) {
            "$httpBaseUrl$path"
        } else {
            "$httpBaseUrl$path${separator}width=1280&height=720"
        }
    }

    /**
     * The 2N Verso has no dedicated MJPEG endpoint: its firmware serves a
     * `multipart/x-mixed-replace` server push from the same snapshot URL with an added `fps` param
     * (2N Streaming manual), so it is derived from the snapshot URL rather than read from the stored
     * mjpeg path. Deriving also self-heals devices saved before this whose `/api/camera/mjpeg`
     * path answers HTTP 200 + JSON and read as a dead camera. Other device types keep their raw path.
     */
    private fun buildMjpegUrl(device: Device, snapshotUrl: String): String {
        if (device.deviceType == DeviceType.TWO_N_VERSO) {
            val fps = device.snapshotFps.coerceIn(1, SNAPSHOT_FPS_HARD_MAX).coerceIn(MJPEG_FPS_MIN, MJPEG_FPS_MAX)
            val separator = if (snapshotUrl.contains("?")) "&" else "?"
            return "$snapshotUrl${separator}fps=$fps"
        }
        var path = device.mjpegPath.trim()
        if (!path.startsWith("/")) path = "/$path"
        return "${buildHttpBaseUrl(device)}$path"
    }
}
