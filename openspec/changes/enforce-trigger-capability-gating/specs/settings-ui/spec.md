# Spec Delta

## ADDED Requirements

### Requirement: Do not offer triggers the transport cannot deliver
The system SHALL gate the motion and noise triggers offered in the device form on the measured device
capability rather than the device type, and SHALL NOT pre-fill a new device with triggers its transport
cannot deliver.

#### Scenario: Verso without the camera-event transport
- **WHEN** the device form opens for a `TWO_N_VERSO` whose HTTP event transport cannot deliver motion/noise
- **THEN** camera-motion and noise are shown as unavailable with a reason and the in-app analyzer is the
  only selectable motion source

#### Scenario: Capability discovery available
- **WHEN** the device reports its event classes via `/api/log/caps`
- **THEN** the form renders only the event classes the device actually reports
