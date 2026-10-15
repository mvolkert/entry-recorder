# Spec Delta

## ADDED Requirements

### Requirement: Drain device events via the 2N Logging API
The system SHALL subscribe to the 2N Logging API with the enabled event filter, periodically drain
the channel's queue via pull, release it via unsubscribe, and re-subscribe when the channel expires,
instead of relying on nonexistent `/api/event/*` or `/api/motion|noise/status` endpoints.

#### Scenario: Doorbell over the pull channel
- **WHEN** a `KeyPressed` entry appears in the drained log queue
- **THEN** the system routes it as a doorbell ring through the shared handler

#### Scenario: Channel closes on expiry
- **WHEN** the subscribe duration elapses without a pull extending it
- **THEN** the system re-subscribes to obtain a fresh channel id

## MODIFIED Requirements

### Requirement: Gate status polling on requested triggers
The system SHALL build the 2N event request set — now the Logging API subscribe filter as well as any
poll — from a single `requestedEventNames()` rule that includes motion only while
`recordOnMotion || wakeOnMotion` and noise only while `recordOnNoise || wakeOnNoise`, and issues no
event subscription at all when neither is wanted.

#### Scenario: Both camera triggers off
- **WHEN** monitoring starts with camera motion and noise both disabled
- **THEN** the system emits no `ConnectionState` and opens no event subscription
