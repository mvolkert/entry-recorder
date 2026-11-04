# Tasks

## 1. The clock seam

- [ ] 1.1 Add an elapsed-millis member to `TimeProvider`, with `SystemTimeProvider` computing it from
  `System.nanoTime()` differences, and convert the two `TimeProvider { … }` test fakes in `TriggerCoordinatorTest` and
  `MonitorLockKeeperTest` to objects over the clock they already hold; verify `./gradlew compileDebugKotlin
  testDebugUnitTest` is green with those suites' assertions unchanged

- [ ] 1.2 Inject `TimeProvider` into `OnDeviceMotionAnalyzer` (constructor default = the production object, so the
  analyzer stays constructible without a device the way `isRecording` already is) and route its health window, frame
  ages and `PreRollFrame` stamp through it — the first two from the elapsed member, the ring stamp from the wall-clock
  member, because it becomes the clip's start time on disk; verify a new `OnDeviceMotionAnalyzerTest` asserts that
  `start()` plus a fake-clock advance reports the window it actually measured and that `stop()` clears the stamps

- [ ] 1.3 Prove the monotonic half matters with a test that steps the fake wall clock backwards mid-episode and keeps
  the stillness count running from elapsed time; verify the episode neither closes early nor stays open, and that the
  pre-roll stamps still follow the wall clock

## 2. The analyzer's own rate and cadence

- [ ] 2.1 Compute the achieved number as scored comparisons over the measured window and print it with the interval
  actually in force (preset base, idle ceiling, or the recording pace) derived from `MotionTuning`; remove the
  `snapshotClient.achievedFps` call and the `device.effectiveSnapshotFps` reference from the analyzer; verify
  tests pin the arithmetic for a known window and that a fake endpoint-side rate cannot reach the line

- [ ] 2.2 Keep the "no analysis frames" WARN a frame-supply statement rather than a rate statement, quoting
  the polls it counted in the window; verify one test where the fake delivers nothing for a whole window and
  one where it delivers at the preset's idle ceiling produce different lines, and that the second does not read
  as zero

- [ ] 2.3 Pin the health line's field set in one assertion so a rename is a deliberate edit, and note the old
  wording in the commit body so a reader of the hold-detector gate evidence can map it; verify the assertion fails
  when a field name or unit is changed

## 3. The stillness duration

- [ ] 3.1 Replace `MotionTuning.requiredClearFrames` with `clearStillnessMs` at design D4's table (SENSITIVE 1.2 s,
  BALANCED 2 s, POWER_SAVER 4.8 s) and close an episode once no qualifying change has been seen for that elapsed
  monotonic time since the first quiet frame; verify tests show the same close duration at the base interval, at the
  idle ceiling and under the 1500 ms recording pace, and that a lone late gap never closes an episode on its own

- [ ] 3.2 Restart the stillness measurement when a changed frame arrives inside the duration; verify a test with the
  sequence changed-quiet-changed keeps the episode open and reports the span from the second changed frame

- [ ] 3.3 Put the span on the decision lines — `Motion start … over N frames in X.Xs` and `Motion end … after X.Xs
  still (N frames)` — and leave the start rule counting frames; verify a test where frames arrive late still starts on
  the required count and prints the real span, and that `MotionAnalysisKey` plus `MotionAnalysisKeyTest` are unchanged

## 4. On-device gates

- [ ] 4.1 Emulator gate (x86_64 Pixel_8, the fake-camera rig used for the hold-detector gates, host clock note
  applies): with the endpoint deliberately delivering late, confirm the health line's rate equals the
  `frames/polls` counts printed beside it, that starting a local recording does not lift the analyzer's number
  toward the recorder's pace, and that a fully backed-off idle analyzer never reads a zero or not-measured
  rate; verify from a tag-filtered logcat capture

- [ ] 4.2 Verso gate: with a real subject, confirm an episode closes about the preset's stillness duration after
  motion stops at every one of the three presets, and record the clip-length change against the same scene before and
  after; verify on-device timing from the monitor log plus the gallery's clip duration, not from frame counts

- [ ] 4.3 Verso gate: change the device's wall clock (NTP jump or time-zone switch) while an episode is open
  and confirm the close still arrives on elapsed time while the finalised clip's start time follows the wall clock;
  verify the recording plays from the expected moment

- [ ] 4.4 Run `./gradlew compileDebugKotlin testDebugUnitTest lintDebug` and report against the recorded
  49-finding lint baseline rather than a clean sweep; verify no new warning points at the analyzer or `TimeProvider`

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
