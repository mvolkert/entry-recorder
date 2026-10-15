# triggers Specification

## Purpose

Decide when to record and alert. This capability owns the monitor service's event routing, the
per-device trigger settings, on-device motion analysis, ring debounce, and folding a second trigger
into a running clip.

## Requirements

### Requirement: Per-device trigger configuration
The system SHALL store record/wake triggers (ring, motion, noise) per device rather than globally,
and route events through a shared started/ended handler.

#### Scenario: Different cameras, different triggers
- **WHEN** one device records on motion and another only wakes on ring
- **THEN** each device's events are handled against its own flags

### Requirement: On-device motion analysis with sensitivity presets
The system SHALL analyze snapshots for motion using a per-device `MotionSensitivity` preset that
drives the change-ratio, required-frames and poll-backoff, shown in the device form only when the
motion source is "Analyzed in-app".

#### Scenario: Fast crosser with Sensitive preset
- **WHEN** a device set to SENSITIVE sees a person cross within ~1.5s
- **THEN** the lower ratio/single-frame bar allows the trigger to fire where Balanced would miss it

### Requirement: Fold a second trigger into a running clip
The system SHALL fold an arriving trigger into a clip already recording for the same device, tagging
it with both event types and extending its deadline, rather than dropping the second event.

#### Scenario: Doorbell during a motion clip
- **WHEN** a ring arrives while a motion recording runs
- **THEN** the system produces one clip filed under RING with MOTION kept, findable by both filters

#### Scenario: Server mode does not fold
- **WHEN** a second trigger arrives for a device recording on the Python server
- **THEN** the system logs that no second job is added (accepted limit until the server carries it)

### Requirement: Debounce duplicate rings
The system SHALL suppress a second ring within the debounce window for the same device while keeping
the running recording, and document the trade-off at the constant.

#### Scenario: Repeated presses
- **WHEN** the doorbell is pressed twice within the window
- **THEN** only one alert/notification is produced
