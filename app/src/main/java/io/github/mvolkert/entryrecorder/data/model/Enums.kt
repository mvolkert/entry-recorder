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
