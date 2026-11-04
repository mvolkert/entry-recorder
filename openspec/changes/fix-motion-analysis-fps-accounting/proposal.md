# Proposal

## Why

The two numbers in the analyzer's per-minute health line — the only evidence anyone has that on-device detection is
alive and at what pace — do not describe the analyzer. The rate is `HttpSnapshotClient.achievedFps(device.id)`: an
exponential average over the gaps between *any* successful `fetchSnapshotBytes` for that device, so while a local
recording runs the analyzer prints the recorder's pace and calls it its own, and it returns `0f` ("not measured")
whenever the last frame from anywhere is older than 5 s — which a healthy POWER_SAVER analyzer sitting at its 2500 ms
idle ceiling behind one retry can hit. The "configured" side names `device.effectiveSnapshotFps`, a field the analysis
loop never reads (its cadence comes from the sensitivity preset), and prints the value from the row the analyzer was
constructed with, because `MotionAnalysisKey` deliberately excludes it. So `1.9 fps achieved (configured 5)` invites
the reader to blame the camera for the app's own backoff. This is the residue the hold-detector change leaves in its
design.md Open questions after task 4.5 shipped only the identity half.

The same frame-versus-second conflation sits in the scoring rule. `requiredMotionFrames` and `requiredClearFrames`
count polls, so what a preset means in wall-clock time depends on whichever interval the previous poll settled on. A
BALANCED episode closes after 4 quiet frames: 2 s at the preset's base 500 ms, 6 s while a local recording pins the
poll to 1500 ms, and up to 15 s for POWER_SAVER at its 2500 ms ceiling. That latency is invisible to the configured
post-record duration, which only starts counting once the ended event arrives — a clip configured to run 20 s past the
last motion actually runs 26-35 s.

## What Changes

- The analyzer measures its own delivered rate over its own health window — comparisons it actually scored, divided by
  the time the window covered — and reports that, so the number means "analysis frames per second this analyzer
  compared" no matter who else is polling the same endpoint.
- The health line names the cadence the analyzer actually targets (the preset's base interval, its idle ceiling, and
  whether the recording pace is in force) and stops printing `effectiveSnapshotFps`. **BREAKING** for log and gate
  evidence: this is the line quoted by the hold-detector gates. No UI text changes.
- Every scored frame carries the time it was fetched, taken from an injected monotonic clock rather than
  `System.currentTimeMillis()`, and each start/end decision logs the span it was taken over, so a frame count can be
  read in seconds instead of inferred from a preset table.
- The motion-**end** rule becomes a stillness duration per preset instead of a quiet-frame count. **BREAKING** for
  episode-end timing: BALANCED closes on 2 s of stillness rather than 4 frames, POWER_SAVER on 4.8 s rather than 6,
  SENSITIVE on 1.2 s rather than 3 — identical to today at each preset's own pace, and never again the 6-15 s a
  backoff can add to it.
- The motion-**start** rule stays a count of consecutive changed comparisons, deliberately: it compares two fetched
  frames, and a count keeps working when an endpoint delivers late. It gains no new bound; design D4 records why the
  time-window alternative was rejected and what seconds each preset costs.
- `OnDeviceMotionAnalyzer` adopts the project's existing `TimeProvider` seam (extended with a monotonic reading)
  instead of reading `System.currentTimeMillis()` in three places, which makes both the window and the health report
  testable on the JVM.

## Out of scope

- The rest of the scoring rework the hold-detector change parks alongside this: the change-ratio thresholds, the 96x54
  grid and its nearest-neighbour downscale, comparing against anything other than the immediately previous frame, the
  full-resolution analysis URL, and pre-roll staleness/freshness. The pre-roll ring stays sized in frames (the count
  became user-configurable in `add-configurable-motion-frame-count`), so its lead time in seconds still varies with
  the delivered rate; changing that alters clip content, not detection, and stays with the scoring rework.
- `device-connectivity`'s "Snapshot frame-rate ceiling" requirement and `HttpSnapshotClient.achievedFps` itself. The
  endpoint-side estimate stays what it is for — the number the device form's measured-FPS display
  (`DeviceProbeRunner.measuredFps`) and the capture path report. Only the analyzer stops borrowing it.
- Any new per-device "analysis fps" setting. Cadence remains derived from the sensitivity preset and `snapshotFps`
  remains a capture-path field. `MotionAnalysisKey` does not widen: the stale configured value disappears by deleting
  the reference, not by rebuilding analyzers over a field that only ever labeled a log line (design D2).
- The lock policy, event serialization and episode-close work under
  `hold-detector-locks-and-serialize-triggers`. Its gate 5.2 stays open for the Verso screen-off soak and this change
  does not sign any part of it off.
- No Room schema, dependency, manifest, permission or Gradle change.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `triggers` — owns on-device motion analysis. Gains the requirement that the analyzer reports its own frame rate,
  cadence and decision spans, and that the frame pipeline is timed from an injected monotonic source; modifies
  "On-device motion analysis with sensitivity presets" so a preset defines an end-of-episode stillness duration as
  well as a start frame count.

## Impact

- Code: `video/OnDeviceMotionAnalyzer.kt` (own rate window, frame stamps, duration-based end rule, injected clock,
  health-line wording), `domain/model/TimeProvider.kt` plus its production object (a monotonic reading), and
  `MotionAnalysisKey` — declared next to the analyzer — unchanged by design.
- Tests: there is no analyzer-level test today — only `MotionAnalysisKeyTest` covers the key. This change adds one
  with a fake clock and a fake snapshot source, pinning the preset-to-seconds table and the two rate cases the bug is
  made of (a shared endpoint with a recording running; a backed-off idle analyzer whose last frame is older than the
  client's stale window).
- Diagnostics: the health line format changes. The gate notes already written under the hold-detector change quote the
  old wording; they are historical evidence and stay as written.
- Verification: `./gradlew compileDebugKotlin testDebugUnitTest lintDebug`, then an on-device gate on the Verso — the
  real ~6 req/s serial ceiling and MJPEG pace are what the achieved number has to survive — and an emulator-coverable
  half against the fake-camera rig, which can be made to deliver late. No live Python server or SIP gate.
