# recordings-archive Specification

## Purpose

Browse, play, protect and delete stored recordings. This capability owns the searchable gallery
(local and merged server rows), the video player, and selection/delete/protect semantics.

## Requirements

### Requirement: Searchable, filterable gallery
The system SHALL present all recordings in a searchable list filterable by event type (ring, motion,
noise) and device.

#### Scenario: Filter by ring
- **WHEN** the user selects the ring filter
- **THEN** only ring-triggered clips are shown (folded clips filed under ring appear here)

### Requirement: Merged local and server recordings
The system SHALL merge local Room recordings and server recordings into one time-sorted list keyed
by a composite stable key so the two id spaces share one list without colliding.

#### Scenario: Equal local and server id
- **WHEN** a local id equals a server id
- **THEN** both rows render independently with their own actions and no duplicate-key crash

### Requirement: Player with timeline and autoplay-after-seek
The system SHALL play clips (MJPEG-MKV and H.264) with a timeline, resuming playback after a scrub
if the clip was playing before the drag.

#### Scenario: Seek while playing
- **WHEN** the user drags the timeline on a playing clip
- **THEN** playback pauses under the thumb and resumes on release

### Requirement: Protect important recordings
The system SHALL let the user mark clips protected so automatic cleanup and bulk delete skip them.

#### Scenario: Bulk delete with protected clips
- **WHEN** the user bulk-deletes a selection that includes protected clips
- **THEN** protected clips are kept and the kept count is reported

### Requirement: Range multi-select
The system SHALL support long-press range selection across the visible order, keeping server rows out
of selection.

#### Scenario: Mark first and last
- **WHEN** the user long-presses a first row then a last row
- **THEN** every visible row between them becomes selected
