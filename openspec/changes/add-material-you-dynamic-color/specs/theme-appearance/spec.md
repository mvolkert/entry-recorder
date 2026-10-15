# Spec Delta

## ADDED Requirements

### Requirement: Opt-in dynamic color
The system SHALL offer a persisted opt-in that applies the Material You dynamic color scheme, with the curated
accent presets and their contrast test serving as the opt-out when it is off.

#### Scenario: Dynamic color enabled
- **WHEN** the user opts into dynamic color on a supporting device
- **THEN** the whole scheme follows the system palette and the Appearance UI states that accents are overridden

#### Scenario: Dynamic color disabled
- **WHEN** the opt-in is off
- **THEN** the curated per-role accent presets and WCAG-AA contrast enforcement apply as before
