package io.github.mvolkert.entryrecorder.data.model

enum class DeviceType {
    TWO_N_VERSO,
    GENERIC_RTSP_ONVIF
}

enum class SipMode {
    PEER_TO_PEER,   // Direct LAN IP-to-IP SIP call
    PBX_REGISTRAR,  // Registered to PBX like Fritz!Box / Asterisk
    DISABLED
}

enum class EventType {
    MOTION,
    RING,
    MANUAL
}
