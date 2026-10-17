# Tasks

The `recordings-archive` requirements this code implements — *Searchable, filterable gallery*, *Merged local and
server recordings*, *Protect important recordings*, *Range multi-select* — must stay satisfied throughout; each
task names the scenario that proves it.

## 1. Gallery query extraction

- [ ] 1.1 Lift the local filter predicate (device / `triggerTypes` / query over deviceName + note) and the server
      predicate (server-id match with name fallback, `EventType.valueOf` fallback, blank `videoUrl` skip) into
      `ui/recordings/GalleryQuery.kt` verbatim, leaving the `GalleryItem.Remote` URL mapping in the ViewModel;
      verify: `.\gradlew.bat compileDebugKotlin` passes and the `uiState` block contains only calls into
      `GalleryQuery`.
- [ ] 1.2 Add device-free `GalleryQueryTest` covering the *Filter by ring* scenario (a clip that folded a ring in
      during motion recording matches both tags), the *Equal local and server id* scenario (no cross-origin
      collision in the merged sort), and an unrecognised server event label mapping to `UNKNOWN`; verify:
      `.\gradlew.bat testDebugUnitTest` passes.

## 2. Selection state extraction

- [ ] 2.1 Create `ui/recordings/GallerySelection.kt` owning the checked-id `StateFlow` and the anchor, delegating
      range math to the existing `SelectionRange.kt` helpers; verify: `compileDebugKotlin` passes and
      `SelectionRangeTest` is untouched and still green.
- [ ] 2.2 Move `SelectionInfo` (selected / protected / filter-hidden counts) onto `GallerySelection` as a pure
      function and rewire `selectionInfo`, `deleteRecording`, `deleteSelected`, `selectAllVisible`,
      `clearFilters`; verify: the *Bulk delete with protected clips* and *Mark first and last* behaviours still
      report the same counts via the moved `RecordingsViewModelTest` cases.
- [ ] 2.3 Move the ViewModel's selection and protected-count assertions from `RecordingsViewModelTest` into
      `GallerySelectionTest` (net test count does not drop), keeping one ViewModel-level integration case;
      verify: `.\gradlew.bat testDebugUnitTest` passes.

## 3. Style and integration

- [ ] 3.1 Run `.\gradlew.bat lintDebug` and `ktlintCheck`/`detekt` if configured; fix warnings introduced here
      only.
- [ ] 3.2 Confirm `RecordingsViewModel` no longer builds filtered lists or computes selection counts inline
      (grep for `filter {` / `count {` inside the file) and is under ~350 lines; verify: reading the file shows
      only state wiring, intents, and repository calls.

## 4. On-device acceptance gate (blocks archive)

- [ ] 4.1 On a phone with both local clips and a server row present: apply each of the three filters, clear them
      from the empty state, and confirm the visible list and the storage total match pre-refactor behaviour.
- [ ] 4.2 Multi-select with a filter active: check a row, long-press a later row for a range, confirm the header
      count includes filter-hidden checks, then bulk-delete and confirm protected clips are skipped with the
      plural message.
