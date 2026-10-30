# Tasks

## 1. Serialized trigger event handling

- [x] 1.1 Add `service/MonitorEventQueue.kt` owning a `Channel<IntercomEvent>(Channel.UNLIMITED)` and one
  collecting coroutine that awaits a suspend handler, so ordering is a property of the queue rather than of the
  dispatcher (design D4); verify with a new `MonitorEventQueueTest` in `app/src/test/.../service/` that drives it
  on `StandardTestDispatcher` with a suspending fake handler and asserts arrival order across a
  `MotionOnDeviceStarted`/`MotionOnDeviceEnded` pair, delivery across two interleaved devices, and exactly-once
  delivery (`./gradlew :app:testDebugUnitTest --tests "*MonitorEventQueueTest"`)
- [x] 1.2 Route `IntercomMonitorService.onEvent` through the queue (`trySend`, never blocking) and make the queue's
  collector the only caller of `MonitorEventHandler.handle`, keeping the existing
  `Received <logIdentity()>` line exactly once per event; delete the per-event
  `serviceScope.launch { eventHandler.handle(event) }`; verify `./gradlew compileDebugKotlin` and that
  `RecorderEvent.Connection` collection and `handleSipRing` still reach the same handler through the one queue

## 2. Lock hold reasons, timed acquire and renewal

- [x] 2.1 Expose `DeviceMonitorPool.anyAnalyzerRunning(): Boolean` from `activeMotionAnalyzers.isNotEmpty()`;
  verify by reading it in the new lock-reconcile call site (2.3) and with the unit test added there
- [x] 2.2 Extract the Android lock mechanics behind a `service/LockController` seam (non-ref-counted
  `acquire(timeoutMs)` / `release()` / `isHeld` for the wake and Wi-Fi pair) with a PowerManager/WifiManager-backed
  implementation, and move the hold decision into a pure policy that takes the reason set; verify `MonitorLockKeeper`
  no longer calls any Android lock type directly
- [x] 2.3 Give `MonitorLockKeeper` one combined reason supplier (pending post-record stop, local recording,
  `keepLocksHeld`, analyzer running) replacing `continuousHold` plus `workOutstanding`, and add the renewal job that
  re-acquires every 5 minutes while a reason holds or an armed `holdFor` deadline has not passed, calling
  `releaseIfIdle()` when neither is true; verify with a new
  `MonitorLockKeeperTest` using a fake `LockController` and a fake reason supplier that asserts: an analyzer running
  alone acquires both locks with the timeout value, the last reason releasing releases both, renewal re-acquires
  without a release in between, renewal stops when the reason goes away, and a tick keeps (then at its deadline
  releases) a hold the phone observes no reason for; the reconcile and the release path read the same decision, so
  an armed deadline survives a device-list re-emit and a short hold cannot pull a longer armed one's release
  forward, and every keeper entry point is `@Synchronized`
- [x] 2.4 Wire `IntercomMonitorService.updateMonitoredDevices` to pass the supplier including
  `pool.anyAnalyzerRunning()`, keeping the per-trigger `holdFor(durationMs)` path on the same timed acquire; verify
  `./gradlew compileDebugKotlin :app:testDebugUnitTest` green and confirm the camera-event-only case (no analyzer, no
  `keepLocksHeld`) still acquires nothing at idle

## 3. Battery-optimization diagnostic

- [x] 3.1 Add `util/BatteryOptimizationProbe` (interface) with a PowerManager-backed implementation bound in
  `di/BindsModule`, and make `AndroidPermissionChecker.isBatteryExempt()` delegate to it so the platform read exists
  once; verify `./gradlew compileDebugKotlin` and that the onboarding Battery page still resolves the same state
- [x] 3.2 Add a pure `BatteryExemptionReport` decision (no change / warn / info from last and current exemption)
  with a unit test covering first-read-not-exempt → warn, unchanged → no line, exempt → info once, and
  not-exempt-after-exempt → warn again; verify with
  `./gradlew :app:testDebugUnitTest --tests "*BatteryExemptionReport*"`
- [x] 3.3 Call the probe and log the decision in the device-list reconcile using the service's own monitor tag,
  naming the consequence for detection and never a credential or password; verify `./gradlew compileDebugKotlin` and
  that a WARN appears at most once per change of state

