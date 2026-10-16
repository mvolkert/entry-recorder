# Proposal

## Why

For stream-capable cameras, capture could demux the RTSP H.264 and re-mux with zero re-encode (no CPU/battery
cost). This only matters for future/optional RTSP-only devices — the primary 2N Verso deployment is
snapshot/MJPEG only by fixed design intent, so this is not scheduled.

## What Changes

- Add an RTSP H.264 passthrough capture path (demux → re-mux, no re-encode) for devices that expose it.

## Capabilities

`skip_specs: true` for now — a capture-pipeline addition for a device class not yet in the deployment; a spec
delta will be authored when an RTSP device is actually onboarded.

## Impact

- Code: `video/` capture pipeline.
- Trigger: owner elected (2026-10-16) to build against the Media3 RTSP API **blind** behind a config flag
  rather than wait for a device; behaviour stays provisional until an on-device gate can confirm it.
