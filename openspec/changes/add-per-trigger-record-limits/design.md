# Design

## Context

Recording length today is bounded only indirectly:
- Motion/noise start a clip armed for `triggerRecordDurationSeconds = postRecordSeconds + 30` headroom
  (`TriggerCoordinator`); the `*Ended` event normally stops it earlier via a post-record timer.
- Ring records `ringRecordSeconds`.
- `LocalCaptureLoop.startLocalRecording` stores `deadlineMs = now + maxDurationSeconds*1000`, and
  `foldIntoRunningClip` **extends** `deadlineMs` (`maxOf(current, wanted)`) with no upper bound, so repeated
  triggers grow the clip without limit. The MJPEG/snapshot write loop already counts `framesWritten`.
- `ServerRecordingOrchestrator.start` sends `durationSeconds = maxDurationSeconds` to the server.

Per-device numeric settings already follow one pattern: `Device`/`DeviceEntity` field + `DeviceEndpointResolver`
`DEFAULT_*`/`*_MIN`/`*_MAX` constants + an `effective*` getter + a string-backed `DeviceFormState` field clamped
in `buildDevice()` (see `motionPreRollFrames`). Motivation is in proposal.md.

## Goals / Non-Goals

**Goals:**
- Three device fields — `motionMaxFrames`, `noiseMaxFrames`, `ringMaxMinutes` — where `0` means unlimited.
- A single hard **ceiling** per trigger that both the local loop and the server honour, and that a fold cannot
  push past.
- Reuse the existing clamp/form pattern so the change is additive and testable.

**Non-Goals:**
- No change to pre-roll, post-record buffer, or the fold mechanism itself beyond capping its extension.
- No global cap, no new event type, no change to connection-quality or logging (other changes).
- Not measuring frames on the server side — the phone expresses the cap as an equivalent duration it already sends.

## Decisions

- **Unit per trigger.** Motion/noise are naturally frame-paced (the capture loop writes frame-by-frame), so a
  **frame count** is the honest unit; ring is time-driven, so **minutes** is. This matches the chosen option in
  the request rather than forcing one unit everywhere.
- **Frame cap → also a duration ceiling.** A frame count only maps cleanly to the MJPEG/snapshot path. To keep
  one enforcement point across the RTSP-H.264 passthrough and the server path (which think in seconds), derive
  `capSeconds = ceil(maxFrames / effectiveSnapshotFps)` and treat the hard ceiling as
  `startMs + capSeconds` when `maxFrames > 0`. The MJPEG loop additionally stops at the exact `framesWritten`
  count so the on-phone clip respects the literal frame number. Alternative (frame count only, no server
  ceiling) rejected: it would leave the server and passthrough paths unbounded, contradicting the spec.
- **Ceiling lives in `TriggerCoordinator`.** Add `recordCeilingSeconds(device, eventType)` (from frame cap →
  seconds for motion/noise, from minutes for ring; `0`/null when unlimited) and have
  `triggerRecordDurationSeconds` / `ringRecordDurationSeconds` return `min(armed, ceiling)` when a ceiling
  exists. `LocalCaptureLoop.foldIntoRunningClip` coerces the extended `deadlineMs` to at most
  `startMs + ceilingMs`, so a fold can never breach the cap.
- **`DeviceEndpointResolver` clamps.** Mirror `motionPreRollFrames`: add `*_MIN = 0` (0 = unlimited), a
  generous `MOTION_MAX_FRAMES_MAX` (e.g. 3000) / `RING_MAX_MINUTES_MAX` (e.g. 60), and `effective*` getters that
  coerce into range. `0` stays `0` (never coerced to min>0).
- **Room migration is additive.** Three new `INTEGER` columns on `devices` with default `0` via `ALTER TABLE`; the
  `@Database` version bumps and the schema JSON regenerates. **Coordination note:** this change and
  `add-timber-file-logging` both add the next migration off the current v22; whichever is applied first owns
  `MIGRATION_22_23`, and the other takes the following number. Do not hand-edit the schema JSON — let KSP emit it.
- **Form fields string-backed and clamped on save**, exactly like `ringRecordSeconds`/`motionPreRollFrames`;
  unparseable input falls back to the default (0).

## Risks / Trade-offs

- [Frame→seconds conversion mis-estimates on the passthrough/server path] → The frame count is exact on the
  MJPEG path; the seconds ceiling is a conservative bound for the other paths. Documented at the constant.
- [Ceiling of 0 read as "record nothing" instead of "unlimited"] → Represent unlimited explicitly as `0`/`null`
  and short-circuit before any `coerceAtLeast`; add a unit test that `0` never truncates a clip.
- [Fold capping changes the "second trigger extends the clip" behaviour] → Only caps the *extension*, never
  shortens below the first trigger's own armed length; existing fold tests keep passing, add a ceiling case.
- [Migration-number collision with the logging change] → Sequence at apply time; flagged above so two
  `MIGRATION_22_23` definitions never both land.

## Migration Plan

1. Add the three `Device`/`DeviceEntity` fields + resolver constants/clamps + mapper + `toString` entries.
2. Add `MIGRATION` for the additive columns; bump the DB version; regenerate schema JSON.
3. Thread the ceiling through `TriggerCoordinator`, `LocalCaptureLoop` (start, fold, MJPEG frame stop), and
   `ServerRecordingOrchestrator` (duration).
4. Add the three form fields + `buildDevice` clamps.
Rollback: revert the code; the added columns default to unlimited and are inert if the feature is removed.

## Open Questions

- None blocking. Exact `*_MAX` ceilings (frames/minutes) are a UX cap chosen at implementation; `0 = unlimited`
  is settled.
