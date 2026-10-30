# Design

## Context

See proposal.md for motivation. Current state:

- `LocalCaptureLoop.finalizeRecording` inserts the finished `Recording` and — when
  `autoExportOnFinalize` and an export folder are set — synchronously copies the raw MKV into the SAF
  folder via `ExportGateway.saveFileToSafFolder`, reporting through `ExportFailureMonitor`.
- The H.264 encode lives behind the `H264Transcoder` interface (`ExportTranscoder` implementation,
  MediaCodec-based, device-gated). It already runs outside the capture thread for manual export
  (`GalleryExportCoordinator`/`ExportRunner`) and for the broadcast trigger
  (`ExportTriggerReceiver` → `ExportTriggerWorker` → `ExportTriggerRunner`).
- `ExportTriggerRunner.run(exportAll)` already implements the exact target semantics per recording:
  honor `transcodeOnExport`, fall back to the raw MKV when the transcode throws, count SAF save
  success/failure, and surface failures via `ExportFailureMonitor`. Its TranscodeFailures fall back
  to raw unconditionally today.
- Settings persist in one Room row (`AppSettingsEntity`, DB version 24); migrations are registered in
  `AppModule.APP_DATABASE_MIGRATIONS`, schemas exported to `app/schemas/`.
- Project constraint (fixed intent): capture never re-encodes; encoding happens on the export side
  only. This design keeps that by triggering the encode after finalize, off the capture loop.

## Goals / Non-Goals

**Goals:**
- Auto-transcode export reuses the existing background export machinery rather than adding a second
  transcode pipeline.
- Consecutive finishes form an ordered, serialized queue: exactly one automatic export encodes at a
  time, later ones run in finalize order, none is dropped.
- Both auto-export modes produce at most one artifact per finished recording.
- The mutual exclusion holds in persisted state, not just UI widget state.

**Non-Goals:**
- No progress UI or notification for the automatic transcode (silent background work, same as the
  broadcast-trigger export today).
- No reordering, prioritising, deduplication or cancellation of queued exports; the queue is
  first-come-first-served with no user-visible management.
- No parallel encodes for throughput (a deliberate serialization, not a limitation to optimize away).
- No transcoding of server-orchestrated recordings or cached transcodes for later manual export.
- No change to manual export/share behavior or the `transcodeOnExport` switch's meaning.

## Decisions

### D1: Encode via WorkManager, not in the capture loop
On finalize with auto-transcode enabled, `LocalCaptureLoop` enqueues unique one-time WorkManager
work instead of doing the (minutes-long) transcode inline. Chosen worker path: extend
`ExportTriggerRunner` with a recording-scoped entry (target = the just-finalized recording id, plus
an explicit `transcode: Boolean` override instead of reading `transcodeOnExport`), reusing its
existing folder gate, raw fallback, SAF save counting, and `ExportTriggerOutcome` handling.
`ExportTriggerWorker` gains an optional recording-id input key; when present the runner exports just
that recording with the mode the finalize site chose.
Rejected: a dedicated `AutoTranscodeWorker` duplicating runner logic; a plain coroutine on the
service scope (dies with process, competes with nothing serialized); encoding inline in
`finalizeRecording` (violates the cheap-capture constraint and blocks the next trigger).

Raw auto-export keeps its current synchronous mirror in `finalizeRecording` — it is a file copy, not
an encode — except when auto-transcode wins (D3), where the copy is skipped because the worker will
place the artifact.

### D2: Two persisted booleans, enforced-off at the write site
`AppSettings`/`AppSettingsEntity` gain `autoTranscodeOnFinalize: Boolean = false`. The storage screen
switch rows keep their current copy-the-settings update style, but the ViewModel/save path normalizes
the pair: turning one on forces the other off in the same update. Rejected: a single enum column
(cleaner model, but breaks the established copy-field settings idiom, backup/restore surface, and
mapper symmetry for no user-visible gain).
Robustness: if a restored/legacy row ever carries both flags on, finalize treats auto-transcode as
taking precedence (never both artifacts for one recording) and logs a warning.

### D3: Finalize-time branch
`finalizeRecording` reads settings once: if `autoTranscodeOnFinalize` and folder set → enqueue the
recording-scoped worker (transcode + export); else if `autoExportOnFinalize` and folder set → the
existing raw mirror. One artifact per recording, no ordering change for the DB insert.

