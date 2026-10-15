# Tasks

## 1. Diagnose

- [ ] 1.1 Reproduce the S4 H.264 export failure and capture the encoder error to pin the cause and verify
  it matches the suspected color-format selection

## 2. Fix

- [ ] 2.1 Read `MediaCodec.getCapabilitiesForType(AVC).colorFormats` and select NV12 → planar → flexible
  instead of hardcoded `COLOR_FormatYUV420Planar`, honoring stride/slice-height, and verify the S4 export
  completes

## 3. Verification

- [ ] 3.1 Run `./gradlew compileDebugKotlin lintDebug` green
- [ ] 3.2 On the S4 and one reference device: export an MJPEG clip and verify the fMP4 plays in VLC

## Workflow follow-up

- Archive after the VLC playback gate passes on the S4.
