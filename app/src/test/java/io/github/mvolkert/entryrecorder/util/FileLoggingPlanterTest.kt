package io.github.mvolkert.entryrecorder.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import timber.log.Timber

/**
 * The runtime file-logging toggle. What matters is *how often* the tree changes: exactly one plant per enable
 * and one uproot per disable, however many times the settings stream re-emits the same value. A second planted
 * writer would duplicate every line in the exported file; a missed uproot would keep appending to a log the
 * user switched off. Fake trees and fake plant/uproot lambdas keep this off the filesystem and off Timber.
 */
class FileLoggingPlanterTest {

    private val planted = mutableListOf<Timber.Tree>()
    private val uprooted = mutableListOf<Timber.Tree>()

    private fun planter() = FileLoggingPlanter(
        createTree = { FakeTree() },
        plant = { planted += it },
        uproot = { uprooted += it },
    )

    @Test
    fun `enabling file logging plants one tree`() {
        planter().setFileLoggingEnabled(true)

        assertEquals(1, planted.size)
        assertEquals(0, uprooted.size)
    }

    @Test
    fun `a re-emission of the same enabled setting plants no second tree`() {
        val planter = planter()
        planter.setFileLoggingEnabled(true)
        // The settings stream re-emits on every unrelated save, and Room may deliver the same row twice.
        planter.setFileLoggingEnabled(true)
        planter.setFileLoggingEnabled(true)

        assertEquals(1, planted.size)
    }

    @Test
    fun `disabling uproots exactly the planted tree`() {
        val planter = planter()
        planter.setFileLoggingEnabled(true)
        planter.setFileLoggingEnabled(false)

        assertEquals(1, uprooted.size)
        assertSame(planted.single(), uprooted.single())
    }

    @Test
    fun `disabling while already disabled touches nothing`() {
        val planter = planter()
        // The very first emission on a fresh install is the default-off row.
        planter.setFileLoggingEnabled(false)
        planter.setFileLoggingEnabled(false)

        assertEquals(0, planted.size)
        assertEquals(0, uprooted.size)
    }

    @Test
    fun `re-enabling after a disable plants a fresh tree`() {
        val planter = planter()
        planter.setFileLoggingEnabled(true)
        planter.setFileLoggingEnabled(false)
        planter.setFileLoggingEnabled(true)

        assertEquals(2, planted.size)
        assertEquals(1, uprooted.size)
        // The disabled generation is gone for good; the new stream gets its own writer.
        assertNotSame(planted[1], uprooted.single())
    }

    /** A tree the planter can count without touching Timber's forest or the filesystem. */
    private class FakeTree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) = Unit
    }
}
