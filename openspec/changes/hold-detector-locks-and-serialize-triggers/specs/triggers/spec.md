# Spec Delta

## ADDED Requirements

### Requirement: Serialized trigger event handling
The system SHALL handle device events on a single handling coroutine in the order they arrive, so a started event
and the following ended event for one device cannot run concurrently or complete out of order.

#### Scenario: Started then ended for one device
- **WHEN** a motion-started and the following motion-ended event arrive while handling suspends between them
- **THEN** the recording is armed before the post-record stop is scheduled, so the clip is neither truncated nor
  discarded as an empty file

#### Scenario: Ended arrives before any started
- **WHEN** an ended event for a device is handled while no episode is running for it
- **THEN** the system schedules the post-record buffer as it does today and starts no recording

#### Scenario: Burst of events
- **WHEN** events arrive faster than the handler can process them
- **THEN** they are queued and every event is delivered exactly once, in arrival order

### Requirement: Analyzer rebuild scoped to analyzed configuration
The system SHALL rebuild an in-app motion analyzer only when configuration the analyzer consumes changes — the
snapshot endpoint and its credentials, the sensitivity preset, or the pre-roll frame count — and SHALL leave a
running analyzer and its state intact for any other edit to the device.

#### Scenario: Unrelated device edit
- **WHEN** the user changes a device setting the analyzer does not read, such as the ring duration or the name
- **THEN** the analyzer keeps its frame reference and pre-roll ring, so a subject already in view is still
  detected without waiting for it to move again

#### Scenario: Analyzed setting changes
- **WHEN** the user changes the snapshot endpoint, credentials, sensitivity preset, or pre-roll count
- **THEN** the analyzer is rebuilt so the new value takes effect without restarting the service

### Requirement: Tearing down an active analyzer closes its episode
The system SHALL emit an on-device motion-ended event when an analyzer whose motion episode is active is stopped or
rebuilt, so the running recording follows the normal post-record path and the device status leaves the active-event
state.

#### Scenario: Rebuild during an active episode
- **WHEN** an analyzer is rebuilt while its motion episode is active
- **THEN** an ended event routes through the shared handler, a post-record stop is scheduled, and the device status
  returns to monitoring

#### Scenario: Device disabled during an active episode
- **WHEN** the last analyzer for a device stops while its episode is active
- **THEN** the recording finalises normally instead of running with no scheduled stop

#### Scenario: No false end
- **WHEN** an analyzer stops or is rebuilt while no episode is active
- **THEN** the system emits no ended event and schedules no post-record stop
