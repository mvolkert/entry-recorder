# Tasks

## 1. Failure state

- [x] 1.1 Add a retained export-failure state updated from `RtspStreamRecorder` and `ExportTriggerWorker`
  write paths and verify a simulated failure sets it (not just `Log.e`)
- [x] 1.2 Render that state on the settings hub row / notification and verify it clears once a write succeeds

## 2. Verification

- [x] 2.1 Run `./gradlew compileDebugKotlin testDebugUnitTest lintDebug` green
- [ ] 2.2 On-device: revoke the folder grant mid-background-export and confirm the user sees the failure
  without opening Settings

## Workflow follow-up

- Archive after the on-device check in 2.2 passes.
