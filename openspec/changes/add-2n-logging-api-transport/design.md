# Design

## Context

See proposal.md - Why. The current `TwoNIPVersoDevice` opens an SSE subscribe then, on content-type
failure, starts a polling fallback against two endpoints that do not exist on this firmware. `handleRaw2NEvent`
already decodes `event` + `params` (`triState`, the `KeyPressed` release deny-list) and log entries carry
the same names, so most of the decode transfers.

## Goals / Non-Goals

**Goals:**
- Working camera motion/noise/call events on the Verso over the real transport.
- Keep the single `requestedEventNames()` rule as the one source for what is subscribed.

**Non-Goals:**
- Not the cheaper "delete the HTTP event path" alternative (owner chose the full Logging API).
- No change to the in-app motion analyzer or the SIP INVITE ring path.

## Decisions

- Subscribe/pull/unsubscribe loop with a per-device channel and expiry re-subscribe, behind the same
  authenticated client. Chosen over long-lived SSE because the firmware has no SSE at all.
- Decode adapts to the real `/api/log/pull` body once captured, reusing `handleRaw2NEvent`.

## Risks / Trade-offs

- [Real `pull` body is unverified] → the device gate must capture one before the decode is finalized;
  until then the field mapping is provisional.
- [Endpoint refused for the account] (`code 7` HTTP / `401 code 9` HTTPS) → the 2N-side owner gate is a
  prerequisite; if it still refuses after enabling the Logging service, the decision reverts to
  delete-the-HTTP-path and this closes as wont-fix-on-this-firmware.
- [24/7 pull load on a ≈5.9 req/s ceiling] → accept; mitigate with the trigger gate (no subscription
  when nothing asked) and a bounded poll interval.

## Migration Plan

Land behind the existing monitor session start; no Room change. Rollback = revert to prior transport
(the prior path was already non-functional, so rollback is a no-op for delivered events).

## Open Questions

- Exact `/api/log/pull` JSON shape for a doorbell press (resolved by the device gate, not here).
