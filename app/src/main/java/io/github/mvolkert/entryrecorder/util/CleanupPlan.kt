package io.github.mvolkert.entryrecorder.util

import io.github.mvolkert.entryrecorder.domain.model.Recording
import java.io.File

/**
 * One document in the export folder, reduced to what a cleanup decision needs: its name, its size and the
 * provider's own clock. Deliberately carries no `Uri` and no `Context`, so the rule that ends in a deletion
 * can be exercised as an ordinary unit test instead of needing a live ContentProvider.
 */
data class ExportEntry(
    val displayName: String,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
) {
    /** Name-grammar proof that this app wrote the file; see [SafExportName.isAppCreated]. */
    val isAppCreated: Boolean get() = SafExportName.isAppCreated(displayName)
}

/**
 * A recording the internal pass is allowed to remove, together with everything deleting it actually
 * reclaims. A clip's thumbnail lives beside it and goes with it, so counting only the video would make the
 * pass believe it is still short after every deletion and remove recordings the quota did not ask for.
 */
data class InternalCandidate(
    val recording: Recording,
    val reclaimableBytes: Long,
)

/**
 * What one cleanup pass decided: how much it is allowed to touch, how much the quota asks back, and the
 * oldest-first prefix of its own candidates that gets there.
 *
 * The target is measured over the deletable bytes alone, never over everything the folder or the archive
 * holds. A quota the pass *can* satisfy therefore stops as soon as it is satisfied, and a quota it cannot
 * satisfy because protected clips and unprunable downloads dominate asks for nothing at all. Measuring the
 * target over all owned bytes instead would turn such a quota into "delete every deletable file, today and
 * after every new export", which is the failure this whole rule exists to prevent.
 *
 * [deletions] is a prefix of the candidate order, so a pass is already bounded by the quota rather than
 * "delete until the loop happens to stop". A plan is always satisfiable by its own candidates — the target
 * is measured over exactly those bytes — so the only way a pass falls short is the provider or the
 * filesystem saying no, which is the caller's to count and report.
 */
data class CleanupOutcome<T>(
    val deletableBytes: Long,
    val bytesToFree: Long,
    val deletions: List<T>,
    val reclaimedBytes: Long,
)

/**
 * The pure half of the cleanup passes: what may be deleted and in which order. Kept apart from the worker so
 * the destructive logic — name grammar, protected mirrors, age ordering, quota bound — has tests.
 */
object CleanupPlan {

    /**
     * Plan the export-folder pass over [entries]. Only files whose name proves this app wrote them are
     * counted for the number the user sees, only the prunable ones bound the quota, and a protected clip's
     * exported mirror (plus its derived transcode name) never appears in `deletions`.
     */
    fun external(
        entries: List<ExportEntry>,
        protectedNames: Set<String>,
        quotaMb: Long,
    ): CleanupOutcome<ExportEntry> {
        val appFiles = entries.filter { SafExportName.isAppCreated(it.displayName) }
        val candidates = appFiles
            .filter { SafExportName.isPrunable(it.displayName) && it.displayName !in protectedNames }
            .sortedBy { SafExportName.recordedTimestampMillis(it.displayName) ?: it.lastModifiedMillis }
        return select(candidates, ExportEntry::sizeBytes, quotaMb)
    }

    /**
     * Plan the phone's own pass over [candidates], which the caller already ordered oldest-first. Protected
     * clips are not candidates at all, so their space can never be asked out of this pass.
     */
    fun internal(
        candidates: List<InternalCandidate>,
        quotaMb: Long,
    ): CleanupOutcome<InternalCandidate> =
        select(candidates, InternalCandidate::reclaimableBytes, quotaMb)

    /**
     * The one deletion loop both passes share: bound the quota over the bytes they are actually allowed to
     * remove, then take the oldest candidates until that amount is covered.
     */
    private fun <T> select(
        orderedCandidates: List<T>,
        sizeOf: (T) -> Long,
        quotaMb: Long,
    ): CleanupOutcome<T> {
        val deletableBytes = orderedCandidates.sumOf(sizeOf)
        val bytesToFree = CleanupPolicy.bytesToFree(deletableBytes, quotaMb)
        val deletions = mutableListOf<T>()
        var reclaimed = 0L
        for (candidate in orderedCandidates) {
            if (reclaimed >= bytesToFree) break
            deletions += candidate
            reclaimed += sizeOf(candidate)
        }
        return CleanupOutcome(deletableBytes, bytesToFree, deletions, reclaimed)
    }

    /**
     * A file younger than this is never an orphan: the recorder creates the video and its thumbnail before
     * the row that names them is committed, so a listing taken while a clip is still finalizing would
     * otherwise delete a recording that is about to acquire its row. One cleanup interval is the schedule
     * that runs the sweep, so anything older than that had a full cycle to be written down.
     */
    const val ORPHAN_MIN_AGE_MS: Long = 24L * 60L * 60L * 1000L

    /**
     * The files directly inside [dir] that no row references and that have not been touched for one cleanup
     * interval — the bytes a delete leaked when its row went but its file did not. Only the flat file list
     * counts: the archive never writes a subfolder, and a directory nobody can name is not this sweep's to
     * remove.
     *
     * [knownPaths] has to cover every path any row still owns, thumbnails included, or the sweep would
     * "reclaim" the thumbnail of a clip that is still listed. [nowMillis] and [minAgeMillis] are parameters
     * rather than read from the clock, so the rule is testable without waiting a day for a file to age.
     */
    fun orphanFiles(
        dir: File,
        knownPaths: Set<String>,
        nowMillis: Long = System.currentTimeMillis(),
        minAgeMillis: Long = ORPHAN_MIN_AGE_MS,
    ): List<File> {
        if (!dir.isDirectory) return emptyList()
        val cutoff = nowMillis - minAgeMillis
        return dir.listFiles()?.filter { file ->
            file.isFile &&
                file.absolutePath !in knownPaths &&
                // lastModified() == 0 means the filesystem will not say how old it is, which is not
                // evidence of age, so an unreadable timestamp is never old enough to delete.
                file.lastModified().let { it > 0L && it <= cutoff }
        }.orEmpty()
    }

    /**
     * Every name an exported copy of a protected clip can carry. The H.264 transcode has no row of its own
     * in the database — its name only exists as a derivation of the original's — so both forms have to be
     * kept, or protecting a clip would still let the pass delete the playable copy of it.
     */
    fun protectedExportNames(protectedPaths: List<String>): Set<String> = protectedPaths.flatMap { path ->
        val name = path.substringAfterLast('/')
        listOf(name, "${name.substringBeforeLast('.')}_h264.mp4")
    }.toSet()
}
