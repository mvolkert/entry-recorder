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
  - Partially verified on the emulator (x86_64 Pixel_8 / API 37, 2026-11-03): the renewal clause needed no scene and
    passed, the soak and the subject did not run. `dumpsys power` still reported
    `EntryRecorder::MonitorWakeLock ACQ=-14m46s396ms` at 02:33:56 for the acquire at 02:19:09.388 — 4m46s past the
    10-minute window — with no `REL` line, and the monitor stream showed no `onStartCommand`, `ConnectionState` or
    battery line after 02:19:09.411, so `reconcile()` never ran and only the 5-minute tick could have moved the
    deadline. No negative control exists: every observable hold has a live reason, so this cannot show the platform
    releasing an unrenewed window. The health line over that stretch read `poll 1500ms, 0.6 fps achieved
    (configured 5)` — the idle back-off, not a gap — so the configured-poll-interval clause stays open with the
    30+ minute screen-off soak and a fast subject on Verso hardware.
- [x] 5.3 On-device gate: disable in-app analysis on the last analyzing device with no `keepLocksHeld` device and
  confirm the locks are dropped at the next idle check (`dumpsys power` shows no `EntryRecorder::MonitorWakeLock`
  **and** `dumpsys wifi` shows no `EntryRecorder::MonitorWifiLock` — the radio half is the one a stacked reference
  count keeps, and the wake lock's timeout alone would not reveal it), then re-enable it and confirm the hold
  returns without restarting the service
  - Emulator-verified (x86_64 Pixel_8 / API 37, 2026-11-03): one service instance, three cycles —
    ACQ 02:06:23.645 → REL 02:12:12.430 → ACQ 02:13:30.619 → REL 02:18:12.073 → ACQ 02:19:09.388 — as
    in-app analysis was disabled and re-enabled on the last analyzing device, and `dumpsys wifi` reported
    no `EntryRecorder::MonitorWifiLock` during either released window: the radio half is the clause a wake
    lock's timeout cannot prove, and it is clean. `IntercomMonitorService creating…` appears once (02:06:11)
    with no `onDestroy` between a REL and the ACQ that follows, so the hold returned without a restart. Both
    releases ran twice over: once with a second non-analyzing device present and once after that device was
    deleted, the analyzing device being then the only remaining row — `deviceCount` counts rows, so neither
    case tore the service down, and neither device ever opted into `keepLocksHeld`.
- [x] 5.4 On-device gate: edit an unrelated device setting (ring duration) while a subject stands in view and confirm
  detection continues without a rebuild gap; then edit the snapshot path and confirm the analyzer rebuilds and takes
  effect without a service restart. While the same analyzer is surviving, toggle that device's alert flags and confirm
  the next motion event routes by the new settings rather than the ones in force when its analyzer was constructed
  (task 4.5)
  - Emulator-verified (x86_64 Pixel_8 / API 37, 2026-11-03; the device clock runs an hour behind the host): one
    service instance, pid 8277 throughout. The subject was a host-side fake camera — a 640x480 two-tone frame
    alternating against a flat one every poll, proven by the health line reading `peak change 50%`.
  - Ring duration 60 → 75 saved 23:05:19.2 with the episode already open: health lines at 23:04:31, 23:05:31,
    23:06:32 and 23:07:32 hold the 60s cadence across the write, `Analyzing EmuA` appears zero times in a window
    cleared at 23:04:11 and the episode never closed, so detection continued with no rebuild gap.
  - Snapshot path `/api/camera/snapshot` → `/alt/camera/snapshot` saved 23:08:09 rebuilt the analyzer 1.1s later
    (`Analyzing EmuA at http://127.0.0.1:8123/alt/camera/snapshot?width=1280&height=720`, that path served), with
    no `IntercomMonitorService creating` and no `onDestroy` in the window; restoring the path repeated it at
    23:15:40.923. The gate text does not say what that rebuild costs: it ended the in-flight episode
    (23:08:10.182) and a new one started 1.6s later (23:08:11.748), so a path edit truncates a live episode even
    though the service survives it.
  - Alert flags: the instance built at 23:08:10 was constructed with `wakeOnMotion` on and routed
    (`START … IncomingCallActivity` 23:08:11.947). Turning the flag off (saved 23:10:44) left `Analyzing EmuA` at
    one and the next episode (23:11:37.488) raised no call screen; turning it back on (saved 23:12:38) left the
    count at one and the next episode (23:13:19.462) launched the call screen 32ms later. One instance, both
    directions — that is the live-row hand-over task 4.5 defers here.
  - Not covered: the notification leg cannot be counted, because a fixed per-device id re-posts in place, so
    routing was read on the call-screen launch alone; the recording leg and the remote relay went unchecked; and a
    two-tone PNG exercises the scoring threshold, not a camera's optics or codec.
- [x] 5.5 On-device gate: force a Started/Ended burst (walk in and out of view repeatedly for ~30 s) and confirm the
  monitor log shows the events handled strictly in arrival order, every clip finalises with a non-zero file, and no
  "Recording produced empty file, discarding" line appears
  - Emulator-verified (x86_64 Pixel_8 / API 37, 2026-11-03, the rig and the stand-in subject of 5.4, one service
    instance, pid 3978): two bursts. At the shipped 20s post-record the subject crossed four times and the stream
    alternates `Motion start on EmuA` / `Motion end on EmuA` with its handling line 0-1ms later every time —
    `Received MotionOnDeviceStarted` / `...Ended`, no inversion — and the third start folded into the clip armed by
    the second (`Device 1 is already recording a MOTION clip`) instead of truncating it. Both clips finalised
    non-zero: id=5 at 202,436 bytes and id=6 at 326,350 bytes.
  - Post-record was then dropped to 2s so that an arm and a stop genuinely compete: eight cycles over 98s emitted
    and handled 8 starts and 8 ends in strict arrival order and produced 8 clips (ids 7-14, 58,450 to 187,260
    bytes) with no `discarding` line and no collision. `run-as` on the private recordings directory finds all
    fourteen files at exactly the logged sizes.
  - Caveats: only the in-app analysis path is reachable on this rig — the device row reads
    `Camera events (ring/motion/noise): offline` — so the same burst arriving over the 2N event transport or SIP
    went unexercised, and nothing the rig can serve creates a genuine 0-byte file, so the absence of the
    `discarding` line carries weight only where the old bug actually bit: the fold-in above.
- [x] 5.6 On-device gate: with the app left in the battery-optimization default, confirm the WARN line appears once
  in the monitor stream, and after exempting the app in system settings that it is not repeated
  - Emulator-verified (same run, 2026-11-03): left non-exempt, the WARN line appeared exactly twice across at least
    six device-list re-emits — once per service instance (02:00:55, 02:06:23) — so it is a state-change line
    and not a per-reconcile one. Exempting the package with
    `dumpsys deviceidle whitelist +io.github.mvolkert.entryrecorder`, the adb equivalent of the settings
    toggle, logged `Battery optimization exemption granted…` once at 02:16:47.632 and nothing further on later
    writes (`warn=2`, `exemption info=1`). The settings screen itself went unexercised, and an OEM battery
    manager that ignores the whitelist is beyond an emulator's reach.

## Workflow follow-up

- Archive only after gates 5.2–5.6 pass; per project convention device-gated work stays open as tasks.
- Reconciled with `openspec/changes/bound-wake-wifi-locks/` on 2026-11-04: superseded and archived to
  `openspec/changes/archive/2026-11-04-bound-wake-wifi-locks/` with `--skip-specs`, its open soak (task 2.2) carried
  into gate 5.2 rather than closed, so the two opposite lock postures no longer both sit active in
  `openspec/changes/`.
- `README.md` carries the human-facing power-posture paragraph for analyzing deployments (added 2026-11-04, before
  archive; the as-built wording still has to be checked against `openspec/specs/` when this change syncs).
- Task 4.5 deliberately keeps `MotionAnalysisKey` narrow, and 4.5 shipped only that identity half. Whether the
  polling loop's **motion scoring** may keep running with a stale effective fps / frame count lives in the analyzer's
  `start()` and is **not** in this change: the change that owned it,
  `add-wake-lock-while-on-device-motion-scoring`, has been deleted from `openspec/changes/` and was never committed,
  so there is nothing to hand it to yet. Re-open it as its own change.
- Split into two atomic commits if convenient (queue/episode fixes first, then lock policy + diagnostic), using the
  project commit format with the `[x]` count matching the subject.
