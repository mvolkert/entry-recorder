# Spec Delta

## ADDED Requirements

### Requirement: Log app-to-server connection loss and recovery
The system SHALL emit a diagnostic log when the app's connection to the Python server is lost — an
unreachable reachability probe, a failed device sync, a mid-session live-relay drop, or a failed remote
motion-alert delivery — and when the server becomes reachable again, carrying the server base URL (without
the API key) and the failure reason.

#### Scenario: Server unreachable during a call
- **WHEN** a request to the server fails with a connect/timeout/unknown-host error
- **THEN** the system logs a WARN naming the server and the reason

#### Scenario: Server reachable again
- **WHEN** a request succeeds after the app had reported the server unreachable
- **THEN** the system logs an INFO recovery line naming the server

#### Scenario: Motion-alert relay failure stays logged not silent
- **WHEN** a remote motion alert cannot be delivered through the server
- **THEN** the system logs the delivery failure and continues without failing the local recording path
