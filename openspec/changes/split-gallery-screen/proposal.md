# Proposal

## Why

`ui.recordings.RecordingsScreen` is a single `@Composable` of ~365 lines (file is 546). The row list, top bar,
filter bar, search band and dialogs already live in their own files, so what remains in the root is four
unrelated bundles hoisted together: the one-shot event collector that also launches the share sheet through an
`EntryPointAccessors` gateway, the search-band state and its one-way `snapshotFlow` bridge, the multi-select /
dialog / celebration booleans, and the per-row callback wall — nine lambdas passed into `RecordingCardItem`, four
of which repeat the same `when (item)` local/server branch. Each bundle is independently understandable and
independently testable, and mixing them in one function makes the recomposition scope of the whole tab larger than
it needs to be. No observable behaviour changes, so there is no spec delta.

## What Changes

- Extract `rememberShareLauncher()` — a helper that resolves `ExportGateway` through `EntryPointAccessors` and
  returns the `(RecordingsUiEvent.Share) -> Unit` dispatch (Activity context for the sheet, snackbar hop for the
  gateway's outcome text). The screen's `LaunchedEffect` over `viewModel.events` stays in place: effect keys and
  the awaited message ordering are not moved.
- Extract `rememberRecordingsSearchBand(...)` returning the existing search state bundle
  (`searchBarState`, `textFieldState`, `clearSearchQuery`, `clearAllFilters`) with its one-way `snapshotFlow →
  setSearchQuery` bridge and the "selection mode collapses the surface" effect.
- Collapse the three near-identical `when (item)` export branches (share / gallery / folder) behind one
  `GalleryItem.exportAs(kind, viewModel)` helper, and extract a `GalleryRunOverlay(...)` composable for the
  `RunDisplay` mapping + cancel + `CelebrationCheck` placement. `onExportRawFolder` keeps its own branch because
  the "raw copy only exists for local clips" rule is what hides that menu entry on a server row, and the row
  callbacks stay defined in the screen that owns `selectionMode`, so no lambda captures a stale selection flag.
- `RecordingsScreen` keeps: state collection, `Scaffold`/top-bar composition, the `rows` lambda passing through the
  collapsed export helper, and the `active`-gated check dismissal — target under ~180 lines.
- Out of scope: the sibling god files (`RecordingsViewModel` — `split-gallery-viewmodel` and
  `extract-export-orchestration`; `RecordingCardItem`'s internals), any visual/layout/animation change, any change
  to which component owns the share sheet (it must stay Activity-scoped), and the `LiveCamerasScreen` /
  `SettingsStorageCard` decomposition.

## Capabilities

### New Capabilities

- None — pure refactor, `skip_specs: true`.

### Modified Capabilities

- None — the `recordings-archive` and `settings-ui` requirements (search band, merged list, export, bulk delete)
  keep their current behaviour.

## Impact

- `app/src/main/java/io/github/mvolkert/entryrecorder/ui/recordings/` — two-three new files; root composable
  shrinks sharply.
- No ViewModel, repository, DI or manifest change; the `RecordingsScreenEntryPoint` stays but is resolved inside
  the extracted effects composable.
- Verification is `compileDebugKotlin` + `lintDebug` (UI change) plus a manual pass over search, multi-select,
  share-sheet, batch export and the celebration check on a device.
