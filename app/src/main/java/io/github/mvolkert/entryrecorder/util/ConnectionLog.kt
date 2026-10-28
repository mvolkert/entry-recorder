package io.github.mvolkert.entryrecorder.util

/** The transport a connection-loss/recovery diagnostic is about. */
enum class ConnectionSource(val label: String) {
    Snapshot("snapshot"),
    CameraEvents("camera-events"),
    SipRegistration("sip-registration"),
    PythonServer("python-server"),
}

/**
 * The one wording every connection-loss/recovery line is built from, so grepping the log stream for
 * `"<source> lost"` / `"<source> recovered"` reconstructs any single link's outage timeline. Pure Kotlin with
 * no Android dependency so the format is unit-testable and reusable from any transport class.
 *
 * Lines are deliberately transition-only: a caller emits [lost] the moment a link crosses into a down state
 * and [recovered] the moment it comes back, never once per failed attempt.
 */
object ConnectionLog {

    /** A link went down: name the source, the device/server, and why. */
    fun lost(source: ConnectionSource, name: String, reason: String): String =
        "${source.label} lost: $name ($reason)"

    /**
     * A link that had been down came back. [downMs] is how long it was out; when there is no recorded loss
     * timestamp the "down" clause is omitted rather than guessed, so the line never reports a fake duration.
     */
    fun recovered(source: ConnectionSource, name: String, downMs: Long? = null): String {
        val down = downMs?.let { " (down ${formatDuration(it)})" } ?: ""
        return "${source.label} recovered: $name$down"
    }

    /** A down span in milliseconds, as `45s` under a minute and `2m 5s` beyond it. */
    fun formatDuration(downMs: Long): String {
        if (downMs < 0L) return "0s"
        val totalSeconds = downMs / 1000L
        if (totalSeconds < 60L) return "${totalSeconds}s"
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return "${minutes}m ${seconds}s"
    }
}

/** What feeding one health observation to [ConnectionEdgeTracker] produced. */
sealed interface ConnectionEdge {
    /** No loss/recovery transition worth a line (still healthy, still down, or a first-ever healthy read). */
    data object None : ConnectionEdge

    /** The link just crossed from healthy into a down state. */
    data class Lost(val reason: String) : ConnectionEdge

    /** The link just came back, having been down for [downMs]. */
    data class Recovered(val downMs: Long) : ConnectionEdge
}

/**
 * Turns a stream of per-link health reads into loss/recovery transitions only, remembering how long each link
 * has been down so a recovery can report the outage duration.
 *
 * A link is keyed by any caller-chosen string (typically `"<capability>|<deviceId>"`) and several producers may
 * report the same link; a first down read announces the loss once, later down reads stay silent, and the first
 * healthy read after a down announces the recovery. A healthy read that never followed a down (a startup
 * "online" notice) is not a recovery and reports [ConnectionEdge.None]. Pure Kotlin with the clock passed in,
 * so it is unit-testable without a wall clock or a device. Not thread-safe: the caller serialises access.
 */
class ConnectionEdgeTracker {
    private val lostAtMs = HashMap<String, Long>()

    /** Records an observation for [key] and returns the transition, if any, at [nowMs]. */
    fun observe(key: String, online: Boolean, reason: String, nowMs: Long): ConnectionEdge {
        val downSinceMs = lostAtMs[key]
        return when {
            !online && downSinceMs == null -> {
                lostAtMs[key] = nowMs
                ConnectionEdge.Lost(reason)
            }
            online && downSinceMs != null -> {
                lostAtMs.remove(key)
                ConnectionEdge.Recovered(nowMs - downSinceMs)
            }
            else -> ConnectionEdge.None
        }
    }

    /** Drops a link's bookkeeping so a removed/re-added id never inherits the old outage clock. */
    fun forget(key: String) {
        lostAtMs.remove(key)
    }
}
