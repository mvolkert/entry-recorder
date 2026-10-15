# Proposal

## Why

The monitor foreground service restarts on boot via `BootReceiver` with no user control. The owner wants
an "Auto Boot" toggle so a dedicated detection device can autostart while a shared phone does not.

## What Changes

- Add a persisted "Auto Boot" setting that gates whether `BootReceiver` starts the monitor service.
- Default it on for a dedicated detection-first device, off where the user has not opted in (decide default
  during apply).

## Capabilities

### Modified Capabilities
- `alerts-lockscreen`: boot autostart becomes user-controlled rather than unconditional.

## Impact

- Code: `receiver/BootReceiver.kt`, `data/local/entity/AppSettingsEntity.kt` (+ Room migration),
  `ui/settings/`.
- Manifest: `RECEIVE_BOOT_COMPLETED` stays; only the receiver's action becomes conditional on the flag.
