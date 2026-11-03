# Proposal

> **Superseded on 2026-11-04** by `openspec/changes/hold-detector-locks-and-serialize-triggers/`. That change
> reverses this one's shipped default for a specific deployment: while an in-app motion analyzer runs, the monitor
> now holds the CPU and Wi-Fi locks continuously, because a suspended CPU and a power-save radio cannot detect the
> motion that would have taken the lock — the chicken-and-egg this proposal never had an answer for. What this
> change narrowed stays narrowed for deployments that get no analyzer-driven reason (camera-event-only devices
> without `keepLocksHeld`): locks during an active recording or ring, released at idle. Implemented code shipped;
> only the soak below was open, and it carries into gate 5.2 of the superseding change rather than being closed
> here.

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
