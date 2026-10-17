# Proposal

## Why

`ui.recordings.RecordingsViewModel` carries three unrelated responsibilities inline, only one of which is
gallery state: the merged local+server filtering/mapping rules (the ~50-line `combine` block that decides device,
event-type and query matches for both origins and builds `GalleryItem`s), the multi-select state machine
(`_selectedIds` plus a mutable `selectionAnchorId`, toggle/range/select-all/clear, and the `SelectionInfo`
derived flow), and the export run plumbing (handled by the separate `extract-export-orchestration` change). The
pure match rules and the selection rules are exactly the logic the Device-Free Deterministic Testing Convention
wants covered, but today they are only reachable through a `@HiltViewModel`. Behaviour does not change, so there
is no spec delta.

## What Changes

- New `ui.recordings.GalleryQuery`: pure, Android-free `match(recording, filters)` for the local origin and
  `match(dto, filters, selectedServerId, selectedDeviceName)` for the server origin, plus the merged sort —
  the rules lifted verbatim out of the `uiState` combine, including the multi-trigger `triggerTypes` match and
  the server-device fallback to a name compare.
- New `ui.recordings.GallerySelection`: owns the checked-id set and the anchor, exposing `toggle`,
  `selectRangeTo(visibleIds, targetId)`, `selectAllVisible(visibleIds)`, `clear`, `drop(id)`, and the
  `SelectionInfo` computation over (all recordings, visible ids, checked ids); it composes the existing
  `SelectionRange.kt` helpers rather than duplicating them.
- `RecordingsViewModel` keeps the `StateFlow` composition and the intents, delegating the decisions; its public
  surface (`uiState`, `selectedIds`, `selectionInfo`, all intent signatures) is unchanged.
- New device-free unit tests for both extracted types (filter matrix across local/server, protected and
  filter-hidden selection counts, range-add-only semantics).
- Out of scope: export run state and `ExportRunner`, server fetch/retry policy, the `Remote` row read-only rule,
  `RecordingsScreen` composition (separate `split-gallery-screen` change).

## Capabilities

### New Capabilities

- None — pure refactor, `skip_specs: true`.

### Modified Capabilities

- None — the `recordings-archive` and `settings-ui` requirements are untouched.

## Impact

- `app/src/main/java/io/github/mvolkert/entryrecorder/ui/recordings/` — two new files; ViewModel loses ~110 lines.
- `app/src/test/java/io/github/mvolkert/entryrecorder/ui/recordings/` — two new test classes plus adjustments to
  `RecordingsViewModelTest` where it asserts filter or selection behaviour.
- No Room schema, no network, no manifest change; verification is `compileDebugKotlin` + `testDebugUnitTest`
  plus a manual gallery filter/selection pass on a device (no live-server gate needed).
