# Tasks

## 1. Diagnose

- [ ] 1.1 Reproduce the S4 H.264 export failure and capture the encoder error to pin the cause and verify
  it matches the suspected color-format selection

## 2. Fix

- [x] 2.1 Read `MediaCodec.getCapabilitiesForType(AVC).colorFormats` and select NV12 → planar → flexible
  instead of hardcoded `COLOR_FormatYUV420Planar`, honoring stride/slice-height, and verify the S4 export
  completes

  Implemented via a new device-free `YuvEncoder` seam: `selectColorFormat` (NV12→planar→flexible, null when
  none) and `pack` (stride/slice-height aware, edge-replicated padding). `H264Encoder.start()` now reads
  `codecInfo.getCapabilitiesForType(AVC).colorFormats`, sets the chosen `KEY_COLOR_FORMAT`, reads
  `KEY_STRIDE`/`KEY_SLICE_HEIGHT` from the input format, and errors with a precise reason when the encoder
  exposes no supported raw format. The selection and packing are covered by `YuvEncoderTest` (7 cases,
  JVM-only). The S4 completion check itself is the on-device gate below.

## 3. Verification

- [x] 3.1 Run `./gradlew compileDebugKotlin lintDebug` green (also `testDebugUnitTest`)
- [ ] 3.2 On the S4 and one reference device: export an MJPEG clip and verify the fMP4 plays in VLC

## Workflow follow-up

- Archive after the VLC playback gate passes on the S4.
