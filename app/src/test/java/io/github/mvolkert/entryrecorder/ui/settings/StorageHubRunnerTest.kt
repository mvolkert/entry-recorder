package io.github.mvolkert.entryrecorder.ui.settings

import io.github.mvolkert.entryrecorder.testing.FakeExportGateway
import io.github.mvolkert.entryrecorder.testing.assertEquals
import io.github.mvolkert.entryrecorder.testing.assertNone
import io.github.mvolkert.entryrecorder.testing.expectIs
import io.github.mvolkert.entryrecorder.testing.assertTrue
import io.github.mvolkert.entryrecorder.util.AppStorageDirs
import io.github.mvolkert.entryrecorder.util.ExportEntry
import io.github.mvolkert.entryrecorder.util.ExportFolderAccess
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The storage hub's blocking rules on their own: a provider that refuses is data, never a crash; a
 * cancellation still travels; only files this app wrote are counted; and the folders under test are ordinary
 * temp directories, so none of this needs a device.
 */
class StorageHubRunnerTest {
    private val dispatcher = StandardTestDispatcher()
    private val gateway = FakeExportGateway()

    private fun runner(thumbnailsDir: File = File(System.getProperty("java.io.tmpdir"), "missing-thumbs-${System.nanoTime()}")) =
        StorageHubRunner(
            exportGateway = gateway,
            storageDirs = AppStorageDirs(thumbnailsDir, thumbnailsDir, thumbnailsDir),
            ioDispatcher = dispatcher,
        )

    private fun entry(displayName: String, sizeBytes: Long) = ExportEntry(
        displayName = displayName,
        sizeBytes = sizeBytes,
        lastModifiedMillis = 1_700_000_000_000L,
    )

    @Test
    fun `only the files this app wrote are counted toward the export folder usage`() {
        gateway.exportedFiles = listOf(
            entry("REC_1_MOTION_20261018_101500.mkv", 1000L),
            entry("REC_1_MOTION_20261018_101600_h264.mp4", 500L),
            entry("server_rec_9.mkv", 250L),
            // Somebody else's holiday video in the same folder: the quota is not this app's to report.
            entry("holiday.mp4", 4_000_000L),
        )

        assertEquals(1750L, runner().exportedBytes(FOLDER), "the number the settings row shows is the app's own share")
    }

    @Test
    fun `a provider that refuses the listing reads as no measurement instead of crashing`() {
        gateway.listFailure = IllegalStateException("provider died")

        assertNone(runner().exportedBytes(FOLDER), "a folder that cannot be read has no byte count, but does not kill the flow")
    }

    @Test
    fun `a cancelled listing is not swallowed by the failure guard`() {
        gateway.listFailure = CancellationException("scope gone")

        val caught = runCatching { runner().exportedBytes(FOLDER) }.exceptionOrNull()

        expectIs<CancellationException>(caught, "a rethrown cancellation keeps the caller's coroutine dead")
    }

    @Test
    fun `a refused folder name reads as an empty label`() {
        gateway.displayNameFailure = IllegalStateException("no display name column")

        assertEquals("", runner().folderDisplayName(FOLDER), "an unnamed folder still has to render")
    }

    @Test
    fun `the folder probe is passed straight through`() {
        gateway.probeAccessResult = ExportFolderAccess.NO_PERMISSION

        assertEquals(ExportFolderAccess.NO_PERMISSION, runner().probeFolderAccess(FOLDER), "a revoked grant reaches the row as the reason, not as a generic failure")
    }

    @Test
    fun `thumbnail usage sums the cache folder and an absent folder measures empty`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "thumbs-${System.nanoTime()}").apply { mkdirs() }
        dir.deleteOnExit()
        File(dir, "a.jpg").apply { writeText("12345") }.deleteOnExit()
        File(dir, "b.jpg").apply { writeText("678") }.deleteOnExit()

        assertEquals(8L, runner(thumbnailsDir = dir).thumbnailCacheBytes(), "both cache files count")
        assertEquals(0L, runner().thumbnailCacheBytes(), "a cache that was never written is empty, not an error")
    }

    @Test
    fun `releasing a grant is handed to the gateway on the io dispatcher`() = runTest(dispatcher) {
        runner().releaseGrant(FOLDER)

        assertEquals(listOf(FOLDER), gateway.grantReleaseCalls, "the folder the user stopped using is the one revoked")
        assertTrue(gateway.grantTakeCalls.isEmpty(), "releasing never takes a grant on the way")
    }

    private companion object {
        /** The stored folder identity is a String, so no test here needs an `android.net.Uri`. */
        const val FOLDER = "content://entryrecorder.test/export"
    }
}
