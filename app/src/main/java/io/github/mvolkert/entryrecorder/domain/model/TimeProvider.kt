package io.github.mvolkert.entryrecorder.domain.model

/**
 * The clock as the domain sees it, so any construction whose timing is observable behaviour can be driven from a
 * test without waiting on (or trusting) [System.currentTimeMillis].
 *
 * Two readings because they answer different questions. [currentTimeMillis] is the wall clock: the value a person
 * recognises, so it is what a timestamp that leaves the process has to be, and the user can move it at any instant
 * by setting the device clock or crossing a time zone. [elapsedMillis] never goes backwards, so a duration measured
 * between two of its readings survives such a jump. Anything that compares two readings taken while its subject runs
 * uses [elapsedMillis]; only a stamp somebody has to recognise uses the wall clock.
 */
interface TimeProvider {
    fun currentTimeMillis(): Long

    /** Milliseconds since an arbitrary point in the past, never decreasing while the process runs. */
    fun elapsedMillis(): Long
}

/** The production [TimeProvider]: the wall clock itself, plus a monotonic counter for durations. */
object SystemTimeProvider : TimeProvider {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()

    // A nanoTime difference rather than android.os.SystemClock.elapsedRealtime: this layer holds no framework
    // types, and only differences are meaningful here, which nanoTime guarantees for a running process.
    override fun elapsedMillis(): Long = System.nanoTime() / 1_000_000
}
