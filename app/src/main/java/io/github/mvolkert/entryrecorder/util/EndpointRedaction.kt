package io.github.mvolkert.entryrecorder.util

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The form of a URL that is safe to write to a log: `scheme://host`, with the path, query and any credentials
 * dropped.
 *
 * A UnifiedPush motion-alert address is a bearer capability — whoever holds it can push notifications to that
 * phone — and it carries its token in the path or query, so the whole URL must never reach logcat or the
 * settings-screen log file the way a server URL may. Anything OkHttp cannot parse is reported as a fixed marker
 * instead of echoing the input, because an unparsable value is the case where a secret is most likely stuck in it.
 */
internal fun redactBearerUrl(url: String): String {
    val http = url.toHttpUrlOrNull() ?: return "<unparsable-address>"
    // The same scheme-default folding `PythonServerLink.keyFor` does, so a redacted address reads like the
    // server keys it already logs.
    val defaultPort = if (http.scheme == "https") HTTPS_DEFAULT_PORT else HTTP_DEFAULT_PORT
    val port = if (http.port == defaultPort) "" else ":${http.port}"
    return "${http.scheme}://${http.host}$port"
}

private const val HTTP_DEFAULT_PORT = 80
private const val HTTPS_DEFAULT_PORT = 443
