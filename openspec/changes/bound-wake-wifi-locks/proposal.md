# Proposal

## Why

Wake/WiFi locks are held for idle monitoring, not just during active recording/ring. That was accepted for a
dedicated detection-first device (never missing a ring beats battery). This change narrows the locks to the
periods they're actually needed. No behaviour requirement changes — power/resource management only.

## What Changes

- Hold wake/wifi locks only during active recording or an active ring, release them during idle monitoring.

## Capabilities

`skip_specs: true`.

## Impact

- Code: `service/IntercomMonitorService.kt`.
- Trade-off: a lock released during idle could delay a wake — acceptable only where battery matters more;
  keep a per-device option for the detection-first deployment that wants locks held.
