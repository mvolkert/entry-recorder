# Design

## Context

`OnDeviceMotionAnalyzer` runs one 24/7 loop per device that fetches a JPEG, decodes it onto a 96x54 grid and compares
it against the previous frame. Everything this change touches lives in that file plus the `HttpSnapshotClient` rate
getter it calls:

- `reportHealthIfDue()` prints `$framesSinceReport/$pollsSinceReport frames` — counters the analyzer owns — and then
  prints `snapshotClient.achievedFps(device.id)` next to `device.effectiveSnapshotFps`. The former is an exponential
  average over the gaps between *any* successful `fetchSnapshotBytes` for that device (the recorder and the live view
  feed the same map), and returns `0f` when the newest frame anywhere is older than `RATE_STALE_MS` (5 s). The latter
  is not read by the loop at all; the loop's interval comes from `motionTuningFor(sensitivity)`.
- `evaluateFrame()` decides on counts: `consecutiveMotionFrames >= tuning.requiredMotionFrames` opens,
  `consecutiveClearFrames >= tuning.requiredClearFrames` closes. Frames carry no time, so the seconds a count costs
  depend on which interval the previous poll settled on (base, +250 ms steps to the idle ceiling, or the 1500 ms
  recording pace).
- Timing reads `System.currentTimeMillis()` in three places: `start()` seeds the health window, `reportHealthIfDue()`
  measures it, and `bufferPreRoll()` stamps each ring entry. Only the ring stamp leaves the process:
  `LocalCaptureLoop` makes it the clip's `startTime` and writes it into the MKV, so it has to stay a
  wall-clock value.
- The domain already has a one-method `TimeProvider` seam (`domain/model/TimeProvider.kt`, `SystemTimeProvider` as the
  production object) consumed by `TriggerCoordinator` and `MonitorLockKeeper`, with two test fakes that build it as a
  `TimeProvider { clock.nowMs }` lambda. There is no analyzer-level test; the only test in the area is
  `MotionAnalysisKeyTest`.

See proposal.md - Why for the two symptoms this fixes.

## Goals / Non-Goals

**Goals:**

- Every number in the analyzer's own log lines describes the analyzer, and each is defined by the code path that
  produces it rather than inherited from a co-tenant of the endpoint.
- Express the episode-close decision in the unit the user configures (seconds), leaving the open decision in the unit
  the comparison actually has (frames).
- Make both windows testable on the JVM with a fake clock, without a device or a wall-clock dependency.

**Non-Goals:**

- Changing what counts as a changed pixel, how many pixels must change, or which frame is the reference. The trigger
  bar and the previous-frame comparison stay exactly as they are.
- Changing the polling schedule itself — the base interval, the 250 ms backoff step, the idle ceiling and
  the recording pace are untouched. This change describes that schedule honestly; it does not retune it.
- `HttpSnapshotClient.achievedFps`, `device-connectivity`'s frame-rate-ceiling requirement, and the device form's
  measured-FPS display, which is a legitimate consumer of the endpoint-side estimate.

## Decisions

### D1 — The analyzer measures its own rate from its own window

`framesSinceReport` and `elapsedMs` already exist in `reportHealthIfDue()`; the achieved number becomes
`scored frames × 1000 / window actually measured`, printed next to the `frames/polls` counts that were always the
truth. The window is divided by the measured elapsed time, not the nominal 60 s, so a loop that was descheduled or a
device that slept reports a real rate instead of a plausible one. `snapshotClient.achievedFps(...)` loses its only
caller in the monitor path.

*Alternative considered:* teach `HttpSnapshotClient` a per-consumer rate (a caller tag plumbed through
`fetchSnapshotBytes`). Rejected: it changes the capture path's shared object to fix a log line, the callers
that matter (the recorder, the live view, the analyzer) would still need to identify themselves at every
call site, and the analyzer can answer the question from counters it already keeps.

### D2 — Delete the stale "configured" reference instead of widening the key

The health line stops printing `effectiveSnapshotFps` and prints the cadence the loop is actually honouring, derived
from `tuning`: base interval, idle ceiling, and which of them (or the recording pace) is in force, plus the rate that
cadence implies. Because nothing reads `snapshotFps` any more, the value the analyzer was constructed with has no
observable effect, which is what the hold-detector change's Open questions asked where the stale fps goes.

*Alternative considered:* add `snapshotFps` to `MotionAnalysisKey` so the line refreshes. Rejected: gate 5.4 measured
what a key-triggered rebuild costs — it ended an in-flight episode and reopened it 1.6 s later — and paying that to
refresh the label of a field the loop ignores is the wrong trade. *Also rejected:* resolve it from the pool's live row
the way task 4.5 does for identities; there is no decision left to make with the number.

### D3 — Two clocks, one seam

`TimeProvider` gains a second member — an elapsed-millis reading — with a production implementation over
`System.nanoTime()` differences, so the analyzer's window and frame ages are monotonic while `PreRollFrame` keeps its
wall-clock stamp. The seam is a `fun interface` today, so a second abstract member ends the lambda form: the
production object and both test fakes become objects backed by the clock they already hold. The alternative
of a second, separate clock type would leave two ways to inject time in the same domain layer.

