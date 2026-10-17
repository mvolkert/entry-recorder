package io.github.mvolkert.entryrecorder.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.data.model.SipMode
import io.github.mvolkert.entryrecorder.data.model.StreamProtocol
import io.github.mvolkert.entryrecorder.data.model.eventTypeLabelRes

/**
 * Localized label for a trigger badge — the single place an [EventType] becomes UI text, shared by the
 * recording list and the call header so both read identically (never the raw enum name). The resource id
 * mapping lives in the data layer so non-composable callers (e.g. the export gateway) resolve it too.
 */
@Composable
fun eventTypeLabel(eventType: EventType): String = stringResource(eventTypeLabelRes(eventType))

/** Localized SIP operating-mode label for a device summary line. */
@Composable
fun sipModeLabel(mode: SipMode): String = stringResource(
    when (mode) {
        SipMode.PEER_TO_PEER -> R.string.sip_mode_peer_to_peer
        SipMode.PBX_REGISTRAR -> R.string.sip_mode_pbx_registrar
        SipMode.DISABLED -> R.string.sip_mode_disabled
    }
)

/** Localized transport label for the live-stream protocol badge. */
@Composable
fun streamProtocolLabel(protocol: StreamProtocol): String = stringResource(
    when (protocol) {
        StreamProtocol.AUTO -> R.string.stream_protocol_auto
        StreamProtocol.RTSP -> R.string.stream_protocol_rtsp
        StreamProtocol.MJPEG_STREAM -> R.string.stream_protocol_mjpeg
        StreamProtocol.HTTP_SNAPSHOT -> R.string.stream_protocol_snapshot
    }
)