## 4. Analyzer rebuild scope and episode closure

- [x] 4.1 Add `MotionAnalysisKey` plus `OnDeviceMotionAnalyzer.Companion.keyFor(device)` over device id, snapshot
  URL, username, password, `motionSensitivity` and `effectiveMotionPreRollFrames`, and expose
  `@Volatile val isEpisodeActive: Boolean`; verify with a unit test asserting an unrelated edit (ring duration, name,
  alert flags) yields an equal key while each analyzed field yields a different one
- [x] 4.2 Replace `existingAnalyzer.device != entity` in `DeviceMonitorPool.sync` with the key comparison; verify
  `./gradlew compileDebugKotlin` and the 4.1 test
- [x] 4.3 Route every reconcile-path analyzer stop (analysis disabled, rebuild, device removed) through one private
  helper that stops and, when `isEpisodeActive`, emits `IntercomEvent.MotionOnDeviceEnded(analyzer.device)` to the
  listener, while `stopAnalyzers()` on teardown stops without emitting; verify with a unit test over the pure
  should-announce predicate (active + reconcile → emit, active + teardown → no emit, inactive → no emit) and confirm
  the emitted event reaches `TriggerEpisodeRunner.handleTriggerEnded` through the 1.x queue
- [x] 4.4 Update the KDoc on `DeviceMonitorPool` and `MonitorLockKeeper` to describe the reason-based hold and the
  key-scoped rebuild; verify by reading both files against the shipped behaviour (no changelog comments)

## 5. Integration verification

- [x] 5.1 Run `./gradlew compileDebugKotlin`, `./gradlew testDebugUnitTest` and `./gradlew lintDebug` from the
  project root and fix every warning introduced; record the test totals in the commit body
- [ ] 5.2 On-device gate (blocks archive, Verso + screen off): with one device set to in-app motion analysis, leave
  the phone idle with the screen off for 30+ minutes, confirm the analyzer's per-minute health line keeps reporting
  the configured poll interval and achieved fps rather than multi-minute gaps, confirm a fast subject crossing
  the view produces a recording, and confirm `dumpsys power` shows `EntryRecorder::MonitorWakeLock` with its
  timeout moving forward across a renewal tick rather than lapsing (this is the assumption the whole renewal
  cadence rests on: re-acquiring a held, non-reference-counted wake lock replaces its deadline)
- [ ] 5.3 On-device gate: disable in-app analysis on the last analyzing device with no `keepLocksHeld` device and
  confirm the locks are dropped at the next idle check (`dumpsys power` shows no `EntryRecorder::MonitorWakeLock`
  **and** `dumpsys wifi` shows no `EntryRecorder::MonitorWifiLock` - the radio half is the one a stacked reference
  count keeps, and the wake lock's timeout alone would not reveal it), then re-enable it and confirm the hold
  returns without restarting the service
- [ ] 5.4 On-device gate: edit an unrelated device setting (ring duration) while a subject stands in view and confirm
  detection continues without a rebuild gap; then edit the snapshot path and confirm the analyzer rebuilds and takes
  effect without a service restart
- [ ] 5.5 On-device gate: force a Started/Ended burst (walk in and out of view repeatedly for ~30 s) and confirm the
  monitor log shows the events handled strictly in arrival order, every clip finalises with a non-zero file, and no
  "Recording produced empty file, discarding" line appears
- [ ] 5.6 On-device gate: with the app left in the battery-optimization default, confirm the WARN line appears once
  in the monitor stream, and after exempting the app in system settings that it is not repeated

## Workflow follow-up

- Archive only after gates 5.2–5.6 pass; per project convention device-gated work stays open as tasks.
- Reconcile with `openspec/changes/bound-wake-wifi-locks/` (its task 2.2 soak is still open and this change
  reverses its shipped default): archive or supersede that change once 5.2/5.3 pass, so the two lock postures do
  not both stay in `openspec/changes/`.
- Update `README.md` manually after ship: the power-posture change for analyzing deployments is human-facing.
- Split into two atomic commits if convenient (queue/episode fixes first, then lock policy + diagnostic), using the
  project commit format with the `[x]` count matching the subject.
