# Spec Delta

## ADDED Requirements

### Requirement: Log camera-event channel loss and recovery
The system SHALL emit a diagnostic log when the 2N Logging API event subscription fails, expires without a
successful re-subscribe, or stops delivering events, and when it is re-established, carrying the device name
and the failure reason. A transport that cannot deliver any event at all SHALL be logged as unavailable rather
than left silent.

#### Scenario: Event subscription drops
- **WHEN** the Logging API pull or re-subscribe for a device fails
- **THEN** the system logs a WARN naming the device and the reason

#### Scenario: Event channel re-established
- **WHEN** a device whose event channel had failed receives a successful re-subscribe again
- **THEN** the system logs an INFO recovery line for that device
