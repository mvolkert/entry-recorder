# Proposal

## Why

`service.IntercomMonitorService` (~600 lines) is the project's worst god file: one `Service` class owns the
foreground-notification lifecycle, the device-monitor pool, the wake/WiFi lock policy (including the
keep-through-idle flag and safety-net release timer), the on-device motion analyzer pool, post-record stop
timers, motion-alert relaying, and SIP ring routing. The precedent change `extract-sip-call-state-seam`
already pulled the pure routing policy into `TriggerCoordinator`, proving the collaborator pattern fits here;
what remains in the service is Android-bound work that belongs in focused owners, not in the lifecycle class.
Behaviour does not change, so there is no spec delta.

## What Changes

- New `service.MonitorLockKeeper`: owns `PowerManager.WakeLock` + `WifiManager.WifiLock` acquisition, the
  `continuousLockHold` flag, `holdLocksFor`, `releaseLocksIfIdle`, `releaseLocks`, and the safety-net release
  job — the whole lock policy behind one injectable class.
- New `service.DeviceMonitorPool`: owns `activeDevices` and `activeMotionAnalyzers` maps and the sync logic of
  `updateMonitoredDevices` (add/update/remove devices, analyzer rebuild on device-row change, per-device
  bookkeeping cleanup on removal).
- New `service.TriggerEpisodeRunner`: owns trigger started/ended handling, the `pendingPostRecordStops` map,
  `schedulePostRecordStop`/`cancelPostRecordStop`, recording start/stop dispatch, and remote motion-alert
  relaying — delegating all duration/debounce decisions to the existing pure `TriggerCoordinator`.
- `IntercomMonitorService` keeps only Android lifecycle, Hilt field injection, `startForeground` sequencing,
  event-listener wiring (`onEvent`, recorder events, repository stream), and thin delegation to the three
  collaborators; direct `NotificationHelper` building stays but is no longer interleaved with domain work.
- No public API, threading, dispatcher, or log-string semantics change; the collaborators take injected
  dispatchers per project convention.

Out of scope: `RecordingController`, `SipCallManager` internals (separate change), `MonitorStatusRegistry`
design, any change to trigger policy rules in `TriggerCoordinator`.

## Capabilities

### New Capabilities

- None — pure refactor, `skip_specs: true`.

### Modified Capabilities

- None — no requirement changes.

## Impact

- `app/src/main/java/io/github/mvolkert/entryrecorder/service/` — one file split into four.
- On-device verification required (the service drives real hardware): Verso camera monitoring with a doorbell
  ring + motion trigger, and a screen-off idle period to prove lock acquire/release behaviour is unchanged.
- `compileDebugKotlin` + `testDebugUnitTest` green; existing `TriggerCoordinator` tests must stay green.
