# Tasks

## 1. The clock seam

- [x] 1.1 Add an elapsed-millis member to `TimeProvider`, with `SystemTimeProvider` computing it from
  `System.nanoTime()` differences, and convert the two `TimeProvider { … }` test fakes in `TriggerCoordinatorTest` and
  `MonitorLockKeeperTest` to objects over the clock they already hold; verify `./gradlew compileDebugKotlin
  testDebugUnitTest` is green with those suites' assertions unchanged

- [x] 1.2 Inject `TimeProvider` into `OnDeviceMotionAnalyzer` (constructor default = the production object, so the
  analyzer stays constructible without a device the way `isRecording` already is) and route its health window, frame
  ages and `PreRollFrame` stamp through it — the first two from the elapsed member, the ring stamp from the wall-clock
  member, because it becomes the clip's start time on disk; verify a new `OnDeviceMotionAnalyzerTest` asserts that
  `start()` plus a fake-clock advance reports the window it actually measured and that `stop()` clears the stamps

- [x] 1.3 Prove the monotonic half matters with a test that steps the fake wall clock backwards mid-episode and keeps
  the stillness count running from elapsed time; verify the episode neither closes early nor stays open, and that the
  pre-roll stamps still follow the wall clock

## 2. The analyzer's own rate and cadence

- [x] 2.1 Compute the achieved number as scored comparisons over the measured window and print it with the interval
  actually in force (preset base, idle ceiling, or the recording pace) derived from `MotionTuning`; remove the
  `snapshotClient.achievedFps` call and the `device.effectiveSnapshotFps` reference from the analyzer; verify
  `MotionAnalysisReportTest` pins the arithmetic for a known window. That no other consumer's rate reaches the line is
  structural here — the analyzer stops calling `achievedFps` and the report takes no endpoint input — and gate 4.1
  proves it with a live view actually sharing the endpoint; on that row the recorder pulls RTSP, so the co-tenant
  feeding the shared snapshot client is `LiveStreamPlayer`.

- [x] 2.2 Keep the "no analysis frames" WARN a frame-supply statement rather than a rate statement, quoting
  the polls it counted in the window; verify one test where a window delivers nothing and one delivering at the
  preset's idle ceiling produce different lines, and that the second does not read as zero

- [x] 2.3 Pin the health line's field set in one assertion so a rename is a deliberate edit, and note the old
  wording in the commit body so a reader of the hold-detector gate evidence can map it; verified by mutation — calling
  the cadence field `(base)` instead `(base pace)` failed that one assertion and no other, then reverted

## 3. The stillness duration

- [x] 3.1 Replace `MotionTuning.requiredClearFrames` with `clearStillnessMs` at design D4's table (SENSITIVE 1.2 s,
  BALANCED 2 s, POWER_SAVER 4.8 s) and close an episode once no qualifying change has been seen for that elapsed
  monotonic time since the first quiet frame; verify tests show the same close duration at the base interval, at the
  idle ceiling and under the 1500 ms recording pace, and that a lone late gap never closes an episode on its own

- [x] 3.2 Restart the stillness measurement when a changed frame arrives inside the duration; verify a test with the
  sequence changed-quiet-changed keeps the episode open and reports the span from the second changed frame

- [x] 3.3 Put the span on the decision lines — `Motion start … over N frames in X.Xs` and `Motion end … after X.Xs
  still (N frames)` — and leave the start rule counting frames; verify a test where frames arrive late still starts on
  the required count and prints the real span, and that `MotionAnalysisKey` plus `MotionAnalysisKeyTest` are unchanged

## 4. On-device gates

