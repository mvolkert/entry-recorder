# Design

## Context

See proposal.md — Why. Today the run-state cluster sits between the ViewModel's filter flows and its
`uiState`: `ExportRunProgress` (a nested data class holding an `@StringRes`), three nullable `MutableStateFlow`s,
`activeRun: Job?`, and the `exportContext` that bridges `ExportRunner`'s sinks onto those flows and onto the
`Channel<RecordingsUiEvent>`. `RecordingsScreen` reads the three flows and calls `cancelActiveRun()`, and
`RecordingsViewModelTest` already pins the cancel behaviour (clears all flows + says so). `ExportRunner` runs in
the caller's coroutine and owns no state, by design.

## Goals / Non-Goals

**Goals:**
- One class owns "which run is active and what the strip shows", including the mutual-exclusion rule.
- The ViewModel's export intents stop repeating guard → launch → `finally { finishRun() }`.
- `RecordingsScreen` and `RecordingsViewModelTest` keep compiling and passing with at most import-level edits.

**Non-Goals:**
- No change to `ExportRunner`, `ExportGateway`, `H264Transcoder`, or the strip's visual design.
- No new lifecycle owner: the coordinator is created per ViewModel and dies with it.

## Decisions

- **`GalleryExportCoordinator` is constructed by the ViewModel with `viewModelScope` passed in**, taking
  `ExportRunner`, `UiStrings`, and two emit lambdas. A run must not outlive the screen, so the scope is the
  ViewModel's; the same "caller supplies the scope" pattern is used for the service collaborators in
  `refactor-intercom-service-responsibilities`. Alternative — a Hilt `@Singleton` coordinator — rejected: it would
  outlive the screen and keep a cancelled run's flows populated.
- **API shape:** `busy: Boolean`, `start(block: suspend (ExportContext) -> Unit)` (guards, launches, clears in
  `finally`), `cancel()`, plus the three read-only flows and `context: ExportContext`. The three intents become
  one-line `coordinator.start { ctx -> exportRunner.exportX(…, ctx) }` calls; target resolution (settings check,
  `no video url`, empty selection) stays in the ViewModel because those are gallery concerns, not run concerns.
- **`ExportRunProgress` moves to the coordinator's file** and `RecordingsScreen`'s reference is updated;
  `@StringRes` stays, so `lintDebug` remains meaningful.
- **The event channel stays in the ViewModel** (it also carries non-export events); the coordinator receives two
  lambdas (`emitMessage`, `emitEvent`) so one-shot delivery semantics are unchanged.

## Risks / Trade-offs

- [Two owners of `finishRun()` semantics → a flow left showing stale progress] → the coordinator's `start` is the
  only place that clears flows, inside `finally`; the existing ViewModel test is extended to assert the same three
  flows through the new path.
- [Passing `viewModelScope` into a helper looks like a leak] → it is the project's existing pattern for
  scope-bound collaborators and keeps cancellation semantics identical.
- [Screen API drift] → ViewModel keeps exposing `exportProgress` / `batchProgress` / `serverDownload` /
  `cancelActiveRun` as thin delegations, so no composable signature changes.

## Migration Plan

Single-file extraction, one commit; rollback = revert. Steps ordered so the screen keeps working at each stage.

## Open Questions

- None. Whether `SelectionCleared` after a batch export moves into the coordinator is decided during
  implementation: it stays in the ViewModel, since selection state is not the coordinator's.
