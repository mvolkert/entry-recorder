# Proposal

## Why

A trigger's clip currently ends only when its `*Ended` event arrives plus a post-record buffer, or — as a
safety net — a `postRecordSeconds + headroom` timer. A doorbell ring records a fixed `ringRecordSeconds`. But
folding a second trigger into a running clip **extends the deadline without any hard ceiling**, and a busy
scene that keeps re-triggering motion can therefore record far longer than intended, filling storage and
battery. There is no per-trigger upper bound a user can set.

## What Changes

- Add a **hard cap per trigger**, stored on the device like the other trigger timings:
  - **Motion** and **Noise**: a maximum **frame count** (snapshot-paced), so a clip stops after N frames even
    if the end event never arrives or keeps folding.
  - **Ring**: a maximum **minutes** duration, so a ring (and rings folded into it) cannot exceed the ceiling.
- Whichever limit is reached first ends the recording: the existing `*Ended`/post-record timer, OR the frame
  cap (motion/noise), OR the minutes cap (ring). The cap is a ceiling a fold can never push past.
- Expose the three fields in the device form (durations/triggers sections), each **clamped on save** to a sane
  range like `snapshotFps` and `motionPreRollFrames` already are.
- A cap of **0 means "no limit"**, preserving today's behaviour, and the Room migration defaults every existing
  device to 0 so an upgrade changes nothing until the user opts in.
- Applies to both capture paths: the local MKV loop (`LocalCaptureLoop`) and the server-side record
  (`ServerRecordingOrchestrator`), since the cap is a property of the trigger, not the recorder.
- Out of scope: changing the pre-roll frame count (already a setting), the post-record buffer semantics, the
  fold-into-running-clip behaviour itself, or any global (non-per-device) cap.

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `triggers`: each trigger gains a user-configurable hard recording limit (max frames for motion/noise, max
  minutes for ring) that bounds a clip even across folds.

## Impact

- Code: `data/local/entity/DeviceEntity.kt` (+ Room migration / schema JSON), `domain/model/Device.kt`
  (fields + `toString`), `domain/model/DeviceEndpointResolver.kt` (clamp constants + effective values),
  `data/mapper/DomainMappers.kt`, `domain/triggers/TriggerCoordinator.kt` (ceiling feeding
  `triggerRecordDurationSeconds`/`ringRecordDurationSeconds`), `video/LocalCaptureLoop.kt` (stop on frame cap
  and refuse fold past the ceiling), `video/ServerRecordingOrchestrator.kt` (server start honours the ceiling),
  `ui/settings/DeviceFormState.kt` + `DeviceFormDurationsSection.kt`/`DeviceFormTriggersSection.kt`.
- Verification: on-device gate (walk past the camera continuously, confirm the motion clip stops at the frame
  cap; press the doorbell repeatedly, confirm the ring clip respects the minutes ceiling); Room over-real-data
  upgrade check for the new columns.
