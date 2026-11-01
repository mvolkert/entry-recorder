package io.github.mvolkert.entryrecorder.data.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.max

/**
 * Long edge, in pixels, a live-view frame is decoded down to by default. A camera's native still is far larger
 * than the on-screen preview box it feeds, so decoding it whole costs several times the pixels that ever reach
 * the screen; sampling to roughly HD keeps the preview sharp while shrinking each decoded frame several-fold.
 */
internal const val DEFAULT_LIVE_MAX_DECODE_EDGE = 720

/**
 * Decodes a JPEG into a [Bitmap] sampled down so its long edge is no larger than [maxDecodeEdge].
 *
 * The decode is the expensive part of the live-view loop — it runs several times a second per card — and a
 * full-resolution ARGB frame is megabytes. [BitmapFactory.Options.inSampleSize] halves both dimensions per
 * step, preserving aspect ratio, so a power-of-two sample is chosen that keeps the long edge at or above
 * [maxDecodeEdge] without dropping below it. Mirrors the analyzer's [io.github.mvolkert.entryrecorder.video]
 * downscale-on-decode approach. Falls back to a plain decode when [maxDecodeEdge] is not positive or the bounds
 * probe yields no usable size; returns null when the bytes are not a decodable image.
 */
internal fun decodeDownsampledJpeg(bytes: ByteArray, maxDecodeEdge: Int): Bitmap? {
    if (maxDecodeEdge <= 0) return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    var sampleSize = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= maxDecodeEdge) {
        sampleSize *= 2
    }
    return BitmapFactory.decodeByteArray(
        bytes, 0, bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sampleSize },
    )
}
