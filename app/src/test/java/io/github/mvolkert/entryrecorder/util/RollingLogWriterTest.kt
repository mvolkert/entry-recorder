package io.github.mvolkert.entryrecorder.util

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Test

/**
 * The rotation arithmetic of [RollingLogWriter] — the testable half of the file-capture change. Pins that a
 * line past the byte cap starts a new generation, that only the newest [RollingLogWriter] generations are
 * kept, that lines stay in order, and that a fresh writer resumes the newest generation instead of orphaning
 * it. Deliberately asserts invariants (bounded count, ordered suffix, newest line present) rather than exact
 * byte boundaries, so it holds whichever line separator the host JVM uses.
 */
class RollingLogWriterTest {

    @JvmField
    @Rule
    val temp: TemporaryFolder = TemporaryFolder()

    private val dir: File get() = temp.root

    private fun line(n: Int): String = "line-%05d".format(n)

    private fun files(): List<File> =
        dir.listFiles { f -> f.name.startsWith("entry-") && f.name.endsWith(".log") }?.sortedBy { it.name }
            ?: emptyList()

    private fun allLines(): List<String> = files().flatMap { it.readLines().filter(String::isNotEmpty) }

    @Test
    fun `a line that would pass the cap rolls to a new file instead of growing the old one`() {
        val writer = RollingLogWriter(dir, maxBytes = 60L, maxGenerations = 5)
        // Each line is ~11 bytes; 60 bytes is a little over five lines, so the sixth forces a roll.
        (1..30).forEach { writer.append(line(it)) }

        val files = files()
        assertTrue("expected a roll into more than one generation", files.size > 1)
        assertTrue(
            "no single file should hold every line",
            files.all { it.readLines().filter(String::isNotEmpty).size < 30 },
        )
    }

    @Test
    fun `only the newest generations are kept`() {
        val generations = 3
        val writer = RollingLogWriter(dir, maxBytes = 30L, maxGenerations = generations)
        (1..200).forEach { writer.append(line(it)) }

        assertTrue("never more than $generations files", files().size <= generations)
    }

    @Test
    fun `lines stay in order and the most recent line always survives`() {
        val writer = RollingLogWriter(dir, maxBytes = 40L, maxGenerations = 2)
        val total = 50
        (1..total).forEach { writer.append(line(it)) }

        val kept = allLines()
        assertTrue("the newest line is always retained", kept.contains(line(total)))
        // Surviving lines are a contiguous suffix of the written sequence, in the original order.
        val indices = kept.map { it.substringAfter("line-").toInt() }
        assertEquals("kept lines are in strictly increasing order", indices.sorted(), indices)
        assertEquals("kept lines are contiguous", indices.last() - indices.first() + 1, indices.size)
    }

    @Test
    fun `a fresh writer resumes the newest generation rather than orphaning it`() {
        RollingLogWriter(dir, maxBytes = 5_000L, maxGenerations = 5).apply {
            append(line(1))
            append(line(2))
        }
        val firstRoundHighest = files().maxOf { it.name }

        RollingLogWriter(dir, maxBytes = 5_000L, maxGenerations = 5).append(line(3))

        // No new file was spawned for the resume: the second writer continued the existing top generation.
        assertEquals(firstRoundHighest, files().maxOf { it.name })
        assertTrue(allLines().contains(line(3)))
    }

    @Test
    fun `file names sort lexically in chronological order`() {
        val writer = RollingLogWriter(dir, maxBytes = 20L, maxGenerations = 5)
        (1..20).forEach { writer.append(line(it)) }

        val names = files().map { it.name }
        assertEquals("names are a lexical sort", names.sorted(), names)
        assertTrue(
            "every name matches entry-<6-digit>.log",
            names.all { it.matches(Regex("entry-\\d{6}\\.log")) },
        )
    }
}
