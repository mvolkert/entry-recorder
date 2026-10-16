package io.github.mvolkert.entryrecorder.data.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The single server media-URL rule Phase 3 pulled out of the ViewModel: base + server-relative path join with
 * exactly one slash regardless of trailing slash on the base, the API key rides as a query param only when
 * present, and a null path stays null (a row with no media must not become a bogus URL).
 */
class ServerMediaUrlsTest {

    @Test
    fun `null relative path stays null`() {
        assertNull(ServerMediaUrls.absolute("https://srv.example", null, "secret"))
    }

    @Test
    fun `joins base and path with a single slash`() {
        assertEquals(
            "https://srv.example/media/clip.mkv?api_key=secret",
            ServerMediaUrls.absolute("https://srv.example", "/media/clip.mkv", "secret"),
        )
        // A trailing slash on the base must not double up with the path's leading slash.
        assertEquals(
            "https://srv.example/media/clip.mkv?api_key=secret",
            ServerMediaUrls.absolute("https://srv.example/", "/media/clip.mkv", "secret"),
        )
    }

    @Test
    fun `blank api key appends no query param`() {
        assertEquals(
            "https://srv.example/media/clip.mkv",
            ServerMediaUrls.absolute("https://srv.example", "/media/clip.mkv", ""),
        )
        assertEquals(
            "https://srv.example/media/clip.mkv",
            ServerMediaUrls.absolute("https://srv.example", "/media/clip.mkv", "   "),
        )
    }
}
