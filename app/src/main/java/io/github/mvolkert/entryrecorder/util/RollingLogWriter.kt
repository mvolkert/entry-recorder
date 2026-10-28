package io.github.mvolkert.entryrecorder.util

import java.io.File
import java.io.FileWriter

/**
 * Appends log lines to the newest of a bounded, rolling set of files under [dir]: once the active file would
 * pass [maxBytes] a fresh one is started, and only the newest [maxGenerations] files are kept, so the on-device
 * log can never grow without limit regardless of how long the app runs.
 *
 * Files are named `<prefix><6-digit-index><suffix>` (e.g. `entry-000001.log`) so a lexical sort is also a
 * chronological one. [append] resumes at the highest existing index across instances, so re-enabling file
 * logging after a restart continues the newest generation rather than orphaning it.
 *
 * Deliberately pure JVM (only [java.io.File]) so the rotation arithmetic is unit-testable without an Android
 * context or a wall clock. Not thread-safe: [FileTree] calls it from a single background thread.
 */
class RollingLogWriter(
    private val dir: File,
    private val maxBytes: Long,
    private val maxGenerations: Int,
    private val prefix: String = FILE_PREFIX,
    private val suffix: String = FILE_SUFFIX,
) {
    /** The file lines are currently being appended to; resolved lazily from the existing generations. */
    private var current: File? = null

    /** Writes [line] plus a line separator to the active file, rolling to a new one when the cap is passed. */
    fun append(line: String) {
        val terminator = System.lineSeparator()
        val lineBytes = (line + terminator).toByteArray(Charsets.UTF_8).size.toLong()
        var file = current ?: newestExistingOrFirst()
        // A non-empty file that the next line would push past the cap gets a fresh generation first. A file
        // already at/over the cap but empty never rolls, so an absurdly small cap cannot spawn a file per line.
        if (file.length() > 0L && file.length() + lineBytes > maxBytes) {
            file = newGenerationAfter(file)
        }
        current = file
        dir.mkdirs()
        FileWriter(file, true).use { writer ->
            writer.write(line)
            writer.write(terminator)
        }
        pruneBeyondGenerations()
    }

    private fun logFiles(): List<File> =
        dir.listFiles { f -> f.isFile && f.name.startsWith(prefix) && f.name.endsWith(suffix) }?.toList()
            ?: emptyList()

    /** The highest-indexed existing file, or the first generation (index 1) when the directory is empty. */
    private fun newestExistingOrFirst(): File {
        val newest = logFiles().maxByOrNull { it.name }
        return newest ?: File(dir, fileName(1))
    }

    /** Creates the next generation above [from] and returns it. */
    private fun newGenerationAfter(from: File): File =
        File(dir, fileName(indexOf(from) + 1))

    private fun fileName(index: Int): String = prefix + index.toString().padStart(INDEX_WIDTH, '0') + suffix

    private fun indexOf(file: File): Int =
        file.name.removePrefix(prefix).removeSuffix(suffix).toIntOrNull() ?: 0

    /** Drops the oldest generations once more than [maxGenerations] are on disk. */
    private fun pruneBeyondGenerations() {
        val files = logFiles().sortedByDescending { it.name }
        if (files.size <= maxGenerations) return
        files.drop(maxGenerations).forEach { it.delete() }
    }

    private companion object {
        const val FILE_PREFIX = "entry-"
        const val FILE_SUFFIX = ".log"
        const val INDEX_WIDTH = 6
    }
}
