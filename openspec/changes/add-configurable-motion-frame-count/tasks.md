# Tasks

## 1. Setting

- [x] 1.1 Add a per-device motion-buffer frame-count field to `DeviceEntity` with a Room migration and
  verify the default preserves today's behaviour (6 frames)
- [x] 1.2 Surface it in `DeviceFormTriggersSection` next to sensitivity and verify it persists per device

## 2. Wire into capture

- [x] 2.1 Size the pre-roll ring from the per-device value in `RtspStreamRecorder`/`OnDeviceMotionAnalyzer`
  and verify a raised value prepends more frames

## 3. Verification

- [x] 3.1 Run `./gradlew compileDebugKotlin testDebugUnitTest lintDebug` green
- [ ] 3.2 On-device: set a higher frame count, walk past the camera, and verify the clip start carries the
  extra approach frames without memory regression

## Workflow follow-up

- Archive after the soak in 3.2 confirms heap/GC is stable at the chosen buffer size.
