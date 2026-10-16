package io.github.mvolkert.entryrecorder.video

import io.github.mvolkert.entryrecorder.domain.model.Device
import kotlinx.coroutines.flow.Flow

/**
 * One H.264 access unit pulled straight off the wire, in Annex-B (start codes intact), with the wall-clock
 * time it arrived. This is the unit of the RTSP passthrough path (change `rtsp-h264-passthrough`): the stream
 * is already H.264, so a clip is written by re-muxing these access units into a container with no decode and
 * no re-encode.
 */
data class H264AccessUnit(
    val annexB: ByteArray,
    val timestampMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as H264AccessUnit

        if (timestampMs != other.timestampMs) return false
        if (!annexB.contentEquals(other.annexB)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = annexB.contentHashCode()
        result = 31 * result + timestampMs.hashCode()
        return result
    }
}

/**
 * The seam the RTSP H.264 passthrough reads elementary-stream access units from. It exists so the
 * demux→re-mux mechanism is decided and unit-tested here, independently of whatever actually pulls bytes off
 * the camera.
 *
 * The production implementation is a decode-free tap on the Media3 RTSP stack. That tap is deliberately NOT
 * provided yet: Media3 1.11.1 exposes no clean public sample/elementary-stream API for `RtspMediaSource`, so a
 * real source would be unverifiable without a physical RTSP camera. It is the deferred on-device task (3.2 of
 * `rtsp-h264-passthrough`). Until it lands, callers pass no source, the passthrough branch stays inert, and the
 * snapshot/MJPEG path remains authoritative — the config flag is effectively off.
 */
fun interface H264SampleSource {
    /** Cold stream of access units for [device]; completes when the stream ends or is cancelled. */
    fun stream(device: Device): Flow<H264AccessUnit>
}

/**
 * Re-muxes a stream of [H264AccessUnit]s into an [MkvStreamMuxer] as an H.264 track, with zero re-encode.
 *
 * This is the entire phone-side cost of the passthrough path: read the first access unit that carries SPS/PPS,
 * build the avcC once, `configureH264`, then hand every later access unit to the muxer flagged for keyframes.
 * All the byte-level decisions (which NAL is a parameter set, what makes an IDR, Annex-B normalization) are the
 * same pure operations [H264StreamHelper] already performs for the re-encode path, so the behavior is identical
 * and unit-testable from synthetic NAL bytes with no camera or Media3 involved.
 *
 * Access units seen before the parameter sets are dropped: an H.264 track cannot be opened without its avcC, and
 * a stream that never emits one simply produces no file rather than a corrupt header.
 */
class H264PassthroughMuxer(
    private val muxer: MkvStreamMuxer,
    private val width: Int,
    private val height: Int,
) {
    private var configured = false

    /** Writes [unit] into the track, opening it from the first unit that carries SPS and PPS. */
    fun writeUnit(unit: H264AccessUnit, baselineMs: Long) {
        val annexB = H264StreamHelper.normalizeToAnnexB(unit.annexB)
        if (!configured) {
            val summary = H264StreamHelper.summarize(annexB)
            val sps = summary.sps ?: return
            val pps = summary.pps ?: return
            muxer.configureH264(H264StreamHelper.buildAvcC(sps, pps), width, height)
            configured = true
        }
        muxer.writeH264Frame(annexB, (unit.timestampMs - baselineMs).coerceAtLeast(0L), H264StreamHelper.isKeyframe(annexB))
    }
}
