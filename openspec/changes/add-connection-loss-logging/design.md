# Design

## Context

Connection health is already modelled as data in several places, but the *logging* of loss is inconsistent:

- `HttpSnapshotClient` tracks `consecutiveFailures` and derives `snapshotQuality` (ONLINE/DEGRADED/OFFLINE) but
  only logs a warning per failed fetch; the edge-triggered recovery line is emitted from `LocalCaptureLoop`
  (`RecorderEvent.Connection`) with the wording "Snapshot endpoint for … is $quality".
- `MonitorEventHandler` handles `IntercomEvent.ConnectionState` and already logs ONLINE at INFO / others at WARN,
  but only for the two capabilities routed through it (DEVICE_EVENTS, SNAPSHOT).
- `TwoNIPVersoDevice` logs Logging API subscribe/pull errors ad hoc; there is no explicit "channel lost / recovered"
  edge.
- `ServerRecordingClient` logs `Failed to connect to Python server` on some paths, and `TriggerEpisodeRunner`
  logs a motion-alert delivery failure, but reachability loss/recovery is not standardised.
- SIP registration state lives behind the Linphone callbacks in `SipCallManager`/`SipDeviceAccountSync`;
  registration loss is not surfaced as a log today.

Motivation is in proposal.md. This design only settles how the transitions are detected and worded.

## Goals / Non-Goals

**Goals:**
- One WARN on entering a loss state and one INFO on returning to healthy, per source, per device/server.
- A shared, greppable wording so `grep`-ing the log stream reconstructs any one link's outage timeline.
- Reuse existing edge-triggered state so recovery-vs-blip semantics stay identical to today's UI dots.

**Non-Goals:**
- No new persisted connection-state model, no Room schema change, no UI surface for these logs.
- No change to retry counts, backoff, thresholds (`DEGRADED_AFTER_POLLS`, `OFFLINE_AFTER_POLLS`) or the
  connection-quality state machine itself.
- Not writing logs to a file (that is `add-timber-file-logging`); this stays on the existing `Log` surface.

## Decisions

- **Edge-triggered, not per-attempt.** Each source already holds enough state (failure streak, subscription
  flag, registration status) to fire only on a transition. Recovery lines carry an outage duration or missed-count
  so operators see magnitude without per-poll spam. Alternative (log every failure) rejected: it drowns the stream
  at the ~6 req/s snapshot ceiling.
- **Standard wording per source.** Adopt one template per transport, e.g. `"<source> lost: <name> (<reason>)"` on
  WARN and `"<source> recovered: <name> (down <duration>)"` on INFO, where `<source>` ∈
  {`snapshot`, `camera-events`, `sip-registration`, `python-server`}. Centralising the strings keeps them
  consistent across classes; a shared helper is optional and left to the implementer.
- **Keep logging on `android.util.Log` with the existing tags.** Do not introduce Timber here (change 2 owns
  that). Reasons: this change must be verifiable independent of the file-logging capability, and the JVM unit-test
  `isReturnDefaultValues` setup already tolerates `Log`.
- **SIP recovery detection.** Compare the incoming Linphone registration state against the last state seen per
  account so only Registered→(Failed/Unregistered)→Registered edges log; the user-initiated test probe keeps its
  own non-logged result path.

## Risks / Trade-offs

- [Duplicate logging where a source already emits a line (snapshot path)] → Consolidate onto the standard wording
  in one place rather than adding a second line; verify by reading the class before editing.
- [Recovery "down duration" needs a timestamp of the loss edge] → Store the loss timestamp alongside existing
  per-device maps (mirrors `lastFrameAtMs`); do not add a new long-lived structure beyond a single `Long` per id.
- [Blips near a threshold could log loss then immediate recovery] → Reuse the existing thresholds so behaviour
  matches what the UI dot already shows; no new sensitivity introduced.
- [SIP native callbacks / main-thread lifecycle] → Only read the registration state and log; never touch core
  teardown from the callback (existing `sip` spec constraint).

## Migration Plan

Additive logging only; no data or API migration. Rollback is reverting the log statements — observable behavior
(state semantics, retry timing) is unchanged, so no downstream consumer can break.

## Open Questions

- None blocking. Whether the standard wording lives in a shared string helper or per-class constants can be
  decided during implementation.
