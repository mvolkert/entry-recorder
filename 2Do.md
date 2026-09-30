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
- [ ] **Snapshot HTTP client times out ~1 min after the display turns off → no continuous observation**
      (owner-reported 2026-09-29, logcat `HttpSnapshotClient D Snapshot fetch exception for 2N IP Verso:
      timeout`). Diagnosed from code, **not yet root-caused on device** — the mechanism matters and the
      three candidates need different fixes.
      Where the log comes from: `data/network/HttpSnapshotClient.kt:41` catches the exception and prints
      `e.message`; OkHttp's `AsyncTimeout` renders a *silent* stall as exactly `"timeout"` (a dead socket
      would instead say `Read timed out`/`Failed to connect`), so the request went out and the 2N never
      answered within the client's **4 s connect / 4 s read** timeouts (L18-19).
      Who is affected with the screen off: only `video/RtspStreamRecorder.kt:228` (the live view at
      `LiveStreamPlayer.kt:197` is not on screen). Consequence chain, which is why the archive ends up
      with nothing usable: the polling loop at L226-234 **swallows** the failure, still sleeps
      `1000 / fps` ms, and the recording deadline (L202) is wall-clock — so every stalled fetch burns
      ~4 s of the recording window as *zero* frames, and a run of them yields `fileSize == 0`, which is
      discarded at L289-291 ("Recording produced empty file"). Motion detection has the same failure
      shape through a *different* client: `video/OnDeviceMotionAnalyzer.kt:112-114` is a plain
      `HttpURLConnection` with 3 s timeouts, so there the frames just stop arriving and no motion event
      is ever raised (that path logs under its own tag, not `HttpSnapshotClient`).
      Candidates, in the order I would test them:
      1. **Doze / light-Doze network denial.** The service holds a `PARTIAL_WAKE_LOCK` (24 h) and a
         Wi-Fi lock (`service/IntercomMonitorService.kt:298-320`), but a wakelock does **not** exempt an
         app from Doze and neither does the `connectedDevice` foreground-service type. The ~1 min delay
         matches the first idle window almost exactly. Fix needs a battery-optimization exemption →
         **manifest permission (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) = ask-first territory**.
      2. **Wi-Fi firmware power save.** `0acd070` changed the API 29+ branch to the tag-only
         `createWifiLock(tag)` overload, i.e. `WIFI_MODE_FULL_LOW_LATENCY`; only
         `WIFI_MODE_FULL_HIGH_PERF` stops the chip's power-save. On Android 10+ this app therefore holds
         a *weaker* lock than it did before that commit — the exact "device ring-poll latency" risk that
         commit's own Tier G note flagged.
      3. **Stale keep-alive at the 2N.** If the camera drops the idle socket without a RST, the next
         pooled request is written into a dead socket and times out silently. Consistent with "after one
         minute", but *not* with "only when the display is off" (the poll keeps the socket hot), so it is
         the weakest candidate unless polling also stops.
      Cheap discriminator before any code: `adb shell dumpsys deviceidle whitelist +io.github.mvolkert.entryrecorder`,
      then screen off for two minutes and watch logcat. Timeouts gone → (1); still there → (2)/(3). Also
      worth confirming `adb shell dumpsys power` still lists `EntryRecorder::MonitorWakeLock` at that
      moment — if the lock is *not* held, that is the bug and the polling is simply being frozen.
      Independent of the cause, the client is fragile by design and worth hardening in its own pass:
      a `callTimeout` so one stalled fetch cannot eat the frame budget, a consecutive-failure counter
      surfaced as a real `IntercomEvent.ConnectionState` instead of a `Log.d` nobody watches, and no
      reuse of a socket idle for more than the camera's keep-alive window.


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
