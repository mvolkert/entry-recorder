package io.github.mvolkert.entryrecorder.domain.model

/**
 * The wall clock as the domain sees it: one method, so any construction whose timestamp is observable
 * behaviour can be driven from a test without waiting on (or trusting) [System.currentTimeMillis].
 */
fun interface TimeProvider {
    fun currentTimeMillis(): Long
}

/** The production [TimeProvider]: the wall clock itself. */
object SystemTimeProvider : TimeProvider {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
}
