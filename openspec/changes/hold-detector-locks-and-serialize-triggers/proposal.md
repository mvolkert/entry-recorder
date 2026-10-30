# Proposal

## Why

Two defects make motion recordings unreliable, and neither needs a camera to fix. First, the two continuous
detection loops (`OnDeviceMotionAnalyzer` polling snapshots, and the monitor's own work) are the only work that
must run while the phone is asleep, yet no lock covers them: [MonitorLockKeeper.acquireForTrigger] is reached
only *after* a trigger has been recognised, so detection is chicken-and-egg — a suspended CPU and a
power-save Wi-Fi radio cannot detect the motion that would have taken the lock. Second, every device event is
dispatched in its own coroutine on the multi-threaded `Dispatchers.IO` scope
(`serviceScope.launch { eventHandler.handle(event) }` in `IntercomMonitorService.onEvent`), so a device's
`Started` and the following `Ended` can run concurrently and finish out of order, which is how a clip ends up
truncated or discarded as an empty file with nothing wrong in the detector.

## What Changes

- While at least one `OnDeviceMotionAnalyzer` is running, the monitor holds the CPU and Wi-Fi locks; it
  releases them when the last analyzer stops. This is system behaviour driven by what is actually running, not
  a new user setting.
- Every wake-lock acquire becomes **acquire-with-timeout**, renewed from the same holder while a reason to hold it
  or an armed per-trigger hold is still outstanding, so no path can pin the CPU indefinitely.
- `keepLocksHeld` keeps its stored column, its default and its current meaning — hold through idle for
  deployments that get no analyzer-driven reason (camera-event-only). It stops being the *only* way to keep the
  phone awake. **BREAKING** for the power posture of an analyzing deployment: a dedicated detection phone now
  stays awake continuously by default, which is the point, and battery-conscious deployments must opt out by
  disabling in-app motion analysis. This reverses the shipped default set by
  `openspec/changes/bound-wake-wifi-locks/`, whose on-device soak (task 2.2) is still open.
- The monitor logs a battery-optimization diagnostic (`PowerManager.isIgnoringBatteryOptimizations`) behind a
  JVM-testable seam: one line at WARN while the app is not exempt, because a wake lock alone does not survive
  OEM battery managers and today that state is read only in onboarding, which runs once.
- All `IntercomEvent`s route through a `Channel` with exactly one collecting coroutine, so per-device
  `Started`/`Ended` ordering is guaranteed even when handling suspends.
- `DeviceMonitorPool.sync` rebuilds an analyzer only when a field the analyzer actually consumes changed
  (snapshot URL/credentials, sensitivity, pre-roll count), not on any edit of the row. When a rebuild or
  removal does tear down an analyzer whose episode is active, the pool emits a synthetic
  `IntercomEvent.MotionOnDeviceEnded` so the running clip closes through the normal post-record path instead of
  being orphaned and leaving the status card stuck on MOTION.

## Out of scope

- The motion-scoring rework — 96x54 analysis grid and its nearest-neighbour downscale, ratio thresholds,
  previous-frame-only comparison, `requiredMotionFrames` versus idle backoff, the full-resolution analysis URL,
  and pre-roll staleness/freshness. It stays a separate, device-gated proposal; this change deliberately does
  not touch detection sensitivity.
- Continuous-presence re-arming (`MotionOnDeviceStarted` is edge-triggered, so a subject still moving after a
  clip's post-record stop never starts a new episode) and the eager `preRollFrames()` drain on the fold path.
  Both belong with the scoring rework.
- The 2N Logging-API pull loop's sleep exposure, and event loss while that loop is backed off.
- Any new permission. `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` remains an explicit non-goal (the existing
  onboarding decision); the diagnostic is read-only. No manifest, Room schema, dependency or Gradle change.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `alerts-lockscreen` — owns the always-on monitor foreground service, so it gains the requirements for the
  wake/Wi-Fi lock policy covering continuous motion analysis, bounded (timed) acquires, and the
  battery-optimization diagnostic.
- `triggers` — owns the monitor service's event routing and on-device motion analysis, so it gains the
  serialized-event-handling requirement and the analyzer-rebuild/synthetic-episode-close requirement.

## Impact

- Code: `service/MonitorLockKeeper.kt` (timed acquire, renew, analyzer-hold reason), `service/IntercomMonitorService.kt`
  (event queue wiring, lock-policy reconcile, battery diagnostic), `service/DeviceMonitorPool.kt`
  (analyzer identity scope, synthetic `MotionOnDeviceEnded`, exposes whether an analyzer runs),
  `video/OnDeviceMotionAnalyzer.kt` (key derivation, exposes whether an episode is active). New files:
  `service/MonitorEventQueue.kt` (channel plus its single collector), `service/LockController.kt` (the wake/Wi-Fi
  seam, since `WifiManager.WifiLock` has no timed acquire), `service/LockHoldPolicy.kt` (the pure reason set),
  `util/BatteryOptimizationProbe.kt` (which also holds the pure `BatteryExemptionReport` decision), bound in
  `di/AppModule.kt` / `di/BindsModule.kt` with `ui/onboarding/PermissionChecker.kt` delegating to the probe. JVM
  tests for each extracted decision.
- Unchanged: `MonitorEventHandler.kt`, `TriggerCoordinator.kt`, `TriggerEpisodeRunner.kt`, `HttpSnapshotClient.kt`,
  `TwoNIPVersoDevice.kt`, `LocalCaptureLoop.kt`, all UI, all Room/schema, all dependencies, the manifest.
- Verification: `compileDebugKotlin`, `testDebugUnitTest`, `lintDebug` plus on-device gates — screen-off
  detection with the analyzer running, lock release after the last analyzer stops, and a replay of a
  `Started`/`Ended` storm to confirm ordered handling. No live Python server or SIP gate.
