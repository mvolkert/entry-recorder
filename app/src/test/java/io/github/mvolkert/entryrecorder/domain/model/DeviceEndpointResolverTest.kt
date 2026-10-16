package io.github.mvolkert.entryrecorder.domain.model

import io.github.mvolkert.entryrecorder.data.model.DeviceType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the pure URL-derivation and rate-clamping rules that Phase 2 moved out of the Room entity: the
 * HTTP base, the RTSP URI (credential embedding, accidental-IP path sanitizing, default path), the
 * snapshot URL (leading slash, resolution params), the type-dependent MJPEG derivation, and the two
 * sanity clamps. None of this needs a Context, a socket or Room, so every branch is checked directly.
 */
class DeviceEndpointResolverTest {

    private fun device(
        ipAddress: String = "192.168.1.50",
        username: String = "admin",
        password: String = "2n",
        useHttps: Boolean = false,
        httpPort: Int = 80,
        httpsPort: Int = 443,
        rtspPort: Int = 554,
        rtspPath: String = "/live.sdp",
        mjpegPath: String = "/api/camera/mjpeg",
        snapshotPath: String = "/api/camera/snapshot",
        snapshotFps: Int = 5,
        motionPreRollFrames: Int = DeviceEndpointResolver.DEFAULT_MOTION_PRE_ROLL_FRAMES,
        deviceType: DeviceType = DeviceType.TWO_N_VERSO,
    ) = Device(
        name = "Front Door",
        ipAddress = ipAddress,
        username = username,
        password = password,
        useHttps = useHttps,
        httpPort = httpPort,
        httpsPort = httpsPort,
        rtspPort = rtspPort,
        rtspPath = rtspPath,
        mjpegPath = mjpegPath,
        snapshotPath = snapshotPath,
        snapshotFps = snapshotFps,
        motionPreRollFrames = motionPreRollFrames,
        deviceType = deviceType,
    )

    @Test
    fun `http base url follows the useHttps flag and its matching port`() {
        assertEquals("http://192.168.1.50:80", device().endpoints.httpBaseUrl)
        assertEquals(
            "https://192.168.1.50:8443",
            device(useHttps = true, httpsPort = 8443).endpoints.httpBaseUrl,
        )
    }

    @Test
    fun `rtsp uri embeds credentials only when both user and password are set`() {
        assertEquals(
            "rtsp://admin:2n@192.168.1.50:554/live.sdp",
            device().endpoints.rtspStreamUri,
        )
        // A blank on either side drops the whole auth part rather than emitting a dangling ":".
        assertEquals(
            "rtsp://192.168.1.50:554/live.sdp",
            device(password = "").endpoints.rtspStreamUri,
        )
        assertEquals(
            "rtsp://192.168.1.50:554/live.sdp",
            device(username = "").endpoints.rtspStreamUri,
        )
    }

    @Test
    fun `rtsp path strips a pasted host and collapses the doubled slash`() {
        // Users often paste a full "ip/path" or "/ip/path" into the path field; the resolver removes the
        // redundant host and repairs the empty segment left behind so the URI stays single-slashed.
        assertEquals(
            "rtsp://admin:2n@192.168.1.50:554/stream1",
            device(rtspPath = "192.168.1.50/stream1").endpoints.rtspStreamUri,
        )
        assertEquals(
            "rtsp://admin:2n@192.168.1.50:554/video",
            device(rtspPath = "/192.168.1.50/video").endpoints.rtspStreamUri,
        )
    }

    @Test
    fun `blank or root rtsp path falls back to the 2N default`() {
        assertEquals(
            "rtsp://admin:2n@192.168.1.50:554/live.sdp",
            device(rtspPath = "").endpoints.rtspStreamUri,
        )
        assertEquals(
            "rtsp://admin:2n@192.168.1.50:554/live.sdp",
            device(rtspPath = "/").endpoints.rtspStreamUri,
        )
    }

    @Test
    fun `rtsp path without a leading slash gets one`() {
        assertEquals(
            "rtsp://admin:2n@192.168.1.50:554/media/video1",
            device(rtspPath = "media/video1").endpoints.rtspStreamUri,
        )
    }

    @Test
    fun `snapshot url adds leading slash and default resolution`() {
        assertEquals(
            "http://192.168.1.50:80/api/camera/snapshot?width=1280&height=720",
            device().endpoints.snapshotUrl,
        )
        // A path already carrying width/height is left untouched; a query present means the params join with &.
        assertEquals(
            "http://192.168.1.50:80/snap?width=640",
            device(snapshotPath = "/snap?width=640").endpoints.snapshotUrl,
        )
        assertEquals(
            "http://192.168.1.50:80/shot.jpg?width=1280&height=720",
            device(snapshotPath = "shot.jpg").endpoints.snapshotUrl,
        )
    }

    @Test
    fun `verso mjpeg derives from the snapshot url with a clamped fps param`() {
        // 2N Verso has no dedicated MJPEG endpoint: it is the snapshot URL plus an fps param, whose value
        // is clamped into the firmware's 1..10 push window regardless of the configured snapshot rate.
        assertEquals(
            "http://192.168.1.50:80/api/camera/snapshot?width=1280&height=720&fps=5",
            device(snapshotFps = 5).endpoints.mjpegUrl,
        )
        assertEquals(
            "http://192.168.1.50:80/api/camera/snapshot?width=1280&height=720&fps=10",
            device(snapshotFps = 50).endpoints.mjpegUrl,
        )
        assertEquals(
            "http://192.168.1.50:80/api/camera/snapshot?width=1280&height=720&fps=1",
            device(snapshotFps = 0).endpoints.mjpegUrl,
        )
    }

    @Test
    fun `non-verso mjpeg uses its raw stored path against the http base`() {
        assertEquals(
            "http://192.168.1.50:80/onvif/mjpeg",
            device(
                deviceType = DeviceType.GENERIC_RTSP_ONVIF,
                mjpegPath = "/onvif/mjpeg",
            ).endpoints.mjpegUrl,
        )
        // Leading slash is added for the raw path too.
        assertEquals(
            "http://192.168.1.50:80/mjpeg",
            device(deviceType = DeviceType.GENERIC_RTSP_ONVIF, mjpegPath = "mjpeg").endpoints.mjpegUrl,
        )
    }

    @Test
    fun `snapshot fps is clamped into the sanity window`() {
        assertEquals(1, device(snapshotFps = 0).endpoints.effectiveSnapshotFps)
        assertEquals(5, device(snapshotFps = 5).endpoints.effectiveSnapshotFps)
        assertEquals(
            DeviceEndpointResolver.SNAPSHOT_FPS_HARD_MAX,
            device(snapshotFps = 100).endpoints.effectiveSnapshotFps,
        )
        assertEquals(DeviceEndpointResolver.SNAPSHOT_FPS_HARD_MAX, device().endpoints.maxSnapshotFps)
    }

    @Test
    fun `motion pre-roll frames are clamped into the heap-bounded window`() {
        assertEquals(
            DeviceEndpointResolver.MOTION_PRE_ROLL_FRAMES_MIN,
            device(motionPreRollFrames = 0).endpoints.effectiveMotionPreRollFrames,
        )
        assertEquals(
            DeviceEndpointResolver.MOTION_PRE_ROLL_FRAMES_MAX,
            device(motionPreRollFrames = 999).endpoints.effectiveMotionPreRollFrames,
        )
        assertEquals(
            DeviceEndpointResolver.DEFAULT_MOTION_PRE_ROLL_FRAMES,
            device().endpoints.effectiveMotionPreRollFrames,
        )
    }
}
