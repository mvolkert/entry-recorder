# Proposal

## Why

Three upstream-triggered maintenance pins, bundled because they are all "do this when the dependency ships":
the Compose BOM/material3 are on a prerelease, the motion tokens are behind a transitional seam, and a Media3
NPE guard is a workaround for an upstream bug. No app behaviour changes.

## What Changes

- Adopted by owner decision: the material3 `1.5.0-beta01` override stays and `MaterialTheme.motionScheme`
  is used directly on the beta — no `MotionScheme.kt` seam exists and every motion site already reads the
  theme scheme (AGENTS requires zero custom durations). When 1.5.0 ships stable, drop the override so the
  BOM controls material3 again.
- When the Media3/RTSP `stop()`-on-unprepared NPE is fixed upstream: remove the targeted uncaught-exception
  guard `setupExoPlayerCrashProtection` in `EntryRecorderApp`.

## Capabilities

`skip_specs: true` — dependency/version maintenance, no behaviour change.

## Impact

- Files: `gradle/libs.versions.toml`, motion call sites (all already on `MaterialTheme.motionScheme`),
  `EntryRecorderApp` RTSP crash guard.
