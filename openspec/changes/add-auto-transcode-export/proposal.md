# Proposal

## Why

Finished recordings are only mirrored to the user's SAF export folder as raw `mjpeg.mkv` when the
"Auto-Export Finished Recordings" toggle is on, and the H.264 transcode only runs when the user
manually exports or shares. Users who want a sync-friendly archive of directly playable files must
either transcode every clip by hand or accept MKVs that most gallery apps cannot play. An opt-in
auto-transcode export closes that gap.

## What Changes

- New setting `autoTranscodeOnFinalize`: when enabled, a finished recording is transcoded to H.264
  fMP4 after finalization and the resulting `.mp4` is written to the SAF export folder (instead of
  the raw MKV mirror that `autoExportOnFinalize` produces).
- The transcode runs in the existing background WorkManager export chain, off the 24/7 capture loop,
  so the "cheap capture, lazy encoding" intent is preserved: capture never re-encodes in the
  recording thread; encoding happens after finalize, only when the user opted in.
- A queueing pipeline for consecutive finishes: when a recording finalizes while an earlier auto
  transcode is still running, the new export is queued behind it (FIFO, one encode at a time) instead
  of running in parallel or being dropped; capture and monitoring never wait on the queue.
- Transcode failure falls back to copying the raw MJPEG MKV, mirroring the broadcast-trigger export
  policy in `ExportTriggerRunner` — the archive never has holes.
- Mutual exclusion in the storage settings UI: enabling "Auto-transcode export" switches off
  "Auto-Export (raw MKV)" and vice versa; the two toggles can never both be on.
- The existing manual "Transcode to H.264 on Export" toggle (`transcodeOnExport`) keeps its current
  meaning and stays independent of the mutually exclusive pair.
- Room schema: new non-null column on `app_settings` (version bump 24 → 25) with a real migration,
  default `0` (off), following the project's migration conventions.

Out of scope:
- Transcoding server-orchestrated (Python backend) recordings; this covers locally captured clips
  finalized by `LocalCaptureLoop` only.
- Cache a transcoded file for later manual export (no "transcode ahead of time" optimization).
- Changing the manual export/share pipeline or the broadcast-intent export behavior.
- Renaming or replacing the existing `transcodeOnExport` setting.

## Capabilities

### New Capabilities
(none)

### Modified Capabilities
- `export`: adds a requirement that a finalized recording is automatically transcoded to H.264 and
  exported to the SAF folder when the auto-transcode setting is enabled, with raw-MKV fallback on
  transcode failure, and that overlapping auto-transcode exports queue and run one at a time in
  finalize order; the existing raw auto-export requirement becomes conditional on the modes being
  mutually exclusive.
- `settings-ui`: adds a requirement that the two auto-export toggles on the storage screen are
  mutually exclusive — turning one on turns the other off.

## Impact

- Settings surface: `AppSettings` (domain), `AppSettingsEntity` (Room entity), `DomainMappers`,
  `AppModule` (migration + version bump to 25 in `AppDatabase`), new `app/schemas/…/25.json`.
- Capture pipeline: `LocalCaptureLoop.finalizeRecording` (current raw auto-export branch) and the
  background export path reused from `ExportTriggerRunner` / `ExportTriggerWorker` /
  `ExportTriggerReceiver` (unique-name WorkManager chain).
- UI: `SettingsStorageCard` switch rows, `strings.xml` for the new toggle and exclusion hints.
- Tests: unit tests for the runner/fold logic (device-free convention), VM/settings logic tests.
- Verification: on-device gate needed — record a clip with auto-transcode on, confirm an H.264 `.mp4`
  lands in the SAF folder and plays; confirm the toggles exclude each other; confirm the v24 → v25
  upgrade over real data; confirm two clips finalized back-to-back export sequentially (no parallel
  encodes, none lost).
