package io.github.mvolkert.entryrecorder.video

/**
 * A captured JPEG plus its wall-clock capture time, used to prepend a motion pre-roll to a recording.
 *
 * The [jpeg] bytes are treated as immutable once built: the analyzer hands a pre-roll frame off and never
 * mutates or reuses the buffer, and the capture loop only reads it. A `data class` documents that value
 * identity (equal timestamp + equal bytes is the same frame) and lets a fold/cancel path copy it safely.
 */
data class PreRollFrame(val timestampMs: Long, val jpeg: ByteArray) {
    // ByteArray gives reference-based equals/hashCode from the data class; override to compare content so
    // two frames with identical pixels compare equal, matching the value semantics the class advertises.
    override fun equals(other: Any?): Boolean =
        this === other || (other is PreRollFrame && timestampMs == other.timestampMs && jpeg.contentEquals(other.jpeg))

    override fun hashCode(): Int = 31 * timestampMs.hashCode() + jpeg.contentHashCode()
}
