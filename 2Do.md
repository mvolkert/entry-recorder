# EntryRecorder – Implementation Phases


## Bug
- [x] Notification is not the correct new Launcher icon — replaced the four legacy `android.R.drawable`
      small icons in `NotificationHelper` with a dedicated `drawable/ic_notification` (the CCTV launcher
      mark as a white 24dp silhouette). `compileDebugKotlin` + `lintDebug` green (done 2026-09-29).
- [x] Top bar inconsistent size across tabs — diagnosed 2026-09-29: Live and Settings use an M3
      `TopAppBar` (title `titleLarge` ≈22 sp, fixed 64 dp band that applies the status-bar inset itself),
      while Recordings hand-rolled a Column whose title was `headlineMedium` (32 sp) plus the storage line
      and the pinned filter row. Fixed 2026-09-29 on the owner's choice (**rebuild on a real `TopAppBar`**):
      `RecordingsHeader.kt` became `RecordingsTopBar.kt` — `RecordingsTopBar` is a `TopAppBar` (title with
      `FontWeight.Bold` like the other two tabs, the four selection actions in its `actions` row) and the
      storage line moved out as `RecordingsStorageLine`. `RecordingsScreen`'s topBar is now
      `Column { RecordingsTopBar; Column(padding(horizontal = 16.dp)) { storage; 8 dp; filter bar; 8 dp } }`:
      the padded inner Column deliberately does **not** wrap the bar, because `TopAppBar` already applies
      its own 16 dp content padding and its own status-bar inset (so the old manual
      `.windowInsetsPadding(WindowInsets.statusBars)` is gone — leaving it would double the inset).
      Net layout change: heading 32 sp → 22 sp, band becomes the standard 64 dp, the 8 dp above the old
      header row is gone. `compileDebugKotlin` + `lintDebug` green (same 25 pre-existing findings);
      string/icon/dp literal multisets identical to the old header.
      ⚠️ **Re-opens the edge-to-edge gate below** — the pass recorded on device belonged to the custom bar.
- [x] Export Video not working: The saved MJPEG.mkv is moved after finish correctly to the Export folder
      but the encoding export shows a toast "Select Export folder". But save to gallery works. Encoded
      video playable — fixed 2026-09-29. **This was a regression from commit `1e89b92` (step 1 of the
      Tier C split).** The ViewModel exposed `exportFolderUri` / `transcodeOnExport` as
      `stateIn(viewModelScope, WhileSubscribed(5000), …)` and the export functions then read `.value` —
      but **nothing in the UI ever collected them**, so with no subscriber both stayed frozen at their
      initial values (`""` and `true`) for the life of the ViewModel: every folder export answered
      "Set an export folder in Settings first", and the Settings transcode toggle silently had no effect
      on manual export. The automatic post-finalize export was unaffected because
      `ExportTriggerWorker` reads `repository.getSettings()` itself — which is exactly why the raw MKV
      still reached the folder while the in-app export claimed no folder was set. Fix: both dead flows
      removed; `exportRecording` / `exportSelected` read `repository.getSettings()` once when the action
      runs and pass the folder URI into `deliver()`. `compileDebugKotlin` + `lintDebug` green — **needs a
      device re-run of the export paths** (single + batch, folder/gallery/share, toggle on and off).
- [x] Accent colors too bland — all eight presets were the Material 3 **baseline** dark tones (`#D0BCFF`,
      `#A9C7FF`, `#9DDC8B`, …), i.e. tone-80 of each hue, which is low-chroma by construction. Fixed
      2026-09-29 on the owner's choice (**raise chroma, keep the hues**), derived rather than eyeballed:
      every color was converted to OKLCh, the hue was taken unchanged from the old preset, and each role
      now sits at 90% of the chroma the sRGB gamut allows at its lightness — `primary` at L≈0.76,
      `primaryContainer` at L≈0.48 (mid-dark is where purple/blue/magenta can go rich: 0.134→0.233,
      0.134→0.212, 0.113→0.181), `on*` colors as dark tints of the same hue at L≈0.27.
      Measured chroma gain of `primary`: green +69%, coral +43%, magenta +38%, default +35%, blue +28%,
      violet +23%, teal +13%, **amber +0%** (0.141→0.140 — sRGB simply has no more chroma for a yellow
      hue at any lightness that stays legible; amber only reads *deeper*, `#F2C14E` → `#D9A932`).
      Contrast re-derived with the pairs, verified by script against the values as they sit in `Theme.kt`:
      worst `on*`/background pair **5.07:1**, worst `primary`-as-text against `#1E1E1E` **7.20:1** (all AA;
      `primary` is a text color for section headers, buttons and icons, so lightness could not just be
      dropped for saturation). The derivation rule is recorded in the comment above `accentPresets`.
      Two consequences: index 0 is no longer the M3 baseline, so the **default** look changes on update
      (the old comment claiming otherwise was replaced), and the Settings swatch row now previews
      saturated chips. ⚠️ Still needs an on-device look in Settings → Appearance (compile + lint only
      prove it builds — same 25 pre-existing findings).
      The `secondaryContainer` question raised from this (that role is not part of `AccentPalette`, so the
      selected recording card stayed the neutral M3 default) was answered by the owner as a **feature
      request instead**: keep the containers neutral and add per-role palette picking — see the Resolved
      log entry "Per-role accent selector".
