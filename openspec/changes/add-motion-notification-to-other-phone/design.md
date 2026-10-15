# Design

## Context

See proposal.md - Why. The monitoring phone detects motion locally; the question is only how to reach a
second phone.

## Goals / Non-Goals

**Goals:** deliver a motion alert to one configured remote phone.
**Non-Goals:** not a general multi-user push system; no change to on-device motion detection.

## Decisions

- **Undecided transport** — options: (a) via the existing Python server as a relay/push, (b) a direct
  peer notification, (c) a third-party push. Prefer (a) since the deployment already has an authenticated
  server channel.

## Risks / Trade-offs

- [New outbound permission / background delivery limits] → confirm before coding.

## Open Questions

- Which transport delivers to the second phone? Must be resolved with the owner before tasks run; the
  spec requirement above is intentionally transport-agnostic.
