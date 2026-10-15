# Proposal

## Why

The Verso device form offers camera-motion ("From camera") and record/wake-on-noise triggers that the
HTTP transport has never been able to deliver on this firmware. They are even pre-filled on (`DeviceEntity`
defaults `recordOnMotion`/`recordOnNoise` true), so every new device asks for events it will not receive.
The generic-RTSP form already refuses "From camera"; the Verso should be honest the same way.

## What Changes

- Gate offered motion/noise triggers on the **measured** device capability, not the device type.
- Until the Logging API transport lands, mark camera-motion and noise unavailable on `TWO_N_VERSO` with a
  reason, keep the in-app analyzer the only selectable motion source, and pre-fill new devices accordingly.
- Once `add-2n-logging-api-transport` lands, read `/api/log/caps` and render only the event classes the
  device reports.

## Capabilities

### Modified Capabilities
- `settings-ui`: the device form must not offer triggers the transport cannot deliver.

## Impact

- Code: `ui/settings/DeviceFormTriggersSection.kt`, `DeviceFormAlertsSection.kt`, `DeviceFormState.kt`,
  `ui/live/LiveCamerasScreen.kt`, `data/local/entity/DeviceEntity.kt`.
- Depends on `add-2n-logging-api-transport` for the capability-driven (`/api/log/caps`) end state.
