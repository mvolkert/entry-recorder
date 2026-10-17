# Proposal

## Why

`ui.recordings.RecordingsViewModel` (539 lines) is still a god ViewModel even though the export *pipeline* was
already pulled into `util.ExportRunner`: the ViewModel separately owns the export *run state* — three mutually
exclusive progress flows (`_exportProgress`, `_batchProgress`, `_serverDownload`), the single-run mutex
(`activeRun` / `runBusy()` / `finishRun()` / `cancelActiveRun()`), the `ExportContext` callback wiring, and the
`ExportRunProgress` type — none of which is gallery state or a user intent. That cluster is one responsibility
held inline, and it forces every export intent to repeat the same guard/launch/finally shape. Gallery filtering
and multi-select are the ViewModel's other two unrelated responsibilities and are handled by the separate
`split-gallery-viewmodel` change. Behaviour does not change, so there is no spec delta.

## What Changes

- New `ui.recordings.GalleryExportCoordinator` (plain class, no Android types beyond annotations-free state):
  owns the three progress `StateFlow`s, `ExportRunProgress`, the `ExportContext` it builds itself, the
  `activeRun` job with its `busy` / `start` / `finish` / `cancel` operations, and the event sink for
  `Share` / `ExportCompleted` / `Message`.
- `RecordingsViewModel` keeps only: gallery state composition, filter/selection intents, delete/protection
  intents, server refresh, and thin pass-throughs (`exportRecording`, `exportSelected`, `exportServerRecording`,
  `cancelActiveRun`) that resolve targets and delegate; it exposes the coordinator's flows as its own
  `StateFlow`s so the screen API is unchanged.
- The busy message (`recordings_run_busy`) and the cancel message stay worded identically and are emitted
  through the same event channel.
- Out of scope: `ExportRunner` internals, gallery filtering rules, multi-select state, `SettingsViewModel`,
  any change to what the export strip shows.

## Capabilities

### New Capabilities

- None — pure refactor, `skip_specs: true`.

### Modified Capabilities

- None — no requirement changes to the `export` or `recordings-archive` specs.

## Impact

- `app/src/main/java/io/github/mvolkert/entryrecorder/ui/recordings/` — one new file; ViewModel loses ~120 lines.
- `ui/recordings/RecordingsScreen.kt` keeps reading the same flow property names, so no UI change is expected
  (`lintDebug` as guard).
- Unit test for the coordinator's mutex semantics (busy refusal, cancel clears all three flows, finish releases
  the run) runs device-free per the Device-Free Deterministic Testing Convention.
- On-device gate: one single-clip export with progress strip, one multi-select batch export, one server
  download, and a cancel mid-run on a real phone.
