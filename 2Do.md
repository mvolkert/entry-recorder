# EntryRecorder – Implementation Plan

Reorganized 2026-09-30 from the old tier list + the four 2026-09-30 review blocks. All closed work was
compacted into the **Resolved log** at the bottom; every still-open item lives in a numbered phase above,
sorted minimal → architectural. Nothing was silently dropped: 34 open + 7 partial items were carried over.

Legend: `[x]` done · `[~]` partial / needs validation · `[ ]` open · 🔄 on-device gate (blocks closing) ·
🔭 long-term / not scheduled · 🖥️ server-side scope

## Feature
- Now/Stop Monitor button in the @RecordingScreen
- Alerts & Lockscreen Behavior per camera setting not global
- Make Camera Edit Screen a full sized Screen as dialog its too much settings for a dialog

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
- [~] 🔄 **The MJPEG path stored for the 2N does not exist on this firmware.** `DeviceEntity.mjpegUrl`
      (`/api/camera/mjpeg`) answers **HTTP 200 + `application/json`** (`{"error":{"code":2 …invalid request
      path}}`); `data/network/MjpegStreamReader.kt` then found no JPEG boundaries and yielded zero frames, so
      `MJPEG_STREAM` read as a dead camera. Done: `requireMjpegContentType()` fails loudly on any content type
      that is not `multipart`/`image` (naming the URL, code and type), both stream functions rethrow so the
      recorder falls back to snapshot polling and the live view shows an error instead of a black box.
      **Remaining device gate:** confirm which live path this firmware actually serves before changing the
      stored `mjpegPath` default — guessing a URL here would just move the failure. Related fact:
      `/api/camera/snapshot` without `width`/`height` returns `{"code":11 …missing mandatory parameter}` —
      `DeviceEntity.snapshotUrl`'s parameter appending is load-bearing.
      Files: `data/network/MjpegStreamReader.kt`, `data/local/entity/DeviceEntity.kt`
- [ ] 🔄 **Retune motion sensitivity from the health line (needs device numbers).** The trigger needs
      `changedRatio >= 3 %` of the 96x54 grid differing by >25 grey levels on **two consecutive** comparisons,
      while idle polling backs off to `MAX_IDLE_POLL_MS = 1500` — a person crossing in ~1.5 s can produce only
      one changed pair and never trigger. `OnDeviceMotionAnalyzer` now prints one health line / 60 s (usable
      frames / polls, peak changed ratio, poll interval). Read one idle line + one walk-past line on the Verso,
      then adjust `MOTION_RATIO_THRESHOLD` / `REQUIRED_MOTION_FRAMES` / backoff from data, not guesses.
      Also open: `recordOnMotionOnDevice` defaults **false** (the plain `recordOnMotion` path is the 2N's own
      SSE `MotionDetected`, a different pipeline) — first thing to check when motion "does nothing".
      File: `video/OnDeviceMotionAnalyzer.kt`

