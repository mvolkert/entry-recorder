# Spec Delta

## ADDED Requirements

### Requirement: Auto-transcode export on finalize
When the auto-transcode export setting is enabled and an export folder is configured, the system
SHALL transcode each locally finalized recording to H.264 fragmented MP4 after finalization — never
during capture — and write the resulting file into the export folder. The encoding SHALL run in the
background, off the monitoring/capture path, and serialized with other export runs.

#### Scenario: Finished recording is exported playable
- **WHEN** a local recording finalizes with auto-transcode export enabled
- **THEN** an H.264 fMP4 of that recording lands in the export folder and plays in standard players
- **AND** the app keeps its private MJPEG MKV copy unchanged

#### Scenario: Capture stays cheap while encoding runs
- **WHEN** the background transcode of a just-finalized clip is still running
- **THEN** monitoring and new recordings continue unaffected

### Requirement: Raw-MKV fallback on auto-transcode failure
If the automatic transcode of a recording fails, the system SHALL export the original MJPEG MKV
instead, so the archive never has holes; the failure SHALL be logged through the existing export
failure surfacing.

#### Scenario: Encoder failure still archives the clip
- **WHEN** an auto-transcode export fails to encode a recording
- **THEN** the raw MKV copy lands in the export folder
- **AND** the failure is recorded for diagnostics and user warning

### Requirement: Auto-transcode exports queue and run one at a time
The system SHALL serialize automatic post-finalize exports: a recording that finishes while an
earlier auto-transcode export is still running SHALL be queued and processed afterwards in finalize
order, never encoded in parallel and never dropped. Queued exports SHALL survive the app process
ending before they ran.

#### Scenario: Next recording finishes while a transcode is running
- **WHEN** a recording finalizes while a previous auto-transcode export has not finished
- **THEN** its export is queued behind the running one
- **AND** it starts automatically once the earlier export completes

#### Scenario: Back-to-back finishes keep their order
- **WHEN** three recordings finalize in quick succession with auto-transcode enabled
- **THEN** all three exports run, one at a time, in finalize order, and all three artifacts land in
  the export folder

### Requirement: Auto-export modes are mutually exclusive
The system SHALL never have raw auto-export and auto-transcode export both enabled: turning one on
turns the other off, and at finalize time the single enabled mode (if any) decides which artifact is
written to the export folder.

#### Scenario: Persisted state cannot hold both
- **WHEN** settings would carry both auto-export modes enabled
- **THEN** the system treats the state as the most recently requested single mode and never produces
  both artifacts for one recording

## MODIFIED Requirements

### Requirement: Transcode to playable H.264 on export
The system SHALL transcode MJPEG-MKV captures into fragmented MP4 at export time only — on explicit
user export/share and, when the auto-transcode export setting is enabled, as the automatic
post-finalize export — finalizing the file on close. Capture itself SHALL never re-encode.

#### Scenario: Export a local clip
- **WHEN** a user exports an MJPEG clip
- **THEN** the produced H.264 fMP4 plays in standard players (VLC gate)

#### Scenario: Automatic post-finalize export
- **WHEN** a recording finalizes with auto-transcode export enabled
- **THEN** the transcode is triggered automatically without user action