- [ ] **Snapshot HTTP client times out → frames silently dropped, "no continuous observation"**
      (owner-reported 2026-09-29, logcat `HttpSnapshotClient D Snapshot fetch exception for 2N IP Verso:
      timeout`. **Owner update 2026-09-30: it also happens with the screen ON.** **Diagnosed the same day by
      the measurement block at the end of this entry**: the timeouts are Wi-Fi retransmit chains, they cost
      zero recorded frames in the current archive, Doze is exonerated, and the frame loss the owner actually
      sees comes from the recorder's polling pacing.)
      **What one log line actually asserts** (`data/network/HttpSnapshotClient.kt:41` prints `e.message`
      for *one* poll of `device.snapshotUrl`, on a shared OkHttpClient with 4 s connect / 4 s read, L18-19):
      - `timeout` is OkHttp's `AsyncTimeout` firing **after the TCP connection was established and the
        request was written** — the 2N accepted the connection, then stayed quiet past the 4 s window. A
        connectivity loss prints differently (`failed to connect to …`, `ECONNREFUSED`, 401/404 as a code
        at L37), so this is *not* "no network". Because the same read timeout also covers
        `response.body.bytes()` (L35), `timeout` can equally mean "the camera answered, trickled part of
        the JPEG, then stalled" — the partial frame is discarded whole.
      - Scope is strictly per request. Nothing is poisoned or closed; the next poll reuses/opens a
        connection and usually succeeds. So **one line ≠ seconds of outage**: it costs one frame plus up to
        4 s of wall clock.
      **What it costs the archive** (`video/RtspStreamRecorder.kt:226-234`): the loop swallows the failure,
      sleeps `1000 / fps` ms, and stamps frames with a wall-clock `relTimeMs` against a wall-clock deadline
      (L202). Isolated timeouts therefore appear as a *gap between two frames* — the in-app player freezes
      on the previous frame and its counter reads e.g. "38 of 100 frames" for a 20 s clip at 5 fps
      (`player_time_frames` is the free measurement of "how many images did I actually lose"). A long run
      burns the recording window while writing nothing, and if the first polls all fail you get
      `fileSize == 0` → "Recording produced empty file, discarding" (L289-291). Motion detection fails in a
      different shape through a *different* client: `video/OnDeviceMotionAnalyzer.kt:112-114` is a plain
      `HttpURLConnection` with 3 s timeouts, so there frames just stop arriving and no event is raised —
      and it logs `Frame grab/analysis failed for …` under its own tag, not `HttpSnapshotClient`.
      **Measured 2026-09-30 on the owner's Fairphone 5 + a wired PC on the same LAN (02:22–02:55) — cause
      pinned, no code written yet.** Both machines timed the same 2N endpoint at the same instants:
      - **The 2N is neither slow nor overloaded.** 300 sequential GETs at 1/s from the PC over
        02:35:18–02:40:18: median 175 ms, p95 311 ms, **max 341 ms, none above 1.2 s** — in a window that
        contains 7 of the app's timeouts. A keep-alive stress run at the *requested* 10 fps
        (02:41:30–02:45:30, spanning the app's 02:42:31 and 02:44:27 timeouts) completed 1427 requests
        (≈5.9 req/s, the endpoint's own ceiling) on **one** connection with a single reconnect:
        median 137 ms, max 562 ms, zero slow. So (1) oversubscription and (2) stale keep-alive are dead as
        *device* behaviour — the camera serves snapshots serially, fast, and keep-alive-friendly.
      - **The phone's IP path stays alive.** `ping -c 240 -i 1` to the camera: 238/240 replies, no RTT over
        300 ms. At 5 Hz (`-i 0.2`, 2000 packets, 02:47:43–02:54:25) the 15 lost packets are not random:
        they form four clusters at 02:48:21, 02:50:16–18, 02:52:12–15, 02:54:08–11, and the app timed out
        at 02:48:24, 02:50:19, 02:52:19, 02:54:16 — one loss every ~0.4 s for 2–4 s right before each
        timeout, and *no* loss anywhere else in 6.7 min. Signature of a short Wi-Fi-layer degradation
        (power-save delivery / rate adaptation, or an AP-side channel scan), not of a dead network: a lost
        request or ACK costs one RTO — the phone's camera socket measured `rto:0.44 s` and
        `ssthresh:2`, i.e. it had already been through retransmit timeouts — and three of them in a row
        exceed our 4 s read window.
      - **Doze is exonerated; the owner's "it also happens with the screen on" was right.** During the
        capture `mWakefulness=Dozing` with the display OFF, yet `mLightDeviceIdleMode=false` and
        `PARTIAL_WAKE_LOCK 'EntryRecorder::MonitorWakeLock'` was continuously held (ACQ −1h12m) — the app is
        never network-restricted, so the screen state is irrelevant. What the radio does under a held
        partial wakelock is exactly the hole `0acd070` opened: `WIFI_MODE_FULL_LOW_LATENCY` does not stop
        Wi-Fi power save, only `WIFI_MODE_FULL_HIGH_PERF` does.
      - **The archive lost no frames to these timeouts.** All five recordings on the device were parsed
        frame by frame (EBML cluster `Timecode` + block relative timecode): 6/16/27/13/15 frames over
        1968/6166/8617/4434/5380 ms, mean inter-frame gap 394/411/331/370/384 ms, **zero gaps above
        700 ms** — every timeout so far landed while no recording was running.
      - **What does cost frames is pacing, and it costs them constantly.** The same parse yields
        2.43–3.02 fps against `snapshotFps = 10`; `wlan0` on the phone shows ≈108 KB/s ≈ 3.2 snapshots/s
        sustained. Pure arithmetic, not the network: `video/RtspStreamRecorder.kt:226-234` and
        `ui/components/LiveStreamPlayer.kt:196-207` *fetch and then* `delay(1000/fps)`, so one cycle costs
        fetch (≈200–330 ms) + 100 ms. 10 fps is unreachable by construction — roughly 70 % of the
        configured frames are never captured, in perfect radio conditions too. This, not the timeout log, is
        what "no continuous observation" has been measuring.
      - **The poller behind the log lines runs while the app is not visible.** The activity was stopped
        (`visible=false`, another app held focus) and `fetchSnapshotBitmap` has exactly one non-recording
        caller: the `LaunchedEffect` at `LiveStreamPlayer.kt:106-211`, keyed on
        `(device, activeProtocol, retryCount, autoPlay)` and looping `while (isActive)` with no lifecycle
        gate. Kept as an open question, not a claim: attributing the ≈108 KB/s to the live-view loop needs
        the Live tab opened and then left while `wlan0` counters are watched.
      **Fix directions the data now supports** (owner's pick still pending, nothing implemented):
      *pacing* — count the fetch into the period (`delay(periodMs - elapsed)`) and/or cap the setting at
      what the endpoint serves, showing the achieved rate next to it; *robustness* — a `callTimeout` plus
      one retry with backoff so an RTO chain does not eat a whole frame slot, and a consecutive-failure
      counter surfaced as a real `IntercomEvent.ConnectionState` instead of a `Log.d` nobody watches;
      *battery/data* — stop the live poller when the activity is stopped, if the attribution above confirms
      ≈100 KB/s running unattended (~9 GB/day).
- [ ] **The MJPEG path stored for the 2N does not exist on this firmware** (found by the same LAN probe
      2026-09-30). `DeviceEntity.mjpegUrl` resolves to `http://<ip>:<port>/api/camera/mjpeg`, which answers
      **HTTP 200 with `application/json`**: `{"success":false,"error":{"code":2,"description":"invalid
      request path"}}`. `data/network/MjpegStreamReader.kt` (used by `video/RtspStreamRecorder.kt:214` and
      `ui/components/LiveStreamPlayer.kt:173`) then finds no JPEG boundaries and yields zero frames, so
      switching this device to `MJPEG_STREAM` would read as a dead camera instead of a wrong path. Related
      fact from the same probe: `/api/camera/snapshot` *without* `width`/`height` returns `{"code":11,
      "param":"width","description":"missing mandatory parameter"}` — `DeviceEntity.snapshotUrl`'s parameter
      appending is load-bearing (a first probe without them measured 139-byte error pages instead of frames,
      which made every latency number in that run meaningless).
      Action: confirm the live-stream path this firmware actually serves, and make `MjpegStreamReader` fail
      loudly on a content type that is not `multipart`/`image` instead of streaming nothing.
- [x] **App-side motion detection never fires / gives no evidence** (owner-reported 2026-09-30; owner
      confirmed the camera is snapshot-poll-only and the Live view does show moving pictures, so the
      endpoint itself is live). Three defects found by reading `video/OnDeviceMotionAnalyzer.kt`, fixed the
      same day; the sensitivity question stays open until the new health log has real numbers on device.
      - **Every fetch failure was silent.** `if (responseCode != 200) return null` and
        `BitmapFactory.decodeByteArray(...) ?: return null` logged nothing, and this device class answers a
        wrong/under-parameterised path with **HTTP 200 + `application/json`** (see the entry above), so a
        dead endpoint and an empty room were indistinguishable from logcat — zero lines either way.
        Fixed: frames now go through `data/network/HttpSnapshotClient` (shared keep-alive OkHttp, same auth
        and timeout behaviour as the recorder and the live view, no `HttpURLConnection` response-cache
        surface) and `HttpSnapshotClient` itself rejects a JSON/XML/HTML/plain body with a `Log.w` naming the
        content type instead of returning it as a "frame" (that body used to be muxed into recordings as a
        fake frame). The analyzer also prints one health line per 60 s: frames usable / polls, the **peak
        changed-pixel ratio** against the 3 % trigger, and the current poll interval — i.e. the data needed
        to retune the thresholds from measurements instead of guesses.
      - **A settings edit did not take effect.** `service/IntercomMonitorService.kt` recreated the *device*
        on a row change but kept the old analyzer, which held the `DeviceEntity` captured at creation —
        editing the snapshot path/credentials left analysis polling the stale URL until the FGS restarted.
        Fixed: the analyzer is rebuilt when the entity differs (public `deviceEntity` on the class).
      - **CPU**: the loop decoded the full 1280x720 JPEG every poll; it now decodes with a computed
        `inSampleSize` down toward the 96x54 analysis grid.
      - **Still open (needs the health line from the device):** the trigger needs `changedRatio >= 3 %` of
        96x54 pixels differing by >25 grey levels on **two consecutive** comparisons, while idle polling
        backs off to 1500 ms — a person crossing the doorway in ~1.5 s can produce only one changed pair and
        never trigger. `recordOnMotionOnDevice` also defaults to **false**, so "motion does nothing" can
        simply be the un-enabled trigger (the plain `recordOnMotion` path is the intercom's own SSE
        `MotionDetected` event, a different pipeline). Note the analyzer is protocol-blind by design: it
        always reads the snapshot endpoint, never `mjpegUrl` — correct for a snapshot-only camera, still a
        gap for a real MJPEG-only device (Tier: unprioritised, no such device on this deployment).
      `compileDebugKotlin` + `lintDebug` green (no new findings); `testDebugUnitTest` NO-SOURCE as usual.
      🔄 Device gate: read one `Motion analysis on …` health line idle and one while walking past the camera.
- [x] **Post-record stops were event-type-blind and non-cancellable → recordings cut short or discarded**
      (found while reviewing the ring/noise paths 2026-09-30, same day). `IntercomMonitorService` scheduled
      `delay(postRecordSeconds); recorder.stopRecording(device.id)` for MotionEnded, NoiseEnded and
      MotionOnDeviceEnded, and `RtspStreamRecorder.stopRecording(deviceId)` stopped **whatever** was running
      on that device. Two concrete losses: (a) a 20 s noise/motion buffer killed a 60 s doorbell recording on
      the same device, and (b) a flickering event (on → off → on) left the *previous* episode's timer armed,
      which then stopped the just-started recording of the next episode — when that landed within a second of
      the start, the clip was a 0-byte file that the recorder discarded as "Recording produced empty file",
      i.e. **detection fired and no recording showed up**. This is the same symptom as the motion bug above
      and had to be fixed before any motion/ring/noise device test could be trusted.
      Fix: `stopRecording(deviceId, reason: EventType?)` only stops a recording whose `eventType` matches the
      reason (manual stop from the Live view passes null and still stops anything), server recordings now
      store their `EventType` instead of `true`, and the service keeps at most one pending stop per device
      (`pendingPostRecordStops` + `schedulePostRecordStop`/`cancelPostRecordStop`), cancelled whenever a new
      episode of the same event arms a fresh recording. `compileDebugKotlin` + `lintDebug` green (no new
      findings). 🔄 Untested on device — verify a doorbell press no longer truncates under 20 s.

## Ring / noise / call pipeline review (2026-09-30, all findings fixed same day)
All line numbers are `data/device/TwoNIPVersoDevice.kt`, `sip/SipCallManager.kt`,
`ui/incoming/IncomingCallControls.kt`, `notification/NotificationHelper.kt` as of today; they drift with
every edit to those files, so treat them as pointers rather than exact locations.

- [x] **One doorbell press raised two `DoorbellRung` events.** `handleRaw2NEvent` fired on `KeyPressed`
      (unconditional — no `action` press/release filter, so the service button and the release edge counted as
      a doorbell) **and** on `CallStateChanged` for `incoming`/`ringing`/`dialing`. Consequence: two ring
      notifications and two `startActivity` per press; the recording was only saved by
      `RtspStreamRecorder.startRecording`'s `isRecording` guard.
      Fix, two layers: `KeyPressed` is now raised only for a press edge (`action` absent, or in
      `PRESS_ACTIONS`; an absent field keeps working for firmware that does not send it), and the service
      debounces rings **per device** over 5 s (`lastRingHandledAt` + `RING_DEBOUNCE_MS`, checked with an
      atomic `ConcurrentHashMap.put` so it holds even when the events land on different IO threads).
      Deliberately **not** done: the `direction == incoming` filter that was suggested at first. A doorbell
      press makes the intercom *dial out* to this phone, so the ringing call is `outgoing`/`dialing` from the
      device's point of view — filtering on direction would have discarded the primary ring path, and the
      payload values are not documented in this repo. `direction` stays logged for the device session.
- [x] **The SSE polling fallback could not deliver doorbell events at all.** `onFailure` →
      `startPollingFallback` polled `/api/motion/status` and `/api/noise/status` only, so every ring was
      silently lost while SSE was down. No pollable ring endpoint is known for this firmware (the device is
      configured with Basic/Digest user auth only, no API-key concept in the code), so instead of inventing a
      path the fix uses the ring source the app already has: `SipCallManager.onIncomingCall` reports every
      inbound INVITE, and `IntercomMonitorService.handleSipRing` turns it into the same `DoorbellRung` that
      the event stream produces — routed through the normal handler (recording, notification, wake) and the
      same 5 s debounce, so it never double-rings with SSE. A SIP ring is attributed by matching
      `Address.domain` (= the SIP host, the intercom's IP in peer-to-peer mode) against the monitored
      devices, falling back to the single monitored device and otherwise logged + ignored. When SIP is
      disabled (`SipMode.DISABLED`, unregistered P2P) the fallback still cannot see rings, and it now says so
      through `ConnectionState` instead of failing silently.
- [x] **The fallback repeated the motion analyzer's silent-failure shape.** `if (response.isSuccessful)
      { parse }` had no `else`, so a non-200 or an HTTP 200 + JSON error document pinned `lastMotionState` /
      `lastNoiseState` at `false` forever — a missing endpoint was indistinguishable from a quiet room — and
      the `?: false` on an unverified `{"result":{"active":bool}}` shape failed the same way.
      Fix: polling extracted into `pollBooleanStatus(url, label)` returning `Boolean?` where **null means
      "no information"**, with a `Log.w` per failure class (HTTP code, parse error incl. a 160-char body
      excerpt so a wrong payload assumption becomes visible, exception). A null no longer overwrites the last
      known state; after 4 polls with nothing usable on both endpoints the device reports
      `ConnectionState(isConnected = false, …)`, once per outage instead of every 1.5 s.
      `IntercomMonitorService` now logs `ConnectionState` at `Log.i`/`Log.w` (was `Log.d` only), so a
      degraded monitor reaches logcat and crash telemetry.
- [x] **`CallUiState.ENDED` was unreachable and the incoming-call screen never auto-dismissed.**
      `SipCallManager` wrote `SipSessionState(ENDED)` and overwrote it with `IDLE` in the same block (dead
      state, `Dead code removal convention` violation), while `IncomingCallControls` rendered accept/decline
      for everything but `CONNECTED` — so after the call ended the full-screen activity kept the stream, a
      dead accept/decline bar and `FLAG_KEEP_SCREEN_ON` until the user tapped.
      Fix: `ENDED` now stays visible for 2.5 s (`POST_CALL_IDLE_MS`, reset job cancelled/re-armed per call),
      `IncomingCallControls` has an explicit `ENDED` branch ("Call ended", no buttons) and
      `IncomingCallActivity` dismisses itself 1.5 s later when the screen belongs to a call
      (`eventType == RING`, or it observed `CONNECTED`). Motion/noise previews never match that gate, so
      they stay open for the stream; and because the dismiss lives in a `LaunchedEffect(sipState.state)`, a
      new ring that reuses the single-top activity cancels the pending `finish()`.
- [x] **`acceptCall()` could no-op silently** — it accepted only `Call.State.IncomingReceived` with no `else`
      and no log, which worked only because nothing calls `call.startRinging()`. Fix: it now answers any
      pre-answer incoming state and logs the reason when it refuses (`no current SIP call` / `call is in
      <state>`). Correction to the note written at review time: Linphone 5.4 has **no**
      `IncomingRinging` state — verified against the SDK sources jar, the enum goes `IncomingReceived`,
      `PushIncomingReceived`, …, `IncomingEarlyMedia` — so the second accepted state is `IncomingEarlyMedia`.
      `PushIncomingReceived` is deliberately excluded (it needs the push payload fetched before it is
      answerable). No `startRinging()` call was added: that changes the audio/routing path and is not
      verifiable from the repo.
- [ ] 🔄 **Peer-to-peer SIP is unverified, not a known bug.** `configureDeviceSip` in `PEER_TO_PEER` clears
      proxy configs and auth and rewrites the UDP/TCP ports on an already-started core and never sets an
      identity address, so inbound INVITE delivery depends on the 2N dialing this phone's IP:port directly.
      This is now load-bearing, not just convenience: the SIP ring source above is what covers doorbell rings
      while SSE is in polling fallback. If no INVITE ever reaches the phone, the ring gap stays open and only
      the `ConnectionState` notice tells the user. Belongs with the Tier G lock-screen ring gate.
- [x] **Trivial:** `NotificationHelper.eventNotificationId` comment said `base + device*4 + slot` while the
      code is `base + (deviceId % 1000) * 4 + slot`. Comment corrected to match, including the consequence
      (device ids 1000 apart share a slot and would replace each other's notification) — harmless at this
      deployment's device count.
- ✅ Checked and correct: motion and noise channels are independent per-event notification ids and channels;
      the app-side analyzer and the device's own `MotionDetected` both record as `EventType.MOTION`, so the
      `isRecording` guard prevents a double recording when both triggers are enabled; `startRecording`
      correctly falls back to local recording when the Python server refuses.
      `compileDebugKotlin` + `lintDebug` + `testDebugUnitTest` (NO-SOURCE) green after these changes, no new
      lint findings in any touched file. 🔄 Nothing verified against the intercom yet: one press should now
      produce exactly one notification (look for `Duplicate ring on …, ignoring`), and a call that the
      intercom hangs up should close its full-screen view by itself.

## Code smell pass over the trigger pipeline (2026-09-30)
Review of the files changed above and their neighbours, focused on the event → recording → alert path.

- [x] **`MotionDetected` / `NoiseDetected` decoded an unknown payload as "no motion".** The read was
      `params.get("state")?.asBoolean ?: (params.get("state")?.asString == "active")`, which is `false` for a
      missing field and for any wording other than exactly `active` — so a firmware that sends `"on"`, `1` or
      puts the boolean under another key fired **Ended** on every real motion event and motion/noise
      recording could never start, with only the raw-event `Log.d` as evidence. Now routed through
      `triState(element): Boolean?` (`TwoNIPVersoDevice`): booleans, numbers and the
      `active|true|on|yes|start|detected` / `inactive|false|off|no|stop|idle|clear` word families map to a
      state, **anything else means "no information"** (`Log.w` + the payload excerpt, no state change). The
      HTTP status poll uses the same helper, so the SSE path and the fallback path cannot disagree about what
      a payload means.
- [x] **The `KeyPressed` press allow-list I added in the previous pass was itself a ring-killer.**
      `PRESS_ACTIONS = {pressed, press, single, click, down}` silently dropped every action string that the
      firmware spells differently, and that string is not documented anywhere in this repo. Inverted to a
      release **deny**-list (`KEY_RELEASE_ACTIONS`): an unknown action rings, and duplicates are absorbed by
      the service debounce. Deliberate fail-safe direction — a missed doorbell is worse than a duplicate
      alert, and duplicates are already handled downstream.
- [x] **`CallUiState.ERROR` was terminal in the data but not in the UI.** `Call.State.Error` wrote ERROR and
      never reset it, so one failed call pinned the session forever: every later screen that renders the
      action bar showed dead accept/decline buttons. ERROR now shares `scheduleIdleReset()` with ENDED (the
      four duplicated lines became one function) and both render the same "nothing left to do" bar — which
      also finally *reads* `SipSessionState.errorMessage`, a field that had been written since the beginning
      and consumed by nothing.
- [x] **`EXTRA_CALLER` was written by two call sites and read by none.** The ring overlay showed the device
      name only, so the caller the service and the notification carefully passed (`Doorbell Button`, the SIP
      peer) was dead data. `IncomingCallContent` → `IncomingCallHeader` now take `caller` and render
      `device · caller` for `EventType.RING`; motion/noise previews stay device-only.
- [x] **The call screen ignored `onNewIntent` while the manifest pins it to `launchMode="singleTask"`.** All
      three event intents (`RING`, `MOTION`, `NOISE`, plus the full-screen intent) use `CLEAR_TOP`, so the
      live instance is reused and `intent` is replaced without `onCreate` running again: a motion screen that
      was re-targeted by a doorbell press kept the **old** device id, event type and caller on screen. The
      extras are now read through a `ScreenSpec` held in `mutableStateOf`, refreshed in `onNewIntent` (with
      `setIntent()`), and `LaunchedEffect(screen.deviceId)` re-resolves the device — including clearing it
      when an intent carries no id, which previously left the old device's stream up.
- [x] **Silent `catch (_: Exception)` around the MJPEG capture.** A stream that died after a few frames fell
      back to snapshot polling with no trace, i.e. indistinguishable from a recording of a static scene.
      Now a `Log.w` naming the device and the reason (`RtspStreamRecorder.recordStreamToMkv`).
- [x] **Per-device bookkeeping outlived the device.** `pendingPostRecordStops` and `lastRingHandledAt` are
      keyed by device id and were only cleared on service destroy; a deleted device left a pending stop armed
      and a re-added id inherited the old ring stamp. Both are now dropped in the same loop that stops the
      device. While there: the `when (event)` in `IncomingCallControls` no longer needs an `else` (terminal /
      ringing / connected are all enumerated), so a new `CallUiState` becomes a compile error instead of
      silently inheriting the accept/decline row.
- [ ] **Remaining smells, reported and deliberately not fixed:**
      1. *Duplication in the event router*: `MotionStarted` and `MotionOnDeviceStarted` are ~20 near-identical
         lines (they differ only in which record flag they test), and the three `*Ended` branches differ only
         in the `EventType` passed to `schedulePostRecordStop`. Collapsing them is a refactor of the 24/7
         router → belongs to Tier E with the other service work.
      2. `IntercomEvent.CallState` has **no consumer**: the service only `Log.d`s it. Either drive
         `MonitorStatusHolder`/the call UI from it or drop it (dead-code convention).
      3. `ConnectionState(isConnected = true, message = "SSE event stream unavailable…")` — the boolean says
         connected while the text says degraded. The event has no third state, so a partially working monitor
         can only be expressed as a string, and `MonitorStatus` has no "degraded" value either: nothing in the
         UI can show it, only logcat. Needs a small `IntercomEvent`/`MonitorStatus` API decision, not an
         invented field.
      4. *Coupled magic constants in two files*: `POST_CALL_IDLE_MS = 2500` (`SipCallManager`) must stay
         larger than `TERMINAL_CALL_DISMISS_MS = 1500` (`IncomingCallActivity`) or the auto-dismiss quietly
         stops firing (the state would flip to IDLE before the screen sees it). Commented at both sites, no
         compile-time link. Cleaner: one shared constant, or dismiss on IDLE instead of racing it.
      5. `SipCallManager.onIncomingCall` is a mutable, non-volatile callback property rather than the
         project's `Channel<UiEvent>` convention, invoked on the Linphone core thread. Fine for the single
         non-UI consumer it has today; a second consumer should get a Flow.
      6. `handleSipRing`'s "if exactly one device is monitored, attribute the SIP ring to it" is a
         by-count heuristic: behind a PBX the remote host is the PBX, not the intercom, so the match is not
         by identity. Logged at info; only correct for single-device deployments.
      7. The 5 s ring debounce also swallows a **genuine** second press inside that window — the recording
         keeps running, but there is no second alert. Intended trade-off; worth knowing when testing.
      8. `SipSessionState.callerAddress` / `callerDisplayName` are still write-only (the overlay uses the
         intent's caller). Pick one source or delete the fields.
      9. Pre-existing and already tracked separately: the snapshot capture loop paces with
         `delay(1000 / fps)` without subtracting the fetch time, so the real frame rate is below the requested one.
      `compileDebugKotlin` + `lintDebug` + `testDebugUnitTest` green after this pass, no lint finding in any
      touched file. 🔄 All of it still needs the intercom: the `triState` change in particular is only
      *correct* if the payload words match reality, and the log lines now say what the device actually sends.

## Android review — Motion & Doorbell on a snapshot-only camera (2026-09-30; findings 1-4 fixed same day, 5-7 resolved no-code)
Scope: `app/` only (server excluded), focused on the trigger path for a camera whose video is reachable
**only** through the HTTP snapshot endpoint. All findings were read/grep-verified against the current source;
line numbers are pointers that drift with edits. **Owner confirmed the camera is registered as `TWO_N_VERSO`
and asked for fixes to all findings.** Findings 1-4 were implemented (one commit each, `assembleDebug` green
before every commit, no push); 5-7 needed no code for a 2N snapshot-only device and are closed with the
rationale below. None of the code fixes are device-verified yet — each carries a 🔄 gate.

- [x] **`HttpSnapshotClient` cannot do Digest auth — its KDoc says it can.** `data/network/HttpSnapshotClient.kt:27`
      documents "Tries Basic auth first, then Digest if requested", but the code only ever sends
      `Credentials.basic(...)` (L38) and its `OkHttpClient` (L20-23) has **no** `authenticator`. The 2N event/status
      client in `data/device/TwoNIPVersoDevice.kt:46-54` *does* attach `TwoNDigestAuthenticator`. Consequence for a
      snapshot-only camera whose endpoint requires Digest: every `fetchSnapshotBytes` returns null (401 → L52 `Log.w`),
      so motion analysis starves silently and recordings finalize as 0-byte files discarded at
      `RtspStreamRecorder.kt:333-335`. This is the single highest-risk gap for the primary use case, because the
      snapshot path *is* the whole video pipeline here. Action: either attach a Digest authenticator to the snapshot
      client (reuse `TwoNDigestAuthenticator`) or correct the KDoc and surface a 401 as a real
      `IntercomEvent.ConnectionState` instead of a bare `Log.w`. Owner's device currently answers Basic, so this is
      latent, not active — verify against the actual firmware auth mode before deciding.
      → **Fixed 2026-09-30 (commit `4b5ed5d`):** `TwoNDigestAuthenticator` extracted to a shared
      `data/network/DigestAuthenticator`; `HttpSnapshotClient` tags each request with the device credentials and
      its shared singleton client resolves a Digest challenge through that authenticator (Basic stays the
      preemptive fast path), and the KDoc now matches the code. `assembleDebug` green. 🔄 Verify a Digest-only
      snapshot endpoint returns frames instead of a silent 401.
- [x] **The live-view snapshot loop still paces wrong and never stops when the app backgrounds.**
      `ui/components/LiveStreamPlayer.kt:192-208` (HTTP_SNAPSHOT branch) does `fetchSnapshotBitmap(...)` **then**
      `delay(1000/fps)` without subtracting the fetch time — the exact bug the recorder already fixed with a rolling
      deadline (`RtspStreamRecorder.kt:251-277`). So the Live tab renders below the configured `snapshotFps`. Worse,
      the `LaunchedEffect(device, activeProtocol, retryCount, autoPlay)` at L106 loops `while (isActive)` with **no**
      lifecycle gate, and `LiveCamerasScreen` composes every card's player with `autoPlay` defaulting to true
      (`LiveCamerasScreen.kt:206-210`, no `Lifecycle`/`repeatOnLifecycle`), so the poller keeps hitting the endpoint
      while the activity is stopped — this is the ~108 KB/s unattended drain the 2026-09-30 measurement block
      (Bug entry above) left as an open attribution question; the code now confirms it. Action (two independent
      fixes): subtract elapsed into the delay, and gate the loop on lifecycle-started. Both are low-risk; the
      lifecycle gate has a visible trade-off (live view must re-warm on return) worth confirming with the owner.
      Note this supersedes smell item #9 above, which described the recorder loop that is already fixed — the
      remaining offender is the live view, not the recorder.
      → **Fixed 2026-09-30 (commit `abb2153`):** the HTTP_SNAPSHOT loop is wrapped in
      `repeatOnLifecycle(Lifecycle.State.STARTED)` (cancels on STOP, restarts on START) and paces against a rolling
      `nextFrameAt` deadline that subtracts the fetch time; the outer `withContext(Dispatchers.IO)` was dropped
      since `fetchSnapshotBitmap` already hops to IO, so the Compose state writes stay on Main. `assembleDebug` +
      `lintDebug` green. 🔄 Confirm the Live tab stops polling when backgrounded and re-warms on return.
- [x] **Motion analyzer and recorder poll the same snapshot endpoint concurrently, with no coordination.**
      `OnDeviceMotionAnalyzer.runLoop` (`video/OnDeviceMotionAnalyzer.kt:74-99`) keeps polling `device.snapshotUrl`
      at 500 ms base for the whole time it is enabled; when motion fires, `IntercomMonitorService` starts
      `RtspStreamRecorder`, whose capture loop (`RtspStreamRecorder.kt:254-278`) polls the *same* URL at
      `snapshotFps`. The analyzer is never paused during a recording. The 2026-09-30 LAN probe measured the 2N
      serving snapshots serially at ≈5.9 req/s ceiling, so two (or three, with the live view open) concurrent pollers
      oversubscribe a serial endpoint and mutually drop frames — degrading both the recording and the detection at the
      exact moment motion matters. Action: pause or slow the analyzer while its device is recording (the recorder
      already publishes `activeDeviceIds`, `RtspStreamRecorder.kt:55-61`), or share one frame source between them.
      Design decision needed — flag before implementing.
      → **Fixed 2026-09-30 (commit `9396b7a`), trade-off accepted via the owner's "implement all" instruction:**
      the analyzer takes an `isRecording: () -> Boolean` provider (wired to `recorder.isRecording(deviceId)` in
      `IntercomMonitorService`) and backs off to a fixed coarse `RECORDING_POLL_MS` (1500 ms) while a recording owns
      the endpoint, instead of pausing — pausing would lose `MotionOnDeviceEnded` and the post-record stop. This
      reduces, not eliminates, contention. `assembleDebug` green. 🔄 Verify motion still ends recordings on device.
- [x] **Motion recordings have no pre-roll, so the triggering moment is missed.** `MotionOnDeviceStarted` fires only
      after `REQUIRED_MOTION_FRAMES = 2` consecutive comparisons over `MOTION_RATIO_THRESHOLD` (3 %) —
      `OnDeviceMotionAnalyzer.kt:155-158` — and recording starts on that event (`IntercomMonitorService.kt:271-283`).
      With idle polling backed off to `MAX_IDLE_POLL_MS = 1500` (L210), two consecutive changed frames can be ~1-3 s
      *after* motion began, and there is no ring buffer of the frames seen before the trigger. Net effect for a
      doorway camera: the person who caused the recording has often already walked through before frame 1 is written.
      This compounds the still-open sensitivity item in the Bug block (2026-09-30, "App-side motion detection never
      fires"). Action: keep a short rolling buffer of the last N analyzed JPEGs and prepend them on trigger —
      a real trade-off (memory/complexity in the 24/7 path), so confirm before building.
      → **Fixed 2026-09-30 (commit `a620e29`), trade-off accepted:** the analyzer keeps a bounded ring of the last
      `PRE_ROLL_FRAMES = 6` raw JPEGs (≈6 full-res frames per motion-enabled device) and exposes `drainPreRoll()`;
      on `MotionOnDeviceStarted` the service drains it into `recorder.startRecording(..., preRoll)`, and
      `recordStreamToMkv` writes those frames first against a baseline of the earliest pre-roll timestamp (the
      recording's `timestamp`/`duration` now cover the arrival). Only the local MJPEG-MKV path is affected; the
      Python-server path ignores pre-roll (it captures independently). `assembleDebug` green. 🔄 Verify a walk-past
      recording now includes the approach frames.
- [ ] **Doorbell trigger has no snapshot-only path at all.** Ring detection comes exclusively from the 2N SSE event
      stream (`KeyPressed`/`CallStateChanged`, `TwoNIPVersoDevice.kt:161-186`) or an inbound SIP INVITE
      (`SipCallManager.onIncomingCall` → `IntercomMonitorService.handleSipRing`). A snapshot endpoint carries no ring
      signal, so for a camera reachable only by snapshot the doorbell depends entirely on either (a) SSE staying up, or
      (b) the peer-to-peer SIP INVITE that item "🔄 Peer-to-peer SIP is unverified" (2026-09-30) flags as never
      validated. If both are down, rings are silently lost (the polling fallback already says so via `ConnectionState`,
      `TwoNIPVersoDevice.kt:200-207`). For a `GENERIC_RTSP_ONVIF` device there is no ring/noise source whatsoever —
      `GenericRtspDevice.startMonitoring` emits only `ConnectionState` (`GenericRtspDevice.kt:36-43`), which is
      documented as a limitation but means the doorbell feature does not exist for that device type. Action: none
      code-side until the owner confirms which device type the snapshot-only camera is registered as; if it is generic,
      the doorbell expectation itself needs revisiting.
      → **Resolved 2026-09-30, no code:** the owner confirmed the camera is `TWO_N_VERSO`, so both ring sources
      (SSE `KeyPressed`/`CallStateChanged` and the SIP INVITE → `handleSipRing`) are active for it — the
      snapshot-only limitation applies to *video*, not to the 2N event/SIP channels, and the `GENERIC_RTSP_ONVIF`
      "no ring source" case does not apply here. Residual risk is unchanged and already tracked by the open
      "🔄 Peer-to-peer SIP is unverified" gate: if SSE is down *and* no INVITE reaches the phone, rings are still
      lost. Left `[ ]` pending that device gate rather than optimistically closed.
- [x] **`OnDeviceMotionAnalyzer` is off by default and easy to conflate with the device's own motion.**
      `recordOnMotionOnDevice` defaults to `false` (`DeviceEntity.kt:43`), while `recordOnMotion` defaults to `true`
      (L39) and drives the *2N SSE* `MotionDetected` pipeline — a different code path. For a snapshot-only camera the
      SSE motion event may never arrive, so "motion does nothing" is frequently just the app-side analyzer being
      un-enabled. Already noted in the Bug block; recorded here because it is the first thing to check when triaging
      motion on this deployment. Action: documentation/settings-hint, not code.
      → **Already satisfied, verified 2026-09-30:** the settings hint exists — `DeviceFormTriggersSection.kt`
      renders `R.string.device_trigger_motion_app_hint` under the toggle ("App analyzes the live video stream itself
      instead of relying on the device's built-in motion detection"), which is exactly the distinction this finding
      warns about. The `false` default is intentional (avoids double-recording alongside the SSE `recordOnMotion`
      path), so no code change; marked `[x]` as documentation-complete.
- [ ] **Snapshot URL forces `width=1280&height=720` when the path omits them.** `DeviceEntity.snapshotUrl`
      (`DeviceEntity.kt:76-86`) appends `?width=1280&height=720` if neither key is present — load-bearing per the
      2026-09-30 probe (the 2N rejects a bare `/api/camera/snapshot` with `code:11 missing mandatory parameter`).
      But the resolution is hardcoded; a snapshot-only camera that does not accept those exact dimensions (or expects
      different param names) would return an error the analyzer/recorder read as "no frame". Low risk on the current
      2N, worth knowing for any other snapshot-only device. Action: consider making the appended size configurable
      rather than fixed, only if a second snapshot-only device type appears.
      → **Deferred 2026-09-30, no code:** the deployment has a single 2N device for which `width=1280&height=720`
      is confirmed load-bearing, so the finding's own action criteria ("only if a second snapshot-only device type
      appears") are not met. Left `[ ]`, to revisit if a non-2N snapshot-only camera is added.

### Post-fix critical review — open todos (2026-09-30, owner perspective: 2N Verso reliability / side effects / regressions)
Self-review of commits `4b5ed5d`, `abb2153`, `9396b7a`, `a620e29`, all grep/line-verified against the
committed source. Two items are genuine regressions worth fixing soon; the rest are accepted trade-offs
to revisit only if observed on device.

- [ ] **Thumbnail regression from pre-roll (fix 4, `a620e29`) — actionable.** `recordStreamToMkv` sets
      `firstFrameRef` from the *first* pre-roll frame (`RtspStreamRecorder.kt:243-246`), and `finalizeRecording`
      builds the thumbnail from exactly that frame (L316-318). Before the fix the thumbnail showed the trigger
      moment; now it shows the oldest buffered frame — typically an **empty approach scene** (the whole point of
      pre-roll). Recordings list tiles become visually identical "nothing happening" images. Fix: capture the
      thumbnail from the first frame written *after* the pre-roll block (or the frame nearest the trigger
      timestamp), not from `preRoll.first()`.
- [ ] **Analyzer backoff is counterproductive in `PYTHON_SERVER` recording mode (fix 3, `9396b7a`) — actionable.**
      `recorder.isRecording(deviceId)` reflects `_activeDeviceIds`, which includes **server-side** recordings. In
      that mode the server polls the snapshot endpoint, not the phone — yet the phone's analyzer still slows to
      `RECORDING_POLL_MS = 1500` (`OnDeviceMotionAnalyzer.kt:98-104`), degrading on-device motion detection for a
      contention that doesn't exist. Fix: only back off when the *local* capture loop owns the endpoint (e.g. gate
      on recording mode, or expose a `isLocalRecording` flag).
- [ ] **Motion-END detection is slower during recordings (fix 3 side effect).** With `isRecording()` taking priority
      in the poll `when`, clear-frame spacing grows to 1500 ms, so `REQUIRED_CLEAR_FRAMES = 4` needs ≥6 s of quiet to
      end a recording (was ≥2 s). Longer files, more storage churn; also delays the post-record stop. Acceptable
      trade-off vs endpoint contention, but revisit if recordings look over-long on device.
- [ ] **Continuous memory/GC cost of the pre-roll ring (fix 4 trade-off).** `bufferPreRoll` runs on **every**
      successful analyzer fetch, 24/7, holding 6 full-res 1280×720 JPEGs per motion-enabled device (≈1-2 MB) plus a
      full copy on each `drainPreRoll()`. On the 24/7 service path this is permanent allocation churn; only the ring
      cap keeps it bounded. Watch heap/GC in a long-run soak before calling fix 4 stable.
- [ ] **Uneven pre-roll frame spacing → jittery clip start (fix 4 cosmetic).** Buffered frames arrive at whatever the
      analyzer poll was (500 ms active, up to 1500 ms idle), but are written with their real timestamps against the
      earliest-frame baseline. The first ~3-6 s of a motion recording play at uneven, slow intervals. A uniform
      synthetic spacing (e.g. 1000/fps) would look smoother at the cost of lying about timestamps.
- [ ] **Pre-roll-only files are now saved instead of discarded (fix 4 behavior change).** If the snapshot endpoint
      dies right after the trigger, previously the recording had 0 live frames and was discarded (0-byte check,
      `RtspStreamRecorder.kt:333-335`); now the 6 pre-roll frames make it a valid ~seconds-long file that gets kept.
      Arguably correct (shows the approach), but expect short "ghost" recordings when the endpoint flaps.
- [ ] **Digest endpoints pay 2 round-trips per snapshot (fix 1 trade-off).** `HttpSnapshotClient` always sends the
      preemptive Basic header first; a Digest-only device answers 401, then the `Authenticator` retries with Digest.
      Correct but doubles latency/requests on the ~5.9 req/s serial 2N ceiling if the owner ever switches firmware
      to Digest. Could cache the observed auth scheme per device after the first challenge.
- [ ] **MJPEG branch of the live view is still ungated (fix 2 incomplete scope).** `repeatOnLifecycle` was applied to
      the HTTP_SNAPSHOT loop only; `MJPEG_STREAM` (and RTSP) branches keep their previous lifecycle behavior. Not a
      regression, but the background-drain class of bug can still apply to MJPEG devices. Out of scope for a
      snapshot-only Verso — track for parity.
- [ ] 🔄 **All four fixes are build-verified only — none is device-verified.** The per-fix gates from the section
      above still stand: Digest-only snapshot fetch, Live tab background stop/re-warm, motion-end still fires during
      recording, walk-past recording includes approach frames. Until a Verso session confirms these, treat findings
      1-4 as `[~]` in practice despite the `[x]` marks.


Reorganized from the code & feature review, re-verified against the current codebase
(`app/`, `server/`, `.github/`) on 2026-09-27; **task list re-sorted by invasiveness (minimal →
architectural) on 2026-09-28**, so small changes get done first and big ones later.
Phase labels are kept on each item for traceability to the original plan.
**Re-sorted by scope on 2026-09-29 (owner: "let's do the Android part first")**: Tiers A–F are
Android-app work only, the server / web-UI work is grouped in **Tier S** just before the Tier G
validation gates. The original `[~]` / `[x]` states and the Tier letters quoted inside item text
(`former Phase …`, commit notes) are kept as-is for traceability.

Legend: `[x]` implemented · `[~]` partially implemented / needs validation · `[ ]` missing
     🔄 pending on-device validation · 🔭 long-term / not scheduled · 🖥️ server-side scope

---

## Design intent (fixed, do not revisit)
- The **primary target camera supports snapshot pulling only** – the app is intentionally built
  around snapshot/MJPEG capture. Missing RTSP/H.264 capture is **not a bug for the main use case**;
  it only matters for future/optional RTSP-only devices.
- **Dual-container strategy** (replaces the old "MKV is the deliberate container everywhere" rule):
  **MKV is the private, crash-resilient raw-MJPEG capture buffer** on the phone — incrementally-flushed
  clusters survive an abort (MP4's trailing `moov` is unrecoverable on abort), and Matroska is the more
  reliable container for MJPEG (VLC/MX play it). **fragmented MP4 (fMP4) is the universal, playable
  deliverable for encoded H.264** — the server encoded recording paths (RTSP H.264 copy, snapshot
  libx264) and the phone export transcode emit fragmented MP4 (`+frag_keyframe+empty_moov+default_base_moof`
  via ffmpeg on the server; the pure-Kotlin `Fmp4StreamMuxer` on the phone). **The phone muxer finalizes the
  file in place on close** — it patches the `mvhd`/`tkhd`/`mdhd` durations and appends an `mfra` seek table —
  because a bare `+empty_moov` fragment stream (durations 0, no `mfra`) shows 00:00/black in strict demuxers
  (VLC) even though lenient players (browsers / ExoPlayer / MX) rebuild the timeline from the fragments.
  *Caveat:* capture stays append-only, but this trailing finalize runs only on a normal close; a hard
  crash/kill mid-export can leave that one file un-finalized (strict players show 00:00) — it self-heals on
  the next completed export/share/transcode (whole-file re-encode).
  *Rejected alternative:* put the raw MJPEG into fMP4 too — MJPEG-in-fMP4 is less reliable for
  third-party players than MJPEG-in-MKV and no browser gains playback; only re-encode-at-capture
  (rejected below: CPU/battery) could fully unify the container.
- **Cheap capture, lazy encoding**: no re-encode at record time (24/7 events → CPU/battery
  unacceptable). H.264 transcode happens only on explicit export/share.
- Capture location stays a forward-only local `FileOutputStream` in `filesDir/recordings`;
  playable output belongs in SAF only via export/transcode-on-demand. **(WON'T DO: SAF live capture)**

## Working convention
Done work (all of former Phases 0–5, the finished parts of 6–7) is frozen — compacted into the
**Resolved log**. Only **open** items are listed below, sorted from one-line/config changes to
multi-module architectural refactors.

---

## How to read the tiers now
- **Tier A–F** — Android app (`app/`, Kotlin). Do these first.
- **Tier S** — server + web UI (`server/`), grouped so the browser-validation session happens once.
  Nothing here is scheduled to *start* before the Android tiers, but note that most of it is already
  implemented and only waits on the owner's Firefox run.
- **Tier G** — on-device validation gates; no code, but blocks marking the related items done.

# Tier A — Trivial / config-level (Android, minimal invasive)
Single-file, non-behavioral or config-only changes.

- [ ] *No open items.* The notification-icon fix is in the Bug block at the top; the two config-level
      server/doc items live in **Tier S**.

# Tier B — Localized one-file changes (Android, small, isolated)
Confined to a single file/module; no cross-layer design decisions.

- [ ] *No open items.* The three originals (mandatory auth, export broadcast, `WifiLock` SDK branch)
      closed in commit `0acd070`; the two server files moved to **Tier S**.

# Tier C — Medium UI features (Android, single-purpose, multi-file)
Behavioral but self-contained UI work.

- [x] **Split the oversized UI files; lift export logic out of the composables** – added after the
      2026-09-29 reordering (owner: "a file refactor splitting into components would be smart"). Facts from
      the current tree: `SettingsScreen.kt` 848 lines / **one** 787-line composable, `RecordingsScreen.kt`
      816 / one 509-line composable + a 206-line card, `DeviceEditDialog.kt` 548 / one composable. Measured
      on `RecordingsScreen`: lines 129-284 are *not* UI — three local `fun`s (`runExport`, `exportToFolder`,
      `runBatchExport`) driving `rememberCoroutineScope`, `ExportTranscoder`, `ExportHelper`, SAF URIs,
      Toasts and broad `catch (e: Exception)`; the `RecordingsViewModel` next to it is 124 lines of pure
      filtering. So the split is worth doing **with** the UDF fix, not instead of it. Ordered sub-steps,
      each its own commit, behaviour-preserving, `compileDebugKotlin` + `lintDebug` between every one:
      1. ✅ `RecordingsScreen`: move the three export functions into the ViewModel behind a
         `RecordingExportKind` enum (`SHARE|GALLERY|FOLDER` magic strings today) and expose progress as UI
         state + results as `Channel<UiEvent>` (Toasts/errors are data per AGENTS.md). Keep the existing
         lazy transcode-on-export policy comments verbatim.
         (done 2026-09-29, commit `1e89b92`. The share sheet stays in the screen through a
         `RecordingsUiEvent.Share` event: `ExportHelper.shareFile` calls `startActivity` without
         `FLAG_ACTIVITY_NEW_TASK`, so it needs the Activity context — moving it to `getApplication()`
         would crash.)
      2. ✅ Extracted the UI seams — as `RecordingsHeader.kt` (title + selection actions + export
         `DropdownMenu` + storage line; renamed `RecordingsTopBar.kt` when the Bug-block top-bar rebuild
         turned it into a real `TopAppBar`), `RecordingsFilterBar.kt` (search field + both chip rows),
         `RecordingsDialogs.kt` (`DeleteRecordingDialog` / `BulkDeleteDialog` / `ExportProgressDialog` /
         `BatchProgressDialog`; the player was already `VideoPlayerModal`) and `RecordingCardItem.kt`
         (`internal`, body decomposed into thumbnail / details / actions-menu). The loading / empty /
         `LazyColumn` block stayed in the screen: as `RecordingsList` it would need ~10 parameters, so it
         adds indirection without narrowing a recomposition scope. `RecordingCardItem` still takes **10**
         params — collapsing the three export callbacks into one `(RecordingExportKind) -> Unit` is the
         obvious follow-up but changes the call surface, so it was left for the next pass.
         `RecordingsScreen.kt` is now 208 lines, one composable.
         (done 2026-09-29, `compileDebugKotlin` + `lintDebug` + `testDebugUnitTest` green — the last one is
         NO-SOURCE: no unit tests exist in `app/`.)
      3. ✅ `SettingsScreen`: cut at its own section comments — Devices (171), Recording Engine & Destination
         (212), Storage & Retention (326), Backup & Restore (716) → one file per section composable.
         Delivered as `DeviceCard.kt` (card + `DevicesEmptyCard`), `SettingsEngineCard.kt`,
         `SettingsStorageCard.kt`, `SettingsAlertsCard.kt`, `SettingsAppearanceCard.kt`,
         `SettingsBackupCard.kt` and `SettingsComponents.kt` (`SettingsCard` shell, `SettingsSectionHeader`,
         two `SettingsSwitchRow` overloads — the six identical toggle rows). There are **six** sections, not
         four: Screen & Alert Notifications (558) and Appearance (651) had no checklist line of their own.
         The `LazyColumn` item structure is untouched (each card is still one `item { }`, devices keep their
         `key = { it.id }`), the SAF launchers + Toasts stay with the screen and the section cards take
         `(AppSettingsEntity) -> Unit`, so `copy()` happens next to the control it belongs to.
         `SettingsScreen.kt` is now 236 lines.
         Follow-up in step 5's pass: lint `ModifierParameter` flagged `SettingsSectionHeader` for a Modifier
         default that was `Modifier.padding(top = 16.dp)`; it is now plain `Modifier` and all six call sites
         state their own gap (8 dp for the first section, 16 dp between the rest) — same layout, no hidden
         default.
         (done 2026-09-29, `compileDebugKotlin` + `lintDebug` green.)
      4. ✅ Same pass fixes the convention gap: all five screens use `collectAsState()` where AGENTS.md
         requires `collectAsStateWithLifecycle()` (`MainActivity:68`, `LiveCamerasScreen:60`,
         `RecordingsScreen:107/111/112/119`, `SettingsScreen:89`, `IncomingCallActivity:87`).
         Approved and done 2026-09-29: `androidx.lifecycle:lifecycle-runtime-compose` (same `lifecycle`
         version ref, 2.11.0) added to `gradle/libs.versions.toml` + `app/build.gradle.kts`, and all **8**
         call sites switched — every one of them collects a `StateFlow`, so the swap is API-compatible.
         ⚠️ One real behaviour difference to keep in mind: `IncomingCallActivity` now stops collecting
         `sipManager.sessionState` while the activity is stopped (screen off over the lockscreen) and
         re-reads the current value on resume; call audio itself lives in the service and is unaffected.
         The rest of the Tier G gates still cover the visual side.
      ⚠️ No UI tests exist, so verification stops at compile + lint; the visual/interaction check rides on
      the Tier G edge-to-edge gates. Do **not** apply the same treatment to `video/Fmp4StreamMuxer.kt` (454)
      or `video/MkvStreamMuxer.kt` (384) — a box writer splits badly, the sequence of writes is the API.
      `DeviceEditDialog.kt` was gated on steps 1–4 being green; they are, so it went as step 5 below.
      Files: `ui/recordings/RecordingsScreen.kt`, `ui/recordings/RecordingsViewModel.kt`,
      `ui/settings/SettingsScreen.kt`, `ui/settings/DeviceEditDialog.kt`, `ui/MainActivity.kt`,
      `ui/live/LiveCamerasScreen.kt`, `ui/incoming/IncomingCallActivity.kt`
      5. ✅ `DeviceEditDialog.kt` (548 → 127 lines) split along its own section comments into
         `DeviceFormState.kt` (124), `DeviceFormComponents.kt` (64: `FormSectionLabel`, `FormDivider`,
         `FormRadioRow`, `FormEmphasizedSwitchRow`), `DeviceFormNetworkSection.kt` (108),
         `DeviceFormStreamSection.kt` (74), `DeviceFormSipSection.kt` (59),
         `DeviceFormTriggersSection.kt` (84, also holds `DeviceFormDurationsSection`) and
         `DeviceFormTestSection.kt` (91). The 28 form `remember { mutableStateOf(...) }` declarations became one
         `DeviceFormState` seeded in a single `remember` — same retention semantics as before (no keys, so a
         changing `initialDevice` still does not re-seed a live dialog). Former Bug #4's copy-over is now
         `buildDevice()`, the only place that starts from `initial` and overwrites just the exposed fields;
         the probe's narrower entity is `buildTestCandidate()`.
         Kept deliberately: numeric fields stay **strings** (no re-parse while typing), `device_protocol_rtsp`
         is still formatted with the raw `rtspPort` text, `sipLocalPort` still has **no** UI field (it
         round-trips untouched, exactly as before) and the trigger rows reuse `SettingsSwitchRow`, whose
         layout is character-for-character the row the dialog used to inline. Two deviations worth knowing:
         the SIP peer-to-peer label's `padding(end = 8.dp)` moved from the `Text` onto its wrap-content row
         (same visual result), and the probe's `isTestingConnection` / `testResult` now live inside
         `DeviceFormTestSection`, so a test result no longer recomposes the whole form.
         (done 2026-09-29, `compileDebugKotlin` + `lintDebug` green; `testDebugUnitTest` still NO-SOURCE.)
      6. ✅ Follow-up the owner asked for after 1–5: the last remaining Android UI monolith,
         `ui/incoming/IncomingCallActivity.kt` (341 → **163** lines). Its 195-line `IncomingCallContent`
         overlay became `IncomingCallHeader.kt` (91: event title + device name + dismiss) and
         `IncomingCallControls.kt` (143: the in-call vs ringing action rows). `IncomingCallControls` is a
         `BoxScope` **extension** because the bar anchors itself with `.align(BottomCenter)` — a plain
         function could not keep that modifier. The Activity keeps the lockscreen flags, the SIP wiring and
         the `collectAsStateWithLifecycle()` on `sipManager.sessionState`; `IncomingCallContent`'s public
         signature is unchanged. Verified mechanically rather than by eye: the multisets of
         `R.string.incoming_*`, `Color(0x…)`, `Icons.*` and every `N.dp` value are identical before/after
         (16 dp values each). One fix on the way: the `sipState` parameter was spelled as a fully qualified
         `io.github…SipSessionState` instead of using an import.
         (done 2026-09-29, `compileDebugKotlin` + `lintDebug` green; lint findings are the same 25
         pre-existing ones, none in the touched package.)

# Tier D — Larger UI / cross-cutting features (Android)
Multiple screens or cross-cutting behavior; design worth confirming before building.

- [x] **M3 Expressive Motion** (former UI) – centralized spring tokens in `ui/theme/Motion.kt` drive an
      expressive pager page scale/fade + a nav-icon selection spring; no custom durations. Applied at
      the pager layer on purpose so distant pages (incl. live camera streams) are NOT force-composed
      (keeps capture cheap). `compileDebugKotlin` + `lintDebug` green; motion feel on device (Tier G) (done 2026-09-29, commit `94fcd2e`).
      Files: `ui/theme/Motion.kt`, `ui/MainActivity.kt`

## Android items waiting on a design decision (still app-side, just not free to start)
Kept in the Android tiers because the work is in `app/`, but both were **DEFERRED per owner Tier-D
selection (2026-09-29)** — confirm the design before building.

- [ ] **Surface server recordings in the app** (former Phase 4, Bug #3) – data layer done
      (`ServerRecordingClient.listRecordings()` + `ServerRecordingDto`, server-relative URLs).
      Remaining is architectural: a UI-model abstraction over the Room-only `RecordingEntity` list
      for network thumbnails + remote playback. `RecordingsScreen.kt` (208 lines since the Tier C split,
      the card and dialogs next to it) is typed on
      `RecordingEntity` (local `filePath`/`thumbnailPath`, Room ids, multi-select, SAF export) and
      local + server ids are both `Long`, so a merged list needs composite keys. ExoPlayer can play
      the server's fMP4 rows directly once `?api_key=` is on the URL (possible since the Tier S Web-UI
      auth fix). **Confirm design before building.**
      Files: `data/server/ServerRecordingClient.kt`, `ui/recordings/RecordingsViewModel.kt`, `ui/recordings/RecordingsScreen.kt`
- [ ] **Live view through the server** (former Phase 4) – consume `/api/live/{id}/mjpeg` in
      `LiveCamerasScreen` (`LiveStreamPlayer`). Blocked for app-managed cameras by an identity gap,
      not by the player: `LiveStreamPlayer` always dials the *device's own* IP from
      `device.streamProtocol`, and `/api/live/{device_id}/mjpeg` takes a *server* device id that
      app-managed devices never have (they are never registered server-side → Tier S sync item).
      Works today only for server-UI-created devices, and needs the key as a query parameter on the
      stream URL (Tier S auth). **DEFERRED** per owner Tier-D selection (2026-09-29).
      Files: `ui/components/LiveStreamPlayer.kt`, `ui/live/LiveCamerasScreen.kt`

# Tier E — Service & data-layer refactors (Android, larger blast radius)
Touch the 24/7 monitoring/recording lifecycle or the capture pipeline.

- [~] **Server-mode stop reconciliation** (former Phase 2) – auto-stop uses a fixed
      `maxDurationSeconds+2` local timer with no reconciliation of the actual server job; the app
      holds no server recording id, so stop/status can drift. Return/persist the server recording
      id and reconcile via `/api/recordings/{id}` — the app side is Tier E; the server returning an
      id from *start* is a Tier S item.
      File: `video/RtspStreamRecorder.kt:69-79`
- 🔭 Long-term: **RTSP H.264 passthrough** (demux→re-mux, zero re-encode/CPU) for stream-capable
      cameras — capture-pipeline rewrite, only for future RTSP-only devices.

# Tier F — Heavy / device-unverifiable refactorings (Android, deferred backlog)
Large, risky, or impossible to validate from CI; each needs a real device/PBX pass. Kept deferred
per owner decisions — do NOT start these before Tiers A–E and the Tier G validation are done.
The server block (Tier S) is scheduled behind this one as well, so Android work is never blocked by it.

- [ ] **MediaCodec color-format selection** (former Lint debt) – stop hardcoding deprecated
      `COLOR_FormatYUV420Planar`; read `getCapabilitiesForType(MIMETYPE_VIDEO_AVC).colorFormats`,
      prefer NV12 (`COLOR_FormatYUV420SemiPlanar`) → planar → flexible; replace `bitmapToI420()`
      with a writer for the SELECTED layout; honor `COLOR_FORMAT_STRIDE`/`SLICE_HEIGHT` when packing
      (even width/height for 4:2:0).
      ⚠️ DEVICE GATE (test spec): re-export and verify the exported H.264 fragmented MP4 still plays in
      strict players like VLC (same gate as the Tier G pipeline note) before marking done. File: `video/H264Encoder.kt:78`
- [ ] **Linphone `ProxyConfig` → `Account` API migration** (+ `createProxyConfig/edit/done/
      addProxyConfig/defaultProxyConfig/clearProxyConfig`, `setDebugMode`) – large SIP refactor;
      ties into the per-device-cores decision below. File: `sip/SipCallManager.kt`
- [~] **SIP per-device cores** (former Phase 2, DEFERRED — owner: revisit with real device/PBX) –
      one global Linphone core reconfigured per device in `updateMonitoredDevices`; with multiple
      devices the last one wins; P2P vs PBX cannot coexist. File: `sip/SipCallManager.kt`
- [~] **Always-on wake/wifi locks** (former Phase 6, DEFERRED — owner: dedicated, detection-first
      device; never missing a ring beats battery) – `PARTIAL_WAKE_LOCK` (24h) + high-perf `WifiLock`
      held continuously; keep locks only during active recording/ring, not idle monitoring.
      File: `service/IntercomMonitorService.kt:265-285`
- [~] **Credential storage** (former Phase 6, DEFERRED) – device HTTP passwords, SIP passwords,
      server API key stored plaintext in Room → Keystore-backed Room TypeConverter + migration
      (large, device-unverifiable; dedicated security pass).
- [~] **Network transport** (former Phase 6, DEFERRED) – `network_security_config` permits global
      cleartext + trusts user CAs (MITM risk) → scope cleartext to local subnet, drop user-CA trust.
      ⚠️ Dropping user CAs breaks self-signed HTTPS on 2N devices; only after the server is
      HTTPS-first (Tier S auth item).
- [~] **Credential leakage in RTSP URLs** (former Phase 6) – `rtsp://user:pass@host` embedded URLs
      get logged/persisted by external libs. No app-side log statement emits this URL today
      (verified); Media3/ffmpeg logging is outside our control — effectively **wont-fix** unless
      auth-via-header on `GenericRtspDevice` is implemented with an RTSP-only device.
- [ ] **Linphone ABI coverage** (former Phase 7, DEFERRED) – only `jni/arm64-v8a/liblinphone.so`
      committed, `jni/` isn't wired into `sourceSets`/`jniLibs.srcDir` so packaging behavior is
      unclear; blind `abiFilters` could exclude devices. Needs a release-bundle ABI inspection first.

# Tier S — Server & web UI (all scopes: `server/`, docs) 🖥️
Grouped per the owner's "Android part first" decision (2026-09-29). Sorted minimal → architectural
*within* the block. Headless verification is possible for most of it (Python `py_compile`, the Node
harness), but the items marked ⚠️ need the server actually running in a real browser — that is one
owner session covering the whole Firefox checklist below.
Three items here are new `[ ]` entries created by splitting server-side obligations that were previously
buried inside Android items (recording id from *start* ← Tier E stop reconciliation; HTTPS-first ← Tier F
network transport; device sync ← Tier D live view). Prune or re-merge them if you disagree with the split.

- [x] **Docs: README server-API example** – payload example now mirrors the real `StartServerRecordingPayload`
      (`source_mode: "auto"`, `snapshot_url`, `note`) + credential-fallback and `X-API-Key` prose.
      Files: `README.md`, `server/README.md` (done 2026-09-28)
- [x] **Server: make auth mandatory** (former Phase 1) – auth deps exist on **all** endpoints incl.
      `/video`, `/thumbnail`, `/api/live/{id}/mjpeg`. `verify_api_key` now always rejects a missing/wrong
      key; a new `ensure_api_key()` generates a random key on first run, persists it to `data/.api_key`
      and logs it (a `.env` `API_KEY` still wins). Server `py_compile` clean (done 2026-09-29, commit `0acd070`).
      Files: `server/entry_recorder_server/main.py`
- [x] **Server: Web UI supplies the mandatory API key** – the always-reject auth change above broke the dashboard:
      `index.html` had no key handling at all and the server returns bare `video_url`/`thumbnail_url`/`live_url`
      without the `api_key` query parameter `verify_api_key` accepts, so every `fetch` and every media tag got a
      401 — and the `<video>` 401 even masqueraded as "browser cannot play this file". Now: key prompted once per
      browser (or taken from `?api_key=` in the page URL), kept in `localStorage`, sent as `X-API-Key` on every
      API call (13 `apiFetch()` sites, incl. the new probe; it re-prompts once on 401, then locks with an alert
      instead of re-prompting on the 5 s poll — the 🔑 header button resets), and appended via `apiUrl()` to
      thumbnail / live / player / download URLs. `openPlayerModal` additionally probes the video URL with an
      authenticated HEAD and reports `401/403/404` as `server returned HTTP <status>`, while a real container
      failure now reports its `MediaError` code in `#playback-warn-detail` — the two cases are no longer
      confusable, which was the point of the playback note below. Other probe statuses still attempt playback.
      Verified off-browser: `node server/tests/webui_auth_harness.mjs server/entry_recorder_server/static/index.html`
      → 36/36 (runs the real inline script in a VM with DOM/fetch stubs: header injection, `?api_key=` on
      thumbnail/live/player/download URLs, the 401 re-prompt-once + lock rule, the fallback wording, and the
      live-card rules). Docs updated in both READMEs.
      ⚠️ Not verified: the server was not booted (no `fastapi`/`uvicorn` in this environment); Starlette's
      automatic HEAD-for-GET support is assumed.
      Files: `server/entry_recorder_server/static/index.html`, `server/tests/webui_auth_harness.mjs` (new),
      `README.md`, `server/README.md` (done 2026-09-29)
- [x] **Live camera `<img>` reconnects every poll** – `loadData()` (5 s interval) called `renderLiveCameras()`,
      which rebuilt `live-cameras-grid`'s `innerHTML`, so every live MJPEG `<img>` was destroyed and recreated
      ~every 5 s: a new upstream connection per camera, constant flicker — and it also dropped the user out of
      fullscreen every cycle, because the fullscreen element left the document. Now the cards are only rebuilt
      when the *device set* or the *API key* changes (signature over `id:name:live_mode:live_url` + key, plus a
      guard that the nodes still exist); otherwise the recording state is patched in place via
      `updateLiveCardStates()` (footer text, `recording` class, icon, title) on the now-identified
      `live-card-{id}` / `live-status-{id}` / `live-rec-btn-{id}` nodes. Since the rebuild was the accidental
      reconnect mechanism, an explicit one replaces it: clicking a camera's image calls
      `reloadLiveStream(id)`, which re-requests `live_url` with a fresh `&_=<ts>` cache-buster and restores the
      opacity that `onerror` dims (and `onload` now restores it on its own when a frame arrives).
      Verified: same harness → **36/36**, incl. "idle poll leaves the live grid untouched", "recording state
      patched without a rebuild", "changed key/device set rebuilds", "empty → non-empty rebuilds" and the
      reconnect URL/opacity assertions. Not verified: no real browser or live camera was attached (the harness
      stubs the DOM), so the flicker-free reconnect behaviour still needs checklist step 6 below.
      File: `server/entry_recorder_server/static/index.html` (`renderLiveCameras()`, `updateLiveCardStates()`,
      `reloadLiveStream()`) (done 2026-09-29)
- [~] **Server: web UI playback note (dual-container aware)** – playback modal shows `#mkv-hint` and,
      on `video.onerror`, a `#playback-warn` with a "Download this recording" link. Reworded for the
      dual-container split: new recordings are fragmented MP4 and play inline everywhere; the warning
      now targets legacy `.mkv` rows and the no-FFmpeg raw-MJPEG snapshot fallback only.
      Server run verified 2026-09-28 (`uvicorn` boots, updated HTML served with all fallback markers;
      `/api/recordings/{id}/video` maps `.mkv` → `video/x-matroska`, otherwise → `video/mp4` via the
      existing suffix branch — the new `.mp4` files are served inline).
      ⚠️ Remaining (owner, real browser): the checklist below. Was previously blocked by the missing
      Web-UI auth (fixed above), which made every recording look unplayable.
      File: `server/entry_recorder_server/static/index.html`

  ### Owner checklist — Firefox (the one GUI-browser gate in the server block)
  Prereq: server running (`cd server; entry-recorder-server`) and its key — `.env` `API_KEY` or the generated
  one in `server/data/.api_key`. Open `http://<server>:8000/`, enter that key when prompted.
  Data needed: one **fMP4** row (any recording made after the 2026-09-28 dual-container change) and one
  **legacy `.mkv`** row (an old capture, or the no-FFmpeg snapshot fallback).

  1. **Auth wiring** — stats, live cards and thumbnails all render (no empty grid, no console 401s, and no
     "API key was rejected" alert). Fail = auth regression, stop here; the rest is meaningless.
  2. **Key change** — click 🔑, enter a wrong key → expect exactly **one** alert plus a redacted retry, and the
     poll must not keep prompting every 5 s. Re-enter the right key → page recovers.
  3. **fMP4 inline** — click the MP4 card's thumbnail. Expect: player modal opens, video actually paints
     frames, seekbar shows a non-zero duration, scrubbing works, and `#playback-warn` stays hidden.
     Note roughly how long the first frame takes (frag files need one keyframe fetch before paint).
  4. **No false warning** — while the MP4 plays, DevTools → Network: `/video` is `200`/`206` with
     `Content-Type: video/mp4`; the earlier HEAD probe request is `200` too.
  5. **Legacy MKV fallback** — click the `.mkv` card. Expect: the player is replaced by the
     `#playback-warn` box whose detail suffix reads **`(player error 4: source not supported)`** (NOT
     `server returned HTTP …`), and the ⬇️ link downloads the file. Download it and confirm it opens in VLC.
  6. **Live view** — a server-created camera shows moving MJPEG, and the no-rebuild fix holds:
     watch one camera for ~15 s — the picture must not blank at each poll, and DevTools → Network must show
     **one** long-lived `/api/live/<id>/mjpeg` request, not a new one every 5 s. Start/stop a recording on that
     card: the footer label and ⏺/⏹ change without the picture restarting. Press ⛶ and wait 15 s: you must
     still be in fullscreen (the old poll ejected you). Unplug a camera → the tile dims; plug it back and click
     the tile → the stream reconnects.
  7. **Safari** — cannot be covered on Windows; if you ever check it, the same steps 3 and 5 apply.

  Mark the playback-note item `[x]` only when 1–5 pass in Firefox (step 6 closes the live-card item).
  Report which step failed and what the modal said.

- [ ] **Split `index.html` into static assets** – the dashboard is one 1368-line file: `<style>` 10-536
      (527 lines), markup 537-800, one inline `<script>` 801-1366 (566 lines). Worth splitting into
      `static/app.js` + `static/styles.css` only when the server block is actually picked up, because it is
      **not** free: `server/tests/webui_auth_harness.mjs` extracts the script by regex over
      `<script>…</script>` blocks in the HTML, so it must be repointed at the new `.js` file in the same
      commit, and FastAPI currently serves the page by reading the file — serving sibling assets needs a
      mount/static handler in `main.py` (check the existing route first).
      Files: `server/entry_recorder_server/static/`, `server/entry_recorder_server/main.py`,
      `server/tests/webui_auth_harness.mjs`
- [ ] **Server ↔ app device sync** (former Phase 4) – server `devices` table and app devices are
      independent; the app never registers devices on the server, so `/api/live/{id}/mjpeg` only
      works for server-UI-created devices. New sync API + pairing flow + conflict handling. This is
      the blocker for the app-side "Live view through the server" item in Tier D.
      Files: `data/server/ServerRecordingClient.kt`, `server/entry_recorder_server/main.py`
- [ ] **Server: return + expose the recording id** (feeds Tier E stop reconciliation) – the start
      endpoint does not hand back a recording id the app can persist, so `/api/recordings/{id}` can't
      be used to reconcile an auto-stop. Return the id from start and keep `GET /api/recordings/{id}`
      available to the app.
      Files: `server/entry_recorder_server/main.py`, `server/entry_recorder_server/recorder.py`
- [ ] **Server: HTTPS-first** *(unlocks Tier F's network-transport cleanup)* – mandatory auth is in
      place, but the server is still plain HTTP behind `network_security_config`'s global cleartext.
      TLS (or an explicit decision to stay LAN-only HTTP) before dropping user-CA trust app-side.
      Files: `server/entry_recorder_server/main.py`, `server/docker-compose.yml`, `server/Dockerfile`
- 🔭 Long-term: **`GET /api/devices` credential handling review** – today it returns no passwords and
      the server falls back to stored credentials on start (Resolved log, Phase 6). Revisit if the sync
      API above starts round-tripping device credentials.

---

# Tier G — On-device validation gates (no code, but blocks marking things done)
Compile-green ≠ done; run these on the owner's real hardware before closing the related tiers.

## targetSdk 37 / edge-to-edge 🔄 (needs an Android 15/16 device)
The 34→37 bump forces edge-to-edge (Android 15) and tightens background-activity-launch (Android 16);
none of this is verifiable from the build.

- [x] Compiles & installs with `targetSdk 37` (assembleDebug green).
- [x] Edge-to-edge layout: status bar no longer overlaps the top of Live / Recordings / Settings; nav bar
      doesn't cover content or the Settings FAB.
- [ ] Recordings top band ("Recordings Archive" + storage line + search/filter chips) sits below the status
      bar, not behind the clock. **Re-opened 2026-09-29**: the pass recorded here belonged to the old
      hand-rolled Column; the band is now a real `TopAppBar` (see the Bug block), which also shrinks the
      heading from 32 sp to 22 sp. This supersedes the Recordings half of the gate above — re-check both.
- [ ] Incoming-call screen: header and call controls (mute/speaker/hangup) clear the status & gesture/nav bars
      on a punch-hole / gesture-nav device; video still renders fullscreen behind the bars.
- [ ] Locked-screen doorbell ring → full-screen intent still fires and shows `IncomingCallActivity` over the keyguard.
- [ ] Motion/noise → high-priority heads-up notification appears (screen may NOT wake directly from the service on
      Android 15/16 due to BAL rules); tapping it opens `IncomingCallActivity`.
- [ ] Reboot → `BootReceiver` autostart of the `connectedDevice` foreground service still succeeds
      (`ForegroundServiceStartNotAllowedException` regression check).

## Per-role accent selector 🔄 (added 2026-09-29)
- [ ] Room **v8→v9** upgrade over real data: install the new build **without uninstalling** → recording
      archive intact, the accent chosen before the update is still what the app shows, and the new summary
      line names that one palette for all three roles.
- [ ] Settings → Appearance: a swatch tap re-themes the whole app and refreshes the summary line;
      **Customize color roles…** opens the dialog, every tap applies immediately, and the choices survive
      backgrounding plus an app restart. Expect **Secondary** to visibly change the bottom-nav active pill,
      the Recordings filter chips and the selected recording card — all `secondaryContainer` consumers.
      **Tertiary** has no consumer in this UI yet (no component here reads a tertiary role), so its row is
      persistence-only until something is deliberately re-coloured with it.
- [ ] Contrast spot-check a deliberately mixed selection (e.g. Primary Teal / Secondary Amber): the nav
      label, the filter-chip text and the selected recording card must all stay readable.
- [ ] Backup round trip after using the dialog: export → restore brings the three role indices back.
      *Caveat to confirm:* a backup written **before** this feature has no role fields, so restoring it
      resets the accents to preset 0 — its legacy `themeAccentIndex` is no longer read.

## Pipeline note 🔄
- [x] Exported H.264 fragmented MP4 plays in strict players like VLC — device-validated 2026-09-28. This
      required fixing the `Fmp4StreamMuxer` container first (in-place durations + `mfra` finalize, plus the
      missing `mdia` wrapper inside `trak`), NOT just Tier F's color-format change. (Browser/Chrome inline
      playback of the phone export not re-checked on a real browser; server fMP4 uses ffmpeg's own frag
      writer, which already emits `mfra`.)
- [ ] After Tier F's color-format change (if/when done): re-run the same exported-fMP4-in-VLC gate (the
      color-format change could regress playback) — same spec as the original Phase 0 export validation.

---

## Resolved log (freeze, don't rebuild)
Compact record of everything completed in the former phases, re-verified 2026-09-27/28.

- **Phase 0 — hybrid pipeline validated on device ✅**: `JpegFramePlayer` + `MjpegMkvReader`
  (EBML/offset index/scrubbing) · `ExportTranscoder` → `H264Encoder` + `MkvStreamMuxer` AVCC/`avcC`
  framing (VLC black-screen fix) · SAF export folder persists across reboot · Room v4→v5 (`transcodeOnExport`),
  v5→v6 (`exportFolderUri`), v6→v7 (`autoExportOnFinalize`) clean over real data.
- **UI quality**: live REC state via `RtspStreamRecorder.activeDeviceIds` StateFlow + `LiveViewModel` ·
  multi-select export (Share/Gallery/Folder, "k of n") · CCTV adaptive launcher icon (monochrome layer) ·
  dark splash (`values`/`values-v31` themes) · monitor-status dot via `MonitorStatusHolder` ·
  motion detection confirmed global (setting checked per monitor cycle, no per-device field).
- **Features**: auto-export complete file to the exposed SAF folder at finalization.
- **Phase 1 hotfixes**: Bug #4 `DeviceEditDialog` copy-over from `initialDevice` (no more reset of
  un-shown fields) · Bug #5 ring wake/vibrate/sound wired + `maxStorageUsageMb` UI · Bug #7 per-event-type
  notification ID spaces · Room `fallbackToDestructiveMigration` removed · server auth deps added to
  all endpoints incl. media/live (mandatory-key remainder moved to Tier S).
- **Phase 2**: FGS start reworked for Android 12+/14 (`connectedDevice`, boot handling) · digest-auth
  parsing fixed (quoted params with commas, retries) · global `UncaughtExceptionHandler` replaced with
  player error callbacks.
- **Phase 3**: HTTPS fields UI · per-event duration fields UI · device-type picker (Generic RTSP
  reachable) · generic-device events documented as manual-record-only · server-URL default left as
  editable field (SKIPPED: entity-default change would need a full table-rebuild migration for a
  cosmetic value).
- **Phase 4 (server, done parts)**: MP4→MKV in `recorder.py` (both FFmpeg copy + snapshot/libx264
  paths, MIME `video/x-matroska`) · cleanup deletes files before rows · `@asynccontextmanager`
  lifespan · `StartServerRecordingPayload` sends `source_mode`/`note`.
- **Phase 5**: bulk delete · device filter chips + MANUAL chip · Exit action in foreground-service
  notification · settings backup export/import · `autoExportOnFinalize` + one-file-per-recording
  folder export.
- **Phase 6 (done parts)**: `MjpegStreamReader` buffered reads + suspend `onFrame` ·
  `OnDeviceMotionAnalyzer` adaptive idle backoff (500ms→1500ms, fast on change) ·
  `GET /api/devices` no longer returns passwords (blank = keep existing, server-side credential
  fallback on start).
- **Phase 7 (done parts)**: `targetSdk` 34→37 + edge-to-edge code migration (build green, device
  gate → Tier G) · `googleplay.yml` `workflow_dispatch` with selectable track ·
  `python-server.yml` push builds on `main` + `dev`.
- **Lint debt (safe fixes)**: `SipCallManager` StaticFieldLeak → holds `Application` ·
  `BUFFER_FLAG_SYNC_FRAME` dropped (keyframe via `containsIdr`) · speakerphone →
  `setCommunicationDevice(TYPE_BUILTIN_SPEAKER)` on API 31+ (pre-31 fallback warning intentionally
  visible).
- **Docs**: README/RtspStreamRecorder MP4→MKV wording fixed.
- **Tab swiping + accent colors (2026-09-29, commit `6f51a5e`)**: `MainActivity` `HorizontalPager`
  synced to the bottom `NavigationBar` (NavHost removed, per-screen state via the Activity-scoped
  ViewModels) · curated dark-scheme presets in `ui/theme/Theme.kt` from a Settings swatch picker,
  persisted in `AppSettingsEntity.themeAccentIndex` (Room v7→v8 migration, default 0 = the previous
  baseline look). `compileDebugKotlin` + `lintDebug` green; swipe feel → Tier G.
- **Per-role accent selector (2026-09-29)**: the owner's answer to the `secondaryContainer` question —
  "add a Primary, secondary, tertiary selector dialog, but all accent colors get the same palette".
  Settings → Appearance keeps the eight-swatch row and a tap now writes the same preset index to **all
  three roles** (uniform look, and it clears any earlier override); under the row a summary line
  ("Primary: Teal • Secondary: Teal • Tertiary: Amber") and **Customize color roles…** open the new
  `AccentRolePickerDialog`, where each role independently picks one of the **eight curated presets** —
  never a free color, so the chosen preset's own `on*` pair keeps every combination contrast-checked
  (the reason a color wheel was not offered). Picked swatches apply immediately (persisted through the
  Activity-scoped `SettingsViewModel`), the dialog stays open, one **Done** button dismisses it.
  Storage: three Int columns `themePrimaryIndex`/`themeSecondaryIndex`/`themeTertiaryIndex`, Room
  **v8→v9** — `MIGRATION_8_9` adds them and backfills all three from `themeAccentIndex`, so an installed
  build upgrades to exactly the look it had. `themeAccentIndex` stays declared on the entity as a
  documented legacy field rather than being dropped: the column would remain in `app_settings` where Room
  validates it against the entity, so removing the field means rebuilding the table inside the migration —
  more risk than carrying one unused, default-0 field (Gson backups keep round-tripping it harmlessly).
  `AppTheme` takes the three indices and builds the scheme from three presets (`remember`ed on those
  keys). Owner choice on the container question: each role also takes **its own preset's** verified
  `primaryContainer`/`onPrimaryContainer` pair as its `secondaryContainer`/`tertiaryContainer` pair,
  because grepping showed the app reads no `colorScheme.secondary`/`.tertiary` at all — the only visible
  consumers of those roles are container-based components (the `NavigationBar` active pill, the
  `FilterChip` rows, `RecordingCardItem.kt:98`), so without this the Secondary/Tertiary rows would have
  been inert. No new colors derived, and the pair's contrast is the already-measured one.
  Remaining gap found while checking this: **no component in this UI reads a tertiary role at all**, so the
  Tertiary row persists a choice that nothing paints with yet — giving it a real consumer (the live REC
  badge or the selected-card accent are the candidates) is follow-up UI work, not more color derivation.
  `compileDebugKotlin` + `lintDebug` green (same 25 pre-existing findings);
  migration + dialog → Tier G.
- **Export broadcast + WifiLock SDK branch (2026-09-29, commit `0acd070`)**: exported
  `ExportTriggerReceiver` (action `io.github.mvolkert.entryrecorder.action.EXPORT_RECORDINGS`,
  `--es scope latest|all`) only enqueues the new `worker/ExportTriggerWorker.kt`, which reuses
  `ExportTranscoder`/`ExportHelper` to write the latest/all finalized recordings into the configured
  SAF folder — external automation (Tasker/`adb`) without touching the capture lifecycle
  (deliberate deviation: NOT hooked into `video/RtspStreamRecorder.kt`'s 24/7 finalize path).
  `acquireWakeAndWifiLocks()` branches on `SDK_INT >= Q` → tag-only `createWifiLock()` with the
  pre-29 `WIFI_MODE_FULL_HIGH_PERF` fallback under a scoped `@Suppress("DEPRECATION")`; device
  ring-poll latency → Tier G.
- **Dual-container split (2026-09-28)**: phone export transcode emits fragmented MP4 via the new
  pure-Kotlin `Fmp4StreamMuxer` (`${name}_h264.mp4`); `ExportHelper.mimeFor` maps mp4/m4v → `video/mp4`
  (dropped the wrong m4v → `video/x-matroska` grouping). Server encoded recording paths (RTSP copy +
  snapshot libx264) write fragmented MP4 (`+frag_keyframe+empty_moov+default_base_moof`); only the
  no-FFmpeg raw-JPEG snapshot fallback stays MJPEG-in-MKV. Raw-MJPEG phone capture (`MkvStreamMuxer`)
  unchanged. `main.py` `/video` maps non-`.mkv` → `video/mp4` (existing suffix logic). Web UI warning
  reworded for the fMP4 default. Validated headless: `assembleDebug` green + JVM/ffprobe round-trip of
  the phone muxer (mov,mp4 demuxer, decodable h264); exact server movflags through ffmpeg + ffprobe.
- **fMP4 export VLC fix (2026-09-28)**: `Fmp4StreamMuxer.finalizeTimeline()` (called from `close()`) now
  seeks back to patch the `mvhd`/`tkhd`/`mdhd` durations and appends an ffmpeg byte-compatible
  `mfra`/`tfra` seek table; also fixed a structural defect where `mdhd/hdlr/minf` were written as direct
  `trak` children instead of inside the required `mdia` box. A bare `+empty_moov` fragment stream (durations
  0, no `mfra`) rendered 00:00/black in strict demuxers (VLC) while browsers/ExoPlayer/MX rebuilt the
  timeline from the fragments. Off-device: `ffprobe` reports duration 2.0 (not 0.0) and full decode is
  clean; on-device: exported `_h264.mp4` now plays in VLC (owner, 2026-09-28).
- ✅ Bug #1 — MJPEG-in-MKV playback (hybrid cheap capture + player + transcode-on-export).
- ✅ Bug #2 — export MIME/extension derived from actual file.
- ✅ Bug #8 — SAF export folder + pure Share + Save-to-Gallery.
- ❌ WON'T DO — SAF live recording capture location (crash-resilience + CPU/battery, see design intent).
