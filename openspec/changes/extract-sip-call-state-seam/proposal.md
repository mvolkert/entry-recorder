# Proposal

## Why

`sip.SipCallManager` holds the call-screen state machine and the registration-probe decision rules, but they
sit inside a Linphone `CoreListenerStub` reading `Call.State` / `RegistrationState`, next to `Factory`/`Core`
lifecycle and `AudioManager` routing. Per the Device-Free Deterministic Testing Convention we do not mock the
native Linphone core. This change pulls the decision logic — the state transition and its side-effect intents,
the probe-input validation, and the registration-answer mapping — into pure seams, so the exact behaviour the
remediation plan Phase 5 wanted covered (the state machine) is unit-testable without a device or native types.
Calling behaviour does not change, so there is no spec delta.

## What Changes

- New pure `sip/SipCallReducer.kt`: `SipCallEvent` / `SipCallEffect` / `SipReducerResult` and
  `SipCallReducer.reduce(...)`, encoding the rules from `coreListener.onCallStateChanged` — ENDED and ERROR
  stay observable (fresh state, not overwritten) and both arm the idle reset; only an incoming call reports;
  only connect routes audio.
- New pure `sip/SipProbeSeams.kt`: `SipProbeInputValidator.validate(Device)` returning `SipProbeInput`
  (`Missing` / `Ready`) and `SipProbeOutcomeMapper.toOutcome(SipRegistrationEvent, ...)` mapping the
  registrar answer to `SipProbeResult`. `SipCallTiming.DEFAULT_SIP_PORT` becomes the single default-port source.
- New device-gated `sip/SipCallMapper.kt` isolating the only native reads: `Call.State? -> SipCallEvent?`,
  `shouldAccept(Call.State?)`, and `RegistrationState? -> SipRegistrationEvent`.
- `SipCallManager` listener, `acceptCall`, and `probeRegistration` delegate to the seams; threading
  (`Dispatchers.Main.immediate`, `NonCancellable` teardown, `probeLock`, port rotation) and side-effect timing
  are unchanged.

## Impact

- Behaviour-neutral refactor of the SIP state machine and probe decision path; `compileDebugKotlin` +
  `testDebugUnitTest` green.
- `Factory`/`Core` lifecycle, audio routing, and real registrar probes stay device-gated, documented as out of scope.
