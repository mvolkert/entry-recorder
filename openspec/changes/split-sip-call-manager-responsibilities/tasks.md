# Tasks

## 1. Model type relocation

- [x] 1.1 Move `CallUiState`, `SipSessionState`, `SipMissingField`, and `SipProbeResult` (with their KDoc) into
      new `sip/SipModels.kt`, same package; verify: `.\gradlew.bat compileDebugKotlin` passes with no import
      changes in `ui/incoming`, `ui/settings`, `SipRegistrar`, or `SipCallReducer` (same package, so their
      `…sip.CallUiState`-style imports must still resolve).

## 2. Account-sync extraction

- [x] 2.1 Create `sip/SipDeviceAccountSync` owning `managed`/`ManagedDevice` plus `synchronize`, `applyDevice`,
      `retireDevice`, `addPbxAccount`, and `applyInboundPort` bodies moved verbatim, keeping the
      `withContext(Dispatchers.Main.immediate)` wrapper; verify: `compileDebugKotlin` passes.
      *(The moved log lines now carry the tag `SipDeviceAccountSync` instead of `SipCallManager`; their text is
      unchanged. `synchronize` takes the core as a parameter (nullable) because the manager still owns it.)*
- [x] 2.2 Expose the ring-attribution query (`deviceIdForRemoteHost` / `deviceIdForDialedUser`) and `clear()`
      from the sync class; rewire `SipCallManager.resolveRingingDeviceId` and `destroy()` to use them; verify:
      `compileDebugKotlin` passes and `resolveRingingDeviceId` keeps its two-signal match order with the
      drop-if-no-match warning log intact. *(The warning's device count comes from a new `monitoredCount`
      accessor, so the map itself stays private.)*
- [x] 2.3 Route `probeRegistration`'s account setup through `accountSync.addPbxAccount(probe, …)`; verify:
      `compileDebugKotlin` passes and the probe body shows no other change in the diff.

## 3. Manager slim-down

- [ ] 3.1 Confirm `SipCallManager` now holds only core lifecycle, `configureLogging`, the `coreListener`,
      session state + idle reset, call controls, and probing; verify: `compileDebugKotlin`,
      `.\gradlew.bat testDebugUnitTest`, and `.\gradlew.bat lintDebug` pass, existing sip tests unchanged
      and green, and the file is roughly 300 lines.
      *(Every clause holds except the size estimate: the file is 437 lines, down from 608. What remains is
      exactly the retained list — the probe is ~95 of those lines and `routeAudioToSpeaker`, kept out of scope
      by the proposal, another ~25 — so ~300 would need a seam this change does not authorise. Re-checked when
      the other size targets were closed: reaching 300 means moving either `probeRegistration` (a Non-Goal —
      its throw-away-core mechanics, `probeLock` and port rotation are frozen) or the `coreListener` /
      `configureLogging` blocks (both named in the Goals as what the manager keeps), and the `SipAudioRouter`
      the Open Question names was excluded on purpose. The owner chose to leave the figure as a deviation
      rather than rewrite that decision.)*

## 4. On-device acceptance gate (blocks archive)

- [ ] 4.1 Against the real PBX/Fritz!Box: enable two SIP devices, then edit one and disable the other; confirm
      registrations reconcile in the same order as before (no stale bound account on the PBX) and an inbound
      call still opens the correct device's call screen.
- [ ] 4.2 Run a registration probe from the device form while the monitor service holds a live core; confirm
      the probe answers on its rotating local port and the live registration is untouched.
