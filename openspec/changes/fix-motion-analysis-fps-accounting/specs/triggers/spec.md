# Spec Delta

## ADDED Requirements

### Requirement: Motion analyzer reports its own delivered frame rate
The system SHALL report, for a running in-app motion analyzer, the rate of frame comparisons that analyzer actually
scored over its own measurement window together with the cadence it is targeting at that moment, and SHALL NOT
substitute a rate measured across other consumers of the same snapshot endpoint.

#### Scenario: A recording shares the endpoint
- **WHEN** a local recording polls the same device's snapshot endpoint at the configured capture rate while the
  analyzer polls at its reduced recording-pace interval
- **THEN** the analyzer's report states the rate it scored itself and names the recording pace in force, not the
  recorder's rate

#### Scenario: Polling has backed off on a static scene
- **WHEN** the analyzer is idle-polling at its preset's ceiling and every poll still returns a usable frame
- **THEN** the report shows the slower achieved rate with the interval it backed off to, and does not read as a
  zero or not-measured rate

#### Scenario: Frames stop arriving
- **WHEN** polls are running but no usable frame is decoded within a measurement window
- **THEN** the report states that the endpoint supplied no frame, distinct from a slow but healthy delivered rate

### Requirement: Motion decisions state the time they were taken over
The system SHALL measure the on-device motion pipeline's timing from elapsed monotonic time, record when each scored
frame was fetched, and report the wall-clock span of each motion-start and motion-end decision alongside the frame
count that caused it.

#### Scenario: Late frames
- **WHEN** frames arrive later than the targeted interval because the endpoint or the network was slow
- **THEN** the reported span for the resulting decision shows the real elapsed time, so equal frame counts cannot be
  mistaken for equal durations

#### Scenario: A wall-clock jump
- **WHEN** the device's wall clock changes while an analyzer episode is open
- **THEN** stillness and decision spans continue from elapsed monotonic time, so an episode neither closes early nor
  stays open because of the jump

## MODIFIED Requirements

### Requirement: On-device motion analysis with sensitivity presets
The system SHALL analyze snapshots for motion using a per-device `MotionSensitivity` preset that drives the
change-ratio, the consecutive changed frames required to open an episode, and the idle poll backoff, and SHALL close
an episode on a stillness duration defined by the preset rather than a count of quiet polls, so the stillness a preset
demands is the same number of seconds whichever polling interval is in force.

#### Scenario: Fast crosser with Sensitive preset
- **WHEN** a device set to SENSITIVE sees a person cross within ~1.5s
- **THEN** the lower ratio/single-frame bar allows the trigger to fire where Balanced would miss it

#### Scenario: Same stillness at any pace
- **WHEN** the scene has shown no qualifying change for the preset's stillness duration, whether the analyzer polls at
  its base interval, at its idle ceiling, or at the reduced interval a local recording forces
- **THEN** the motion-ended event is raised, so a configured post-record duration counts from the last observed motion
  rather than from that plus however long a quiet-frame run took to accumulate

#### Scenario: A late change keeps the episode open
- **WHEN** a frame showing qualifying change arrives before the stillness duration has elapsed
- **THEN** the episode stays open and the stillness measurement restarts from that frame

#### Scenario: Opening stays a frame-count decision
- **WHEN** the required consecutive changed frames are scored, however long the intervals between them were
- **THEN** the episode opens on that count, so a late-delivering endpoint delays detection rather than suppressing it
