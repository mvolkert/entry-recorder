# Design

## Context

See proposal.md — Why. The root composable resolves `ExportGateway` through `EntryPointAccessors` because the share
sheet needs an Activity context (the single-file path adds no `FLAG_ACTIVITY_NEW_TASK`), collects one-shot
`RecordingsUiEvent`s into that gateway plus the app snackbar, holds the `SearchBarState` / `TextFieldState` pair
with a one-way `snapshotFlow` bridge into `viewModel.setSearchQuery`, and builds the `rows: LazyListScope.() -> Unit`
lambda that both the tab list and the expanded search surface render. The run-progress → `RunDisplay` mapping and
`CelebrationCheck` sit in the `Box` content scope so they can `Modifier.align(Alignment.TopCenter)`.

## Goals / Non-Goals

**Goals:**
- The root composable reads as: collect state → compose bar → compose gallery → dialogs.
- Every effect keeps its current key, position in the composition, and ordering guarantees.
- No new `@Composable` lambda parameters stuffed into data classes; no `@Immutable` / `@Stable` additions.

**Non-Goals:**
- No change to component anatomy (chips, badges, app bar), animation specs, or the pull-to-refresh branch.
- No change to who owns the share sheet (still the screen, still Activity context) or the event channel.

## Decisions

- **Share dispatch becomes `rememberShareLauncher()`, not a moved `LaunchedEffect`.** The helper composable returns
  a `(RecordingsUiEvent.Share) -> Unit` lambda closing over context, gateway, snackbar host and scope; the screen's
  `LaunchedEffect(Unit) { viewModel.events.collect … }` stays where it is. Alternative — extract a
  `RecordingsExportEffects(...)` composable that owns the collector — rejected: moving a `LaunchedEffect` block
  changes its position in the composition and therefore its restart semantics, and the collector's ordering note
  (a batch that reports skipped-protected *and* a saved count must deliver both, which is why `showMessage` is
  awaited) is exactly the kind of behaviour an effect move can silently break.
- **`rememberRecordingsSearchBand(...)` is a composable helper that may run `LaunchedEffect`s** (the query bridge
  and the selection-mode collapse), and is called unconditionally from the root — documented at the helper so a
  later reader does not move it behind an `if`. It takes `viewModel`, `selectionMode`, and the
  `clearFilters`-side-effect, returning a small holder with `searchBarState`, `textFieldState`, `clearSearchQuery`,
  `clearAllFilters`.
- **`GalleryItem.exportAs(kind, viewModel)` is an internal extension in the screen file**, not a ViewModel method:
  the branch it replaces is UI dispatch over a sealed UI type, and keeping it local means the raw-folder exception
  stays visible where the menu is wired.
- **`GalleryRunOverlay` is a `BoxScope` extension composable** (`@Composable private fun BoxScope.…`) so
  `Modifier.align(Alignment.TopCenter)` keeps resolving against the same box; the `RunDisplay` mapping moves inside
  it, taking the three nullable progress values.
- **Dialog state stays in the root** (`recordingToDelete`, `serverItemToDelete`, `showBulkDeleteConfirm`) — these
  are the screen's own transient UI state and `remember` is the right holder; only the row-callback wall shrinks.

## Risks / Trade-offs

- [Search-band helper breaks the one-way bridge, so a keystroke gets eaten] → keep the `snapshotFlow` +
  `distinctUntilChanged` chain byte-for-byte and assert by typing into search with a filter active on-device.
- [Overlay extraction shifts a row] → it must stay in the same `Box` with the same padding/align calls; verified by
  the on-device run-progress check where the current comment already states "starting or ending a run never shifts a
  row".
- [Helper composables add recomposition scopes] → acceptable and intended; parameters are the same state reads, and
  `lintDebug` plus the Compose compiler stability report (if enabled) is the check.

## Migration Plan

Four independent extractions (share launcher → search band → export branch → overlay), each compiling alone;
rollback = revert one step.

## Open Questions

- None. Whether the celebration check belongs inside `GalleryRunOverlay` or stays beside it is decided during
  implementation — both keep the same `Box` scope.
