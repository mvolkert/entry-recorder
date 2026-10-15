# Proposal

## Why

The stale export-folder grant is now surfaced in the settings UI, but an auto-export mirror that fails at
write time while the app is backgrounded (`RtspStreamRecorder`, `ExportTriggerWorker`) is still only a
`Log.e`. A backgrounded app therefore silently stops filling the SAF archive and the user never learns.

## What Changes

- Surface a backgrounded export write failure the same way a stale grant is surfaced (a persistent state
  the user can see without the settings screen being open).

## Capabilities

### Modified Capabilities
- `export`: an export write failure must be observable to the user, not only logged.

## Impact

- Code: `video/RtspStreamRecorder.kt`, `worker/ExportTriggerWorker.kt`, plus wherever the surfaced state is
  shown (settings hub / notification).
- No spec-level behaviour of successful export changes; only the failure surfacing is added.
