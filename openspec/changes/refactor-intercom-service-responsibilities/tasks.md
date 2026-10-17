# Tasks

## 1. Lock policy extraction

- [ ] 1.1 Create `service/MonitorLockKeeper` moving `acquireLocks`, `continuousLockHold`, `holdLocksFor`,
      `releaseLocksIfIdle`, `releaseLocks`, and the safety-net release job verbatim; the service delegates.
      Verify: `./gradlew compileDebugKotlin` (Windows: `.\gradlew.bat compileDebugKotlin`) passes and the
      service no longer references `PowerManager`/`WifiManager` directly.

## 2. Device/analyzer pool extraction

- [ ] 2.1 Create `service/DeviceMonitorPool` absorbing `activeDevices`, `activeMotionAnalyzers`, and the
      `updateMonitoredDevices` body (including removal-time cleanup of registry, coordinator, and snapshot
      client); verify: `compileDebugKotlin` passes, cleanup call order matches the original line-for-line in
      the diff.
- [ ] 2.2 Pass the post-record-stop cancellation into the pool as a lambda so `pendingPostRecordStops` is not
      touched from two classes; verify: `compileDebugKotlin` passes.

## 3. Trigger-episode extraction

- [ ] 3.1 Create `service/TriggerEpisodeRunner` owning `handleTriggerStarted`, `handleTriggerEnded`,
      `relayMotionAlertToRemote`, `schedulePostRecordStop`, `cancelPostRecordStop`, and
      `pendingPostRecordStops`, delegating policy to `TriggerCoordinator`; verify: `compileDebugKotlin` and
      `testDebugUnitTest` pass with existing `TriggerCoordinator` tests untouched.

## 4. Service thinning and cleanup

- [ ] 4.1 Reduce `IntercomMonitorService` to lifecycle, injection, `startForeground` sequencing, listener
      wiring, and delegation; move now-obsolete why-comments onto the code they describe in the
      collaborators; verify: `compileDebugKotlin` and `lintDebug` pass, file is under ~250 lines.
- [ ] 4.2 Run `.\gradlew.bat testDebugUnitTest` and `.\gradlew.bat lintDebug` for the whole app module and
      fix anything this change introduced.

## 5. On-device acceptance gate (blocks archive)

- [ ] 5.1 On a phone with the Verso camera: enable monitoring, fire a doorbell ring and a motion trigger, and
      confirm recording, SIP call, remote motion alert, and post-record stop timings behave as before.
- [ ] 5.2 Screen-off idle period with and without a keep-locks device: confirm wake/WiFi locks are released
      when idle and held during recordings, matching pre-refactor `adb shell dumpsys power` behaviour.
