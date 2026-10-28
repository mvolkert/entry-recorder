# Tasks

## 1. Domain + storage model

- [x] 1.1 Add `motionMaxFrames`, `noiseMaxFrames` (Int frames) and `ringMaxMinutes` (Int) to `Device` and
  `DeviceEntity` (default `0` = unlimited) with `toString` entries; verify `./gradlew compileDebugKotlin`.
- [x] 1.2 Add `*_MIN = 0` / `*_MAX` clamp constants and `effective*` getters in `DeviceEndpointResolver` (0 stays
  0) and map the fields in `DomainMappers`; verify with a resolver unit test that over-range clamps and 0 is
  preserved.

## 2. Room migration

- [x] 2.1 Add the `devices` `ALTER TABLE ... ADD COLUMN` migration (default `0`) and bump the `@Database` version
  (coordinate the number with `add-timber-file-logging` so only one owns `MIGRATION_22_23`); verify KSP regenerates
  the schema JSON and `compileDebugKotlin` is green.

## 3. Ceiling policy

- [x] 3.1 Add `TriggerCoordinator.recordCeilingSeconds(device, eventType)` (frame cap → seconds for motion/noise,
  minutes for ring, null when unlimited) and have `triggerRecordDurationSeconds`/`ringRecordDurationSeconds`
  return `min(armed, ceiling)`; verify a `testDebugUnitTest` that 0/unlimited never truncates and a set cap does.

## 4. Enforce on capture paths

- [x] 4.1 In `LocalCaptureLoop`, stop the MJPEG/snapshot write loop at the exact frame cap (motion/noise) and
  coerce `foldIntoRunningClip`'s extended `deadlineMs` to at most `startMs + ceilingMs`; verify a unit test that a
  fold cannot breach the ceiling and the loop halts at `framesWritten == cap`.
- [x] 4.2 In `ServerRecordingOrchestrator`, send the ceiling-derived `durationSeconds` so a server-mode clip is
  bounded by the same limit; verify compile and the existing server-orchestrator tests stay green.

## 5. Device form UI

- [x] 5.1 Add string-backed fields for motion max frames, noise max frames and ring max minutes to
  `DeviceFormState` (parse-with-fallback to 0) and clamp them in `buildDevice()`; verify `compileDebugKotlin`.
- [x] 5.2 Render the three fields in `DeviceFormTriggersSection`/`DeviceFormDurationsSection` with hints and add
  the strings to `strings.xml`; verify `./gradlew lintDebug` and that the value round-trips per device on save.

## 6. Build verification

- [x] 6.1 Run `./gradlew compileDebugKotlin testDebugUnitTest lintDebug` green.

## 7. On-device verification (gate — do not mark complete until it passes)

- [ ] 7.1 Set a low motion frame cap, walk past the camera continuously, and confirm the clip stops at the cap
  even while motion persists.
- [ ] 7.2 Set a short ring max-minutes, press the doorbell repeatedly so rings fold, and confirm the clip stops at
  the ceiling rather than extending indefinitely.
- [ ] 7.3 Confirm `0` (unlimited) reproduces today's behaviour, and upgrade a real pre-feature database to check
  existing devices default to unlimited with recordings intact.

## Workflow follow-up

- Update `README.md` manually once the per-trigger limits ship.
- Archive after the group 7 gates pass and the schema upgrade is confirmed over real data.
