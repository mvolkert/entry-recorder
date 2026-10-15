# Spec Delta

## ADDED Requirements

### Requirement: Configurable motion buffer frame count
The system SHALL let the user set, per device, the number of frames held in the motion pre-roll buffer
instead of fixing it at a constant.

#### Scenario: Larger pre-roll buffer
- **WHEN** a device is configured with a higher motion buffer frame count
- **THEN** more approach frames are retained and prepended when a motion clip starts
