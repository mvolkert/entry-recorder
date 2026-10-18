# Tasks

## 1. Lock policy extraction

- [x] 1.1 Create `service/MonitorLockKeeper` moving `acquireLocks`, `continuousLockHold`, `holdLocksFor`,
      `releaseLocksIfIdle`, `releaseLocks`, and the safety-net release job verbatim; the service delegates.
      Verify: `./gradlew compileDebugKotlin` (Windows: `.\gradlew.bat compileDebugKotlin`) passes and the
      service no longer references `PowerManager`/`WifiManager` directly.
      *(The two-line `continuousLockHold = …; if (…) acquire else releaseIfIdle` sequence becomes one
      `setHoldThroughIdle(holdThroughIdle: Boolean)` call rather than design.md's `holdContinuous` name; the
      release check's own evidence — pending stops, then a running local recording — is injected as the
      `workOutstanding` lambda because those two facts live in the other collaborators.)*

## 2. Device/analyzer pool extraction

- [x] 2.1 Create `service/DeviceMonitorPool` absorbing `activeDevices`, `activeMotionAnalyzers`, and the
      `updateMonitoredDevices` body (including removal-time cleanup of registry, coordinator, and snapshot
      client); verify: `compileDebugKotlin` passes, cleanup call order matches the original line-for-line in
      the diff.
      *(The pool absorbs the device/analyzer half of the body only. `sync` is `suspend` because
      `startMonitoring`/`stopMonitoring` are; the four per-device cleanup calls keep their original order.
      The SIP reconciliation, lock-policy line, `resumePersistedServerRecordings`, the `stopForeground` +
      `stopSelf()` early return and the foreground-notification update stay in the service — design.md's
      "whole body" wording contradicts its own goal that the service is the only place touching `Service`
      lifecycle and `startForeground`, and a plain class cannot call `stopSelf()`. Reported rather than
      absorbed silently.)*
- [x] 2.2 Pass the post-record-stop cancellation into the pool as a lambda so `pendingPostRecordStops` is not
      touched from two classes; verify: `compileDebugKotlin` passes.

## 3. Trigger-episode extraction

- [x] 3.1 Create `service/TriggerEpisodeRunner` owning `handleTriggerStarted`, `handleTriggerEnded`,
      `relayMotionAlertToRemote`, `schedulePostRecordStop`, `cancelPostRecordStop`, and
      `pendingPostRecordStops`, delegating policy to `TriggerCoordinator`; verify: `compileDebugKotlin` and
      `testDebugUnitTest` pass with existing `TriggerCoordinator` tests untouched.
      *(Bodies moved verbatim; the lock calls now route through `MonitorLockKeeper`. The log tag stays
      `IntercomMonitorService` in all three collaborators so one trigger is still one logcat stream.)*

## 4. Service thinning and cleanup

- [ ] 4.1 Reduce `IntercomMonitorService` to lifecycle, injection, `startForeground` sequencing, listener
      wiring, and delegation; move now-obsolete why-comments onto the code they describe in the
      collaborators; verify: `compileDebugKotlin` and `lintDebug` pass, file is under ~250 lines.
      *(Both builds pass and every why-comment travelled with the code it describes, but the size target is
      missed: 412 lines, down from 619. What remains is the retained list plus the ~48-line `DoorbellRung`
      branch — which the proposal keeps in the service because it builds the doorbell notification and the
      full-screen intent — and `applyConnectionQuality`, `handleSipRing` and the companion start/stop helpers.*
      *Following up on that: a fourth collaborator, `MonitorEventHandler`, now owns the `onEvent` fan-out — the
      whole `when` (doorbell record → notification → call-screen launch, the six motion/noise episode calls and
      the `ConnectionState` routing including `applyConnectionQuality`). Bodies moved verbatim; only three
      mechanical changes: `this@IntercomMonitorService` becomes an injected `context`, `return@launch` becomes
      `return` (the `when` was the last statement of the launched block), and the handler owns no scope — the
      service keeps `override fun onEvent`, its `Log.i(… logIdentity())` line and `serviceScope.launch`, so
      design.md's "the service remains the only place that touches `Service` lifecycle, `startForeground`, and
      listener wiring" still holds and nothing can outlive the service. The service is 288 lines, still over
      ~250: the residue is `onCreate` (channel creation, the `startForeground` ordering and its 5s-window
      comment), `onStartCommand`, `updateMonitoredDevices`' notification refresh and `stopSelf()` branch, the
      four lazy collaborator blocks, `handleSipRing` and the companion start/stop helpers — all of it lifecycle
      or wiring, which the change's own goals pin here. Closing the last 38 lines would reverse Decision 2 or
      Goal 3, so it is left as a deviation rather than done silently.)*
- [x] 4.2 Run `.\gradlew.bat testDebugUnitTest` and `.\gradlew.bat lintDebug` for the whole app module and
      fix anything this change introduced.
      *(227 tests / 34 suites / 0 failures, unchanged from the pre-change baseline; `lintDebug` is
      BUILD SUCCESSFUL with no finding on the five files except one relocated `WakelockTimeout` warning on
      the unchanged `wake.acquire()` call, re-confirmed after the `MonitorEventHandler` extraction. Bounding
      that acquire is already tracked as the separate `bound-wake-wifi-locks` change; adding a timeout here
      would change lock-hold behaviour, which this change's "zero behaviour delta" goal forbids.)*

## 5. On-device acceptance gate (blocks archive)

- [ ] 5.1 On a phone with the Verso camera: enable monitoring, fire a doorbell ring and a motion trigger, and
      confirm recording, SIP call, remote motion alert, and post-record stop timings behave as before.
- [ ] 5.2 Screen-off idle period with and without a keep-locks device: confirm wake/WiFi locks are released
      when idle and held during recordings, matching pre-refactor `adb shell dumpsys power` behaviour.
