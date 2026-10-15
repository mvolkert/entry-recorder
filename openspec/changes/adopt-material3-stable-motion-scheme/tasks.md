# Tasks

## 1. Motion scheme / BOM (wait for material3 1.5.0)

- [ ] 1.1 Confirm material3 1.5.0 is stable, revert `composeBom` to `2025.09.00` in
  `gradle/libs.versions.toml`, and verify the build resolves
- [ ] 1.2 Delete the `MotionScheme.kt` seam and switch motion call sites to `MaterialTheme.motionScheme`
  directly, verifying durations are unchanged

## 2. Media3 NPE guard (wait for the upstream fix)

- [ ] 2.1 Once the `stop()`-on-unprepared NPE is fixed upstream, remove the `runCatching` guard (grep the
  `b/433552411` tag) and delete the `Media3RtspStopNpeRegressionTest` canary, verifying the RTSP reset path
  still holds

## 3. Verification

- [ ] 3.1 Run `./gradlew compileDebugKotlin testDebugUnitTest lintDebug assembleRelease` green
- [ ] 3.2 Re-run the release lint pass to confirm no new `MissingTranslation` regressions from the version bump

## Workflow follow-up

- Do NOT revert the `composeBom` pin to `2025.09.00` while material3 is still at 1.5.0-beta01 — beta01 has no
  stable `motionScheme`.
