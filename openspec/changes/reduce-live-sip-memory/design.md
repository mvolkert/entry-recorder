# Design

## Context

Motivation is in proposal.md. Two constraints shaped the how:

- liblinphone requires a core to be created/started/stopped on the main thread, and the monitor
  service already runs device synchronization inside an IO-context loop (`updateMonitoredDevices`).
- Compose renders a `Bitmap` held by the UI; recycling a bitmap the composition still references
  crashes the render ("Canvas: trying to use a recycled bitmap").

The live-view loop previously decoded every JPEG/MJPEG frame at the camera's native still
resolution (1080p ≈ 8 MB ARGB_8888), replacing it several times a second. Motion analysis already
decoded its own downscaled 96x54 grayscale grid from raw bytes, and recording stores raw JPEG
bytes — neither path goes through the preview decode functions.

## Goals / Non-Goals

**Goals:**
- Zero native SIP memory mapped when no monitored device uses SIP, with no behavior regression in
  call flows (doorbell-over-SIP, answer-from-lock-screen) once a SIP device exists.
- Bounded, constant preview-frame memory per Live card regardless of camera resolution.
- Decisions reversible by one tunable constant, without touching capture/analysis/export code.

**Non-Goals:**
- Redesigning the `SipRegistrar`/`SipDeviceAccountSync` seam or moving `initialize()` behind the interface.
- Any change to the MKV/frag-MP4 container strategy or the analyzer's grid size.
- GPU/`EGL mTrack` surface memory and the unbounded gallery Room list.

## Decisions

- **Lazy start/hook lives in `SipCallManager.synchronizeDevices`, not the service.** The service
  already feeds every monitored-device change through this one suspend funnel, so it knows the
  aggregate SIP demand; `IntercomMonitorService.onCreate` just dropped its eager
  `sipManager.initialize()`. Alternative considered: a dedicated lifecycle-aware starter in the
  service — rejected as a second source of truth about device config.
- **Init/teardown hop to `Dispatchers.Main.immediate`** because liblinphone requires main-thread
  core operations; `immediate` avoids a redundant dispatch when already on main.
- **Release is guarded by `currentCall == null` and uses `stopCoreKeepingWiring()`.** `destroy()`
  was split: a shared private `stopCore()` (remove listener, `core.stop()`, clear account sync)
  plus a variant that keeps `onIncomingCall` and the logging listener, so a device disabled and
  later re-enabled gets a fully wired core without service recreation. The alternative (full
  `destroy()` on idle) would silently break the doorbell-via-SIP path after re-enable.
- **Release is edge-triggered by device sync.** An idle release happens on the next
  `synchronizeDevices` call; no timer was added, since no observable requirement mandates an
  immediate reclaim after the last call ends.
- **Shared `decodeDownsampledJpeg(bytes, maxDecodeEdge)` helper in `data/network`** (new
  `JpegDownsample.kt`), used by `MjpegStreamReader.streamBitmaps`/`streamBitmapsFromUrl` and
  `HttpSnapshotClient.fetchSnapshotBitmap`. It does an `inJustDecodeBounds` pass, then picks a
  power-of-two `inSampleSize` so the long edge lands at or above `DEFAULT_LIVE_MAX_DECODE_EDGE`
  (720) — mirroring the analyzer's proven downscale-on-decode. `streamRawJpeg` (recording)
  intentionally never decodes and was not touched. Alternative: Coil/Canvas downsampling —
  rejected because decode-time `inSampleSize` never allocates the full-size bitmap.
- **Recycling via `DisposableEffect(displayedFrame)` in `LiveStreamPlayer`.** Keying on the
  displayed bitmap recycles the previous frame only after composition has moved on, and the last
  frame when the card leaves composition; a `isRecycled == false` guard prevents double recycle.
  Synchronous recycle-on-replace was rejected for the crash above.

## Risks / Trade-offs

- [720px long edge may look soft on large-phone screens when a camera serves 4K stills] → single
  tunable constant (`DEFAULT_LIVE_MAX_DECODE_EDGE`); callers can override per use.
- [Idle core release mid-signalling could drop an INVITE the `currentCall` check doesn't see yet]
  → device-gated verification of disable-during-ring and disable/re-enable cycles.
- [Recycling a bitmap another holder still references] → the preview decode functions are only
  consumed by `LiveStreamPlayer` (verified by call-site audit); no other holder exists today.
- [Room/list and GPU memory contributions unchanged] → explicitly out of scope; the 200 MB audit
  ranked these below the two fixes landed here.

## Migration Plan

Code-only, no schema/manifest/dependency changes. Forward-compatible; rollback is reverting the
two commits (`dcb6a2f`, `036679d`). The 720 bound and lazy release need one on-device pass (see
tasks acceptance criteria) before the change can be archived as verified.

## Open Questions

- Should the idle core release also be triggered when a call terminates (not just on the next
  device-sync edge)? Deferrable: no spec scenario mandates a reclaim deadline.
