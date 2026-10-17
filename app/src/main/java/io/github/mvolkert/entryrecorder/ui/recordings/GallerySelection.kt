package io.github.mvolkert.entryrecorder.ui.recordings

import io.github.mvolkert.entryrecorder.domain.model.Recording
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the selection title and the bulk-delete dialog read. The counts run over the whole archive rather than
 * the filtered view, because that is what a bulk operation acts on: [hiddenSelectedCount] says how many checked
 * rows the filters currently hide, so the number on screen never promises a different batch than the one that
 * gets deleted or exported.
 */
data class SelectionInfo(
    val selectedCount: Int,
    val protectedCount: Int,
    val hiddenSelectedCount: Int = 0,
)

/**
 * Which rows are checked in multi-select, plus the row the range gesture measures from.
 *
 * Extracted from [RecordingsViewModel], which keeps deciding *what* a selection acts on. The ids on screen are
 * always supplied by the caller — they come from the gallery state this holder deliberately knows nothing
 * about — which is what keeps the "a range is measured on the visible order, so filtered-away rows are never
 * swept in" rule visible at one call site. The range and hidden-count math itself stays in `SelectionRange.kt`.
 */
class GallerySelection {
    private val _checkedIds = MutableStateFlow<Set<Long>>(emptySet())

    /** Ids currently checked in multi-select delete mode. */
    val checkedIds: StateFlow<Set<Long>> = _checkedIds.asStateFlow()

    /** Row the range gesture (long-press) measures from; null until something has been picked. */
    private var anchorId: Long? = null

    /** The checked ids right now. A bulk action snapshots these before it launches, so a mark made while the
     * action is already running is not silently swept into a batch the user never confirmed. */
    val currentIds: Set<Long> get() = _checkedIds.value

    val isEmpty: Boolean get() = _checkedIds.value.isEmpty()

    fun toggle(id: Long) {
        _checkedIds.update { if (id in it) it - id else it + id }
        anchorId = id
    }

    /**
     * Marks every local row between the last picked row and [targetId], inclusive — the "pick the first,
     * pick the last" gesture. Measured on [visibleIds], the order the gallery shows, so rows a filter hides are
     * never swept in, and it only ever adds: deselecting stays a per-row action.
     */
    fun selectRangeTo(visibleIds: List<Long>, targetId: Long) {
        _checkedIds.update { it + rangeSelection(visibleIds, anchorId, targetId) }
        anchorId = targetId
    }

    /** Checks every row currently visible, [visibleIds] being the local rows the filters let through. */
    fun selectAll(visibleIds: List<Long>) {
        _checkedIds.value = visibleIds.toSet()
    }

    fun clear() {
        _checkedIds.value = emptySet()
        anchorId = null
    }

    /**
     * Drops [id] from the batch where it still sits, so the header count never names a clip that is already
     * gone and a deleted anchor cannot stretch a range across the list.
     */
    fun drop(id: Long) {
        _checkedIds.update { it - id }
        if (anchorId == id) anchorId = null
    }

    /** The checked subset of [recordings], filters ignored — what a bulk operation acts on. */
    fun targetsIn(
        recordings: List<Recording>,
        ids: Set<Long> = currentIds,
    ): List<Recording> = recordings.filter { it.id in ids }

    /** Counts for the header and the bulk-delete dialog, over [recordings] rather than the filtered view. */
    fun info(
        recordings: List<Recording>,
        visibleIds: List<Long>,
        ids: Set<Long>,
    ): SelectionInfo {
        val selected = recordings.filter { it.id in ids }
        return SelectionInfo(
            selectedCount = selected.size,
            protectedCount = selected.count { it.isProtected },
            hiddenSelectedCount = hiddenSelectedCount(ids, visibleIds),
        )
    }
}