- [x] 4.1 Emulator gate (x86_64 Pixel_8, the fake-camera rig used for the hold-detector gates, host clock note
  applies): with the endpoint deliberately delivering late, confirm the health line's rate equals the
  `frames/polls` counts printed beside it, that starting a local recording does not lift the analyzer's number
  toward the recorder's pace, and that a fully backed-off idle analyzer never reads a zero or not-measured
  rate; verify from a tag-filtered logcat capture
  - Emulator-verified (x86_64 Pixel_8 / API 37, 2026-11-04, one monitor instance pid 4450 throughout; the device
    clock runs an hour behind the host, so every timestamp below is the device's). Subject: the rebuilt
    fake-camera stub on `:8123` behind `adb reverse`, serving a 640x480 grayscale PNG at the row's
    `/api/camera/snapshot?width=1280&height=720` in three modes — alternating two-tone/flat (`peak change 50%`),
    held flat, and a per-response delay (`900ms`, `3000ms`, and one answering inside the 2s read timeout, the next
    past it). Read through `logcat -v brief` filtered on `OnDeviceMotionAnalyzer`.
  - The printed rate is the printed counts over the printed window, in every window: `40/40 polls scored … 0.7 fps
    over 61.2s`, `24/24 … 0.4 fps over 60.1s` (900ms late), `17/17 … 0.3 fps over 63.3s`, `10/17 … 0.2 fps over
    60.7s` and `7/15 … 0.1 fps over 60.4s`. The last two are the polls-vs-scored split the numerator is meant to
    make: with the endpoint at 3s, timeouts land in the denominator and the window runs to 63.3s, which the nominal
    minute the superseded line assumed would have mis-divided.
  - Co-tenancy, the clause that was the bug: while the call screen's MJPEG live view polled the same device through
    the same client, the stub counted 322 requests served in 71s (4.5 req/s) and the analyzer printed `38/38 polls
    scored … 0.6 fps over 60.6s`, `34/34 … 0.6 fps over 60.9s`, `38/38 … 0.6 fps over 61.0s` — its own polls, not the
    endpoint's aggregate. In the same stream the recorder reported its own pace (`Recorded EmuA at 2.2 fps
    (configured 5 fps, endpoint ceiling 30 fps) 154 frames`) and the overlapping analyzer window read `19/31 polls
    scored … 0.3 fps over 61.4s, poll 1500ms (recording pace)`.
  - Backed-off idle never reads zero or unmeasured: `39/39 … 0.6 fps over 61.2s, poll 1500ms (idle backoff, ceiling
    1500ms)` down to `0.1 fps` when most polls timed out, and no window printed `0.0 fps` or an absent number. In the
    `10/17` window successful fetches averaged 6.1s apart, past `RATE_STALE_MS = 5s` — the condition under which the
    superseded line read `0.0 fps achieved (configured 5)`.
  - A window with polls and nothing usable stays a supply statement with no rate in it at all: `No analysis frames
    from EmuA: 11 polls, 0 usable JPEGs in 63.8s`, beside `snapshot lost: EmuA (Snapshot endpoint delivered no frame
    for 3 polls in a row)`.
  - Discarded, not counted: the session's first window came from a stub defect, not the app — the PNG omitted the
    per-scanline filter byte, so `BitmapFactory` decoded nothing and the line read `40 polls, 0 usable JPEGs in
    61.6s` while the endpoint was answering.
  - Not covered: the row stayed BALANCED, so POWER_SAVER's 2500ms ceiling and SENSITIVE's 400ms base lines the unit
    table pins were never printed by a running loop, and a two-tone PNG exercises the scoring threshold, not a
    camera's optics or codec. 4.2 and 4.3 stay with the Verso.

- [ ] 4.2 Verso gate: with a real subject, confirm an episode closes about the preset's stillness duration after
  motion stops at every one of the three presets, and record the clip-length change against the same scene before and
  after; verify on-device timing from the monitor log plus the gallery's clip duration, not from frame counts

- [ ] 4.3 Verso gate: change the device's wall clock (NTP jump or time-zone switch) while an episode is open
  and confirm the close still arrives on elapsed time while the finalised clip's start time follows the wall clock;
  verify the recording plays from the expected moment

- [x] 4.4 Run `./gradlew compileDebugKotlin testDebugUnitTest lintDebug` and report against the recorded
  49-finding lint baseline rather than a clean sweep; verify no new warning points at the analyzer or `TimeProvider`
  - Verified 2026-11-04 on forced re-execution (`testDebugUnitTest lintDebug --rerun-tasks`, result XMLs 22:24, lint
    report 22:25): 57 suites, 392 tests, 0 failures, and `lintDebug` exit 0 with 47 findings against the recorded 49
    — 8 UnusedResources, 7 PluralsCandidate, 5 GradleDependency, 4 NewerVersionAvailable, 4 BinaryOperationInTimber,
    3 ThrowableNotAtBeginning. No finding's location is in `OnDeviceMotionAnalyzer.kt`, `TimeProvider.kt`,
    `MotionAnalysisReport.kt`, `MotionEpisodeTracker.kt` or `DeviceMonitorPool.kt`.

## Workflow follow-up

- Archive only after 4.1–4.3 pass; per project convention device-gated work stays open as tasks, and a clause fully
  proven by the emulator stand-in gets a scope note naming rig, API level and date.
- Point the superseded change's residue at this one: replace the "Re-open it as its own change" line under
  `hold-detector-locks-and-serialize-triggers` `tasks.md` follow-ups with this change's name once this proposal is
  accepted. Its gate 5.2 stays open for the Verso soak and this change does not sign it off.
- The wider scoring rework (ratio thresholds, the 96x54 grid and its downscale, previous-frame-only comparison, the
  full-resolution analysis URL, pre-roll seconds) is still unopened; this change hands it the `MotionTuning` table and
  the frame stamps, nothing else.
- Split into two commits if convenient: the clock seam and rate reporting (groups 1–2), then the stillness
  duration and decision spans (group 3), each with its tests, using the project format with the `[x]` count matching
  the subject.
- `README.md` needs no edit: the stillness rule is not a user-facing setting, and clip timing is documented in the
  `triggers` spec. Revisit only if the Verso gate shows a clip-length change worth telling people about.
