package io.github.mvolkert.entryrecorder.domain.model

import io.github.mvolkert.entryrecorder.data.model.DeviceType
import io.github.mvolkert.entryrecorder.data.model.EventType
import io.github.mvolkert.entryrecorder.data.model.MotionSensitivity
import io.github.mvolkert.entryrecorder.data.model.SipMode
import io.github.mvolkert.entryrecorder.data.model.StreamProtocol

/**
 * A configured intercom camera — the domain form of the stored device row, free of Room. Endpoint URLs
 * and clamped rates are derived once in [endpoints]; everything else is the configuration as saved.
 *
 * Carries the plain HTTP/SIP credentials because authentication and the RTSP URL still need them at
 * call time; the `secure-credential-storage` change replaces both with an opaque reference.
 */
data class Device(
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
    val streamProtocol: StreamProtocol = StreamProtocol.AUTO,
    val mjpegPath: String = "/api/camera/mjpeg",
    val snapshotPath: String = "/api/camera/snapshot",
    val snapshotFps: Int = 5,
    val sipMode: SipMode = SipMode.PEER_TO_PEER,
    val sipLocalPort: Int = 5060,
    val sipServerHost: String? = null,
    val sipServerPort: Int? = 5060,
    val sipUser: String? = null,
    val sipPassword: String? = null,
    val recordOnMotion: Boolean = true,
    val recordOnRing: Boolean = true,
    val recordOnNoise: Boolean = true,
    val recordOnMotionOnDevice: Boolean = false,
    val motionSensitivity: MotionSensitivity = MotionSensitivity.BALANCED,
    val motionPreRollFrames: Int = DeviceEndpointResolver.DEFAULT_MOTION_PRE_ROLL_FRAMES,
    val motionPostRecordSeconds: Int = 20,
    val noisePostRecordSeconds: Int = 20,
    val ringRecordSeconds: Int = 60,
    // Hard per-trigger recording ceilings; 0 means "no limit", which is also what an upgraded device reads.
    val motionMaxFrames: Int = DeviceEndpointResolver.DEFAULT_MAX_RECORD_LIMIT,
    val noiseMaxFrames: Int = DeviceEndpointResolver.DEFAULT_MAX_RECORD_LIMIT,
    val ringMaxMinutes: Int = DeviceEndpointResolver.DEFAULT_MAX_RECORD_LIMIT,
    val wakeOnRing: Boolean = true,
    val soundOnRing: Boolean = true,
    val vibrateOnRing: Boolean = true,
    val wakeOnMotion: Boolean = true,
    val wakeOnNoise: Boolean = true,
    val keepLocksHeld: Boolean = false,
    val isEnabled: Boolean = true,
    val serverDeviceId: Long? = null,
) {
    /**
     * All URL derivation and sanity clamping for this device. A getter rather than a stored property so
     * it is not part of the data-class identity and, more importantly, is not serialized into a backup
     * (the derived RTSP/MJPEG URLs embed the credential; the plaintext fields above are the source).
     */
    val endpoints: DeviceEndpoints get() = DeviceEndpointResolver.resolve(this)

    val httpBaseUrl: String get() = endpoints.httpBaseUrl
    val rtspStreamUrl: String get() = endpoints.rtspStreamUri
    val snapshotUrl: String get() = endpoints.snapshotUrl
    val mjpegUrl: String get() = endpoints.mjpegUrl
    val effectiveSnapshotFps: Int get() = endpoints.effectiveSnapshotFps
    val maxSnapshotFps: Int get() = endpoints.maxSnapshotFps
    val effectiveMotionPreRollFrames: Int get() = endpoints.effectiveMotionPreRollFrames
    val effectiveMotionMaxFrames: Int get() = endpoints.effectiveMotionMaxFrames
    val effectiveNoiseMaxFrames: Int get() = endpoints.effectiveNoiseMaxFrames
    val effectiveRingMaxMinutes: Int get() = endpoints.effectiveRingMaxMinutes

    /**
     * The frame count [eventType] is allowed to write into one clip, clamped; 0 means the trigger has no
     * frame ceiling. Motion and noise are capped in frames because that is what the capture loop counts,
     * while ring is a time-bound trigger and is capped in minutes instead.
     */
    fun maxFramesFor(eventType: EventType): Int = when (eventType) {
        EventType.MOTION -> effectiveMotionMaxFrames
        EventType.NOISE -> effectiveNoiseMaxFrames
        // Ring is a time-bound trigger capped in minutes; manual and unrecognised events have no frame cap.
        EventType.RING, EventType.MANUAL, EventType.UNKNOWN -> 0
    }

    /**
     * Diagnostic form, mirroring the entity's: replaces the data-class `toString()`, which would print
     * the HTTP and the SIP password in clear wherever a device is interpolated into a log.
     */
    override fun toString(): String = buildString {
        append("Device(id=$id, name=$name, deviceType=$deviceType, isEnabled=$isEnabled")
        append(", address=$ipAddress, httpPort=$httpPort, httpsPort=$httpsPort, useHttps=$useHttps")
        append(", rtspPort=$rtspPort, rtspPath=$rtspPath, username=$username, password=$REDACTED")
        append(", streamProtocol=$streamProtocol, mjpegPath=$mjpegPath, snapshotPath=$snapshotPath")
        append(", snapshotFps=$snapshotFps")
        append(", sipMode=$sipMode, sipLocalPort=$sipLocalPort, sipServerHost=$sipServerHost")
        append(", sipServerPort=$sipServerPort, sipUser=$sipUser, sipPassword=${sipPassword?.let { REDACTED }}")
        append(", recordOnMotion=$recordOnMotion, recordOnRing=$recordOnRing, recordOnNoise=$recordOnNoise")
        append(", recordOnMotionOnDevice=$recordOnMotionOnDevice, motionSensitivity=$motionSensitivity")
        append(", motionPreRollFrames=$motionPreRollFrames")
        append(", motionPostRecordSeconds=$motionPostRecordSeconds, noisePostRecordSeconds=$noisePostRecordSeconds")
        append(", ringRecordSeconds=$ringRecordSeconds")
        append(", motionMaxFrames=$motionMaxFrames, noiseMaxFrames=$noiseMaxFrames")
        append(", ringMaxMinutes=$ringMaxMinutes")
        append(", wakeOnRing=$wakeOnRing, soundOnRing=$soundOnRing, vibrateOnRing=$vibrateOnRing")
        append(", wakeOnMotion=$wakeOnMotion, wakeOnNoise=$wakeOnNoise, keepLocksHeld=$keepLocksHeld")
        append(", serverDeviceId=$serverDeviceId)")
    }

    companion object {
        /** Placeholder both credential fields collapse to in [toString]. */
        private const val REDACTED = "<redacted>"
    }
}
