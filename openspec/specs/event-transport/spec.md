# event-transport Specification

## Purpose

Deliver device-side events (ring, motion, noise, call state) to the monitor service so triggers can
fire. This capability owns the transport that carries those events and how it reports availability.

## Requirements

### Requirement: Ring via inbound SIP INVITE
The system SHALL treat an inbound SIP INVITE as a doorbell ring source, folded into the same ring
handler as device events, when SIP is enabled.

#### Scenario: Doorbell press over SIP
- **WHEN** an INVITE arrives on the monitor's SIP stack
- **THEN** the system routes it as a `DoorbellRung` through the shared handler and debounce

### Requirement: Gate status polling on requested triggers
The system SHALL start the 2N HTTP status-poll loop only for triggers the user actually enabled,
polling motion only while `recordOnMotion || wakeOnMotion` and noise only while
`recordOnNoise || wakeOnNoise`, and not start at all when neither is wanted.

#### Scenario: Both camera triggers off
- **WHEN** monitoring starts with camera motion and noise both disabled
- **THEN** the system logs that polling was not started and issues zero motion/noise status requests

### Requirement: Classify absent endpoints as permanent
The system SHALL classify a `code 2 invalid request path` (or HTTP 404) answer as a permanent
"Absent" endpoint for the firmware, re-probing it at most once every 5 minutes and reporting OFFLINE
only once on the first such answer.

#### Scenario: Nonexistent endpoint
- **WHEN** a polled status endpoint returns an invalid-request-path body
- **THEN** the system stops hammering it and drops to a 5-minute re-probe

### Requirement: Report event transport as unavailable when it cannot deliver
The system SHALL represent "this endpoint is not applicable" distinctly from degraded/offline so a
capability the transport cannot deliver is not falsely reported as online.

#### Scenario: Startup online notice with no working path
- **WHEN** monitoring emits its startup camera-event notice but no event path can deliver
- **THEN** the status is corrected rather than left reading "online" indefinitely
