# export Specification

## Purpose

Deliver playable files out of the app. This capability owns share/gallery/SAF export, the
on-export H.264 transcode, and the broadcast-intent export path for external automation.

## Requirements

### Requirement: Share and save-to-gallery export
The system SHALL export clips through the Android share sheet and save copies into the public
gallery/downloads (`Movies/EntryRecorder`).

#### Scenario: Share a clip
- **WHEN** the user shares a recording
- **THEN** a playable file is handed to the system share sheet

### Requirement: Transcode to playable H.264 on export
The system SHALL transcode MJPEG-MKV captures into fragmented MP4 only at export time, finalizing
the file in place on close.

#### Scenario: Export a local clip
- **WHEN** a user exports an MJPEG clip
- **THEN** the produced H.264 fMP4 plays in standard players (VLC gate)

### Requirement: Broadcast-intent export for automation
The system SHALL expose an exported broadcast receiver that triggers export of completed recordings
into the configured SAF folder, running transcode in a background WorkManager job rather than
blocking the monitoring service.

#### Scenario: adb-triggered export
- **WHEN** an external tool sends the export broadcast with scope `all` or `latest`
- **THEN** the receiver enqueues a worker that transcodes/exports without returning data inline

### Requirement: SAF export naming grammar
The system SHALL name SAF exports `REC_<device>_<EVENT>_<stamp>.mkv` (and `_h264.mp4` transcode
copies) so downstream cleanup can identify the app's own files by whole-name match.

#### Scenario: Export to a synced folder
- **WHEN** a clip is exported to the SAF folder
- **THEN** the file name follows the grammar the cleanup pass matches exactly
