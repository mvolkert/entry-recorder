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
- [x] 1.3 Give each dispatched event its own failure boundary inside the queue's collector, so one event that throws
  cannot cancel the single collecting coroutine and end trigger handling for the rest of the process lifetime. A
  `CancellationException` is judged by origin rather than type: the scope is a `SupervisorJob`, so one raised while
  the scope is still active belongs to the event being handled, is logged and leaves the collector running, and only
  an inactive scope rethrows. Teardown `close()`s the channel, which makes `submit()`'s failure branch honest, and
  drains what the dead collector never read so those events are named too; the collector logs its own stop from a
  `finally`. Verify with new `MonitorEventQueueTest` cases asserting that a handler failure is logged with the event
  identity and no credential, that a per-event cancellation is logged and the following event is still handled, that
  a cancelled scope ends the collector and says so, and that `close()` reports the queued events it drops, plus
  `./gradlew compileDebugKotlin :app:testDebugUnitTest --tests "*MonitorEventQueueTest"`

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

- [x] 2.5 Make monitor teardown order-safe against the keeper's own jobs: one `shutdown()` that raises a closed
  flag, cancels the renewal loop and the armed safety release, and releases the pair, so a tick already awake when
  the service cancelled its scope cannot re-arm the untimed Wi-Fi hold after the last release. Every entry point
  that could acquire — `reconcile`, `holdFor` and the renewal tick's `renewOrRelease` — answers the flag itself
  before it touches the controller, which is what closes the race; the flag is deliberately kept out of
  `holdStillWanted()` so each refusal fails a test on its own. The jobs are awaited afterwards, under the teardown
  pass's own deadline, because joining inside the monitor would deadlock against the tick being waited out, and
  `shutdown()` plus that join run in a `finally` so a device that throws during the teardown above it cannot leave
  the pair held for the life of the process. Verify with `MonitorLockKeeperTest` cases asserting that teardown
  releases and hands back cancelled jobs, that a reconcile or `holdFor` racing it appends nothing to the
  controller's log, and that a renewal tick already awake after teardown acquires nothing; the `finally` placement
  itself is read in `IntercomMonitorService.onDestroy`, since no JVM test can construct a `Service` teardown

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
- [x] 4.5 Stop a surviving analyzer from identifying itself with a stale `Device` row: an on-device motion event must
  name the row the reconcile kept for that device, not the snapshot taken when the analyzer was constructed, or
  alert routing and the per-device record limit read flags the user has since changed. Resolve the current row as
  the event arrives in `IntercomMonitorService.onEvent`, through `DeviceMonitorPool.deviceFor`, before it is queued
  rather than when the handler reaches it — an event may sit behind another device's HTTP work — and not by widening
  `MotionAnalysisKey`, since identity fields never affect the polling loop that the key exists to protect; verify
  with a new `IntercomEventCurrentRowTest` asserting every event kind keeps its class and its own fields while only
  the row changes, that an unmonitored device keeps the row it arrived with, and that a motion event re-pointed at
  an edited row carries the edited `wakeOnMotion`. That the pool really hands over the live row is read at gate 5.4

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
  **and** `dumpsys wifi` shows no `EntryRecorder::MonitorWifiLock` — the radio half is the one a stacked reference
  count keeps, and the wake lock's timeout alone would not reveal it), then re-enable it and confirm the hold
  returns without restarting the service
- [ ] 5.4 On-device gate: edit an unrelated device setting (ring duration) while a subject stands in view and confirm
  detection continues without a rebuild gap; then edit the snapshot path and confirm the analyzer rebuilds and takes
  effect without a service restart. While the same analyzer is surviving, toggle that device's alert flags and confirm
  the next motion event routes by the new settings rather than the ones in force when its analyzer was constructed
  (task 4.5)
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
- Task 4.5 deliberately keeps `MotionAnalysisKey` narrow, and 4.5 shipped only that identity half. Whether the
  polling loop's **motion scoring** may keep running with a stale effective fps / frame count lives in the analyzer's
  `start()` and is **not** in this change: the change that owned it,
  `add-wake-lock-while-on-device-motion-scoring`, has been deleted from `openspec/changes/` and was never committed,
  so there is nothing to hand it to yet. Re-open it as its own change.
- Split into two atomic commits if convenient (queue/episode fixes first, then lock policy + diagnostic), using the
  project commit format with the `[x]` count matching the subject.