### D4: Room v25 migration follows project convention
`AppDatabase` version 24 → 25; new `Migration(24, 25)` in `APP_DATABASE_MIGRATIONS` runs
`ALTER TABLE app_settings ADD COLUMN autoTranscodeOnFinalize INTEGER NOT NULL DEFAULT 0` (off by
default, preserving upgraded users' current raw auto-export behavior). KSP exports
`app/schemas/…/25.json`; backup/restore relies on the entity default plus Gson-missing-field
handling as with prior boolean settings — no extra clamping needed for booleans beyond verifying the
absent-field default path, matching the project's existing convention.

### D5: Naming and cleanup compatibility
The exported file keeps the transcoder's `_h264.mp4` name via `H264Transcoder.outputFor`, matching
the SAF naming grammar the cleanup pass already recognizes (`CleanupPlan` knows the derived-transcode
name). No new naming rules.

### D6: The queue is the existing unique-name WorkManager chain
Queueing comes from WorkManager's named sequential chain rather than a hand-rolled queue:
the finalize site enqueues the recording-scoped export as unique work on one shared name with
`ExistingWorkPolicy.APPEND_OR_REPLACE`, which is what `ExportTriggerReceiver` already relies on.
Semantics that satisfy the export spec's queue requirement:
- `APPEND` adds the new request to the end of the chain, so enqueues keep finalize (submission) order.
- A chain runs its work serially, so only one `ExportTriggerWorker` — and therefore only one
  MediaCodec encode — is ever active; a finalize during a running encode simply lands behind it.
- The chain is persisted by WorkManager, so queued exports survive process death and are picked up
  when the app or the device returns.
- `Result.retry()` from a failed run re-attempts that item before the chain continues, so a transient
  failure delays the queue rather than losing an entry.
Rejected alternatives: a `Channel`/`Mutex` inside the monitor service (dies with the process, no
persistence, and duplicates work WorkManager already does); `PARALLEL` policies or per-recording work
names (would run encodes concurrently and contend for the single SAF folder and the codec); a
hand-maintained pending-export table with a poller (new state to keep consistent, no benefit over the
chain). One shared chain also serializes against broadcast-triggered `all` exports, which is intended:
the archive folder is only ever written by one run at a time.
Queue depth is unbounded by design (each finished clip gets its own entry); the backlog drains in
order and a long queue only delays later artifacts, which the best-effort posture already accepts.

## Risks / Trade-offs

- [Transcode of a long clip runs while the phone is otherwise idle-charging or not] → WorkManager
  default constraints; the export is best-effort like today's mirror, failures land in
  `ExportFailureMonitor` and logs, the authoritative copy stays private.
- [Process death between finalize and worker run] → WorkManager persists the enqueue; worst case the
  clip exports late, never lost. If work input targets a recording id, a missing file is skipped as
  the runner already does.
- [Two auto-modes confuse users about which artifact to expect] → hints on both switches spell out
  the produced file type and the exclusion (settings-ui spec scenario).
- [Worker chain contention with broadcast-triggered `all` exports] → same unique-name serialization
  the receiver already relies on: runs pile up, never run in parallel against the SAF folder.
- [A burst of triggers builds a queue whose last artifact arrives minutes later] → accepted as
  best-effort: the private copy is authoritative and complete immediately, and the queue drains in
  order without dropping entries; no user-visible stall because nothing waits on the chain.
- [Enqueue order differing from finalize order under concurrent finalization] → finalize already
  runs serialized against the trigger pipeline, and each enqueue happens right after its DB insert,
  so submission order matches recording order in practice.
- [MediaCodec transcode still only verifiable on device] → accepted; the JVM-testable parts (runner
  scope/override logic, finalize branch decision, settings normalization) get device-free unit tests
  per project convention.

## Migration Plan

Shipped as a regular app update; schema migration 24 → 25 runs at first open. Rollback = downgrade
install is not supported on Android; the new column defaults off so an older app version reading a
newer DB is not a scenario (Room rejects and the app keeps v25 column inert for old code paths).

## Verification Gates (acceptance)

- On-device: with auto-transcode on and a folder set, finish a trigger recording; confirm an
  `_h264.mp4` appears in the SAF folder, plays in the gallery/VLC, and capture/monitoring continued
  during the encode.
- On-device queue gate: let two or three clips finish within one encode's duration; confirm the
  exports run one at a time in finalize order, every clip produces exactly one artifact, and a forced
  process restart mid-queue still drains the remaining exports.
- On-device: with both toggles exercised in settings, confirm turning one on switches the other off,
  including after app restart (persisted exclusion).
- Over-real-data upgrade: install over an existing v24 database with auto-export on; confirm
  `autoTranscodeOnFinalize` comes up off and raw auto-export still works.
- Failure path: point the folder grant at a broken SAF target with auto-transcode on; confirm raw
  MKV fallback or logged failure via `ExportFailureMonitor`, no crash.
