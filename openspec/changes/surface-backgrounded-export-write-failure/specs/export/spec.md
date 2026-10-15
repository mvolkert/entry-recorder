# Spec Delta

## ADDED Requirements

### Requirement: Surface export write failures without the settings screen open
The system SHALL record an export write failure that happens while the app is backgrounded as a
user-visible state, so a silently stalled SAF mirror is discoverable without opening Settings.

#### Scenario: Auto-export mirror fails in background
- **WHEN** an auto-export write to the folder fails while the app is not foreground
- **THEN** the failure is retained and surfaced (e.g. on the settings hub row or a notification) rather
  than only written to the log
