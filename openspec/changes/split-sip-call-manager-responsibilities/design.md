# Design

## Context

See proposal.md — Why. `SipCallManager` is a `@Singleton` implementing the `SipRegistrar` interface; its Linphone
`Core` lives for the whole process. Two facts constrain the split: the `managed` device→account map is read by
three callers (`synchronizeDevices` writes it, `resolveRingingDeviceId` reads it for ring attribution, `destroy`
clears it), and `addPbxAccount` is shared between the live sync path and `probeRegistration` so a green test
cannot diverge from a saved account. All account mutation runs on `Dispatchers.Main.immediate` because the SDK
core loop is main-thread and not thread-safe.

## Goals / Non-Goals

**Goals:**
- One class owns the account reconciliation surface (`managed`, apply/retire/port); the manager owns core
  lifecycle, listener, session state, controls, and probing.
- The four shared model types live in their own file so `sip` consumers stop importing them via the manager.
- `addPbxAccount` stays a single implementation reachable by both sync and probe.

**Non-Goals:**
- No change to the `SipRegistrar` interface, `probeRegistration`'s throw-away-core mechanics, its `probeLock`
  and port rotation, or the `Main`-vs-`Main.immediate` teardown workaround.
- No change to reducer/mapper seam behaviour (already extracted).

## Decisions

- **`SipDeviceAccountSync` holds `managed` and `ManagedDevice`.** It exposes:
  `suspend fun synchronize(core, devices)` (the moved body), `fun accountFor(call)`-style lookups the manager
  uses in `resolveRingingDeviceId` (by remote host / dialed user), `fun addPbxAccount(core, …)` (internal,
  called by both `applyDevice` and the manager's probe via a delegate), and `fun clear()` for `destroy`.
  Alternative — keep `managed` in the manager and pass the map in — rejected: it would leave the reconciliation
  logic split across two classes reading one mutable map, which is the scatter we are removing.
- **The sync class is constructed by the manager, not Hilt-injected.** It is stateless apart from `managed` and
  only ever runs against the manager's core; a graph singleton would invite a second core reference.
- **`addPbxAccount` moves into the sync class** and `probeRegistration` calls `accountSync.addPbxAccount(probe, …)`
  on the throw-away core, preserving the "one implementation for probe and save" invariant.
- **`SipModels.kt` is a pure relocation** — no new wrapper types; imports in `SipRegistrar`, screens and
  ViewModels that already reference these types update to the new file (same package, so mostly no import churn).

## Risks / Trade-offs

- [Ring attribution loses access to `managed`] → the sync class exposes an explicit query API rather than the
  raw map, so `resolveRingingDeviceId` becomes a call, not a field read; behaviour identical.
- [Main-thread contract broken when the map moves] → `synchronize` stays `suspend` + `withContext(Main.immediate)`
  inside the sync class; the manager's callers keep their existing dispatch.
- [Probe and live sync drift on `Factory.instance()` usage] → both go through the one `addPbxAccount`; covered by
  the existing probe path staying byte-for-byte identical in the diff.

## Migration Plan

Mechanical extraction in three steps (types → sync class → probe delegate). No data migration; rollback = revert.

## Open Questions

- None. Whether `routeAudioToSpeaker` eventually moves to an `SipAudioRouter` is out of scope for this change
  (the audit flags the file, not the audio concern, as the top problem).
