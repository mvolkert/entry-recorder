package io.github.mvolkert.entryrecorder.data.server

import timber.log.Timber
import io.github.mvolkert.entryrecorder.util.ConnectionEdge
import io.github.mvolkert.entryrecorder.util.ConnectionEdgeTracker
import io.github.mvolkert.entryrecorder.util.ConnectionLog
import io.github.mvolkert.entryrecorder.util.ConnectionSource
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The one place that decides whether the Python server link is up, so every consumer of that server — the
 * background API calls in [ServerRecordingClient] and the live-view relay the app streams for itself — reports
 * the same outage instead of each announcing its own. Loss and recovery are announced once per link on the
 * shared `python-server` wording.
 *
 * Only the transport counts: a server that answers, even with a 4xx/5xx or a body of the wrong shape, is
 * reachable, so callers report [reportReachable] for an answer and [reportFailure] for a connect/timeout/
 * unknown-host [IOException] or a relay the server closed. Keyed on the server origin, which never carries the
 * API key, so the line is safe to log and to grep.
 *
 * Not thread-safe on its own; the [lock] serialises every read because the singleton serves concurrent
 * coroutine callers and a live stream collector.
 */
@Singleton
class PythonServerLink @Inject constructor() {

    private val tag = "PythonServerLink"
    private val edges = ConnectionEdgeTracker()
    private val lock = Any()

    /**
     * Records that the server answered [serverUrl], closing an open outage with a recovery line if there was
     * one. The announced [ConnectionEdge] is returned so a test (or a caller that cares) can see whether this
     * read was the one that crossed the edge; every transition is otherwise announced here alone.
     */
    fun reportReachable(serverUrl: String): ConnectionEdge = observe(serverUrl, reachable = true, reason = "")

    /** Records a request failure as a lost link only when it is a transport failure: an error answer is alive. */
    fun reportFailure(serverUrl: String, e: Exception): ConnectionEdge =
        if (e is IOException) {
            observe(serverUrl, reachable = false, reason = e.message ?: e.javaClass.simpleName)
        } else {
            ConnectionEdge.None
        }

    /** Records a non-exception loss of the same kind, e.g. a live relay the server closed. */
    fun reportFailure(serverUrl: String, reason: String): ConnectionEdge =
        observe(serverUrl, reachable = false, reason = reason)

    private fun observe(serverUrl: String, reachable: Boolean, reason: String): ConnectionEdge {
        val key = keyFor(serverUrl)
        val edge = synchronized(lock) {
            edges.observe(key, reachable, reason, System.currentTimeMillis())
        }
        when (edge) {
            is ConnectionEdge.Lost ->
                Timber.tag(tag).w(ConnectionLog.lost(ConnectionSource.PythonServer, key, edge.reason))

            is ConnectionEdge.Recovered ->
                Timber.tag(tag).i(ConnectionLog.recovered(ConnectionSource.PythonServer, key, edge.downMs))

            ConnectionEdge.None -> Unit
        }
        return edge
    }

    companion object {
        /**
         * The key a link's outage clock is kept under: the server origin (`scheme://host[:port]`) rather than the
         * full URL, so an API call and a relay stream against the same server share one timeline. A value the
         * app cannot parse as an absolute URL is used as-is minus its trailing slash, which is what the
         * per-call sites compared before this class existed.
         */
        fun keyFor(url: String): String {
            val trimmed = url.trimEnd('/')
            val http = trimmed.toHttpUrlOrNull() ?: return trimmed
            // `toHttpUrlOrNull` only accepts http/https, so those are the only two scheme defaults to fold away.
            val schemeDefaultPort = if (http.scheme == "https") HTTPS_DEFAULT_PORT else HTTP_DEFAULT_PORT
            val port = if (http.port == schemeDefaultPort) "" else ":${http.port}"
            return "${http.scheme}://${http.host}$port"
        }

        private const val HTTP_DEFAULT_PORT = 80
        private const val HTTPS_DEFAULT_PORT = 443
    }
}
