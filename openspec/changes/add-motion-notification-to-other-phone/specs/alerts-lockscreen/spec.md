# Spec Delta

## ADDED Requirements

### Requirement: Notify a second device on motion
The system SHALL let the user configure a remote recipient that receives a notification when the
monitoring device detects motion.

#### Scenario: Motion fires the remote alert
- **WHEN** motion is detected and a remote recipient is configured
- **THEN** the recipient is notified through the chosen transport
