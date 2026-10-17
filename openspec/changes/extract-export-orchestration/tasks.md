# Tasks

## 1. Coordinator extraction

- [x] 1.1 Create `ui/recordings/GalleryExportCoordinator.kt` owning `_exportProgress` / `_batchProgress` /
      `_serverDownload`, the `ExportRunProgress` type, `activeRun`, and the `ExportContext` built from the
      runner sinks; expose `busy`, `start { ctx -> … }`, `cancel()`, and the three read-only flows; verify:
      `.\gradlew.bat compileDebugKotlin` passes.
- [x] 1.2 Rewire `RecordingsViewModel` to construct the coordinator with `viewModelScope` and its event/message
      lambdas, and delegate `exportProgress` / `batchProgress` / `serverDownload` / `cancelActiveRun`; verify:
      `compileDebugKotlin` passes and `RecordingsScreen.kt` needs no signature change beyond the
      `ExportRunProgress` import.

## 2. Intent slim-down

- [x] 2.1 Collapse `exportRecording`, `exportSelected`, and `exportServerRecording` onto
      `coordinator.start { … }`, keeping each pre-run check (folder unset, empty selection, blank video url) and
      its exact message in the ViewModel; verify: the diff shows no new or reworded string resources.
- [x] 2.2 Move the `finally` cleanup (`finishRun`) so only the coordinator clears progress flows; verify: the
      existing `RecordingsViewModelTest` case
      "cancelActiveRun cancels the transcode, clears every progress flow and says so" still passes unchanged.

## 3. Tests and style

- [x] 3.1 Extend `RecordingsViewModelTest` (or add a device-free `GalleryExportCoordinatorTest`) with a
      second-request-during-a-run case asserting the busy refusal and that only one run owns the strip; verify:
      `.\gradlew.bat testDebugUnitTest` passes, no Robolectric or mocking new dependency added.
- [x] 3.2 Run `.\gradlew.bat lintDebug` plus `ktlintCheck`/`detekt` if configured and fix what this change
      introduced.

## 4. On-device acceptance gate (blocks archive)

- [ ] 4.1 On a phone: export one clip to Share (transcode toggle on) and watch the percent strip; confirm the
      sheet opens and the strip clears exactly as before.
- [ ] 4.2 Multi-select two clips → folder export, then a server-row download; confirm the k-of-n and download
      percent displays, and that starting a second action mid-run shows the busy message instead of a rival run.
- [ ] 4.3 Cancel a transcode mid-run; confirm the half-written encode is dropped and the strip clears.