## Phase 2 — Motion feature close-out & accepted trade-offs
Follow-ups from the four snapshot-only fixes (`4b5ed5d` Digest, `abb2153` live loop, `9396b7a` analyzer
backoff, `a620e29` pre-roll; regressions `75a1855`). None is device-verified; the trade-offs below were
deliberately accepted to ship the fixes and are revisit-if-observed, not bugs. The items still `[ ]` after
this pass are exactly those revisit-if-observed trade-offs plus device gates: no code change is due until a
Verso session or a second snapshot-only device type triggers them.

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
- [ ] **Doorbell trigger has no snapshot-only path.** Ring detection comes only from the 2N SSE stream
      (`KeyPressed`/`CallStateChanged`) or an inbound SIP INVITE; a snapshot carries no ring signal. For this
      `TWO_N_VERSO` both 2N channels are active, so the only residual risk is SSE down **and** no INVITE
      reaching the phone → rings lost (surfaced via `ConnectionState`). Rides on the P2P SIP gate (Phase G);
      no code until that resolves. Files: `data/device/TwoNIPVersoDevice.kt`, `service/IntercomMonitorService.kt`
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
- [~] **Server-mode stop reconciliation.** Auto-stop used a fixed `maxDurationSeconds + 2` local timer with an
      unconditional stop, so app and server drifted both ways: the phone kept showing REC after the server had
      finalized, and a `maxDurationSeconds + 2` stop could cut short a job the server reported as
      `already_recording` (someone else's duration). Done (app side): the recorder tracks an
      `ActiveServerRecording` (trigger type, requested duration, `startedByThisRequest`, `recordingId`) and
      `watchServerRecording` reconciles it against `/api/status.active_recordings` every 10 s — local tracking is
      dropped as soon as the server has no job, the explicit stop only goes out for a job this request started
      (or after 3 failed probes, where it is the safe fallback), and a job that outlived the requested duration is
      left alone. `ServerRecordingClient` now reads the start/stop bodies (`started` vs `already_recording`,
      `stopped` vs `not_recording`) and an optional `recording_id` instead of trusting HTTP 200.
      **Remaining:** the id is only held in memory — persisting it needs a server-recording column, which belongs
      to the Phase 5 UI-model abstraction, and `GET /api/recordings/{id}` only becomes usable once the server
      returns the id from *start* (Phase S). Files: `video/RtspStreamRecorder.kt`, `data/server/ServerRecordingClient.kt`
- 🔭 **RTSP H.264 passthrough** (demux → re-mux, zero re-encode/CPU) for stream-capable cameras — capture-pipeline
      rewrite, only for future RTSP-only devices. Not scheduled.

## Phase 5 — Cross-cutting UI features (design decision required)
Both DEFERRED per owner Tier-D selection (2026-09-29); confirm the design before building.
- [ ] **Surface server recordings in the app.** Data layer done (`ServerRecordingClient.listRecordings()` +
      `ServerRecordingDto`). Remaining is architectural: a UI-model abstraction over the Room-only
      `RecordingEntity` list for network thumbnails + remote playback. Local and server ids are both `Long`, so
      a merged list needs composite keys; ExoPlayer can play the server's fMP4 rows once `?api_key=` is on the
      URL. Files: `data/server/ServerRecordingClient.kt`, `ui/recordings/RecordingsViewModel.kt`,
      `ui/recordings/RecordingsScreen.kt`
- [ ] **Live view through the server** — consume `/api/live/{id}/mjpeg` in `LiveStreamPlayer`. Blocked by an
      identity gap, not the player: `LiveStreamPlayer` dials the device's own IP, and the endpoint takes a
      *server* device id that app-managed devices never have (never registered server-side → Phase S sync).
      Works today only for server-UI-created devices. Files: `ui/components/LiveStreamPlayer.kt`,
      `ui/live/LiveCamerasScreen.kt`

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

## Phase S — Server & web UI 🖥️
Sorted minimal → architectural. Headless verification is possible for most; items marked ⚠️ need the server
running in a real browser — that is the one owner session covering the Firefox checklist below.
- [~] **Web UI playback note (dual-container aware).** Modal shows `#mkv-hint` and, on `video.onerror`, a
      `#playback-warn` with a download link, reworded for the fMP4 default. Server boots clean and serves the
      HTML; ⚠️ remaining = the owner Firefox checklist (below). File: `server/entry_recorder_server/static/index.html`
- [ ] **Split `index.html` into static assets** — one 1368-line file (style/markup/inline script). Not free:
      `server/tests/webui_auth_harness.mjs` extracts the script by regex over `<script>` blocks, so it must be
      repointed in the same commit, and FastAPI must gain a static handler. Only when the server block is picked
      up. Files: `server/entry_recorder_server/static/`, `server/entry_recorder_server/main.py`,
      `server/tests/webui_auth_harness.mjs`
- [ ] **Server ↔ app device sync** — the app never registers devices server-side, so `/api/live/{id}/mjpeg` only
      works for server-UI-created devices. New sync API + pairing flow + conflict handling. Blocker for Phase 5
      "Live view through the server". Files: `data/server/ServerRecordingClient.kt`, `server/.../main.py`
- [ ] **Server: return + expose the recording id** (feeds Phase 4 stop reconciliation) — start does not hand back
      a persistable id, so `/api/recordings/{id}` can't reconcile an auto-stop. Return the id from start and keep
      `GET /api/recordings/{id}` available to the app. The app is already ready for it: `ServerStartResponse`
      parses `recording_id`, the recorder stores it, and reconciliation falls back to `/api/status` while it is
      null. Files: `server/.../main.py`, `server/.../recorder.py`
- [ ] **Server: HTTPS-first** (unlocks Phase 6 network-transport cleanup) — mandatory auth is in place but the
      server is still plain HTTP behind global cleartext. TLS, or an explicit decision to stay LAN-only HTTP,
      before dropping user-CA trust app-side. Files: `server/.../main.py`, `server/docker-compose.yml`, `server/Dockerfile`
- 🔭 **`GET /api/devices` credential handling review** — returns no passwords today; revisit if the sync API
      above starts round-tripping device credentials.

  ### Owner checklist — Firefox (the one GUI-browser gate in the server block)
  Prereq: server running (`cd server; entry-recorder-server`) and its key (`.env` `API_KEY` or the generated
  `server/data/.api_key`). Open `http://<server>:8000/`, enter the key. Data: one **fMP4** row and one **legacy
  `.mkv`** row.
  1. **Auth wiring** — stats, live cards, thumbnails render (no 401s, no "key rejected" alert). Fail = stop here.
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
      video renders fullscreen behind the bars.
- [ ] Locked-screen doorbell ring → full-screen intent still fires and shows `IncomingCallActivity` over keyguard.
- [ ] Motion/noise → high-priority heads-up notification appears (screen may NOT wake directly from the service
      on Android 15/16 due to BAL rules); tapping opens `IncomingCallActivity`.
- [ ] Reboot → `BootReceiver` autostart of the `connectedDevice` FGS still succeeds
      (`ForegroundServiceStartNotAllowedException` regression check).

### Ring / noise / SIP (needs the intercom)
- [ ] 🔄 **Peer-to-peer SIP is unverified, not a known bug.** `configureDeviceSip` in P2P clears proxy/auth and
      rewrites UDP/TCP ports on an already-started core, never sets an identity address, so inbound INVITE
      delivery depends on the 2N dialing this phone's IP:port directly. Now load-bearing: the SIP ring source is
      what covers rings while SSE is in polling fallback. If no INVITE reaches the phone the ring gap stays open.
- [ ] 🔄 One doorbell press produces exactly one notification (look for `Duplicate ring on …, ignoring`); a call
      the intercom hangs up closes its full-screen view by itself (`ENDED` branch).
- [ ] 🔄 A doorbell press no longer truncates a longer recording under 20 s (post-record stop is event-type-aware).

### Motion health (needs the Verso)
- [ ] 🔄 Read one `Motion analysis on …` health line idle and one walking past the camera (feeds Phase 1 retune).

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

### Pipeline note
- [ ] After Phase 6's color-format change (if/when done): re-run the exported-fMP4-in-VLC gate (the change could
      regress playback) — same spec as the original export validation.

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
