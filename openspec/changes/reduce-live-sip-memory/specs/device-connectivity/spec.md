# Spec Delta

## ADDED Requirements

### Requirement: Bounded live-view preview decoding
The system SHALL decode on-device Live-preview frames to a bounded long-edge resolution rather
than the camera's full native resolution, and SHALL release each replaced preview frame once the
UI has stopped drawing it. Recording, on-device motion analysis, thumbnails, export/transcode and
in-app playback SHALL continue to use full-resolution raw-byte or own-decode paths unaffected by
the preview bound.

#### Scenario: Live preview at native-resolution source
- **WHEN** a Live card displays frames from a camera whose snapshots are larger than the preview bound
- **THEN** the displayed frame is decoded down to the bound and the previously displayed frame is released, without visual artifacts or render crashes

#### Scenario: Motion detection while Live is open
- **WHEN** the on-device analyzer polls the same camera while a Live preview is displayed
- **THEN** the analyzer's comparison grid and trigger sensitivity are unchanged by the preview downsample

#### Scenario: Export after live viewing
- **WHEN** the user exports a recording captured while bounded Live previews were running
- **THEN** the exported video and its thumbnails remain at full recorded resolution
