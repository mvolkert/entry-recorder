package io.github.mvolkert.entryrecorder.data.repository

/**
 * What one recording delete actually reclaimed.
 *
 * The row always goes: the user asked for the clip to be deleted, and keeping the row because a file
 * refused to die would leave the archive listing a clip that is already half gone. The files are the part
 * that can fail, and a file that outlives its row is space no quota counts anymore — so a caller has to be
 * able to say that out loud rather than report a clean delete.
 */
sealed interface RecordingDeleteResult {

    /** The row and every file it owned are gone. */
    data object Deleted : RecordingDeleteResult

    /**
     * The row is gone, but [orphanPaths] are still on disk and no longer referenced by anything. They are
     * what the cleanup worker's orphan sweep picks up on its next run.
     */
    data class DeletedWithOrphans(val orphanPaths: List<String>) : RecordingDeleteResult
}

/**
 * The aggregate of a bulk delete, so one bulk action reports one honest message instead of one per row.
 * [deleted] counts the rows that went, which is every row the caller asked about; [orphanPaths] is what
 * still occupies the partition afterwards.
 */
data class RecordingDeleteReport(
    val deleted: Int,
    val orphanPaths: List<String>,
) {
    val leftOrphans: Boolean get() = orphanPaths.isNotEmpty()
}
