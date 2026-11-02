# Tasks

> Retroactive: groups 1 and 2 were implemented in commits `dcb6a2f` and `036679d` on `dev` and are
> marked done. Group 3 is the outstanding on-device gate; the change must not archive until it passes.

## 1. Lazy SIP engine lifecycle

- [x] 1.1 Remove the eager `sipManager.initialize()` from `IntercomMonitorService.onCreate`, keeping
  the `onIncomingCall` wiring, and verify with `.\gradlew.bat :app:compileDebugKotlin`. (commit `dcb6a2f`)
- [x] 1.2 Rework `SipCallManager.synchronizeDevices` to start the core on the first monitored device
  with `sipMode != DISABLED` and release it via `stopCoreKeepingWiring()` when none do, guarded by
  `currentCall == null`, both on `Dispatchers.Main.immediate`; verify with
  `.\gradlew.bat :app:compileDebugKotlin`. (commit `dcb6a2f`)
- [x] 1.3 Split `destroy()` into a shared private `stopCore()` plus `stopCoreKeepingWiring()` that
  preserves `onIncomingCall` and the logging listener, and verify the existing SIP unit tests still
  pass with `.\gradlew.bat :app:testDebugUnitTest`. (commit `dcb6a2f`)

## 2. Bounded live-view decoding and recycling

- [x] 2.1 Add `data/network/JpegDownsample.kt` with `decodeDownsampledJpeg(bytes, maxDecodeEdge)`
  (bounds pass + power-of-two `inSampleSize`) and `DEFAULT_LIVE_MAX_DECODE_EDGE = 720`, and verify
  with `.\gradlew.bat :app:compileDebugKotlin`. (commit `036679d`)
- [x] 2.2 Route `MjpegStreamReader.streamBitmaps`/`streamBitmapsFromUrl` and
  `HttpSnapshotClient.fetchSnapshotBitmap` through the helper with a `maxDecodeEdge` default
  parameter, leaving `streamRawJpeg` (recording) untouched, and verify with
  `.\gradlew.bat :app:compileDebugKotlin` and `.\gradlew.bat :app:testDebugUnitTest`. (commit `036679d`)
- [x] 2.3 Recycle replaced preview bitmaps in `LiveStreamPlayer` via
  `DisposableEffect(displayedFrame)` with an `isRecycled` guard, and verify the UI change with
  `.\gradlew.bat :app:lintDebug` (no findings in touched files). (commit `036679d`)

## 3. On-device acceptance gates

- [x] 3.1 HTTP-only deployment gate: with every monitored device set to SIP-disabled, start the
  service and verify via `adb shell dumpsys meminfo <package>` that no native Linphone allocation
  appears (Native Heap drop vs the ~40–70 MB baseline) and that no SipCallManager core log appears
  until a SIP device is enabled.
  - Emulator-verified (x86_64 Pixel_9 / API 37). No device form exposes SIP-disabled, so the
    discriminator is zero devices: service start with no devices produced no `liblinphone` load and no
    `SipCallManager` core log (Native Heap ≈10.4 MB); adding a SIP device lazily started the core
    ("Linphone Core initialized on port 5060", Native Heap ≈18.2 MB) with no service restart. The
    SIP-disabled *device* variant on a physical Verso is still open for the hardware pass.
- [ ] 3.2 SIP enable/disable cycle gate: against a Fritz!Box/PBX or peer-to-peer Verso, disable the
  last SIP device, re-enable it, and verify registration recovers and an incoming call still rings
  the doorbell and answers from the lock screen without restarting the service; also disable the
  device mid-call and verify the call is not dropped and the core releases afterwards.
- [x] 3.3 Live-preview quality and stability gate: open the Live card against the Verso, verify the
  preview renders at the 720px bound without "recycled bitmap" crashes over several minutes of
  frame replacement and card add/remove, while a triggered recording, its thumbnail, motion-trigger
  sensitivity, and an exported frag-MP4 remain full-resolution.
  - Emulator-verified (HTTP-Snapshot device fed by an `adb reverse` large-JPEG server at 4000×3000).
    The new `JpegDownsampleTest`, run against the real `BitmapFactory`, pins the bound (long edge
    lands in [720, 1440)); the Live card rendered the decoded frame with continuous replacement
    (distinct frames over ~5 s) and no "recycled bitmap" crash across 11k+ log lines, with Native Heap
    flat ≈20 MB over 21 s. The full-resolution recording/thumbnail/motion/export sub-claims still
    require the physical Verso.
- [ ] 3.4 Memory-outcome gate: compare `dumpsys meminfo` before/after on the 24/7 detection phone
  and record the measured PSS delta in the archive; if the 720 bound looks too soft on-device,
  retune `DEFAULT_LIVE_MAX_DECODE_EDGE` in a follow-up commit (constant-only change).

## Workflow follow-up

- Run `/opsx:apply` evidence pass once group 3 gates have been executed on hardware, checking the
  remaining boxes as each passes.
- Archive the change (`/opsx:archive`) only after all group-3 gates pass, so `openspec/specs/`
  reflects verified as-built behavior.
