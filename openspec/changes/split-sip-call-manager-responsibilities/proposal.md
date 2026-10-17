# Proposal

## Why

`sip.SipCallManager` (556 lines) mixes four concerns in one file and one class: shared model types
(`CallUiState`, `SipSessionState`, `SipMissingField`, `SipProbeResult`), Linphone `Factory`/`Core` lifecycle
with native logging config, per-device account synchronization (`synchronizeDevices`, `applyDevice`,
`retireDevice`, `addPbxAccount`, `applyInboundPort`), and active-call controls (`acceptCall`, mute, speaker
routing, terminate). The completed `extract-sip-call-state-seam` change already pulled the pure decision
logic (reducer, probe seams, mapper) out of this class; what remains is the structural split of the still
entangled types and the device-account sync strategy. Calling behaviour does not change, so there is no
spec delta.

## What Changes

- New `sip/SipModels.kt`: `CallUiState`, `SipSessionState`, `SipMissingField`, `SipProbeResult` move out of
  the manager's file (declarations unchanged, just relocated).
- New `sip/SipDeviceAccountSync`: owns the per-device account reconciliation — `synchronizeDevices`,
  `applyDevice`, `retireDevice`, `addPbxAccount`, `applyInboundPort` — operating on an injected `Core`, so
  the sync policy becomes testable against a fake `Core` handle without a registrar.
- `SipCallManager` retains only: `Factory`/`Core` lifecycle and `initialize()`/`destroy()`, the
  `CoreListenerStub` wiring that delegates to the existing `SipCallReducer`/`SipCallMapper` seams, `configureLogging`,
  idle reset, and active-call controls.
- No signature, threading, or Linphone-call-order changes; `LinphoneAbi` gating and the account-API
  migration already archived stay untouched.

Out of scope: audio-routing behaviour (`routeAudioToSpeaker` internals), Linphone SDK version or API usage
changes, the already-extracted reducer/probe seams, per-device account data model (Room schema untouched).

## Capabilities

### New Capabilities

- None — pure refactor, `skip_specs: true`.

### Modified Capabilities

- None — no requirement changes to the `sip` spec.

## Impact

- `app/src/main/java/io/github/mvolkert/entryrecorder/sip/` — two new files, manager shrinks to ~300 lines.
- On-device verification: PBX/Fritz!Box registration with two enabled SIP devices (one add, one edit, one
  removal) to prove account sync order against the real registrar is unchanged.
- `compileDebugKotlin` + `testDebugUnitTest` green; existing `SipCallMapper`/reducer tests untouched.
