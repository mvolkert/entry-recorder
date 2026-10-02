package io.github.mvolkert.entryrecorder.data.model

enum class DeviceType {
    TWO_N_VERSO,
    GENERIC_RTSP_ONVIF
}

enum class StreamProtocol {
    AUTO,           // Prefers RTSP, automatically falls back to MJPEG or Snapshot if unavailable
    RTSP,           // Direct RTSP video stream
    MJPEG_STREAM,   // HTTP multipart/x-mixed-replace stream
    HTTP_SNAPSHOT   // HTTP periodic snapshot polling
}

enum class SipMode {
    PEER_TO_PEER,   // Direct LAN IP-to-IP SIP call
    PBX_REGISTRAR,  // Registered to PBX like Fritz!Box / Asterisk
    DISABLED
}

/**
 * How eagerly the in-app motion analyzer triggers a recording, chosen per device. BALANCED reproduces
 * the analyzer's original fixed constants, so a device saved before this setting behaves identically.
 * The other two trade detection speed against battery/network for the owner to pick from the live
 * health line. Applies only to in-app analysis, never the camera's own motion events.
 */
enum class MotionSensitivity {
    SENSITIVE,   // low bar + fast polling: catches quick crossers, costs battery, can over-trigger
    BALANCED,    // the shipped default = the analyzer's original numbers
    POWER_SAVER  // high bar + slow idle polling: calmest and cheapest, may miss a fast walk-by
}

enum class EventType {
    MOTION,
    RING,
    NOISE,
    MANUAL
}

enum class RecordingMode {
    APP_LOCAL,       // Recorded directly by the Android app on local device storage
    PYTHON_SERVER    // Recorded centrally by external Python server
}

/** Runtime monitoring state of a device as tracked by IntercomMonitorService (transient, not persisted). */
enum class MonitorStatus {
    DISABLED,     // Device disabled, or the service does not monitor it (yet)
    MONITORING,   // Events are being monitored, no motion right now
    MOTION,       // Motion currently detected
    DEGRADED,     // Reachable, but a reduced path (polling fallback, or snapshot frames going missing)
    OFFLINE       // Nothing usable arrives: the endpoints answer with errors or no frames at all
}

/**
 * Observability of a device's event/image path. Three values because "reachable" and "working" are
 * not the same: an SSE stream in polling fallback, or a snapshot endpoint that keeps timing out, is
 * neither connected nor disconnected, and a boolean forced that into one of the two.
 */
enum class ConnectionQuality {
    ONLINE,       // Full path works: events and frames arrive
    DEGRADED,     // Reachable but reduced: fallback polling only, or frames intermittently missing
    OFFLINE       // Nothing usable arrives for an extended stretch
}

/**
 * Which of a device's two independent delivery paths a [ConnectionQuality] describes. They fail
 * separately: a camera can serve snapshots fine (live view, recording and in-app motion analysis
 * work) while its own event stream is dead (doorbell rings, camera-reported motion and noise never
 * arrive), and a single conflated status made those indistinguishable to the UI.
 */
enum class ConnectionCapability {
    DEVICE_EVENTS,  // The device's own event stream: doorbell ring, camera motion, noise
    SNAPSHOT        // The HTTP snapshot/image path: live view, recording, in-app motion analysis
}
