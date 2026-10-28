# Proposal

## Why

`util.ExportTriggerWorker` is the last untested branch-heavy seam from the code-smell remediation plan
(Phase 5 test backfill). Its export loop — folder gate, scope resolution (`latest` vs `all`), per-file
missing-file skip, lazy H.264 transcode with fallback, SAF save counting, and retry-on-exception — is welded
to `CoroutineWorker` (`Context` + `WorkerParameters`), so it cannot be exercised without WorkManager. The
Device-Free Deterministic Testing Convention forbids adding Robolectric / `androidx.work:work-testing`. This
change extracts the decision logic behind an injectable seam, mirroring the `CleanupRunner` / `ExportRunner`
precedent, so the behaviour is covered by a JVM unit test. The export behaviour itself does not change, so
there is no spec delta.

## What Changes

- New `util.ExportTriggerRunner` (`@Inject constructor(repository, exportGateway, transcoder, @IoDispatcher)`)
  owning the export pass; returns a sealed `ExportTriggerOutcome` (`Failure` / `Empty` / `Done` / `Retry`)
  instead of a WorkManager `Result`.
- `ExportTriggerWorker.doWork()` becomes a thin adapter: read `scope` from `inputData`, delegate to the
  runner, map the sealed outcome to `Result.failure` / `Result.success` / `Result.retry` and `buildOutput`,
  preserving the TAG logs and output keys.
- Test `ExportTriggerRunnerTest` over the existing hand-written fakes (fake DAOs + `FakeExportGateway` +
  `FakeH264Transcoder` + a throw knob added to the fake).

## Impact

- Behaviour-neutral refactor of the trigger-export path; `compileDebugKotlin` + `testDebugUnitTest` green.
- The `Result`/`Data` mapping and WorkManager scheduling stay device-gated glue, documented as out of scope.
