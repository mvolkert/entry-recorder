# Spec Delta

## ADDED Requirements

### Requirement: Per-trigger hard recording limit
The system SHALL let the user set, per device, a hard upper bound on how long a trigger may record: a maximum
frame count for motion and for noise, and a maximum duration in minutes for ring. A value of zero SHALL mean
"no limit". The bound is a ceiling that a folded-in second trigger cannot extend past.

#### Scenario: Motion hits its frame ceiling
- **WHEN** a motion recording reaches the configured maximum frame count while the motion event is still
  ongoing
- **THEN** the system stops the clip at that cap rather than recording until the end event or timer

#### Scenario: Ring keeps getting folded
- **WHEN** repeated doorbell rings fold into a running ring clip
- **THEN** the clip does not exceed the configured maximum ring minutes

#### Scenario: No limit chosen
- **WHEN** the cap for a trigger is zero
- **THEN** the system bounds that clip only by its existing end-event/timer behaviour, as before

### Requirement: Recording limit clamped and defaulted
The system SHALL clamp a typed limit on save into a supported range (unparseable input falls back to the
default) and SHALL default every existing device's limits to "no limit" on upgrade, so adopting the feature
changes recording behaviour only where the user opts in.

#### Scenario: Out-of-range value
- **WHEN** the user saves a limit above the supported maximum
- **THEN** the stored value is clamped to that maximum

#### Scenario: Upgrade an existing install
- **WHEN** a device row saved before this feature is loaded after the schema upgrade
- **THEN** its recording limits read as "no limit" and its clips behave as they did before

### Requirement: Hard limit honoured on both capture paths
The system SHALL apply the configured per-trigger limit whether the clip is captured on the phone or on the
Python server, since the limit is a property of the trigger rather than of the recorder.

#### Scenario: Server-mode motion clip
- **WHEN** a motion trigger records on the Python server for a device with a frame ceiling set
- **THEN** the server-side clip is bounded by that same limit
