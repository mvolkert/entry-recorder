package io.github.mvolkert.entryrecorder.data.model

/**
 * Whether the transport for this device type can deliver its own camera motion and noise events over HTTP.
 *
 * The signal is deliberately a capability rather than a device-type check at the call site: the same rule
 * gates the device form, the pre-fill for a new row, and the live card's glyph, so a transport change moves
 * one function instead of three places.
 *
 * Interim answer is `false` for every shipping type. The Verso's HTTP event path never worked on this
 * firmware (`/api/event` endpoints and the two status polls answered `invalid request path`), and the
 * replacement Logging API transport is provisional until tasks 3.2/3.3 in `add-2n-logging-api-transport`
 * confirm a real subscribe and a real pull body on hardware. Generic RTSP/ONVIF has no event bus at all.
 * Once that change archives, this becomes a `/api/log/caps`-driven signal so the form and the live card
 * follow what the device actually reports rather than a compile-time guess.
 */
fun supportsCameraEvents(deviceType: DeviceType): Boolean = when (deviceType) {
    DeviceType.TWO_N_VERSO -> false
    DeviceType.GENERIC_RTSP_ONVIF -> false
}
