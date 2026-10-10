package io.github.mvolkert.entryrecorder.ui.recordings

/**
 * The ids covered by a "mark first, mark last" range, in on-screen row order.
 *
 * [visibleLocalIds] is the order the gallery actually shows, so a row hidden by a filter is never swept
 * into the range even when it sits between the two anchors chronologically. The result is inclusive of
 * both ends and independent of which end was marked first. A missing anchor (nothing tapped yet, or the
 * anchor row was deleted or filtered away) degrades to just the target row.
 */
internal fun rangeSelection(
    visibleLocalIds: List<Long>,
    anchorId: Long?,
    targetId: Long
): Set<Long> {
    val targetIndex = visibleLocalIds.indexOf(targetId)
    if (targetIndex < 0) return setOf(targetId)
    val anchorIndex = visibleLocalIds.indexOf(anchorId ?: return setOf(targetId))
    if (anchorIndex < 0) return setOf(targetId)
    val from = minOf(anchorIndex, targetIndex)
    val to = maxOf(anchorIndex, targetIndex)
    return visibleLocalIds.subList(from, to + 1).toSet()
}

/**
 * How many of the checked ids [all] are not in the rows on screen ([visibleIds]).
 *
 * Bulk operations resolve the selection against the whole archive, so a checked row a filter hides is
 * still deleted or exported - which the user cannot see. The count is what lets the title say so instead
 * of letting the number of check marks silently disagree with the number of rows.
 */
internal fun hiddenSelectedCount(all: Set<Long>, visibleIds: List<Long>): Int =
    (all - visibleIds.toSet()).size
