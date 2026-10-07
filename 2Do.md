# EntryRecorder – Implementation Plan

Reorganized 2026-09-30 from the old tier list + the four 2026-09-30 review blocks. All closed work was
compacted into the **Resolved log** at the bottom; every still-open item lives in a numbered phase above,
sorted minimal → architectural. Nothing was silently dropped: 34 open + 7 partial items were carried over.

Legend: `[x]` done · `[~]` partial / needs validation · `[ ]` open · 🔄 on-device gate (blocks closing) ·
🔭 long-term / not scheduled · 🖥️ server-side scope

Review my Compose UI for Material 3 compliance and cross-screen consistency. Do NOT fix anything yet — produce a prioritized findings report only.
Scope: all composables under app/src/main/java/.../ui (and any feature folders).
Check each of these dimensions and report violations with file:line, a short why-it-matters note, and a suggested fix:
Design tokens vs hardcoded values — any literal dp, sp, Color, fontSize, corner radius, or animation duration that should come from MaterialTheme.colorScheme, .typography, .shapes, .spacing, or motionScheme. Flag custom durations especially.
Typography — text that uses raw fontSize/fontWeight instead of an M3 TextStyle role; inconsistent heading/body hierarchy across screens.
Component consistency — compare how cards, buttons, dialogs, and bottom sheets are built screen-to-screen. Flag divergent surfaces (e.g. one screen uses ElevatedCard, another uses Card with a hand-rolled border for the same semantic role), inconsistent touch targets, and icon usage/tinting.
Color semantics — wrong onX pairings, status/error colors hardcoded instead of derived from the scheme, contrast problems, dark/light (DayNight) breakdowns.
Layout & spacing rhythm — inconsistent paddings/margins/gutters for equivalent containers; layout that isn't inset/wedge-safe.
Motion — asymmetric enter/exit transitions, animating with non-token durations, or missing MaterialTheme.motionScheme usage.
Accessibility/UX — missing content descriptions, non-localized (hardcoded) user-facing strings that belong in strings.xml, state that isn't hoisted through the ViewModel/StateFlow pattern.
Group findings by severity (High / Medium / Low) and by pattern (any issue repeated across many files should be reported once as a pattern with a count, not 20 separate lines). End with the top 3 highest-leverage fixes. Follow the project's AGENTS.md and existing Material 3 conventions as the source of truth over generic M3 advice.

## Bug
- [~] Motion/ Noise pull without being toggled → Phase 2 "Gate the 2N status polling on the triggers the user
  actually asked for" — done and **Verso-verified 2026-10-07** (both triggers off: zero polls; motion-wake only: one
  endpoint polled, `code 2` → one report + 5 min re-probe); the "do not offer triggers the transport cannot deliver"
  half and the Logging API transport are still open, run after the SIP Test gate
- [~] 🔄 **Retention is editable in single days.** The stepper moved ±7, so 3 or 15 days were unreachable even
  though the cleanup cutoff is day-exact (`retentionDays * 24 h`). Now ±1 day (0 = keep indefinitely, capped at
  3650) and tapping the value opens `RetentionDaysDialog` for exact entry; unparsable or out-of-range input
  blocks Save instead of silently clamping. Build/test/lint green, no device check yet.
  Files: `ui/settings/SettingsStorageCard.kt`, `ui/settings/RetentionDaysDialog.kt`, `res/values/strings.xml`
- [~] 🔄 **The export folder now says when it has to be granted again.**
  `ExportHelper.probeExportFolderAccess` checks the persisted read **and** write grant and that the tree still
  resolves; `SettingsViewModel.exportFolderAccess` runs it on IO behind `distinctUntilChanged` on the URI (plus a
  re-check when Settings is shown), and `ExportFolderRow` renders `NO_PERMISSION` / `MISSING` as an error line with
  a "Grant folder again" button. A revoked grant, a folder deleted behind a still-held grant and a URI that only
  came back from a backup file all used to look healthy until an export failed; the picker now pre-selects the
  stored folder. While there, the screen's ad-hoc Toasts became a `SettingsUiEvent` channel and the SAF
  take/release handling moved out of the composable into the ViewModel. Build/test/lint green, no device check yet.
  Files: `util/ExportHelper.kt`, `ui/settings/SettingsViewModel.kt`, `ui/settings/SettingsStorageCard.kt`,
  `ui/settings/SettingsScreen.kt`, `res/values/strings.xml`
- [] The warning above only appears while Settings is open: an auto-export mirror that fails at write time
  (`video/RtspStreamRecorder.kt`, `worker/ExportTriggerWorker.kt`) is still just a `Log.e`, so a backgrounded app
  silently stops filling the archive. Surface a write failure the same way the stale grant is now surfaced.
- [x] **Player Autoplay after seeking.** Done: `JpegFramePlayer`'s timeline forced `playing = false` on every
      drag and never came back, so one scrub ended the clip. The drag still pauses (the frame under the thumb
      stays put) but now remembers that the clip had been running and resumes when the thumb is released; a clip
      the user paused first stays paused. The timing loop re-reads the frame index each iteration, so it carries
      on from the scrubbed position with that frame's recorded dwell — no pacing change. The ExoPlayer branch
      (server rows, non-MKV files) already kept `playWhenReady` across a seek and is untouched. Build/test/lint
      green only — the resume needs a device check.
      File: `ui/components/JpegFramePlayer.kt`
- [x] Able to export MJPEG Recordings manual
- [x] Check logcat for mor warnings/errors
- [x] **The monitor no longer prints the device password into logcat.** `IntercomMonitorService.onEvent` opened
      with `Log.i(tag, "Received IntercomEvent: $event")`, and every `IntercomEvent` is a data class holding a
      `DeviceEntity`, so the entity's generated `toString()` put the HTTP `password` and the `sipPassword` in
      clear on **every** event — including the polling fallback, which emits one per re-probe. Done: the sealed
      class now declares `abstract val device` (each subclass already had that property, so only the keyword was
      added) and a pure `IntercomEvent.logIdentity()` returns `<EventClass> from device <id> (<name>)`, which is
      what the service logs; the per-branch lines already named only `device.name`, so nothing else changed.
      Defense in depth: `DeviceEntity` got a hand-written `toString()` listing the diagnostics-relevant fields and
      masking both credentials, so a future `$device` / `$event` interpolation is harmless. Nothing reads that
      `toString()` for logic — device diffing uses the data-class `equals`, Room and Gson read fields.
      A sweep of `Log.*` in `app/src/main` found this as the only leak site; the credential-bearing
      `rtspStreamUrl` is never logged and the OkHttp `Authorization` headers stay out of log output.
      Files: `domain/device/IntercomDevice.kt`, `service/IntercomMonitorService.kt`,
      `data/local/entity/DeviceEntity.kt`,
      `app/src/test/java/.../domain/device/IntercomEventLogIdentityTest.kt` (new, 4/4)
      ⚠️ Build/test green only — the "grep logcat for the password, zero hits" check still needs a device run.
- [] S4 not able to export in H264

# UI
- [x] Rework SettingsScreen to have sub-menus
- [x] Live View fit borders around the image
- [x] **Multiple Selection in RecordingsScreen — long-press marks a range.** Done: mark the first row (a
      long-press opens selection on it), mark the last row and everything between is checked. `selectRangeTo`
      unions `rangeSelection(visibleIds, anchor, target)` over the current pick — both ends inclusive,
      direction-independent, measured on the **visible** order so a filter-hidden row is never swept in — and
      every tap or long-press moves the anchor; an anchor that is gone (deleted or filtered out) degrades to a
      plain single toggle rather than selecting the whole list. Server rows stay out of selection: they keep no
      long-press at all. Bulk delete now keeps protected clips the way retention cleanup does, says so in the
      dialog before confirming and reports the kept count afterwards; the header count comes from the new
      `selectionInfo` flow, which counts only rows that still exist (a single delete prunes its id).
      Files: `ui/recordings/SelectionRange.kt` (new, pure), `ui/recordings/RecordingsViewModel.kt`,
      `ui/recordings/RecordingsScreen.kt`, `ui/recordings/RecordingCardItem.kt`,
      `ui/recordings/RecordingsDialogs.kt`, `res/values/strings.xml`,
      `app/src/test/java/.../ui/recordings/SelectionRangeTest.kt` (new, 6/6)
      ⚠️ Build/test/lint green only — the long-press gesture and the kept-protected delete need a device check.

# Code quality
- Dispatcher I/O

## Feature
- [] Notification to other Smartphone when Motion was detected
- [x] Now/Stop Monitor button in the @RecordingScreen
- [x] Alerts & Lockscreen Behavior per camera setting not global
- [x] Make Camera Edit Screen a full sized Screen as dialog its too much settings for a dialog
- [x] **Video Player timeline is now edge-to-edge with the timestamps above it.** Done: the floating rounded
      `Surface` card in `JpegFramePlayer` was replaced by a full-bleed bottom `Column` carrying the same
      black scrim — top row holds the play/pause button and the timestamp readout, the `Slider` timeline sits
      below it flush to the bottom edge across the full screen width. Decode, timing and seek logic unchanged.
      File: `ui/components/JpegFramePlayer.kt`
- [x] **Password fields in the device form can now be made visible.** Done: one shared `PasswordTextField`
      (`ui/settings/DeviceFormComponents.kt`) owns the trailing eye toggle, swapping
      `PasswordVisualTransformation()` for `VisualTransformation.None` on a `remember`ed flag — revealing is
      transient UI state, so it is deliberately not saved. Both former bare-masked fields (intercom `password`,
      `sipPassword`) use it, and it asks for `KeyboardType.Password`, which neither field had before, so
      suggestions no longer sit over a credential. The Settings server API key stays plain text by owner choice.
      Files: `ui/settings/DeviceFormComponents.kt`, `ui/settings/DeviceFormNetworkSection.kt`,
      `ui/settings/DeviceFormSipSection.kt`, `res/values/strings.xml`
