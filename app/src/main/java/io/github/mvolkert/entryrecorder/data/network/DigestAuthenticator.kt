package io.github.mvolkert.entryrecorder.data.network

import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.security.MessageDigest

/**
 * Handles HTTP Digest and Basic Authentication challenges (RFC 7616). Shared by the 2N event/status
 * client and [HttpSnapshotClient], so a snapshot endpoint that requires Digest is answered the same way
 * as the event stream instead of failing with a bare 401.
 */
class DigestAuthenticator(
    private val username: String,
    private val password: String
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        val authHeaders = response.headers("WWW-Authenticate")
        for (header in authHeaders) {
            val candidate = when {
                header.startsWith("Digest", ignoreCase = true) -> buildDigestHeader(header, response.request)
                header.startsWith("Basic", ignoreCase = true) -> Credentials.basic(username, password)
                else -> null
            }
            if (candidate != null) {
                // Loop guard: only stop retrying if we already sent this exact credential. A stale
                // nonce yields a different Digest value, so a single legitimate retry is still allowed.
                val priorAuth = response.priorResponse?.request?.header("Authorization")
                if (priorAuth == candidate) return null
                return response.request.newBuilder()
                    .header("Authorization", candidate)
                    .build()
            }
        }
        return null
    }

    private fun buildDigestHeader(header: String, request: Request): String? {
        val params = parseDigestParams(header)
        val realm = params["realm"] ?: return null
        val nonce = params["nonce"] ?: return null
        val qop = params["qop"]
        val algorithm = params["algorithm"] ?: "MD5"
        val opaque = params["opaque"]

        val uri = request.url.encodedPath + (if (request.url.encodedQuery != null) "?${request.url.encodedQuery}" else "")
        val method = request.method

        val isSession = algorithm.endsWith("-sess", ignoreCase = true)
        val nc = "00000001"
        val cnonce = java.util.UUID.randomUUID().toString().replace("-", "").take(16)

        var ha1 = hashHex(algorithm, "$username:$realm:$password")
        if (isSession) ha1 = hashHex(algorithm, "$ha1:$nonce:$cnonce")
        val ha2 = hashHex(algorithm, "$method:$uri")

        val responseVal = if (qop != null && qop.contains("auth")) {
            hashHex(algorithm, "$ha1:$nonce:$nc:$cnonce:auth:$ha2")
        } else {
            hashHex(algorithm, "$ha1:$nonce:$ha2")
        }

        val sb = StringBuilder()
        sb.append("Digest ")
        sb.append("username=\"").append(username).append("\", ")
        sb.append("realm=\"").append(realm).append("\", ")
        sb.append("nonce=\"").append(nonce).append("\", ")
        sb.append("uri=\"").append(uri).append("\", ")
        sb.append("response=\"").append(responseVal).append("\", ")
        if (qop != null && qop.contains("auth")) {
            sb.append("qop=auth, ")
            sb.append("nc=").append(nc).append(", ")
            sb.append("cnonce=\"").append(cnonce).append("\", ")
        }
        if (opaque != null) {
            sb.append("opaque=\"").append(opaque).append("\", ")
        }
        sb.append("algorithm=").append(algorithm)

        return sb.toString()
    }

    /**
     * Parses Digest challenge parameters without naively splitting on commas: values may be quoted
     * and can themselves contain commas (e.g. certain realm/nonce strings). Handles backslash escapes
     * inside quoted values.
     */
    private fun parseDigestParams(header: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        val content = if (header.length >= 6 && header.substring(0, 6).equals("Digest", ignoreCase = true)) {
            header.substring(6)
        } else {
            header.substringAfter(' ')
        }
        var i = 0
        val n = content.length
        while (i < n) {
            while (i < n && (content[i] == ' ' || content[i] == ',' || content[i] == '\t')) i++
            val keyStart = i
            while (i < n && content[i] != '=') i++
            if (i >= n) break
            val key = content.substring(keyStart, i).trim()
            i++ // skip '='
            val value = if (i < n && content[i] == '"') {
                i++ // skip opening quote
                val sb = StringBuilder()
                while (i < n && content[i] != '"') {
                    if (content[i] == '\\' && i + 1 < n && (content[i + 1] == '"' || content[i + 1] == '\\')) {
                        sb.append(content[i + 1]); i += 2
                    } else {
                        sb.append(content[i]); i++
                    }
                }
                if (i < n) i++ // skip closing quote
                sb.toString()
            } else {
                val vStart = i
                while (i < n && content[i] != ',') i++
                content.substring(vStart, i).trim()
            }
            if (key.isNotEmpty()) params[key] = value
        }
        return params
    }

    /**
     * Computes the digest hash for the requested algorithm (RFC 7616). Supports MD5 and SHA-256
     * (including the -sess variants); falls back to MD5 for unknown algorithms so behaviour never
     * regresses on devices that only advertise MD5.
     */
    private fun hashHex(algorithm: String, input: String): String {
        val base = algorithm.removeSuffix("-sess").removeSuffix("-SESS").uppercase()
        val instance = when (base) {
            "SHA-256", "SHA256" -> "SHA-256"
            "MD5", "MD5-SESS" -> "MD5"
            else -> "MD5"
        }
        val md = try {
            MessageDigest.getInstance(instance)
        } catch (_: Exception) {
            MessageDigest.getInstance("MD5")
        }
        return md.digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
