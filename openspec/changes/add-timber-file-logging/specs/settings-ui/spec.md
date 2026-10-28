# Spec Delta

## ADDED Requirements

### Requirement: Diagnostics controls in settings
The system SHALL expose, in the settings hub, a "Save logs to file" toggle reflecting the persisted
diagnostics-logging setting and a "Share logs" action, so the user can turn file capture on/off and export the
log without leaving the app.

#### Scenario: Toggle persists
- **WHEN** the user flips the "Save logs to file" switch
- **THEN** the choice is persisted and reappears enabled the next time settings open

#### Scenario: Share from settings
- **WHEN** the user taps "Share logs"
- **THEN** the system opens the platform share sheet with the current log file
