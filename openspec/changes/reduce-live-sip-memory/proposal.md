# Proposal

## Why

The monitor service mapped the native Linphone SIP stack on every start and the live-view loop decoded every camera frame at native resolution, so a detection-only phone resident in memory 24/7 carried roughly 40–70 MB of native SIP stack it often never used plus several megabytes of full-resolution preview bitframes replaced many times a second. Both are avoidable: the SIP engine is only needed when a device is actually configured for it, and the on-screen preview never renders at a camera's native still resolution.

## What Changes

- The Linphone core is started lazily when the first monitored device that actually uses SIP (peer-to-peer or PBX) appears, and released again once no monitored device uses SIP, instead of unconditionally at service creation. An in-flight call pins the core; releasing it keeps the incoming-call wiring so a later re-init stays complete.
- Live-view preview decoding is bounded to a fixed long-edge resolution (`inSampleSize`), and each replaced preview bitmap is recycled once the composition moves past it.
- Unchanged and explicitly protected: recording, on-device motion analysis, thumbnails, H.264 export/transcode, and in-app playback continue to use the full-resolution raw-byte / own-decode paths. Only the on-device Live preview is affected.

Out of scope:
- Any change to recorded frame content, container strategy, or the "cheap capture, lazy encoding" intent.
- Motion-detection sensitivity or the analyzer's own 96x54 grayscale comparison grid.
- Raising the preview resolution target beyond the chosen long-edge cap, and any GPU/`EGL mTrack` surface memory.
- Server-backend and the unbounded gallery Room list (separate concerns).

## Capabilities

### New Capabilities
- (none)

### Modified Capabilities
- `sip`: add the lazy SIP-engine lifecycle — start on the first SIP-configured monitored device, release when none are, preserving active calls and the existing main-thread core-teardown rules.
- `device-connectivity`: add live-view preview decode bounding and replaced-frame recycling, guaranteeing capture, motion analysis, thumbnail, export and playback stay full-resolution.

## Impact

- `service/IntercomMonitorService.kt` (drops the eager `sipManager.initialize()`), `sip/SipCallManager.kt` (lazy `synchronizeDevices`, split `stopCore`/`stopCoreKeepingWiring`).
- New `data/network/JpegDownsample.kt`; `data/network/MjpegStreamReader.kt`, `data/network/HttpSnapshotClient.kt` (downsampling decode args), `ui/components/LiveStreamPlayer.kt` (recycling).
- No dependency, manifest, schema, or server changes.