- [x] **SIP connection test button** beside the HTTP "Test Connection", on the throw-away-core option the owner
      picked. `SipCallManager.probeRegistration(device)` builds a **second** Linphone core on its own local port
      (never the live 5060, rotating over four so a repeat test cannot inherit one a previous core has not handed
      back yet), registers the typed account and returns a `SipProbeResult`
      (`Registered` / `Rejected(state, reason)` / `NoAnswer(server, lastState)` / `MissingFields`) within
      `SipCallTiming.SIP_PROBE_TIMEOUT_MS`; the form renders it exactly like the HTTP outcome, so a wrong SIP
      password became a line on screen instead of a logcat grep. A `NoAnswer` now says what it saw last:
      `nothing was sent` is a local transport problem, `Progress` only means the REGISTER went out and nothing came
      back — a registrar ignoring it and a network dropping it are indistinguishable from here (the Tab S6 was the
      second: a randomized Wi-Fi MAC made the Fritz!Box refuse that device's SIP outright, see Phase G).
      The live core is never reconfigured, so an abandoned test cannot leave the monitor holding
      unsaved credentials. `applyPbxRegistrar` is now the single implementation the live path *and* the probe share
      — a green test cannot drift from what saving does — and `buildTestCandidate` carries the registrar fields to
      keep that true. ⚠️ Open by design: the probe registers the same account a second time, which some PBXs answer
      by moving the live binding; the form warns whenever a core is already up, and the Phase G gate below is the
      actual proof.
      Files: `sip/SipCallManager.kt`, `sip/SipCallTiming.kt`, `ui/settings/DeviceFormTestSection.kt`,
      `ui/settings/DeviceFormState.kt`, `ui/settings/DeviceEditScreen.kt`, `res/values/strings.xml`
- [x] **SIP registrations are now withdrawn instead of stacking forever.** Reported as "the test always times out
      but the same credentials register on another phone", and the cause was in the live path: `addAuthInfo` /
      `addProxyConfig` only ever append, so every save of an edited device left the **retired** account registered
      and refreshing on the monitor's core, with two proxy configs for one address and the older `AuthInfo` still
      available to answer its 401 challenge. `clearProxyConfig()` (what the peer-to-peer and disabled branches used
      to call) only drops entries from the config — per the SDK it is `removeProxyConfig()` that sends the
      unregister REGISTER — and `destroy()` did a bare `core.stop()`, so stopping monitoring left the phone's
      binding on the PBX until expiry while `initialize()` went on to build a **second core over the same port**.
      Done: one `unregisterAndClearAuth(core)` helper (remove every proxy config, then clear the auth infos)
      called before the live core is reconfigured.
      Two lifecycle rules the probe was violating and now follows, both from the SDK's own docs: build/start/stop a
      core on the **main thread** (liblinphone schedules `iterate()` there and "our API isn't thread-safe"), and
      never treat `stop()` as a synchronous release — which is why the probe holds a `Mutex`, rotates ports, and
      tears down under `NonCancellable` so a screen left mid-test cannot strand a core. Debug builds now get
      Linphone's logcat output (`FLAG_DEBUGGABLE` instead of a hardcoded `false`), so a SIP failure is legible
      without a second phone.
- [x] **…and that first attempt crashed the app on the Test tap, fixed the same day.** The crash was native, not a
      Java exception: `SIGSEGV / null pointer dereference` at `pthread_mutex_lock` ←
      `belle_sip_main_loop_add_source` ← `Account::triggerUpdate()` ← `Account::setState()` ←
      `SalRegisterOp::registerRefresherListener()` ← `linphone_core_iterate()` on the main thread. The logcat
      immediately before it names the mechanism: `notified [account_registration_state_changed]`, one millisecond
      later `Callbacks … unregistered` + `Switching LinphoneCore … On to Shutdown`, then
      `belle_sip_main_loop_run(): reentrancy detected, doing nothing`. **The teardown was running inside
      liblinphone's own registration callback**: `Main.immediate` resumes the waiting probe synchronously from
      `registration.complete()`, so `stop()` destroyed the belle-sip stack underneath `Account::setState`, which
      still had work to do after notifying the listeners — its next main-loop call took a null pointer.
      Two changes, both required: the probe's `finally` hands off to a plain `Dispatchers.Main` message, which
      queues *behind* the running `iterate()` instead of nesting inside it, and the teardown is `removeListener`
      + `stop()` only — `removeProxyConfig()` frees the `Account` a registration state change reports to, so
      account deletion stays where it belongs, on the live core that keeps running. The first version of this fix
      blamed the account deletion alone and still crashed three times; `stop()` on the main thread is not enough
      when the main thread is the callback's caller. **Device-verified 2026-10-07**: two consecutive Test taps
      answered `Registered` (`SIP probe on … answered Registered(…)`, the line that had never printed before), on
      local ports 5091 then 5092 with two different accounts, each probe core reaching `Core released` ~4 ms after
      `registration state: Ok` — the hand-off, not the callback stack, now performs the teardown. No `Fatal
      signal 11` since. What still carries the working doorbell ring → Phase G gate.
      Files: `sip/SipCallManager.kt`, `sip/SipCallTiming.kt`, `ui/settings/DeviceFormTestSection.kt`,
      `res/values/strings.xml`
- [x] **The call screen no longer zooms a landscape camera frame.** `LiveStreamPlayer`'s bitmap renderer used
      `ContentScale.Crop`, which scales a 16:9 snapshot until it *covers* a 9:16 portrait screen — ≈1.8× zoom with
      only about a third of the frame width surviving, so a doorbell filled the phone with the middle of the scene.
      Done: `ContentScale.Fit`, which is what the RTSP branch of the same component already gave through `PlayerView`
      (default resize mode fit), so the two protocols finally agree and the Live card inherits it. Capture was never
      affected — whole frames always went to disk, so no recording lost field of view.
      Files: `ui/components/LiveStreamPlayer.kt`
- [x] **A second trigger during a running recording folds into it instead of being dropped.**
      `RtspStreamRecorder.startRecording` held one slot per device and returned early, at `Log.d`, when a clip was
      already running — so a doorbell pressed during a motion recording produced **no ring file at all**, while the
      notification and full-screen call still appeared and made it look healthy. Stopping the clip to start a ring clip
      was rejected: the muxer is mid-file, so that costs the frame in flight plus the reconnect gap — frames that never
      get recorded. Done: the arriving trigger folds into the live clip. `ActiveRecordingJob` now carries a
      `MutableTriggers` (the trigger the row is filed under plus the folded-in ones) and an `AtomicLong` deadline the
      capture loop re-reads every frame, so a fold both tags the clip and holds it open for the new event's configured
      length. The more significant trigger leads: a ring inside a motion clip is filed under RING with MOTION kept
      alongside, so the press is findable by the ring filter *and* the clip stays findable by the motion filter
      (`RecordingsViewModel` and the `RecordingDao` type filter each match either tag). Persisting that needed a
      `RecordingEntity.alsoEventTypes` CSV column (Room **v14→v15**, `DEFAULT ''`, every existing row keeps its single
      tag) and the card now draws one badge per trigger. `stopRecording` compares its reason against the filed-under
      trigger, so a motion post-record timer still cannot truncate a ring clip, and the doorbell branch stopped
      cancelling that timer on its way past — it used to disarm a running motion buffer even with `recordOnRing` off.
      ⚠️ `PYTHON_SERVER` mode cannot fold: the server owns that job's trigger and duration and its API has no way to add
      one, so a second event there is logged and left out. Build/test/lint green only → Phase G gate.
      Files: `video/RtspStreamRecorder.kt`, `service/IntercomMonitorService.kt`,
      `data/local/entity/RecordingEntity.kt`, `data/local/AppDatabase.kt`, `data/local/dao/RecordingDao.kt`,
      `ui/recordings/GalleryItem.kt`, `ui/recordings/RecordingCardItem.kt`, `ui/recordings/RecordingsViewModel.kt`

## How to read the phases
- **Phase 1–5** — Android app (`app/`, Kotlin). Do these in order; each phase is smaller-blast-radius than the next.
- **Phase 6** — heavy / device-unverifiable app backlog; do NOT start before Phases 1–5 and the Phase G gates land.
- **Phase S** — server + web UI (`server/`), grouped so the one browser-validation session happens at once.
- **Phase G** — on-device validation gates; no code, but each blocks marking its related phase done.

## Design intent (fixed, do not revisit)
- The **primary target camera supports snapshot pulling only** — the app is intentionally built around
  snapshot/MJPEG capture. Missing RTSP/H.264 capture is **not a bug for the main use case**; it only
  matters for future/optional RTSP-only devices. The owner's camera is registered as `TWO_N_VERSO`.
- **Dual-container strategy**: **MKV** is the private, crash-resilient raw-MJPEG capture buffer on the phone
  (incrementally-flushed clusters survive an abort; MP4's trailing `moov` does not). **fragmented MP4** is
  the playable deliverable for encoded H.264 (server encode paths + phone export transcode). The phone muxer
  finalizes in place on close (patches `mvhd`/`tkhd`/`mdhd` durations + appends an `mfra` seek table); a hard
  crash mid-export can leave one file un-finalized (00:00 in strict players) and it self-heals on the next
  completed export.
- **Cheap capture, lazy encoding**: no re-encode at record time (24/7 events → CPU/battery unacceptable).
  H.264 transcode happens only on explicit export/share.
- Capture stays a forward-only local `FileOutputStream` in `filesDir/recordings`; playable output belongs in
  SAF only via export/transcode-on-demand. **(WON'T DO: SAF live capture)**

## Working convention
Done work is frozen — compacted into the **Resolved log**. Only open items are listed as phases. Commit per
phase (or per item), `assembleDebug`/`compileDebugKotlin` + `lintDebug` green before each commit; no push
without the owner. Flag significant trade-offs (CPU/battery/risk) before implementing.

---

# Part 1 — Open work, as planned phases

## Phase 1 — Snapshot-path reliability hardening (the deployment's whole video path)
The camera is reachable **only** through `device.snapshotUrl`, so these small app-local fixes are the highest
value. Diagnosed 2026-09-30 by the LAN measurement block (cause pinned: Wi-Fi RTO chains cost isolated frames;
the 2N itself serves serially at ≈5.9 req/s, fast and keep-alive-friendly). Pacing (fetch-counted rolling
deadline) is already fixed in the recorder and live view — what remains is robustness + surfacing.

- [x] **Snapshot timeout: retry + a real connection state.** `data/network/HttpSnapshotClient.kt` printed one
      `timeout` per failed poll (`Log.w`) on a shared client with 4 s connect/read. A lost request or ACK costs
      one RTO (≈0.44 s observed); three in a row exceed the 4 s window and drop a frame. Done: connect/read
      2 s + `callTimeout` 3 s, one 150 ms-backoff retry for retryable failures only (timeouts, 5xx, 429 — a
      401/404/non-image body is permanent and not retried), a per-device `consecutiveFailures` counter and
      `snapshotQuality()` (ONLINE < 3 misses, DEGRADED 3–9, OFFLINE ≥ 10). The recorder and the motion analyzer
      report quality **edges** as `IntercomEvent.ConnectionState`, the service maps them onto `MonitorStatus`
      (new DEGRADED / OFFLINE) and the live card dot is now state-driven instead of always green.
      Files: `data/network/HttpSnapshotClient.kt`, `video/RtspStreamRecorder.kt`, `domain/device/IntercomEvent`
- [x] **Surface achieved vs configured FPS; cap the setting.** The 2N ceiling is ≈5.9 req/s while `snapshotFps`
      was configurable up to 30; requests above what the endpoint serves are unreachable by construction.
      Done: `DeviceEntity.effectiveSnapshotFps` clamps to `maxSnapshotFps` (6 for TWO_N_VERSO, 30 as the hard
      ceiling) and both the recorder and the live view pace by it; saves are capped in `DeviceFormState`; the
      device form shows the ceiling (and an error tint above it) plus the rate the shared client actually
      measured (`HttpSnapshotClient.achievedFps`, 0f = not polled recently); the recorder logs achieved vs
      configured at the end of every capture and the analyzer health line carries the measured rate.
      Files: `ui/settings/DeviceFormStreamSection.kt`, `data/local/entity/DeviceEntity.kt`
- [x] 🔄 **The MJPEG path stored for the 2N does not exist on this firmware — now routed to the real one.**
      `DeviceEntity.mjpegUrl` (`/api/camera/mjpeg`) answered **HTTP 200 + `application/json`** (`{"error":{"code":2 …invalid request
      path}}`); `data/network/MjpegStreamReader.kt` then found no JPEG boundaries and yielded zero frames, so
      `MJPEG_STREAM` read as a dead camera. `requireMjpegContentType()` already fails loudly on any non-
      `multipart`/`image` type and the stream functions rethrow (recorder → snapshot fallback, live view → error).
      The open "which live path does this firmware serve" question is now answered authoritatively by the
      **2N Streaming manual**: the Verso has **no** `/api/camera/mjpeg` — MJPEG (`multipart/x-mixed-replace`
      server push) is served from the **snapshot endpoint with an added `fps` param** (`/api/camera/snapshot?width=W&height=H&fps=N`, N = 1–10).
      Done: for `TWO_N_VERSO` `mjpegUrl` **derives** from `snapshotUrl` (`…&fps=<effectiveSnapshotFps clamped 1–10>`)
      instead of trusting the stored `mjpegPath` — so `MJPEG_STREAM` and the AUTO→MJPEG fallback produce a real
      stream, and devices saved before this self-heal with **no Room migration**; generic-RTSP devices keep their raw
      path. **Residual device gate → Phase G** (confirm the derived URL paints in the live view / records on the
      Verso; build-verified only today). Related fact kept: `/api/camera/snapshot` without
      `width`/`height` returns `{"code":11 …missing mandatory parameter}` — `DeviceEntity.snapshotUrl`'s parameter
      appending is load-bearing and the derived stream URL inherits it.
      Files: `data/network/MjpegStreamReader.kt`, `data/local/entity/DeviceEntity.kt`
- [x] 🔄 **Motion sensitivity is now a per-device pick (this retunes item 4 from data, no rebuild).** The trigger
      used to be fixed constants (`changedRatio >= 3 %` on **two consecutive** frames, idle backoff
      `MAX_IDLE_POLL_MS = 1500`), so a person crossing in ~1.5 s could yield only one changed pair and never trigger
      — and any retune meant editing constants and rebuilding. Done: a `MotionSensitivity` preset (Room **v10→v11**,
      default `BALANCED` reproducing the exact old numbers so no device changes behavior) now drives the analyzer's
      ratio / required-frames / poll-base / idle-backoff, surfaced in the device form's Triggers section (shown only
      when motion source = "Analyzed in-app", since the 2N's own SSE motion ignores it) with each tier's battery /
      latency trade-off spelled out — `SENSITIVE` (2 % / 1 frame / 400→900 ms) catches fast crossers at more
      battery/network, `POWER_SAVER` (5 % / 3 frames / 800→2500 ms) is calmest but may miss a quick walk-by. The
      60 s health line now prints the active preset + its bar. `recordOnMotionOnDevice` still defaults **false** (the
      plain `recordOnMotion` path is the 2N's own SSE `MotionDetected`, a different pipeline) — first thing to check
      when motion "does nothing". **Residual device gate → Phase G:** read one idle + one walk-past health line on
      the Verso and pick the tier from data, not guesses.
      Files: `video/OnDeviceMotionAnalyzer.kt`, `data/model/Enums.kt`, `data/local/entity/DeviceEntity.kt`,
      `data/local/AppDatabase.kt`, `ui/settings/DeviceFormTriggersSection.kt`

## Phase 2 — Motion feature close-out & accepted trade-offs
Follow-ups from the four snapshot-only fixes (`4b5ed5d` Digest, `abb2153` live loop, `9396b7a` analyzer
backoff, `a620e29` pre-roll; regressions `75a1855`). None is device-verified; the trade-offs below were
deliberately accepted to ship the fixes and are revisit-if-observed, not bugs. The items still `[ ]` after
this pass are exactly those revisit-if-observed trade-offs plus device gates: no code change is due until a
Verso session or a second snapshot-only device type triggers them. **One exception, added 2026-10-05 after
measuring against the Verso: the 2N event-transport item below is a real code fix that is due now**, along with
the owner gate in Phase G that unblocks it.

- [ ] 🔄 **All four Phase-1/2 fixes are build-verified only.** Gates still standing: Digest-only snapshot
      fetch returns frames; Live tab stops polling on background + re-warms on return; motion-end still fires
      during a recording; a walk-past recording includes the approach frames; thumbnail shows the person (not
      the empty doorway); detection speed unchanged in PYTHON_SERVER mode during a server recording. Until one
      Verso session confirms these, treat the fixes as `[~]` in practice. The Phase-1 work (`7f3fe90`) adds two
      more observations to the same session: the live card dot must show DEGRADED/OFFLINE when the endpoint is
      starved, and the settings screen must report a measured rate near 5.9 fps at a 6 fps cap.
- [ ] **Motion-END is slower during recordings (fix `9396b7a` side effect).** `isRecording()` takes priority in
      the poll `when`, so clear frames space to 1500 ms × `REQUIRED_CLEAR_FRAMES = 4` ≈ ≥6 s of quiet to end a
      recording (was ≥2 s) → longer files, later post-record stop. Acceptable vs endpoint contention; revisit if
      recordings look over-long on device. File: `video/OnDeviceMotionAnalyzer.kt`
- [~] 🔄 **Pre-roll ring costs ~1–2 MB + GC churn 24/7 (fix `a620e29`).** `bufferPreRoll` ran on every
      successful fetch, holding 6 full-res JPEGs per motion-enabled device. Done: the ring is only refilled
      while no local recording owns the endpoint — those frames are already going to disk, and the buffer
      refills within a few polls once the recording stops, which is when a *new* trigger needs it. **Remaining
      device gate:** watch heap/GC in a long-run soak before calling pre-roll stable.
      File: `video/OnDeviceMotionAnalyzer.kt`
- [ ] **Uneven pre-roll frame spacing → jittery clip start (fix `a620e29`).** Buffered frames carry real
      spacing (500 ms active, up to 1500 ms idle) but are written against the earliest-frame baseline, so the
      first ~3–6 s play at uneven, slow intervals. Uniform synthetic spacing would look smoother at the cost of
      lying about timestamps. File: `video/RtspStreamRecorder.kt`
- [ ] **Pre-roll-only files are now kept instead of discarded (fix `a620e29`).** If the endpoint dies right
      after trigger, the 6 pre-roll frames now make a valid short file instead of a 0-byte discard. Arguably
      correct, but expect short "ghost" recordings when the endpoint flaps. File: `video/RtspStreamRecorder.kt`
- [x] **Digest endpoints pay 2 round-trips per snapshot (fix `4b5ed5d`).** `HttpSnapshotClient` always sent
      preemptive Basic first; a Digest-only device 401s, then the `Authenticator` retried with Digest — doubling
      latency on the ~5.9 req/s serial ceiling. Done: the first Digest challenge is remembered per device and
      replayed preemptively on subsequent polls (`DigestAuthenticator.preemptiveHeader`), so a Digest-only
      endpoint is one request again. A stale nonce self-heals (the server answers 401 with a fresh challenge,
      the `Authenticator` retries and refreshes the cache); a 401/403 that survives that round drops the cache
      and falls back to Basic, and `forgetDevice()` clears it. File: `data/network/HttpSnapshotClient.kt`
- [x] **MJPEG branch of the live view is still lifecycle-ungated (fix `abb2153` scope).** `repeatOnLifecycle`
      covered HTTP_SNAPSHOT only. Done: the `MJPEG_STREAM` collect is gated on `STARTED` too, so a long-lived
      multipart response is cancelled on STOP (ending the call) and reconnects when the user returns.
      `RTSP` stays ungated on purpose — ExoPlayer's own surface handling governs it and no RTSP device is in
      the deployment; revisit with the Phase 4/6 RTSP passthrough work. File: `ui/components/LiveStreamPlayer.kt`
- [ ] **Doorbell trigger has no snapshot-only path — and on this deployment SIP is the *only* ring source.**
      Ring detection comes from the device's own events or an inbound SIP INVITE; a snapshot carries no ring
      signal. The premise that "both 2N channels are active" is **false**, measured 2026-10-05 (see the
      event-transport item below): the SSE endpoint does not exist on this firmware and neither do the fallback's
      status endpoints, so nothing has ever arrived over HTTP here. What that leaves is one path — the INVITE —
      which additionally requires the monitor service to be alive (SIP is initialized in its `onCreate`) and,
      behind a PBX, `handleSipRing`'s by-count attribution (Phase 3). A ring is therefore one wrong switch away
      from vanishing with only `ConnectionState` + logcat to say so. Rides on the SIP gate (Phase G) and the
      item below.
      Files: `data/device/TwoNIPVersoDevice.kt`, `service/IntercomMonitorService.kt`, `sip/SipCallManager.kt`
- [ ] **Replace the invented 2N event transport with the real Logging API (`/api/log/subscribe` + `/api/log/pull`).**
      Verso FW 2.50.1.76.4, probed over the LAN 2026-10-05: `GET /api/event/subscribe?events=…` answers **HTTP 200 +
      `application/json`** `{"success":false,"error":{"code":2,"description":"invalid request path"}}`, and so do
      `/api/event/stream`, `/api/event/get`, `/api/event/socket` — there is **no SSE on this API at all**. okhttp-sse
      refuses the content type, so `onFailure` → `startPollingFallback` fires *every* start, and that fallback polls
      `/api/motion/status` + `/api/noise/status`, which are not 2N paths either (same `code 2`): no `result.active`,
      four dead polls, `ConnectionState(OFFLINE)` on the `DEVICE_EVENTS` capability while the snapshot path keeps
      serving frames — exactly the "reachable but no events" shape the two-capability split was built for.
      2N's actual event mechanism is the **Logging API**:
      `GET /api/log/subscribe?filter=KeyPressed,KeyReleased,CallStateChanged,MotionDetected,NoiseDetected&include=new&duration=90`
      → `{"success":true,"result":{"id":…}}`, then drain `/api/log/pull?channel=<id>` and release with
      `/api/log/unsubscribe`; each channel owns a queue, and it closes on its own after `duration` unless a pull
      extends it, so the loop must re-subscribe on expiry rather than assume a permanent handle.
      `handleRaw2NEvent`'s decode (`event` + `params`, `triState`, the `KeyPressed` release deny-list) transfers
      nearly unchanged because log entries carry the same names — **but the real `pull` body is unverified**: the
      device refuses the function for this account today. Over HTTP every Logging / Call / IO / phone /
      System-status / automation path returns `code 7 invalid connection type` (2N's table: HTTPS required; only
      Camera and `/api/system/info` answered), and over HTTPS the same admin credentials pass on
      `/api/camera/snapshot` yet get `401 code 9 authorization required` on `/api/log/caps` and `/api/log/subscribe`.
      So the 2N-side owner gate (Phase G) is a prerequisite, not a footnote.
      Consequence wider than the doorbell: the device form's motion source **"From camera"** and the
      **record-on-noise** trigger cannot fire at all on this deployment — both wait on `MotionDetected` /
      `NoiseDetected` from the same dead transport, which is why in-app motion analysis is the only working motion
      path today and noise has no working path at all (no in-app audio analyzer exists).
      ⚠️ Trade-off to settle before coding: pull-based events cost **one HTTP request every 1–2 s per device,
      24/7** where SSE was meant to be a single long-lived connection — small JSON, no frames, but on the Verso's
      serial ≈5.9 req/s ceiling shared with the live/recording snapshot path and never idle. The cheaper design is
      to delete the HTTP event path outright and run rings off the SIP INVITE plus motion off the in-app analyzer,
      at the cost of the camera's own motion/noise and `CallStateChanged`. **Decided 2026-10-07 by the owner: the
      Logging API transport — the full replacement above, not the cheaper deletion.** That knowingly accepts one small
      request every 1–2 s per device around the clock on the Verso's ≈5.9 req/s serial ceiling, shared with the live
      view and recordings; if the Phase G gate still refuses the Logging API after the service is enabled for the
      app's credentials, the decision reverts to the delete-the-HTTP-path branch and this closes as
      wont-fix-on-this-firmware. Sequenced after the SIP Test gate in Phase G, together with the two gating items below.
      Files: `data/device/TwoNIPVersoDevice.kt`, `service/IntercomMonitorService.kt`
- [~] 🔄 **Gate the 2N status polling on the triggers the user actually asked for.** Measured on the Tab S6 2026-10-07:
      from monitoring start until the service was destroyed, `2N_Verso_1` logged
      `Noise status poll returned no result.active from http://192.168.178.10:80/api/noise/status` every 1.53 s
      (13:12:40.562 → 13:13:21), one line per endpoint per cycle — ≈78 requests and ≈100 warning lines per minute per
      device, 24/7, producing **zero** events. The loop (`TwoNIPVersoDevice.kt:209-259`) reads neither
      `recordOnMotion`/`recordOnNoise` nor `wakeOnMotion`/`wakeOnNoise`, so the owner's framing — "pull without it being
      toggled" — is exactly right: turning both triggers off changes nothing. The tablet's Room row read afterwards
      agrees (`recordOnMotion = 0`, `recordOnNoise = 0`, `recordOnRing = 1`) but carries no timestamp, so it
      corroborates the report rather than proving the flags as of 13:12 — the code is the proof either way, the loop
      reads neither flag. Done (compile/`lintDebug`/`testDebugUnitTest` green, 🔄 device gate open):
      - one poll per trigger, gated on what consumes it: a `StatusPoller` is built for motion only while
        `recordOnMotion || wakeOnMotion` and for noise only while `recordOnNoise || wakeOnNoise` (a wake-only trigger
        still needs the event, it just does not arm a clip), and the loop does not start at all when neither is wanted;
      - `requestedEventNames()` is now the single rule behind the SSE `events=` filter and the poll set alike, so the
        Logging API item above inherits the gate when it lands instead of re-learning it;
      - both triggers off emits **no** `ConnectionState` — it is a healthy state, not a degraded one — and the
        entry DEGRADED line now names only the endpoints actually being polled;
      - a body saying `code 2 invalid request path` (or an HTTP 404) is classified `Absent`, i.e. permanent for this
        firmware: that endpoint drops to one re-probe every 5 min instead of ≈40 requests an hour, and the OFFLINE
        report goes out once on the first such answer (with the recovery edge kept) rather than after four more dead
        polls. Lowering the log level was rejected — the request is the cost;
      - the polls run on a client derived from the authenticated one with 2 s connect/read + 3 s call timeout,
        because the shared SSE client's indefinite read timeout let one silent endpoint park the whole loop.
      **Still open:** the `*Ended` edge is **not** preserved across a mid-clip flag flip.
      `updateMonitoredDevices` tears the device down and rebuilds it on every row change, so the poller dies with the
      old session and no per-instance latch can outlive it — keeping that early stop needs the armed-trigger knowledge
      to live in the service next to `pendingPostRecordStops` (Phase 4 territory), which is why no latch was written
      here rather than unreachable code. A stale DEGRADED camera-event glyph can also survive that rebuild: "the user
      never asked for this endpoint" has no representation in `MonitorStatusHolder` (only ONLINE/DEGRADED/OFFLINE).
      ⚠️ Gate → Phase G: on the Verso with both camera triggers off, monitoring must log
      `HTTP event polling not started — no camera motion/noise trigger requested` and produce no
      `/api/motion/status` / `/api/noise/status` traffic at all; with motion on and noise off, only motion is polled.
      Files: `data/device/TwoNIPVersoDevice.kt`
- [ ] **Do not offer triggers the transport cannot deliver.** The form already refuses "From camera" motion on
      generic RTSP cameras for exactly this reason (`DeviceFormTriggersSection.kt:38-40`), but on the Verso it is
      offered anyway and has never fired: the only 2N event delivery today is the polling fallback reading two
      nonexistent endpoints. The ring still has the SIP INVITE to fall back on, but camera-motion, `recordOnNoise`,
      `wakeOnNoise` and the noise glyph on the live card all promise a signal that cannot arrive on this deployment.
      The snapshot path cannot substitute — it carries JPEG bytes, no audio and no device-side motion state — so
      "noise/motion over the snapshot poll" is not a missing feature but an impossible one, and the form should say
      so instead of defaulting
      it on (`DeviceEntity` ships `recordOnMotion = true` / `recordOnNoise = true`, so every newly created device asks
      for events it will never receive). Gate on the **measured** capability, not the device type: once the Logging
      API item lands, `/api/log/caps` says which event classes the device reports and the form renders only those;
      until then mark camera-motion and noise unavailable on `TWO_N_VERSO` with the reason, keeping the in-app
      analyzer the only selectable motion source, and pre-fill a new device accordingly.
      Files: `ui/settings/DeviceFormTriggersSection.kt`, `ui/settings/DeviceFormAlertsSection.kt`,
      `ui/settings/DeviceFormState.kt`, `ui/live/LiveCamerasScreen.kt`, `data/local/entity/DeviceEntity.kt`
- [ ] **Snapshot URL forces `width=1280&height=720` when the path omits them.** Confirmed load-bearing on the
      one Verso, so the deferred criteria ("only if a second snapshot-only device type appears") are not met.
      Make the appended size configurable only if a non-2N snapshot-only camera is added.
      File: `data/local/entity/DeviceEntity.kt`

## Phase 3 — Trigger-pipeline cleanup (reported, deliberately unfixed)
From the 2026-09-30 code-smell pass over the event → recording → alert path. Small-to-medium app work, no
cross-layer design pivot except where noted.

- [x] **Event-router duplication.** `MotionStarted` and `MotionOnDeviceStarted` were ~20 near-identical lines
      (differing only in which record flag they test), and the three `*Ended` branches differed only in the
      `EventType` passed to `schedulePostRecordStop`. Done: `handleTriggerStarted` / `handleTriggerEnded` own the
      routing, the branches keep just their log line and their differences as arguments (flag that authorises
      recording, pre-roll provider, log label), the wake flag / notification kind / post-record duration are
      derived from the `EventType` by one exhaustive `when`, and the bare `+ 30` safety headroom is now
      `TRIGGER_RECORD_HEADROOM_SECONDS`. File: `service/IntercomMonitorService.kt`
- [x] **`IntercomEvent.CallState` has no consumer** — the service only `Log.d`s it. Done: dropped the event and
      its emission (the 2N `CallStateChanged` SSE payload still logs state/direction where it is parsed, and the
      ring decision from it is unchanged), per the dead-code convention. Files: `domain/device/IntercomEvent.kt`,
      `data/device/TwoNIPVersoDevice.kt`, `service/IntercomMonitorService.kt`
- [x] **`ConnectionState` cannot express "degraded".** Closed by the Phase 1 API decision rather than an
      invented field: `ConnectionQuality` (ONLINE / DEGRADED / OFFLINE) on `IntercomEvent.ConnectionState`, mapped
      onto the new `MonitorStatus.DEGRADED` / `OFFLINE` by the service and shown by the live card dot. The SSE
      polling fallback now reports DEGRADED instead of `{isConnected = true, message = "…unavailable…"}`.
      Files: `data/model/Enums.kt`, `domain/device/IntercomDevice.kt`, `service/IntercomMonitorService.kt`
- [x] **Coupled magic constants in two files.** `POST_CALL_IDLE_MS = 2500` (`sip/SipCallManager.kt`) had to stay
      larger than `TERMINAL_CALL_DISMISS_MS = 1500` (`ui/incoming/IncomingCallActivity.kt`) or the auto-dismiss
      quietly stopped firing. Done: both live in `sip/SipCallTiming`, and the dismiss value is *derived*
      (`POST_CALL_IDLE_MS - DISMISS_HEADROOM_MS`), so the invariant cannot be broken by editing one number.
      Dismissing on IDLE itself was rejected: it would stretch the terminal bar to the whole idle window.
- [x] **`SipCallManager.onIncomingCall` is a mutable non-volatile callback property**, invoked on the Linphone
      core thread while assigned from the service. Done: `@Volatile` plus a note that a second consumer should
      get a Flow instead of another callback property (the project's `Channel<UiEvent>` convention does not fit a
      process singleton with one non-UI consumer). File: `sip/SipCallManager.kt`
- [ ] **`handleSipRing` device attribution is a by-count heuristic.** Behind a PBX the remote host is the PBX,
      not the intercom, so "if exactly one device is monitored, attribute the ring to it" is not identity-based.
      Logged at info; only correct for single-device deployments. No app-side identity exists until the SIP
      identity/registration work (Phase 6 per-device cores, Phase G P2P gate) lands.
- [x] **The 5 s ring debounce also swallows a genuine second press** inside the window (recording keeps running,
      no second alert). Intended trade-off — documented at the constant itself so the next reader of the router
      learns it from the code, not from this file.
- [x] **`SipSessionState.callerAddress` / `callerDisplayName` are write-only** (the overlay renders the caller
      from the intent extras). Done: fields deleted, so there is one source for the caller again.
      Files: `sip/SipCallManager.kt`, `ui/incoming/*`

## Phase 4 — Service & data-layer refactors (larger blast radius)
- [x] **Server-mode stop reconciliation.** Auto-stop used a fixed `maxDurationSeconds + 2` local timer with an
      unconditional stop, so app and server drifted both ways: the phone kept showing REC after the server had
      finalized, and a `maxDurationSeconds + 2` stop could cut short a job the server reported as
      `already_recording` (someone else's duration). Done (app side): the recorder tracks an
      `ActiveServerRecording` (trigger type, requested duration, `startedByThisRequest`, `recordingId`) and
      `watchServerRecording` reconciles it against `/api/status.active_recordings` every 10 s — local tracking is
      dropped as soon as the server has no job, the explicit stop only goes out for a job this request started
      (or after 3 failed probes, where it is the safe fallback), and a job that outlived the requested duration is
      left alone. `ServerRecordingClient` now reads the start/stop bodies (`started` vs `already_recording`,
      `stopped` vs `not_recording`) and an optional `recording_id` instead of trusting HTTP 200.
      **Done (persistence):** the in-flight server recording is now persisted, so the reconcile survives a process
      restart. New transient Room table `active_server_recordings` (v11→v12; one row per device: `deviceId` PK,
      `recordingId`, `eventType`, `maxDurationSeconds`, `startedByThisRequest`, `startedAtMs`) behind
      `ActiveServerRecordingDao` + repository methods; `RtspStreamRecorder` is a process singleton, so its map alone
      died with the process. The recorder writes the row on a successful server start and deletes it on every clear
      path (`clearServerRecording`, the app-initiated stop). The reconcile deadline is now anchored to the persisted
      `startedAtMs` (`startedAtMs + maxDurationSeconds + grace`) instead of "now", so a resumed watcher honours the
      time already elapsed rather than granting a fresh duration. `IntercomMonitorService.updateMonitoredDevices`
      calls `resumePersistedServerRecordings(enabledDevices)` (idempotent) to re-adopt rows and relaunch watchers;
      rows whose device is unmonitored or whose mode is no longer `PYTHON_SERVER` are dropped (the server finalizes
      on its own). Reconcile still keys off `/api/status` by `deviceId`; the persisted `recordingId` is carried for
      identity and the Phase 5 surfacing work, and `GET /api/recordings/{id}` remains the id-based alternative if
      device-keyed probing ever proves insufficient. Compile + `lintDebug` green; the live server+reboot check is a
      Phase G gate. Files: `video/RtspStreamRecorder.kt`, `data/server/ServerRecordingClient.kt`,
      `data/local/entity/ActiveServerRecordingEntity.kt`, `data/local/dao/ActiveServerRecordingDao.kt`,
      `data/local/AppDatabase.kt`, `data/repository/IntercomRepository.kt`, `service/IntercomMonitorService.kt`
- 🔭 **RTSP H.264 passthrough** (demux → re-mux, zero re-encode/CPU) for stream-capable cameras — capture-pipeline
      rewrite, only for future RTSP-only devices. Not scheduled.

## Phase 5 — Cross-cutting UI features (design decision required)
Design decided with owner 2026-10-03: merged list with composite keys, read-only first cut; mutating actions +
pull-to-refresh added same day. The Phase S device-sync identity gap is now closed (app devices carry a server id).
- [x] **Surface server recordings in the app.** Done: the Recordings gallery merges the local Room recordings and
      the server's into one timestamp-sorted list behind a `GalleryItem` sealed model (`Local` / `Remote`) keyed by
      a composite `stableKey` (`local:<id>` / `server:<id>`), so the two independent `Long` id spaces share one
      `LazyColumn` without colliding. `RecordingsViewModel` calls `ServerRecordingClient.listRecordings()` lazily
      on screen entry and on pull-to-refresh (only in `PYTHON_SERVER` mode; a failure toasts and leaves local rows
      intact — the network fetch never gates the Room flow) and resolves each remote thumbnail/video path to an
      absolute URL carrying `?api_key=` (Coil/ExoPlayer can't set the `X-API-Key` header). Server rows reuse the
      card with a "Server" tag and are excluded from multi-select; `VideoPlayerModal` was generalized to a
      `PlaybackTarget` (local file → JPEG-MKV player or ExoPlayer; remote → ExoPlayer streaming the fMP4).
      Mutating actions are wired through the same card menu: protect toggle (`POST /api/recordings/{id}/protect`),
      delete with the shared confirm dialog (`DELETE /api/recordings/{id}`), and share/save-to-gallery/export-to-folder
      via `exportServerRecording` — the server file is already H.264, so it streams into the app cache
      (`ServerRecordingClient.downloadVideo`, no read timeout, extension from `Content-Type`) under a progress dialog
      and is then handed to `ExportHelper`, which gained naming-based overloads so a downloaded file needs no local
      row. `ExportHelper` share/gallery take a `deviceName`/`eventTypeLabel` pair; the `Share` UI event carries those
      for the server case. Compile + `lintDebug` + `testDebugUnitTest` green.
      ⚠️ Device gate → Phase G "Server recordings in-app" (thumbnail load + LAN playback + the three mutating actions
      against a live server); also exercises the `?api_key=`-in-URL exposure, unaddressed until Phase S HTTPS-first.
      Remote rows are now device-filtered by `serverDeviceId` (Phase S sync), with a name fallback for unregistered
      / pre-sync devices.
      Files: `ui/recordings/GalleryItem.kt`, `ui/recordings/RecordingsViewModel.kt`,
      `ui/recordings/RecordingsScreen.kt`, `ui/recordings/RecordingCardItem.kt`, `ui/recordings/RecordingsDialogs.kt`,
      `ui/components/VideoPlayerModal.kt`, `data/server/ServerRecordingClient.kt`, `util/ExportHelper.kt`
- [x] **Live view through the server** — consume `/api/live/{id}/mjpeg` in `LiveStreamPlayer`. Done (the Phase S
      sync gave app devices a `serverDeviceId`, making the endpoint addressable): in `PYTHON_SERVER` mode a
      registered device's card now pulls one MJPEG stream from the server (which owns the camera poll) instead of
      the phone dialing the device IP. `MjpegStreamReader.streamBitmapsFromUrl` reads the multipart stream with an
      `X-API-Key` **header** (no `?api_key=` in the URL — OkHttp can set headers, unlike the Coil/ExoPlayer image
      paths); `LiveStreamPlayer` gains a `serverLiveUrl`/`serverApiKey` branch that renders server bitmaps and
      **falls back to the direct-to-device path** on any stream failure (latched `serverFailed`, reset on retry /
      device switch); `LiveUiState` folds in `AppSettingsEntity` so `LiveCamerasScreen` computes the URL only when
      `recordingMode == PYTHON_SERVER && serverDeviceId != null`. `APP_LOCAL` and unregistered devices are
      unchanged. Verified compile/lint (live e2e → Phase G "Live view through the server" gate). Files:
      `data/network/MjpegStreamReader.kt`, `ui/components/LiveStreamPlayer.kt`, `ui/live/LiveViewModel.kt`,
      `ui/live/LiveCamerasScreen.kt`, `res/values/strings.xml`

## Phase 6 — Heavy / device-unverifiable app backlog
Large, risky, or impossible to validate from CI; each needs a real device/PBX pass. Do NOT start before
Phases 1–5 and the Phase G gates are done.
- [ ] **MediaCodec color-format selection** — stop hardcoding deprecated `COLOR_FormatYUV420Planar`; read
      `getCapabilitiesForType(MIMETYPE_VIDEO_AVC).colorFormats`, prefer NV12 → planar → flexible; replace
      `bitmapToI420()` for the SELECTED layout; honor stride/slice-height. ⚠️ Device gate: re-export and verify
      the H.264 fMP4 still plays in VLC (Phase G pipeline note). File: `video/H264Encoder.kt`
- [ ] **Linphone `ProxyConfig` → `Account` API migration** (+ `createProxyConfig/edit/done/addProxyConfig/
      defaultProxyConfig/clearProxyConfig`, `setDebugMode`) — large SIP refactor; ties into per-device cores.
      File: `sip/SipCallManager.kt`
- [~] **SIP per-device cores** (DEFERRED — revisit with real device/PBX) — one global Linphone core
      reconfigured per device in `updateMonitoredDevices`; with multiple devices the last wins; P2P vs PBX
      cannot coexist. File: `sip/SipCallManager.kt`
- [~] **Always-on wake/wifi locks** (DEFERRED — dedicated detection-first device; never missing a ring beats
      battery) — keep locks only during active recording/ring, not idle monitoring.
      File: `service/IntercomMonitorService.kt`
- [~] **Credential storage** (DEFERRED) — HTTP/SIP passwords + server API key plaintext in Room →
      Keystore-backed Room TypeConverter + migration (large, device-unverifiable security pass).
- [~] **Network transport** (DEFERRED) — `network_security_config` permits global cleartext + trusts user CAs
      (MITM) → scope cleartext to the local subnet, drop user-CA trust. ⚠️ Dropping user CAs breaks self-signed
      HTTPS on 2N devices; only after the server is HTTPS-first (Phase S).
- [~] **Credential leakage in RTSP URLs** — `rtsp://user:pass@host` embedded URLs get logged/persisted by
      external libs. No app-side log emits it today (verified); Media3/ffmpeg logging is outside our control →
      effectively **wont-fix** unless auth-via-header on `GenericRtspDevice` is implemented with an RTSP device.
- [ ] **Linphone ABI coverage** (DEFERRED) — only `jni/arm64-v8a/liblinphone.so` committed; `jni/` isn't wired
      into `sourceSets`/`jniLibs.srcDir`, so packaging is unclear and blind `abiFilters` could exclude devices.
      Needs a release-bundle ABI inspection first.
- [ ] 🔭 **Material You dynamic color** (DEFERRED by decision 2026-10-04) — `dynamicDarkColorScheme`/
      `dynamicLightColorScheme` in `AppTheme` behind a new persisted opt-in flag. Deliberately not folded into the
      light-role work: dynamic color replaces the WHOLE scheme, so the curated presets + `AccentPaletteContrastTest`
      become the opt-out path and the swatch/role UI needs an explanatory hint ("system palette overrides accents").
      Files: `ui/theme/Theme.kt`, `data/local/entity/AppSettingsEntity.kt` (+ Room migration),
      `ui/settings/SettingsAppearanceCard.kt`

## Phase S — Server & web UI 🖥️
Sorted minimal → architectural. Headless verification is possible for most; items marked ⚠️ need the server
running in a real browser — that is the one owner session covering the Firefox checklist below.
- [~] **Web UI playback note (dual-container aware).** Modal shows `#mkv-hint` and, on `video.onerror`, a
      `#playback-warn` with a download link, reworded for the fMP4 default. Server boots clean and serves the
      HTML; ⚠️ remaining = the owner Firefox checklist (below). File: `server/entry_recorder_server/static/index.html`
- [x] **Split `index.html` into static assets** — the 1368-line single file (style/markup/inline script) is now
      three: `static/index.html` (278 lines, markup only), `static/styles.css` (526) and `static/app.js` (565),
      referenced as `/static/styles.css` + `/static/app.js`. No `main.py` change was due — `/static` is already
      mounted, so the assets are served as-is. The harness `server/tests/webui_auth_harness.mjs` was repointed in
      the same commit to run `app.js` directly instead of regex-extracting `<script>` blocks from the HTML; re-run
      → 36/36 green. ⚠️ Owner gate: load the page in the Firefox checklist session and confirm styling + script
      actually load from `/static/` (no 404s) and the auth/poll behaviour is unchanged. Files:
      `server/entry_recorder_server/static/{index.html,styles.css,app.js}`, `server/tests/webui_auth_harness.mjs`
- [x] **Server ↔ app device sync (one-way, app→server)** — the app now registers each device on the server
      (`POST /api/devices`) and stores the returned row id as `DeviceEntity.serverDeviceId`, using it ONLY at the
      HTTP boundary while every internal key (active maps, notifications, `active_server_recordings.deviceId`) stays
      the local Room id. Done: v12→v13 Room migration (`ALTER TABLE devices ADD COLUMN serverDeviceId INTEGER`);
      `ServerRecordingClient` gains `registerDevice`/`updateServerDevice`/`deleteServerDevice` + a `ServerDeviceDto`,
      and `startRecording` now sends `serverDeviceId` with NO credentials (the server resolves them from the
      registered row via the existing `get_device_by_id` fallback → the device password no longer rides every start);
      `SettingsViewModel.saveDevice` register-or-update (PYTHON_SERVER only, best-effort — local save never blocked),
      `deleteDevice` best-effort server delete, `restoreBackup` nulls `serverDeviceId` (re-register against the current
      server); `RtspStreamRecorder` registers just-in-time (`ensureRegistered`) if a device reaches a server start
      unregistered, else falls back to local, and threads `serverDeviceId` through watch/stop/`activeRecordingFor`;
      `RecordingsViewModel` attributes remote rows by `serverDeviceId` (name fallback), closing the Phase 5
      "filtered by name" caveat. Server unchanged; no server schema change. Verified compile/lint (live e2e → Phase G
      "Server device sync" gate). This unblocks "Live view through the server" (`/api/live/{serverDeviceId}/mjpeg` is
      now addressable — separate follow-up). Files: `data/local/entity/DeviceEntity.kt`, `data/local/AppDatabase.kt`,
      `data/server/ServerRecordingClient.kt`, `ui/settings/SettingsViewModel.kt`, `video/RtspStreamRecorder.kt`,
      `ui/recordings/RecordingsViewModel.kt`
- [x] **Server: return + expose the recording id** (feeds Phase 4 stop reconciliation) — start did not hand
      back a persistable id, so `/api/recordings/{id}` couldn't reconcile an auto-stop. Done: the `recordings`
      table gains a `status` column (`recording` → `completed`) migrated via the same `PRAGMA`+`ALTER` pattern as
      `devices.live_mode`; `start_recording` now pre-inserts the row in `recording` state and returns its id (and
      `already_recording` returns the running job's id via `get_active_recording_id`); `_finalize_recording` updates
      that row (or deletes it when the capture ends empty) instead of inserting a new one; the list/stats/cleanup
      queries filter to `completed` so in-progress rows never surface, while `GET /api/recordings/{id}` still returns
      them (now carrying `status`) for reconciliation. The app was already ready: `ServerStartResponse` parses
      `recording_id`, the recorder stores it. Verified headless (`py_compile` + DB lifecycle/migration/import smoke
      tests green). ⚠️ Owner gate → Phase G "Server recording-id reconciliation" (live-server checks + migration
      over real data). Files: `server/.../main.py`, `server/.../recorder.py`, `server/.../database.py`,
      `server/.../models.py`
- [ ] **Server: HTTPS-first** (unlocks Phase 6 network-transport cleanup) — mandatory auth is in place but the
      server is still plain HTTP behind global cleartext. TLS, or an explicit decision to stay LAN-only HTTP,
      before dropping user-CA trust app-side. Files: `server/.../main.py`, `server/docker-compose.yml`, `server/Dockerfile`
- 🔭 **`GET /api/devices` credential handling review** — returns no passwords today; revisit if the sync API
      above starts round-tripping device credentials.

  ### Owner checklist — Firefox (the one GUI-browser gate in the server block)
  Prereq: server running (`cd server; entry-recorder-server`) and its key (`.env` `API_KEY` or the generated
  `server/data/.api_key`). Open `http://<server>:8000/`, enter the key. Data: one **fMP4** row and one **legacy
  `.mkv`** row.
  1. **Auth wiring** — stats, live cards, thumbnails render (no 401s, no "key rejected" alert). DevTools Network
     must show `/static/styles.css` + `/static/app.js` served 200 (the split assets load) — a dark/unstyled page = a
     404 there. Fail = stop here.
  2. **Key change** — 🔑, wrong key → exactly one alert + redacted retry, poll must not re-prompt every 5 s; right key recovers.
  3. **fMP4 inline** — MP4 card thumbnail → modal opens, frames paint, non-zero duration, scrubbing works, `#playback-warn` hidden.
  4. **No false warning** — while it plays, `/video` is 200/206 `video/mp4`; HEAD probe is 200.
  5. **Legacy MKV fallback** — `.mkv` card → `#playback-warn` reads **(player error 4: source not supported)**,
     ⬇️ downloads the file; confirm it opens in VLC.
  6. **Live view** — server camera shows MJPEG; watch ~15 s, must not blank per poll, DevTools shows one long-lived
     `/api/live/<id>/mjpeg`. Start/stop on the card changes the label without restarting the picture; ⛶ survives 15 s;
     unplug dims, replug + click reconnects. (Closes the live-card item.)
  7. **Safari** — cannot be covered on Windows; if ever checked, steps 3 and 5 apply.
  Mark the playback-note item `[x]` only when 1–5 pass (6 closes the live-card item); report which step failed.

## Phase G — On-device validation gates 🔄 (blocks closing related phases)
Compile-green ≠ done; run on the owner's real hardware before closing.

### targetSdk 37 / edge-to-edge (needs Android 15/16)
- [ ] Recordings top band sits below the status bar, not behind the clock. **Re-opened 2026-09-29** — the pass
      belonged to the old hand-rolled Column; it is now a real `TopAppBar` (heading 32→22 sp). Re-check both.
- [ ] Incoming-call screen: header and call controls clear status & gesture/nav bars on punch-hole/gesture-nav;
      a landscape camera frame is fully visible (letterboxed by `ContentScale.Fit`, no longer cropped to a zoom) with
      the video container still fullscreen behind the bars.
- [ ] Locked-screen doorbell ring → full-screen intent still fires and shows `IncomingCallActivity` over keyguard.
- [ ] Motion/noise → high-priority heads-up notification appears (screen may NOT wake directly from the service
      on Android 15/16 due to BAL rules); tapping opens `IncomingCallActivity`.
- [ ] Reboot → `BootReceiver` autostart of the `connectedDevice` FGS still succeeds
      (`ForegroundServiceStartNotAllowedException` regression check).

### Ring / noise / SIP (needs the intercom)
- [ ] 🔄 **2N Logging API is reachable at all — the prerequisite for the Phase-2 event-transport item.** On the
      Verso: Settings → Services → HTTP API → enable the **Logging** service (plus Phone/Calls monitoring) for the
      connection the app uses and grant that API user the Keypad / Call-monitoring privileges. Then confirm
      `GET /api/log/subscribe?filter=KeyPressed` returns a `result.id` (today: `code 7 invalid connection type` over
      HTTP, `401 code 9 authorization required` over HTTPS with the stored admin credentials, while Camera passes)
      and capture **one real `/api/log/pull` body of a doorbell press** — that payload, not the manual, is the spec
      for the decode rewrite, and it finally settles the `KeyPressed` action wording the deny-list guesses at today.
- [ ] 🔄 **Peer-to-peer SIP is unverified, not a known bug.** `configureDeviceSip` in P2P clears proxy/auth and
      rewrites UDP/TCP ports on an already-started core, never sets an identity address, so inbound INVITE
      delivery depends on the 2N dialing this phone's IP:port directly. Now load-bearing on its own: the SIP ring
      source covers rings while the device event stream sits in its polling fallback, and because that event
      endpoint does not exist on this firmware (Phase 2) the fallback is the only state it ever reaches — the
      INVITE is today's sole ring path. If no INVITE reaches the phone the ring gap stays open.
- [ ] 🔄 **The SIP registration test button tells the truth.** `Test SIP Registration` must answer `Registered`
      against the Fritz!Box with the real account and `Rejected` (not `NoAnswer`) when the SIP password is
      deliberately wrong — that distinction is the whole feature. **Confirmed part:** two taps in a row returned
      `Registered` for two different accounts (no timeout on the repeat, no crash). **Still open:** then run it
      **while monitoring is active** and
      press the doorbell once: the ring still has to reach the phone, i.e. the probe's second REGISTER did not
      take the live binding for good. Also confirm the probe leaves an in-progress call alone (it opens no audio
      device on purpose) and that `lintDebug`/`compileDebugKotlin` were green on a real device, not just here.
      If it ever fails, read the `(last state: …)` suffix: `nothing was sent` is local (port/transport),
      `Progress` only means the REGISTER left the device and nothing came back — the app cannot tell "the PBX saw it
      and stayed silent" from "it never reached the PBX", which is exactly what the Tab S6 measurement below settled.
      **Press it three or four times** — the crash this came back with was a native `SIGSEGV` a moment after a
      probe answered, so a tap that shows a result and then kills the app is the same bug, not a new one. Its
      fingerprint in logcat is `registration state: Ok` followed ~50 ms later by a `Fatal signal 11` **and no
      `SIP probe on … answered` line**: the probe died in its own teardown. Both crash rounds reproduced that
      exact shape, so if `answered` appears, the teardown is no longer the problem.
      **Tab S6 (SM-T860, Android 12) 2026-10-07 — measured, and it was not the app: the randomized Wi-Fi MAC.** The probe answers
      `NoAnswer(server=192.168.178.1:5060, observed=Progress)`, the live core dies identically (`no message received
      during last 60 seconds`, `[503] io error`, then `[408] timeout`), and the crash buffer is empty — the teardown
      fix holds. Replaying the app's **own REGISTER bytes from the PC (192.168.178.220) pulls `SIP/2.0 401
      Unauthorized`**, so `EntryRecorderTabS6` exists on the Fritz!Box and is not locked: the "unknown numbers get
      dropped in silence" guess is dead and the credential swap is not needed. What remains is one decision inside
      FRITZ!OS about one device — identical bytes, different source host:
      - `REGISTER` UDP/5060: PC `401`, tablet silence. `REGISTER` TCP/5060: PC `401`, tablet silence. `OPTIONS`
        UDP/5060: PC `401`, tablet silence — the box refuses this device's SIP outright, on both transports, before
        authentication is even involved.
      - The tablet is not cut off from the box otherwise: DNS on UDP/53 answers it and TCP/80, /443, /53, /5060 all
        connect. Its SIP packets do reach the LAN — a UDP/5060 listener on the PC caught the tablet's 509-byte
        REGISTER arriving from .45.
      - The tablet speaks SIP fine with another host: `OPTIONS` to the 2N (192.168.178.10) came back `SIP/2.0 200 OK`.
      - The box ignores Via/real-source-port mismatch (`;rport` honoured), so each probe above was a valid test. A
        message with **no user part in `From`** is dropped for *any* host — that, not the network, is what made the
        hand-built controls of the previous session look inconclusive.
      **Resolved the same hour: the MAC was it.** Switching that SSID from the randomized address
      (`e2:f2:18:7a:0e:11`, lease .45) to the tablet's hardware MAC (`d8:0b:9a:fa:a5:b0`, fresh lease 192.168.178.78)
      made the box answer, and the app's own Test then printed `401 Unauthorized` → `200 OK` →
      `Register refresher [200] reason [OK]` → `answered Registered(server=192.168.178.1:5060)` — the first
      `Registered` this device has ever produced. The reboot and the access-profile hunt were never needed. Why the
      silence looked absolute: FRITZ!OS matches telephony permission to the device entry it recognises by MAC, so a
      locally-administered MAC falls back to a profile that refuses the box's SIP outright — no challenge, on either
      transport — which from inside the app is indistinguishable from a PBX ignoring the probe. The Fairphone's entry
      carries a non-local MAC, which is why the same account always worked there. No app change; the message was
      already truthful.
      What this gate still owes, now reachable for the first time: press Test three or four times in a row (two taps
      answered and the process survived, no `Fatal signal 11`), then **press it while monitoring is active and ring the
      doorbell once** — the probe registers the same number a second time (live core :5060 + probe :5092) and some PBXs
      evict the first binding, so a delivered ring is the proof that this one does not. On the tablet that risk was
      untestable because no probe ever reached `Registered`.
      Files: `sip/SipCallManager.kt`, `ui/settings/DeviceFormTestSection.kt`
- [ ] 🔄 **A credential change withdraws the old registration.** Edit the SIP password and save; debug builds now
      print Linphone's signalling, so logcat should show an unregister REGISTER for the retired account followed by
      exactly one successful registration for the new one — never a refresh loop against the old password. Then
      stop monitoring from the notification, start it again and press the doorbell: `destroy()` deliberately only
      calls `stop()` — shutting a core down is the SDK's own unregister — so this is the gate for the assumption
      that it withdraws the registrations and eventually frees the port. If the ring is dead after a
      restart, that assumption is wrong and the teardown needs a different primitive — do not solve it by putting
      `removeProxyConfig` back before `stop()`, and never call either primitive from inside a core callback.
      Files: `sip/SipCallManager.kt`, `service/IntercomMonitorService.kt`
- [ ] 🔄 One doorbell press produces exactly one notification (look for `Duplicate ring on …, ignoring`); a call
      the intercom hangs up closes its full-screen view by itself (`ENDED` branch).
- [ ] 🔄 A doorbell press no longer truncates a longer recording under 20 s (post-record stop is event-type-aware).
- [ ] 🔄 **A ring during a motion recording is folded, not lost.** Start a motion clip, then press the doorbell while
      it is still running: expect **one** file carrying both `RING` and `MOTION` badges, filed under Ring (the ring
      filter finds it, the motion filter finds it too), running at least `ringRecordSeconds` past the press, with
      `Folded RING into the running MOTION clip …` in logcat. Repeat in reverse (motion during a ring clip) and confirm
      it is still one file rather than a truncated pair. Before this the press recorded nothing at all.
      Files: `video/RtspStreamRecorder.kt`, `ui/recordings/RecordingCardItem.kt`
- [ ] 🔄 **Server mode deliberately does not fold.** In `PYTHON_SERVER`, ring during a motion clip: the app logs
      `… is recording on the server; a RING event adds no second job` and the ring gets no recording of its own — the
      accepted limit until the server can carry a second trigger.
- [ ] 🔄 **Room v14→v15 over real data.** Upgrade without uninstalling: `alsoEventTypes` is added, every existing
      recording keeps exactly one badge and its old filter answers.

### Motion health (needs the Verso)
- [ ] 🔄 **Camera event polling only runs for triggers that were asked for** (Phase 2 gating item, code done,
      build/lint/test green). With camera motion **and** noise off: logcat shows
      `HTTP event polling not started — no camera motion/noise trigger requested` and a packet capture / the device's
      own HTTP log shows **zero** `/api/motion/status` + `/api/noise/status` requests while monitoring stays up;
      the live card must not go DEGRADED/OFFLINE for the camera-event path. With motion on and noise off, exactly one
      endpoint is polled and the fallback line names `motion` only. If dead endpoints are still being asked every
      1.5 s, the `code 2` body is not matching this firmware's wording — capture one body and extend
      `isInvalidRequestPath` rather than raising the poll interval.
      **FP5 (Fairphone 5, Android 15) 2026-10-07 19:41 — first half passes, on-device.** Row read from the event log:
      `recordOnMotion=false, recordOnNoise=false, wakeOnMotion=false, wakeOnNoise=false, recordOnRing=false`. The SSE
      attempt failed exactly as the transport item predicts (`SSE Failure (200): Invalid content-type: application/json`)
      and the very next line was `HTTP event polling not started — no camera motion/noise trigger requested`; the
      following 65 s contain **zero** status-endpoint lines (the pre-fix shape was ≈100 warning lines and ≈78 requests a
      minute) and no DEGRADED/OFFLINE camera-event report was emitted after the startup `ONLINE` line.
      **Second half passes too, 19:46, with the real firmware body.** Toggling only *Wake Screen on Motion Detection*
      (`wakeOnMotion=true`, noise left off) and saving restarted the session as `Starting 2N HTTP polling fallback loop
      (motion)` with the degraded notice wording now honest — "polling **motion** status only" — and the device answered
      the very first poll with the body the transport item predicted:
      `{"success":false,"error":{"code":2,"description":"invalid request path"}}` → classified `Absent` immediately, one
      OFFLINE report, and **5 status-related lines total for the next 65 s** (one-shot) instead of ≈160; the 5-minute
      re-probe is the only remaining traffic. Two message-grammar defects surfaced by this run are fixed on the spot
      (singular "motion status endpoint **has** returned…", and the recovery notice now names the polled labels instead
      of hardcoding "Motion/noise"). **Still owed on this gate:** the wake-only trigger must be turned back off in the
      device form afterwards (left `wakeOnMotion=true` on the FP5 by this test), and the 5 min re-probe itself has never
      been observed over a long session.
      Unrelated observations from the same session, load-bearing for the ring gate above: the **live** core registration
      went `Progress → Failed (io error)` after 32 s, so no INVITE path was up while this ran — the phone also logged
      `Device is restricting metered network activity … data saver is probably ON` — and the Settings device row reads
      "Camera events (ring/motion/noise): online" whenever monitoring starts, because `startMonitoring` always emits an
      `ONLINE` notice before the SSE attempt fails; with the triggers off nothing corrects it, which is the
      "no representation for *not applicable*" caveat above seen from the other side.
- [ ] 🔄 Read one `Motion analysis on …` health line idle and one walking past the camera, then pick the device's
      **Motion sensitivity** tier (Sensitive / Balanced / Power saver) from the measured peak-change percentage and
      achieved poll rate — the presets replaced editing the analyzer constants.
- [ ] 🔄 **MJPEG live path (Phase 1 item 3, code done via 2N docs).** With a `TWO_N_VERSO` set to `MJPEG_STREAM`
      (or `AUTO` reaching the MJPEG fallback), the derived `snapshotUrl&fps=N` must actually paint in the live view
      and a motion recording must contain stream frames — confirming the documented endpoint serves this firmware
      revision. A content-type `Log.w` ("not a multipart MJPEG stream") means the derivation needs revisiting.

### Per-role accent selector (needs Android 15/16 + real data)
- [ ] Room **v8→v9** upgrade over real data: install the new build **without uninstalling** → archive intact,
      the previously chosen accent still shows, the summary line names that palette for all three roles.
- [ ] Settings → Appearance: a swatch tap re-themes the app + refreshes the summary; **Customize color roles…**
      applies each role immediately and survives backgrounding + restart. Expect Secondary to change the nav
      active pill, filter chips and selected card. Tertiary has no consumer yet (persistence-only).
- [ ] Contrast spot-check a mixed selection (e.g. Primary Teal / Secondary Amber): nav label, chip text and
      selected card all stay readable.
- [ ] Backup round trip after using the dialog: export → restore brings the three role indices back. Caveat: a
      backup written **before** this feature has no role fields, so restoring it resets accents to preset 0.

### Theme mode & splash coherence (needs Android 12+; light-role checks want real data) — 2026-10-04 code, unverified
The uiMode override + derived light roles are compile/lint/test green only. If a stale splash persists, fully
uninstall and reboot (OEM launcher caching).
- [ ] 🔄 **Forced modes drive the splash.** With the SYSTEM setting the OPPOSITE of the picker: Dark+system-light
      (and Light+system-dark) cold starts must show the navy halo + navy-disc mark (dark) / white halo + navy-disc
      mark (light) — never the device setting's variant. `System` follows the device as before.
- [ ] 🔄 **Runtime flip recreates**: switching Light⇄Dark in Settings while the app is open re-themes framework
      chrome (dialog/window backgrounds) after the brief recreate; flipping System⇄anything does not loop-recreate.
- [ ] 🔄 **Device day/night flip while in System mode** updates splash + app on the next cold start
      (`adb shell uimode --night yes|no`).
- [ ] 🔄 **Light mode readability pass**: section headers (primary as text), nav active pill, filter chips,
      selected recording card and the Appearance swatches against the new explicit light neutrals — the unit test
      proves the role PAIRS, not the composition of unusual mixed-role selections.
- [ ] 🔄 **Themed (monochrome) launcher icon + splash layers**: enable themed icons on the launcher and confirm the
      monochrome silhouette; cold-start splash shows the disc built from the launcher foreground layers (Phase 1),
      including on Android < 12 (windowBackground only).

### Pipeline note
- [ ] After Phase 6's color-format change (if/when done): re-run the exported-fMP4-in-VLC gate (the change could
      regress playback) — same spec as the original export validation.

### Server recording-id reconciliation (needs the server running) — Phase S ✅ code, unverified
The `recording_id`-from-start work is `py_compile` + headless DB-lifecycle/migration/import green only; confirm
against a live server before closing.
- [ ] 🔄 **Start returns a persistable id, finalize flips it.** `POST /api/recordings/start` → non-null
      `recording_id`; `GET /api/recordings/{id}` shows `status: recording` while running, `completed` after stop;
      the app logs "server recording id …" and reconciles an auto-stop off that row instead of `/api/status`.
- [ ] 🔄 **In-progress rows stay hidden.** While a capture runs it must NOT appear in `/api/recordings` (list),
      the `total_recordings_count`/storage stats, or the web gallery; it appears only once `completed`.
- [ ] 🔄 **`already_recording` carries the running id.** A second `/start` for a busy device returns
      `already_recording` with the in-flight job's `recording_id`, not null.
- [ ] 🔄 **Empty capture discards the row.** Stop a job that produced 0 bytes → the pre-inserted row is deleted
      (no ghost `recording`/0-byte row lingers in `GET /api/recordings/{id}`).
- [ ] 🔄 **Process-restart resume (Phase 4 persistence).** Start a long server recording, then kill the app process
      (swipe-away or reboot with autostart) while it is still running: the app must re-adopt it — show REC, keep
      reconciling, and auto-stop only if `startedByThisRequest` — honouring the deadline from `startedAtMs` (not a
      fresh duration), then delete the `active_server_recordings` row once it ends. Also confirm a row whose device
      was disabled/removed, or left over after switching off `PYTHON_SERVER`, is dropped on the next service pass.
- [ ] 🔄 **Migration over real data.** Boot the server against the existing `data/recordings.db` without wiping it:
      `status` column added, every legacy row backfills to `completed`, gallery count unchanged.

### Server recordings in-app (needs the server running) — Phase 5 ✅ code, unverified
The merged gallery + read/write actions are compile/lint/test green; confirm against a live `PYTHON_SERVER` deployment.
- [ ] 🔄 **Merged list + composite keys.** With both local and server recordings present, the tab shows one
      time-sorted list; a local id and a server id that happen to be equal do not crash the `LazyColumn`
      (duplicate-key) and each keeps its own actions (server rows: no checkbox, "Server" tag).
- [ ] 🔄 **Remote thumbnail + playback.** A server card's thumbnail loads over `?api_key=` (a wrong/offline key
      falls back to the event icon, not a black box); tapping it streams the server fMP4 in `VideoPlayerModal`
      over LAN HTTP. Confirm cleartext is allowed by `network_security_config` on the test build.
- [ ] 🔄 **Pull-to-refresh.** Dragging the list re-runs `listRecordings()`; an offline server toasts and keeps the
      previous rows rather than clearing them.
- [ ] 🔄 **Mutating server actions.** On a server row: protect/unprotect flips the server flag and the row's lock
      icon follows; delete removes it from the server and the list after the confirm; share / save-to-gallery /
      export-to-folder each stream the video down (progress dialog, correct `.mp4`/`.mkv` extension) and deliver a
      playable file. Export-to-folder with no folder set must toast the same hint as the local path.

### Server device sync (needs the server running) — Phase S ✅ code, unverified
The app→server device registration is compile/lint green; confirm against a live `PYTHON_SERVER` deployment.
- [ ] 🔄 **Register on save.** In `PYTHON_SERVER`, create/edit a device in Settings → the server row is created once
      (`POST /api/devices`) and its id lands in `DeviceEntity.serverDeviceId`; a subsequent edit issues a `PUT` (no
      duplicate row), and the server's web device list shows the camera with its RTSP/snapshot config.
- [ ] 🔄 **Start carries no credentials.** With a registered device, `POST /api/recordings/start` sends only
      `device_id` (the server id) and no url/username/password; recording still works because the server resolves
      them from the stored row. The device password never appears on the start request.
- [ ] 🔄 **Just-in-time registration + local fallback.** A device created while in `APP_LOCAL` (never synced) that
      triggers a server recording registers on first start; if the server is unreachable the register fails and the
      app falls back to local recording without losing the capture.
- [ ] 🔄 **Id-based attribution.** Filtering the gallery by a device matches server rows by `serverDeviceId`, so a
      server device renamed after recording still attributes correctly (only pre-sync legacy rows keyed by the old
      Room id need the name fallback).
- [ ] 🔄 **Delete + restore.** Deleting a registered device also `DELETE`s its server row (recordings intentionally
      remain); restoring a backup clears every `serverDeviceId` so devices re-register against the current server.
- [ ] 🔄 **Room v12→v13 over real data.** Upgrade an install without wiping: the `serverDeviceId` column is added
      null for existing devices (they re-register lazily), no other data disturbed.

### Live view through the server (needs the server running) — Phase 5 ✅ code, unverified
The server-relayed live feed is compile/lint green; confirm against a live `PYTHON_SERVER` deployment.
- [ ] 🔄 **Relayed feed paints.** In `PYTHON_SERVER` with a registered device, the Live card shows MJPEG sourced
      from `/api/live/<serverDeviceId>/mjpeg` (badge reads SERVER); DevTools/logcat show the phone hitting only the
      server, not the camera IP.
- [ ] 🔄 **Header auth, no key in URL.** The live request carries `X-API-Key`; the URL has no `?api_key=`.
- [ ] 🔄 **Graceful fallback.** Stop the server (or kill the feed) → the card drops to the direct-to-device stream
      (badge reverts to the device protocol) rather than going blank; a device the phone cannot reach directly shows
      the existing error + Retry, and Retry re-attempts the server first.
- [ ] 🔄 **Scope unchanged elsewhere.** `APP_LOCAL` mode and unregistered devices render exactly as before.

---

# Part 2 — Resolved log (freeze, don't rebuild)

Compact record of completed work. The former tier/phases (0–7, Phases A–D originals) and the 2026-09-30 review
blocks were all closed the same day they were opened; they are one-line summaries here.

## 2026-09-30 — snapshot-only Motion & Doorbell fixes (Android review findings 1–4 + post-fix regressions)
- **Digest auth for the snapshot client** (`4b5ed5d`): `TwoNDigestAuthenticator` extracted to a shared
  `data/network/DigestAuthenticator` (RFC 7616, MD5/SHA-256/-sess, quoted params, nonce-retry loop guard);
  `HttpSnapshotClient` tags each request with the device credentials and resolves a 401 Digest challenge through
  it, Basic stays the preemptive fast path; KDoc now matches.
- **Live snapshot loop** (`abb2153`): HTTP_SNAPSHOT branch wrapped in `repeatOnLifecycle(STARTED)` (cancels on
  STOP, re-warms on START) and paced against a rolling `nextFrameAt` deadline that subtracts fetch time; the
  redundant outer `withContext(Dispatchers.IO)` dropped. `assembleDebug` + `lintDebug` green.
- **Analyzer/recorder contention** (`9396b7a`): `OnDeviceMotionAnalyzer` backs off to `RECORDING_POLL_MS = 1500`
  while a **local** recording owns the endpoint, via an injected `isRecording` provider.
- **Motion pre-roll** (`a620e29`): bounded ring of the last `PRE_ROLL_FRAMES = 6` JPEGs, drained on
  `MotionOnDeviceStarted` into `startRecording(..., preRoll)`; `recordStreamToMkv` prepends them against the
  earliest-frame baseline so timestamp/duration cover the arrival. Local MJPEG-MKV path only (server ignores it).
- **Post-fix regressions** (`75a1855`): `firstFrameRef`/thumbnail now taken from the first **live** frame (not
  the empty pre-roll scene; pre-roll-only falls back to muxer extraction); `isLocallyRecording` (backed by the
  `activeRecordings` map) replaces `isRecording` in the analyzer provider so PYTHON_SERVER recordings no longer
  slow the phone-side analyzer. Docs `c4a4b33` / `e4c9b55`. All build-verified; device gates live in Phase G.

## 2026-09-30 — ring / noise / call pipeline & code-smell pass (all findings fixed same day)
- Duplicate `DoorbellRung` from one press → `KeyPressed` fires only on a press edge (release **deny**-list,
  unknown action rings, fail-safe) + per-device 5 s debounce (`lastRingHandledAt`, atomic `ConcurrentHashMap.put`).
- SSE polling fallback couldn't deliver rings → SIP INVITE folded into `handleSipRing` as the same `DoorbellRung`
  (normal handler + same debounce); when SIP is disabled the fallback says so via `ConnectionState`.
- Polling silent-failure shape → `pollBooleanStatus(url, label): Boolean?` where null = "no information" (never
  overwrites last known state), `Log.w` per failure class with a 160-char body excerpt; after 4 unusable polls on
  both endpoints reports `ConnectionState(isConnected=false)` once; `ConnectionState` logged at `Log.i/w`.
- `MotionDetected`/`NoiseDetected` decoded unknown payloads as "no motion" → routed through
  `triState(element): Boolean?` (booleans, numbers, `active|true|on|yes|start|detected` families; anything else =
  no info), shared by the SSE path and the HTTP status poll so they cannot disagree.
- `CallUiState.ENDED` unreachable + no auto-dismiss → `ENDED` stays 2.5 s, explicit "Call ended" branch, screen
  self-dismisses 1.5 s later for RING/CONNECTED (motion/noise previews stay open); `ERROR` now shares
  `scheduleIdleReset()` and reads `errorMessage`.
- `acceptCall()` could no-op → answers any pre-answer incoming state (`IncomingReceived`, `IncomingEarlyMedia` —
  5.4 has no `IncomingRinging`) and logs refusals.
- `EXTRA_CALLER` dead → header renders `device · caller` for RING. `onNewIntent` ignored on a `singleTask`
  screen → extras read via `mutableStateOf<ScreenSpec>` (with `setIntent()`), re-resolves device incl. clearing.
- Silent MJPEG catch → `Log.w` naming device + reason; per-device bookkeeping (`pendingPostRecordStops`,
  `lastRingHandledAt`) dropped when the device stops; notification-id comment corrected.
- Motion analyzer: frames now go through `HttpSnapshotClient` (shared client, JSON/XML/HTML rejected with a
  content-type `Log.w` instead of muxed as a fake frame), 60 s health line, rebuilt when the `DeviceEntity`
  changes, decodes with `inSampleSize` down to the 96x54 grid.
- Post-record stops were event-type-blind and non-cancellable → `stopRecording(deviceId, reason)` only stops a
  matching `eventType` (manual null stops anything), server recordings store their `EventType`, at most one
  pending stop per device. `compileDebugKotlin` + `lintDebug` + `testDebugUnitTest` green after this pass.

## Original phases & tiers (re-verified 2026-09-27/28/29)
- **Phase 0 — hybrid pipeline validated on device ✅**: `JpegFramePlayer` + `MjpegMkvReader` (EBML/offset index/
  scrubbing) · `ExportTranscoder` → `H264Encoder` + `MkvStreamMuxer` AVCC/`avcC` framing (VLC black-screen fix) ·
  SAF export folder persists across reboot · Room v4→v5→v6→v7 clean over real data.
- **Phase 1 hotfixes**: `DeviceEditDialog` copy-over from `initialDevice` · ring wake/vibrate/sound wired +
  `maxStorageUsageMb` UI · per-event-type notification ID spaces · `fallbackToDestructiveMigration` removed ·
  server auth deps on all endpoints (mandatory-key remainder → Phase S).
- **Phase 2**: FGS start for Android 12+/14 (`connectedDevice`, boot) · digest-auth parsing (quoted commas,
  retries) · global `UncaughtExceptionHandler` → player error callbacks.
- **Phase 3**: HTTPS fields UI · per-event duration fields UI · device-type picker (Generic RTSP reachable) ·
  generic-device events documented manual-record-only · server-URL default kept editable.
- **Phase 4 (server, done parts)**: MP4→MKV in `recorder.py` (FFmpeg copy + snapshot/libx264, MIME
  `video/x-matroska`) · cleanup deletes files before rows · `@asynccontextmanager` lifespan ·
  `StartServerRecordingPayload` sends `source_mode`/`note`.
- **Phase 5**: bulk delete · device filter chips + MANUAL chip · Exit action in FGS notification · settings backup
  export/import · `autoExportOnFinalize` + one-file-per-recording folder export.
- **Phase 6 (done parts)**: `MjpegStreamReader` buffered reads + suspend `onFrame` · `OnDeviceMotionAnalyzer`
  adaptive idle backoff (500→1500 ms, fast on change) · `GET /api/devices` no longer returns passwords.
- **Phase 7 (done parts)**: `targetSdk` 34→37 + edge-to-edge migration (build green, gate → Phase G) ·
  `googleplay.yml` `workflow_dispatch` with track · `python-server.yml` push builds on `main` + `dev`.
- **Lint debt (safe fixes)**: `SipCallManager` StaticFieldLeak → holds `Application` · `BUFFER_FLAG_SYNC_FRAME`
  dropped (keyframe via `containsIdr`) · speakerphone → `setCommunicationDevice(TYPE_BUILTIN_SPEAKER)` API 31+.
- **Tier C/D UI done**: M3 Expressive Motion spring tokens (`94fcd2e`, `ui/theme/Motion.kt` + `MainActivity`) ·
  oversized UI split — `RecordingsScreen`→208 lines + ViewModel behind `RecordingExportKind` (`1e89b92`),
  `SettingsScreen`→236 (one file per section), `DeviceEditDialog`→127 (`DeviceForm*` files),
  `IncomingCallActivity`→163 (`IncomingCallHeader`/`Controls`) — and all 8 `collectAsState()` sites switched to
  `collectAsStateWithLifecycle()`. **Do NOT split** `video/Fmp4StreamMuxer.kt`/`MkvStreamMuxer.kt`.
- **Tabs + accents (2026-09-29, `6f51a5e`)**: `MainActivity` `HorizontalPager` synced to the bottom
  `NavigationBar` (NavHost removed, per-screen state via Activity-scoped ViewModels) · curated dark presets from a
  Settings swatch picker (`themeAccentIndex`, Room v7→v8). **Per-role accent selector**: three preset indices
  `themePrimaryIndex/Secondary/Tertiary`, Room **v8→v9** backfilled from the legacy single index;
  `AccentRolePickerDialog` picks one of eight curated presets per role (never a free color, so contrast stays
  checked); each role borrows its preset's `primaryContainer` pair as its `secondaryContainer`/`tertiaryContainer`
  (the app reads no `.secondary`/`.tertiary` directly; only container-based components consume those roles). Gap:
  no component reads a tertiary role yet. Migration + dialog → Phase G.
- **Server auth mandatory + Web-UI key + live `<img>` reconnect (`0acd070`, 2026-09-29)**: `verify_api_key` always
  rejects a missing/wrong key; `ensure_api_key()` generates + persists `data/.api_key` (`.env` wins);
  `index.html` prompts the key once, keeps it in `localStorage`, sends `X-API-Key` on 13 `apiFetch()` sites and
  appends `?api_key=` via `apiUrl()`, probes the video URL (reports `401/403/404` distinctly from a container
  `MediaError`). Live grid now rebuilds only when the device set or key changes (`updateLiveCardStates()` +
  explicit `reloadLiveStream()` reconnect) — no per-poll reconnect/flicker, fullscreen survives the poll. Verified
  off-browser via `node server/tests/webui_auth_harness.mjs` → 36/36 (server itself not booted — no uvicorn).
  Docs: README server-API example + MP4→MKV wording fixed.
- **Export broadcast + WifiLock SDK branch (`0acd070`)**: exported `ExportTriggerReceiver`
  (`EXPORT_RECORDINGS`, `--es scope latest|all`) enqueues `ExportTriggerWorker` reusing
  `ExportTranscoder`/`ExportHelper` — external automation without touching the 24/7 finalize path.
  `acquireWakeAndWifiLocks()` branches on `SDK_INT >= Q` (tag-only `createWifiLock()`, pre-29
  `WIFI_MODE_FULL_HIGH_PERF` fallback).
- **Dual-container split (2026-09-28)**: phone export transcode emits fragmented MP4 via pure-Kotlin
  `Fmp4StreamMuxer` (`${name}_h264.mp4`); `ExportHelper.mimeFor` maps mp4/m4v → `video/mp4`. Server encoded paths
  (RTSP copy + snapshot libx264) write fMP4 (`+frag_keyframe+empty_moov+default_base_moof`); only the no-FFmpeg
  raw-JPEG fallback stays MJPEG-in-MKV. **fMP4 VLC fix**: `finalizeTimeline()` patches `mvhd`/`tkhd`/`mdhd`
  durations + appends `mfra`/`tfra`; fixed `mdia` wrapper missing inside `trak`. Validated with `ffprobe`
  (duration 2.0 not 0.0) and on-device in VLC.
- ✅ Bug #1 MJPEG-in-MKV playback · ✅ Bug #2 export MIME/extension from actual file · ✅ Bug #8 SAF export folder
  + pure Share + Save-to-Gallery · ✅ notification icon `drawable/ic_notification` · ✅ top-bar consistency
  (`RecordingsTopBar` real `TopAppBar`) · ✅ export regression from `1e89b92` (dead `.value` flows; now reads
  `repository.getSettings()` per action).
- ❌ WON'T DO — SAF live recording capture location (crash-resilience + CPU/battery, see Design intent).
