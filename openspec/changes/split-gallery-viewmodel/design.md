# Design

## Context

See proposal.md — Why. The `uiState` `combine` in `RecordingsViewModel` interleaves four sources
(`repository.allRecordings`, `allDevices`, `settingsFlow`, `_serverState`) with the filter application and the
`GalleryItem` construction, including `ServerMediaUrls.absolute(...)` for remote rows. Multi-select lives beside
it as `_selectedIds` + a plain `var selectionAnchorId`, with the already-extracted pure helpers in
`SelectionRange.kt` (`rangeSelection`, `hiddenSelectedCount`) and a `selectionInfo` derived flow. Existing
device-free coverage: `RecordingsViewModelTest` (283 lines) and `SelectionRangeTest`.

## Goals / Non-Goals

**Goals:**
- Filter/match rules and selection rules become constructor-free pure types with their own tests.
- The ViewModel keeps only `StateFlow` wiring, intents, and repository calls.
- `SelectionRange.kt` stays the single implementation of range/hidden-count math; `GallerySelection` calls it.

**Non-Goals:**
- No change to what the filters mean to the user, to the read-only `Remote` rule, or to `ServerMediaUrls`.
- No `@Immutable` / `@Stable` markers added — the extracted types are not Compose parameters.

## Decisions

- **`GalleryQuery` is an object/file-level pure API, not an injected class.** It has no dependencies; taking the
  `Filters` triple plus the source data and returning `List<GalleryItem>` keeps it callable from a JVM test with
  hand-built `Recording` / `ServerRecordingDto` fixtures. Alternative — a `CombineGalleryUseCase` injected via
  Hilt — rejected: nothing to inject, and AGENTS.md warns against ceremony the project does not already use.
- **Absolute-URL building stays at the ViewModel edge.** `GalleryQuery` returns matched rows; the ViewModel maps
  to `GalleryItem.Remote` with `ServerMediaUrls.absolute(...)`, because settings (base url + api key) are the
  ViewModel's inputs and the mapping must stay a one-liner to remain obviously correct.
- **`GallerySelection` is a small state holder owned by the ViewModel**, exposing `state: StateFlow<Set<Long>>`
  and the anchor internally, with `visibleIds` supplied per call by the ViewModel (it comes from `uiState`, which
  the holder must not know about). `deleteRecording` / `deleteSelected` / `exportSelected` keep calling
  `drop` / `targetsIn(checkedIds)` on it.
- **`SelectionInfo` moves onto `GallerySelection`** as a pure `info(recordings, visibleIds)` function so the
  protected/hidden counts are testable without a ViewModel.

## Risks / Trade-offs

- [Filter rules drift while being lifted] → lift the predicates verbatim, then write the test matrix against the
  *old* behaviour documented in `openspec/specs/recordings-archive` before deleting any code path.
- [Two sources of truth for `visibleIds`] → `GallerySelection` never reads `uiState`; every caller passes the
  visible id list explicitly, which keeps the "range measured on screen order" rule observable in one place.
- [Test churn masks a real regression] → `RecordingsViewModelTest` filter/selection cases are moved (not deleted)
  into the new pure tests, so coverage count does not drop.

## Migration Plan

Extract `GalleryQuery` first (pure move + tests), then `GallerySelection`. No data or API migration; rollback =
revert.

## Open Questions

- None. Whether `Filters` becomes a public type of `GalleryQuery` or stays private is an implementation detail
  resolved when the first test is written.
