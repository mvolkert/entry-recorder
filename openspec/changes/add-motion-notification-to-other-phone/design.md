# Design

## Context

See proposal.md - Why. The monitoring phone detects motion locally; the question is only how to reach a
second phone.

## Goals / Non-Goals

**Goals:** deliver a motion alert to one configured remote phone.
**Non-Goals:** not a general multi-user push system; no change to on-device motion detection.

## Decisions

- **Transport (owner, 2026-10-16):** a pluggable `MotionAlertTransport` seam with an ordered delivery
  ladder — (a) **primary**: relay through the existing authenticated Python server (already the
  event-transport backend, works behind NAT); (b) **vendor-free push**: UnifiedPush when the recipient
  phone has a distributor and the server path is unavailable; (c) **fallback**: FCM where UnifiedPush isn't
  an option. The spec requirement stays transport-agnostic; only the seam + the backend order change.
- UnifiedPush and FCM are **new dependencies** (client libraries; FCM additionally the Google services
  plugin + manifest changes). Per the project Ask-First rule they are added only on explicit approval, so
  the server-relay backend — which needs no new library — is the first deliverable.

## Risks / Trade-offs

- [New outbound permission / background delivery limits] → confirm before coding.

## Open Questions

- ~~Which transport delivers to the second phone?~~ Resolved 2026-10-16 (server relay primary; UnifiedPush
  then FCM as push fallbacks) — see Decisions.
- Are the UnifiedPush / FCM dependencies and the FCM plugin/manifest approved (Ask-First)? Until answered,
  only the server-relay backend is implementable without adding libraries.
