# Spec Delta

## ADDED Requirements

### Requirement: Auto-transcode export on finalize
When the auto-transcode export setting is enabled and an export folder is configured, the system
SHALL transcode each locally finalized recording to H.264 fragmented MP4 after finalization — never
during capture — and write the resulting file into the export folder. The encoding SHALL run in the
background, off the monitoring/capture path, and serialized with the other WorkManager export runs;
the in-app gallery export is not part of that chain and may overlap a queued automatic export.

#### Scenario: Finished recording is exported playable
- **WHEN** a local recording finalizes with auto-transcode export enabled
- **THEN** an H.264 fMP4 of that recording lands in the export folder and plays in standard players
- **AND** the app keeps its private MJPEG MKV copy unchanged

#### Scenario: Capture stays cheap while encoding runs
- **WHEN** the background transcode of a just-finalized clip is still running
- **THEN** monitoring and new recordings continue unaffected

### Requirement: Automatic export failures are surfaced
The system SHALL record an automatic export that lands nothing — a transient error that ends the run, a
pass that delivered no artifact for that recording, or no export folder left to write to — through the
existing export-failure surfacing, and SHALL withdraw that record when an artifact lands again. A clip
that reached the folder degraded to the raw MKV SHALL NOT count as a failure.

#### Scenario: The automatic mirror stops landing
- **WHEN** an automatic export ends without delivering its recording
- **THEN** the stalled-export warning is raised and withdrawn again by the next artifact that lands

#### Scenario: A degraded delivery is not a failure
- **WHEN** an automatic export delivers the raw MKV because the encode failed
- **THEN** no stalled-export warning is raised
- **AND** a warning an earlier failure had set is withdrawn

### Requirement: Raw-MKV fallback on auto-transcode failure
If the automatic transcode of a recording fails, the system SHALL export the original MJPEG MKV
instead, so the archive never has holes. The encoder failure SHALL be logged and the run SHALL report
the delivery as degraded — see *Automatic export failures are surfaced* for what that means for the
user-facing warning.

#### Scenario: Encoder failure still archives the clip
- **WHEN** an auto-transcode export fails to encode a recording
- **THEN** the raw MKV copy lands in the export folder
- **AND** the run reports the delivery as degraded and logs the encoder failure

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
- **AND** a settings restore that carries both stores the H.264 mirror only

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
