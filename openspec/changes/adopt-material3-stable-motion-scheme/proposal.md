# Proposal

## Why

Three upstream-triggered maintenance pins, bundled because they are all "do this when the dependency ships":
the Compose BOM/material3 are on a prerelease, the motion tokens are behind a transitional seam, and a Media3
NPE guard is a workaround for an upstream bug. No app behaviour changes.

## What Changes

- When material3 1.5.0 (stable `motionScheme`) ships: revert `gradle/libs.versions.toml` `composeBom` from
  the `2025.09.22` beta back to `2025.09.00`, delete the `MotionScheme.kt` seam, and switch all motion sites
  to `MaterialTheme.motionScheme` directly (AGENTS requires zero custom durations).
- When the Media3/RTSP `stop()`-on-unprepared NPE (b/433552411) is fixed upstream: remove the defensive
  `runCatching` guard (grep the tag) and delete the `Media3RtspStopNpeRegressionTest` canary.

## Capabilities

`skip_specs: true` — dependency/version maintenance, no behaviour change.

## Impact

- Files: `gradle/libs.versions.toml`, `ui/theme/MotionScheme.kt`, motion call sites, `video/VideoTwoWayPlayer`
  guard, RTSP reset test.
