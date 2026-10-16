package io.github.mvolkert.entryrecorder.data.server

/**
 * How a server-relative media path becomes a URL a loader can fetch. It lives in the server layer rather
 * than a ViewModel so both the gallery's URL resolution and any future export/server code share one rule:
 * the base URL, the server-relative path, and the API key combine the same way everywhere.
 *
 * The key rides as a query param because image and video loads go through Coil / ExoPlayer, which cannot
 * set an `X-API-Key` header on a request they own.
 */
object ServerMediaUrls {

    /** Prefixes [relative] with [baseUrl]; appends `?api_key=[apiKey]` unless the key is blank. Null stays null. */
    fun absolute(baseUrl: String, relative: String?, apiKey: String): String? {
        if (relative == null) return null
        val full = baseUrl.trimEnd('/') + relative
        return if (apiKey.isBlank()) full else "$full?api_key=$apiKey"
    }
}
