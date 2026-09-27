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
    MOTION        // Motion currently detected
}
