# Tasks

## 1. Reducer seam

- [x] 1.1 New pure `sip/SipCallReducer.kt`: `SipCallEvent` / `SipCallEffect` / `SipReducerResult` and `reduce(...)`, encoding the ENDED/ERROR stay-observable + idle-reset, incoming-only-report, connect-only-audio rules.

## 2. Probe seams

- [x] 2.1 New pure `sip/SipProbeSeams.kt`: `SipProbeInputValidator.validate(Device)` → `SipProbeInput` (Missing/Ready) and `SipProbeOutcomeMapper.toOutcome(...)`.
- [x] 2.2 `SipCallTiming.DEFAULT_SIP_PORT` becomes the single default-port source.

## 3. Native mapper + delegation

- [x] 3.1 New device-gated `sip/SipCallMapper.kt`: `Call.State? -> SipCallEvent?`, `shouldAccept`, `RegistrationState? -> SipRegistrationEvent`.
- [x] 3.2 `SipCallManager` listener, `acceptCall`, and `probeRegistration` delegate to the seams; threading and side-effect timing unchanged.

## 4. Test

- [x] 4.1 `SipCallReducerTest`: the four transitions + effect sets, terminal states not collapsing to idle, error surfacing its reason, mute/speaker preservation on connect and reset on terminal.
- [x] 4.2 `SipProbeMappingTest`: validator field priority + port/password defaults; outcome mapper Ok/Failed/Cleared/blank-message/Other.

## 5. Verification

- [x] 5.1 `compileDebugKotlin` green.
- [x] 5.2 `testDebugUnitTest` green.
- [x] 5.3 `lintDebug` green.
