# Spec Delta

## ADDED Requirements

### Requirement: Log snapshot-path connection transitions
The system SHALL emit one diagnostic log when the snapshot/MJPEG polling path for a device crosses into a
loss state (DEGRADED or OFFLINE) and one when it returns to ONLINE, carrying the device name, the reason,
and — on recovery — the number of missed polls or outage duration. It SHALL NOT log once per failed poll.

#### Scenario: Snapshot endpoint goes offline
- **WHEN** the snapshot path for a device crosses the offline consecutive-failure threshold
- **THEN** the system logs a WARN naming the device and the reason (e.g. missed-poll count)

#### Scenario: Snapshot endpoint recovers
- **WHEN** a device whose snapshot path was degraded or offline delivers a frame again
- **THEN** the system logs an INFO recovery line for that device stating how long it was down
