# Proposal

## Why

A Galaxy Tab S4 reports it cannot export in H.264. The export path already promises a playable H.264
fMP4, so this is a defect against existing behaviour, not a new capability — likely the MediaCodec
color-format handling on that device's encoder.

## What Changes

- Make H.264 export succeed on the S4, or surface a precise reason when the device encoder cannot.

## Capabilities

No spec-level behaviour change — this restores behaviour the `export` spec already requires
("Transcode to playable H.264 on export"). `skip_specs: true`.

## Impact

- Code: `video/H264Encoder.kt`, `video/ExportTranscoder.kt` (candidate for the Phase 6 MediaCodec
  color-format work).
- Acceptance: exported fMP4 from the S4 plays in VLC (on-device gate).
