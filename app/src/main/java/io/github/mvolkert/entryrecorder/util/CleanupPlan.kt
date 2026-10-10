package io.github.mvolkert.entryrecorder.util

/**
 * One document in the export folder, reduced to what a cleanup decision needs: its name, its size and the
 * provider's own clock. Deliberately carries no `Uri` and no `Context`, so the rule that ends in a deletion
 * can be exercised as an ordinary unit test instead of needing a live ContentProvider.
 */
data class ExportEntry(
    val displayName: String,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
)

/**
 * The external pass's whole decision: what the folder holds because of this app, how much of that has to go
 * to satisfy the quota, and which files it will ask the provider to remove, oldest recording first.
 *
 * [deletions] is a prefix of the candidate order, so the plan is already bounded by the quota rather than
 * "delete until the loop happens to stop", and [quotaReachable] says out loud when even deleting all of it
 * cannot get under the limit — which is the case the log has to report instead of trying again tomorrow.
 */
data class ExternalCleanupPlan(
    val appBytes: Long,
    val bytesToFree: Long,
    val deletions: List<ExportEntry>,
    val reclaimedBytes: Long,
) {
    val quotaReachable: Boolean get() = reclaimedBytes >= bytesToFree
}

/**
 * The pure half of the cleanup passes: what may be deleted and in which order. Kept apart from the worker so
 * the destructive logic — name grammar, protected mirrors, age ordering, quota bound — has tests.
 */
object CleanupPlan {

    /**
     * Plan the export-folder pass over [entries]. Only files whose name proves this app wrote them are
     * counted, only those the app may prune [SafExportName.isPrunable] are candidates, and a protected
     * clip's exported mirror (plus its derived transcode name) never appears in [deletions].
     */
    fun external(
        entries: List<ExportEntry>,
        protectedNames: Set<String>,
        quotaMb: Long,
    ): ExternalCleanupPlan {
        val appFiles = entries.filter { SafExportName.isAppCreated(it.displayName) }
        val appBytes = appFiles.sumOf { it.sizeBytes }
        val bytesToFree = CleanupPolicy.bytesToFree(appBytes, quotaMb)

        val candidates = appFiles
            .filter { SafExportName.isPrunable(it.displayName) && it.displayName !in protectedNames }
            .sortedBy { SafExportName.recordedTimestampMillis(it.displayName) ?: it.lastModifiedMillis }

        val deletions = mutableListOf<ExportEntry>()
        var reclaimed = 0L
        for (candidate in candidates) {
            if (reclaimed >= bytesToFree) break
            deletions += candidate
            reclaimed += candidate.sizeBytes
        }
        return ExternalCleanupPlan(appBytes, bytesToFree, deletions, reclaimed)
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
