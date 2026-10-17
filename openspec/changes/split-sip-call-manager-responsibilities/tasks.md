# Tasks

## 1. Model type relocation

- [ ] 1.1 Move `CallUiState`, `SipSessionState`, `SipMissingField`, and `SipProbeResult` (with their KDoc) into
      new `sip/SipModels.kt`, same package; verify: `.\gradlew.bat compileDebugKotlin` passes with no import
      changes in `ui/incoming`, `ui/settings`, `SipRegistrar`, or `SipCallReducer` (same package, so their
      `…sip.CallUiState`-style imports must still resolve).

## 2. Account-sync extraction

- [ ] 2.1 Create `sip/SipDeviceAccountSync` owning `managed`/`ManagedDevice` plus `synchronize`, `applyDevice`,
      `retireDevice`, `addPbxAccount`, and `applyInboundPort` bodies moved verbatim, keeping the
      `withContext(Dispatchers.Main.immediate)` wrapper; verify: `compileDebugKotlin` passes.
- [ ] 2.2 Expose the ring-attribution query (`deviceIdForRemoteHost` / `deviceIdForDialedUser`) and `clear()`
      from the sync class; rewire `SipCallManager.resolveRingingDeviceId` and `destroy()` to use them; verify:
      `compileDebugKotlin` passes and `resolveRingingDeviceId` keeps its two-signal match order with the
      drop-if-no-match warning log intact.
- [ ] 2.3 Route `probeRegistration`'s account setup through `accountSync.addPbxAccount(probe, …)`; verify:
      `compileDebugKotlin` passes and the probe body shows no other change in the diff.

## 3. Manager slim-down

- [ ] 3.1 Confirm `SipCallManager` now holds only core lifecycle, `configureLogging`, the `coreListener`,
      session state + idle reset, call controls, and probing; verify: `compileDebugKotlin`,
      `.\gradlew.bat testDebugUnitTest`, and `.\gradlew.bat lintDebug` pass, existing sip tests unchanged
      and green, and the file is roughly 300 lines.

## 4. On-device acceptance gate (blocks archive)

- [ ] 4.1 Against the real PBX/Fritz!Box: enable two SIP devices, then edit one and disable the other; confirm
      registrations reconcile in the same order as before (no stale bound account on the PBX) and an inbound
      call still opens the correct device's call screen.
- [ ] 4.2 Run a registration probe from the device form while the monitor service holds a live core; confirm
      the probe answers on its rotating local port and the live registration is untouched.