*Alternative considered:* `android.os.SystemClock.elapsedRealtime()`. Rejected: it would put an Android import
into `domain/model`, where the current `SystemTimeProvider` is pure JVM, and `nanoTime` differences give the
same guarantee. A fake clock in tests drives both members.

Frame ages are stored per scored frame (`lastFrameAtMs`, `firstClearFrameAtMs`, `runStartAtMs`); `start()` seeds them
from the injected clock instead of the wall clock, and `resetState()` clears them so a rebuilt analyzer does not carry
an episode's stillness across.

### D4 — The end becomes a duration, the start stays a count

Close the episode when the scene has shown no qualifying change for `clearStillnessMs`, measured from the first quiet
frame's fetch time and restarted by any changed frame. Per preset the value is today's `requiredClearFrames ×
pollIntervalMs` — SENSITIVE 3 × 400 = 1.2 s, BALANCED 4 × 500 = 2 s, POWER_SAVER 6 × 800 = 4.8 s — so the change is
invisible when the analyzer polls at its own pace and only removes the stretch that backoff or the recording pace adds
today (6 s and up to 15 s). `requiredClearFrames` is then deleted from `MotionTuning` rather than kept beside it, so
one knob owns one decision. The granularity floor stays honest: the check can only run on a frame, so a 2 s
rule at the 1500 ms recording pace closes between 2.0 s and 3.5 s after the last motion, not exactly at 2.0 s.

The start keeps counting consecutive changed comparisons. A comparison is inherently between two fetched frames, so a
duration would have to be measured over gaps the endpoint controls.

*Alternative considered:* a time window for the start too ("a change within the last T ms"). Rejected with a concrete
case: a subject entering a fully backed-off BALANCED scene produces its first changed comparison 1.5 s after the
previous frame, and the run only then snaps the poll to 500 ms, so the earliest start any such rule could confirm
spans 2.0 s — a 1.5 s bound would have suppressed exactly the detection the device exists for. *Also considered:* keep
the count and stop the backoff while an episode is open. Rejected: it removes the battery saving on the one path where
the phone is already recording, and the post-record setting would still not mean the seconds it says.

### D5 — Spans go on the decision lines, not into the health line

`Motion start on EmuA: 42% changed pixels` gains `over 2 frames in 2.0s`; `Motion end on EmuA` gains
`after 2.4s still (3 frames)`. Those are the lines that bracket an episode, so the span belongs where the decision is
announced, and the health line stays one-per-minute diagnostic rather than growing a second set of numbers.

### D6 — One analyzer test, built on fakes

`OnDeviceMotionAnalyzerTest` with a fake `TimeProvider`, a fake clock-driven snapshot source and a recording flag the
test controls, pinning: the scored-rate arithmetic against a known window; that a recorder-rate `achievedFps` cannot
appear in the analyzer's line; a close at the preset duration whether the loop is at base, idle ceiling or recording
pace; a changed frame restarting stillness; and a wall-clock step that moves `currentTimeMillis` but not elapsed time.
This is the seam the rest of the tasks verify through, and it is the first test of the loop itself.

## Risks / Trade-offs

- **[Clips end sooner than they do today]** → that is the defect: today's extra seconds come from the analyzer's own
  backoff, not from the configured post-record. The Verso gate records clip lengths before and after so the delta is
  measured rather than argued.
- **[A slow endpoint now closes an episode the old count would have kept open]** → the close still requires the same
  *evidence* (no qualifying change); only the unit changes, and the duration equals what the count cost at that
  preset's own pace, so the loss window is the backoff stretch the change deliberately removes.
- **[Deleting `requiredClearFrames` breaks a preset someone tuned against frame counts]** → the sensitivity
  presets are app-owned constants, not user settings; the device form exposes the preset only.
- **[The new rate number is lower than the number people are used to seeing]** → intended, and the line names the
  cadence beside it so a 0.7 fps idle-backed analyzer reads as designed rather than as a failing camera. Anyone
  grepping the old wording needs to know it moved: called out in the commit subject and in the change's tasks.
- **[`TimeProvider` gaining a member ends its lambda form]** → the production object and the two test fakes become
  objects, each backed by a clock they already hold; mechanical, compiler-enforced, and it prevents two
  competing clock seams in the domain layer.

## Migration Plan

No data, schema, setting-surface or permission change. The new behaviour takes effect when an analyzer is next built —
on service start or on any analyzed-field edit — so no restart is needed beyond installing the build, matching the
hold-detector change's plan. Revert is a single `git revert`; the deleted `requiredClearFrames` returns with it.

## Open questions

- Should the health window stay a fixed 60 s or be derived from the preset's idle ceiling so a POWER_SAVER device
  reports sooner than one poll per five windows? Neither answer changes the specs, the approach or the task breakdown.
- Does the pre-roll ring get the same seconds treatment once the scoring rework lands? Out of scope here, but the two
  now share the `MotionTuning` table, so the follow-up has somewhere to put it.
