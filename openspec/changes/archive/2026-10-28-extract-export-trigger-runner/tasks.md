# Tasks

## 1. Runner seam

- [x] 1.1 New `util.ExportTriggerRunner` (`@Inject`) with `suspend fun run(exportAll: Boolean): ExportTriggerOutcome`, owning the folder gate, scope resolution, missing-file skip, lazy transcode + fallback, and SAF save counting.
- [x] 1.2 Sealed `ExportTriggerOutcome` (`Failure` / `Empty` / `Done` / `Retry`) replacing the WorkManager `Result`.

## 2. Worker adapter

- [x] 2.1 `ExportTriggerWorker` injects the runner; `doWork()` reads `scope`, delegates, and maps the outcome to `Result.failure` / `success` / `retry` + `buildOutput`, keeping TAG logs and output keys.

## 3. Test

- [x] 3.1 Add `transcodeFailure` / `saveFailure` knobs to `FakeH264Transcoder` / `FakeExportGateway`.
- [x] 3.2 `ExportTriggerRunnerTest`: no-folder, no-recordings, latest vs all scope, missing-file skip, transcode + fallback, save-failure not counted, exception → retry.

## 4. Verification

- [x] 4.1 `compileDebugKotlin` green.
- [x] 4.2 `testDebugUnitTest` green.
