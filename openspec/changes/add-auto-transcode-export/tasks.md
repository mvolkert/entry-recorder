# Tasks

## 1. Persist the auto-transcode setting (Room v25)

- [ ] 1.1 Add `autoTranscodeOnFinalize: Boolean = false` to `AppSettingsEntity` and domain
      `AppSettings` (including the `toString()` line), wire both directions in `DomainMappers`, and
      verify with `gradlew.bat compileDebugKotlin`.
- [ ] 1.2 Bump `AppDatabase` version 24 → 25 and register `Migration(24, 25)`
      (`ALTER TABLE app_settings ADD COLUMN autoTranscodeOnFinalize INTEGER NOT NULL DEFAULT 0`) in
      `AppModule.APP_DATABASE_MIGRATIONS`; verify by compiling and confirming KSP writes
      `app/schemas/io.github.mvolkert.entryrecorder.data.local.AppDatabase/25.json` whose column
      list matches the migration SQL.
- [ ] 1.3 Extend the existing device-free settings/mapper unit tests to cover the new field's
      entity↔domain round-trip and the absent-field (backup restore) default-off path; verify with
      `gradlew.bat testDebugUnitTest`.

## 2. Mutual exclusion in settings state and UI

- [ ] 2.1 Normalize the pair where settings updates are saved (storage settings ViewModel/save
      path): turning one auto-export mode on forces the other off in the same update; verify with a
      unit test using the injected test dispatchers and fakes.
- [ ] 2.2 Add the "Auto-transcode export" `SettingsSwitchRow` to `SettingsStorageCard` beside the
      raw auto-export row, with new `strings.xml` title/hint for both switches that state the
      artifact produced and the exclusion; keep the manual transcode switch untouched. Verify with
      `gradlew.bat lintDebug` and `compileDebugKotlin`.

## 3. Recording-scoped export in the background runner

- [ ] 3.1 Extend `ExportTriggerRunner` with a single-recording entry: target recording id plus an
      explicit transcode-mode override (bypassing `transcodeOnExport`), reusing the folder gate,
      raw-MKV fallback, SAF save counting, and `ExportTriggerOutcome`; add JVM unit tests with fake
      transcoder/gateway covering transcode-success, transcode-failure-falls-back-to-raw,
      missing-file skip, and blank-folder failure. Verify with `gradlew.bat testDebugUnitTest`.
- [ ] 3.2 Add the optional recording-id/transcode input keys to `ExportTriggerWorker` (and the
      unique-name enqueue path), mapping outcomes as the existing scope does; verify with
      `compileDebugKotlin` and worker-mapping unit tests where practical.
- [ ] 3.3 Introduce a scheduler abstraction (interface + WorkManager-backed impl, following the
      `RetentionCleanupScheduler` pattern) so `LocalCaptureLoop` can enqueue the recording-scoped
      export without holding `WorkManager`/`Context` policy; the impl enqueues on the single shared
      unique work name with `ExistingWorkPolicy.APPEND_OR_REPLACE` so requests queue and run one at a
      time in submission order; bind it in the DI module; verify with `compileDebugKotlin`.
- [ ] 3.4 Unit-test the queueing contract device-free: assert consecutive finalize requests produce
      sequential unique-work enqueues on the shared chain rather than parallel or replaced work,
      using a fake scheduler; verify with `gradlew.bat testDebugUnitTest`.

## 4. Finalize-time branch in LocalCaptureLoop

- [ ] 4.1 In `finalizeRecording`, branch on settings: auto-transcode (with folder set) enqueues the
      recording-scoped transcode-export worker and skips the raw mirror; otherwise keep the existing
      raw auto-export path; if both flags are ever persisted on, auto-transcode wins and a warning is
      logged. Extract the decision as a pure, testable function and cover it with unit tests; verify
      with `gradlew.bat testDebugUnitTest`.

## 5. Integration and on-device gates

- [ ] 5.1 Run `gradlew.bat compileDebugKotlin`, `gradlew.bat testDebugUnitTest` and
      `gradlew.bat lintDebug`; fix everything they flag.
- [ ] 5.2 On-device gate: with auto-transcode on and an export folder set, finish a trigger
      recording; confirm an `_h264.mp4` lands in the SAF folder, plays in gallery/VLC, and
      monitoring/new recordings continued during the encode.
- [ ] 5.3 On-device queue gate: let two or three clips finish inside one encode's duration; confirm
      the exports run one at a time in finalize order, each clip yields exactly one artifact, and
      killing the app mid-queue still drains the remaining exports later.
- [ ] 5.4 On-device gate: exercise both toggles in storage settings; confirm turning one on switches
      the other off and the exclusion survives an app restart; confirm the manual transcode switch is
      unaffected.
- [ ] 5.5 Over-real-data upgrade gate: install over an existing v24 database with raw auto-export
      on; confirm the app opens (migration 24 → 25), `autoTranscodeOnFinalize` reads off, and raw
      auto-export still mirrors.
- [ ] 5.6 Failure-path gate: break the SAF folder grant with auto-transcode on; confirm the raw MKV
      fallback or a logged `ExportFailureMonitor` failure, no crash, and the queue keeps draining the
      next clip.

## Workflow follow-up

- Update `README.md` manually if the shipped behavior summary changes (it is human-facing, not
  auto-edited by OpenSpec).
- Archive the change (`/opsx:archive`) only after the on-device gates in group 5 pass.
