package io.github.mvolkert.entryrecorder.util

import java.io.File
import timber.log.Timber

/**
 * Plants and uproots the opt-in file-capture [Timber.Tree] as the Settings toggle changes, so turning file
 * logging on or off takes effect at runtime without an app restart.
 *
 * The plant/uproot pair lives here rather than in `EntryRecorderApp` because the whole guarantee the settings
 * stream depends on is *how often* the tree changes: exactly one plant per enable and one uproot per disable,
 * however many times the stream re-emits the same value. That is a decision about a [Timber.Tree], not about the
 * Application class, so the seams below let a JVM test drive it with fake trees while the app wires the
 * real [FileTree] and [Timber] through [forFilesDir].
 */
class FileLoggingPlanter(
    private val createTree: () -> Timber.Tree,
    private val plant: (Timber.Tree) -> Unit = Timber::plant,
    private val uproot: (Timber.Tree) -> Unit = Timber::uproot,
) {

    /** One nullable field so a re-emission of an already-applied setting never plants a second writer. */
    private var plantedTree: Timber.Tree? = null

    /**
     * Applies the persisted file-logging choice. A re-emission of a value that is already applied is a no-op, so
     * the settings stream can neither double the writers nor drop a live tree.
     */
    fun setFileLoggingEnabled(enabled: Boolean) {
        val current = plantedTree
        if (enabled && current == null) {
            val tree = createTree()
            plant(tree)
            plantedTree = tree
        } else if (!enabled && current != null) {
            uproot(current)
            plantedTree = null
        }
    }

    companion object {
        /** Subdirectory of the app's private files dir that holds the rolling log files. */
        const val LOG_DIR_NAME = "logs"

        /** Per-file byte cap; once passed the writer rolls to a new generation. */
        const val MAX_LOG_BYTES = 2L * 1024 * 1024

        /** How many rolled files are kept before the oldest is dropped. */
        const val MAX_LOG_GENERATIONS = 5

        /** The production planter: a rolling [FileTree] under [filesDir]/`logs`, planted through Timber. */
        fun forFilesDir(filesDir: File): FileLoggingPlanter = FileLoggingPlanter(
            createTree = {
                FileTree(
                    dir = File(filesDir, LOG_DIR_NAME),
                    maxBytes = MAX_LOG_BYTES,
                    maxGenerations = MAX_LOG_GENERATIONS,
                )
            },
        )
    }
}
