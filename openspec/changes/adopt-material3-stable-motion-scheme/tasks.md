# Tasks

## 1. Motion scheme / BOM (beta adopted by decision)

- [x] 1.1 Keep `composeBom` at `2026.09.00` with the `material3` `1.5.0-beta01` override in
  `gradle/libs.versions.toml`; the build resolves and every module compiles against it
- [x] 1.2 `MotionScheme.kt` seam is gone and all motion call sites use `MaterialTheme.motionScheme`
  directly (`MaterialExpressiveTheme` + `MotionScheme.expressive()` in `Theme.kt`)

## 2. Media3 NPE guard (wait for the upstream fix)

- [ ] 2.1 Once the Media3 RTSP `stop()`-on-unprepared NPE is fixed upstream, remove the targeted
  `setupExoPlayerCrashProtection` uncaught-exception guard in `EntryRecorderApp` (the only site matching
  the `RtspClient` NPE signature; there is no `b/433552411`-tagged `runCatching` guard or canary test in
  this codebase), verifying RTSP playback reset still holds

## 3. Verification

- [x] 3.1 Run `./gradlew compileDebugKotlin testDebugUnitTest lintDebug assembleRelease` green
- [x] 3.2 Re-run the release lint pass to confirm no new `MissingTranslation` regressions from the version bump

## Workflow follow-up

- When material3 1.5.0 reaches stable, drop the `material3Expressive` version override so the BOM controls
  material3 again, then archive this change.
