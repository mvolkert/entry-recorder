# Proposal

## Why

The motion pre-roll and the required-frames threshold are fixed constants (6 pre-roll frames; the
analyzer's required-frame count). The owner wants to set the frame amount for a motion capture or its
buffer per device, the way motion sensitivity already is.

## What Changes

- Expose a per-device setting for the motion pre-roll / buffer frame count.
- Feed it into the pre-roll ring and (optionally) the analyzer required-frames instead of hardcoded values.

## Capabilities

### Modified Capabilities
- `triggers`: the motion pre-roll/buffer frame amount becomes user-configurable per device.

## Impact

- Code: `video/OnDeviceMotionAnalyzer.kt`, `video/RtspStreamRecorder.kt`, `data/local/entity/DeviceEntity.kt`
  (+ Room migration), `ui/settings/DeviceFormTriggersSection.kt`.
