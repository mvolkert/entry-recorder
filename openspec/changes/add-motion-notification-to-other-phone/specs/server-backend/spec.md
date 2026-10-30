# Spec Delta

## ADDED Requirements

### Requirement: Motion-alert relay dispatch
The server SHALL deliver a relayed motion alert to the recipient push endpoint the app supplies, keeping
the alerting phone's server as its only outbound destination, and SHALL judge a caller-supplied endpoint
before dialling it.

#### Scenario: Accepted and delivered
- **WHEN** an authenticated app posts a motion alert whose recipient is an acceptable endpoint and the
  push distributor answers with a success status
- **THEN** the server sends the alert body to that endpoint and reports the alert as delivered

#### Scenario: An endpoint the server refuses to dial
- **WHEN** the recipient is not a usable absolute URL, embeds credentials, or is a cleartext or
  non-routable target while insecure endpoints are not explicitly allowed
- **THEN** the server answers `400` with the reason and posts nothing

#### Scenario: The remote end refuses or is unreachable
- **WHEN** the push endpoint answers a non-success status, times out, or cannot be reached
- **THEN** the server answers `502` so the app records a missed delivery, rather than reporting a relay
  that never happened

#### Scenario: No endpoint secret reaches a log
- **WHEN** a relay is logged, whether it succeeded, was refused, or failed
- **THEN** the recipient appears as scheme and host only, and an unparsable recipient is named by a fixed
  marker instead of being echoed
