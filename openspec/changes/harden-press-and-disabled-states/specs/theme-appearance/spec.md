# Spec Delta

## ADDED Requirements

### Requirement: Disabled content has one intensity

The system SHALL render content it marks as unavailable at one shared disabled intensity — the theme's
primary text colour at 38 % — wherever it appears, so two screens never show the same "not right now" at two
different strengths.

#### Scenario: Monitoring switched off

- **WHEN** a camera is not being monitored
- **THEN** its status dot and label read at the shared disabled intensity rather than a screen-local alpha

#### Scenario: A trigger cannot fire

- **WHEN** the transport feeding a camera's events is degraded or offline
- **THEN** that trigger's glyph fades to the same intensity the status dot uses
