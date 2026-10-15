# capture Specification

## Purpose

Turn snapshots/MJPEG frames into stored video. This capability owns the recording engine, the
crash-resilient container strategy, pre-roll buffering, and the server-side recording reconciliation.

## Requirements

### Requirement: Crash-resilient local capture container
The system SHALL record raw MJPEG/snapshot frames into an incrementally-flushed MKV under
`filesDir/recordings` via a forward-only file stream, so clusters written before an abort survive.

#### Scenario: Process killed mid-recording
- **WHEN** the app dies while a clip is being written
- **THEN** the already-flushed frames remain playable rather than losing the whole file

### Requirement: Cheap capture, lazy encoding
The system SHALL NOT re-encode at record time; H.264 encoding happens only on explicit export or
share, keeping 24/7 capture CPU/battery-cheap.

#### Scenario: Recording a triggered event
- **WHEN** a recording starts
- **THEN** frames are muxed as captured with no transcode pass

### Requirement: Motion pre-roll buffer
The system SHALL hold a bounded ring of recent frames while no local recording owns the endpoint and
prepend them to a motion-triggered clip so the approach frames are covered.

#### Scenario: Motion begins
- **WHEN** a motion trigger fires and pre-roll frames exist
- **THEN** the clip starts with the buffered approach frames against the earliest-frame baseline

### Requirement: Reconcile server-mode recordings
When recording via the Python server, the system SHALL track the active server job, reconcile it
against the server's reported state, and persist it so reconciliation survives a process restart.

#### Scenario: App restart during a server recording
- **WHEN** the process is killed while a server recording runs and the app relaunches
- **THEN** the app re-adopts the job, honours its original deadline, and stops it only if it started it
