# EntryRecorder – Implementation Phases


## Bug
- [x] Notification is not the correct new Launcher icon — replaced the four legacy `android.R.drawable`
      small icons in `NotificationHelper` with a dedicated `drawable/ic_notification` (the CCTV launcher
      mark as a white 24dp silhouette). `compileDebugKotlin` + `lintDebug` green (done 2026-09-29).

Reorganized from the code & feature review, re-verified against the current codebase
(`app/`, `server/`, `.github/`) on 2026-09-27; **task list re-sorted by invasiveness (minimal →
architectural) on 2026-09-28**, so small changes get done first and big ones later.
Phase labels are kept on each item for traceability to the original plan.

Legend: `[x]` implemented · `[~]` partially implemented / needs validation · `[ ]` missing
     🔄 pending on-device validation · 🔭 long-term / not scheduled

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

# Tier A — Trivial / config-level (minimal invasive)
Single-file, non-behavioral or config-only changes.

- [x] **Docs: README server-API example** – payload example now mirrors the real `StartServerRecordingPayload`
      (`source_mode: "auto"`, `snapshot_url`, `note`) + credential-fallback and `X-API-Key` prose.
      Files: `README.md`, `server/README.md` (done 2026-09-28)
- [x] **Server: Web UI supplies the mandatory API key** – Tier B's always-reject auth broke the dashboard:
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
      Tier B live-card rules). Docs updated in both READMEs.
      ⚠️ Not verified: the server was not booted (no `fastapi`/`uvicorn` in this environment); Starlette's
      automatic HEAD-for-GET support is assumed.
      Files: `server/entry_recorder_server/static/index.html`, `server/tests/webui_auth_harness.mjs` (new),
      `README.md`, `server/README.md` (done 2026-09-29)
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

  ### Owner checklist — Firefox (the one GUI-browser gate left in Tier A)
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
  6. **Live view** — a server-created camera shows moving MJPEG, and the Tier B no-rebuild fix holds:
     watch one camera for ~15 s — the picture must not blank at each poll, and DevTools → Network must show
     **one** long-lived `/api/live/<id>/mjpeg` request, not a new one every 5 s. Start/stop a recording on that
     card: the footer label and ⏺/⏹ change without the picture restarting. Press ⛶ and wait 15 s: you must
     still be in fullscreen (the old poll ejected you). Unplug a camera → the tile dims; plug it back and click
     the tile → the stream reconnects.
  7. **Safari** — cannot be covered on Windows; if you ever check it, the same steps 3 and 5 apply.

  Mark this item `[x]` only when 1–5 pass in Firefox (step 6 closes the Tier B live-card item).
  Report which step failed and what the modal said.


# Tier B — Localized one-file changes (small, isolated)
Confined to a single file/module; no cross-layer design decisions.

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
      stubs the DOM), so the flicker-free reconnect behaviour still needs the Tier A Firefox checklist step 6.
      File: `server/entry_recorder_server/static/index.html` (`renderLiveCameras()`, `updateLiveCardStates()`,
      `reloadLiveStream()`) (done 2026-09-29)
- [x] **Server: make auth mandatory** (former Phase 1) – auth deps exist on **all** endpoints incl.
      `/video`, `/thumbnail`, `/api/live/{id}/mjpeg`. `verify_api_key` now always rejects a missing/wrong
      key; a new `ensure_api_key()` generates a random key on first run, persists it to `data/.api_key`
      and logs it (a `.env` `API_KEY` still wins). Server `py_compile` clean (done 2026-09-29, commit `0acd070`).
      Files: `server/entry_recorder_server/main.py`
- [x] **Broadcast intent to trigger export** (former Features) – exported `ExportTriggerReceiver`
      (action `io.github.mvolkert.entryrecorder.action.EXPORT_RECORDINGS`, `--es scope latest|all`)
      only enqueues a `WorkManager` `ExportTriggerWorker` that reuses `ExportTranscoder`/`ExportHelper`
      to write the latest/all finalized recordings into the configured SAF folder; enables external
      automation (Tasker/`adb`).
      **Deviation (deliberate):** did NOT hook the 24/7 finalize path in `video/RtspStreamRecorder.kt` —
      not needed for the automation case and avoids adding a broadcast to the capture lifecycle.
      Files: new receiver + `AndroidManifest.xml` + new `worker/ExportTriggerWorker.kt` (done 2026-09-29, commit `0acd070`).
- [x] **WifiLock SDK-version branch** (former Lint debt, safe part) – `acquireWakeAndWifiLocks()` now
      branches on `Build.VERSION.SDK_INT >= Q (29)` → non-deprecated tag-only
      `createWifiLock("EntryRecorder::MonitorWifiLock")`; the pre-29 fallback keeps
      `WIFI_MODE_FULL_HIGH_PERF` under a single-line scoped `@Suppress("DEPRECATION")`.
      `compileDebugKotlin` green (device ring-poll latency check covered in Tier G) (done 2026-09-29, commit `0acd070`).
      File: `service/IntercomMonitorService.kt` (`acquireWakeAndWifiLocks()`)

# Tier C — Medium UI features (single-purpose, multi-file)
Behavioral but self-contained UI work.

- [x] **Tab swiping** (former UX) – `MainActivity` now uses a `HorizontalPager` synced to the bottom
      `NavigationBar` (swipe + tap both change pages); per-screen state persists via the Activity-scoped
      ViewModels. NavHost removed. `compileDebugKotlin` + `lintDebug` green; swipe feel on device (Tier G) (done 2026-09-29, commit `6f51a5e`).
      File: `ui/MainActivity.kt`
- [x] **Pickable accent colors** (former UI) – curated dark-scheme presets in `ui/theme/Theme.kt`
      applied from a Settings swatch picker; the choice persists in `AppSettingsEntity.themeAccentIndex`
      (Room v7→v8 migration, default 0 = previous baseline look). `compileDebugKotlin` + `lintDebug` green (done 2026-09-29, commit `6f51a5e`).
      Files: `ui/theme/Theme.kt`, `ui/MainActivity.kt`, `ui/settings/SettingsScreen.kt`, `data/local/entity/AppSettingsEntity.kt`, `data/local/AppDatabase.kt`

# Tier D — Larger UI / cross-cutting features
Multiple screens or cross-cutting behavior; design worth confirming before building.

- [x] **M3 Expressive Motion** (former UI) – centralized spring tokens in `ui/theme/Motion.kt` drive an
      expressive pager page scale/fade + a nav-icon selection spring; no custom durations. Applied at
      the pager layer on purpose so distant pages (incl. live camera streams) are NOT force-composed
      (keeps capture cheap). `compileDebugKotlin` + `lintDebug` green; motion feel on device (Tier G) (done 2026-09-29, commit `94fcd2e`).
      Files: `ui/theme/Motion.kt`, `ui/MainActivity.kt`
- [ ] **Live view through the server** (former Phase 4) – consume `/api/live/{id}/mjpeg` in
      `LiveCamerasScreen` (`LiveStreamPlayer`). Depends on server auth (Tier B, now done) but is **still
      blocked** for app-managed cameras by the sync gap (Tier F) — works today only for server-UI-created
      devices. **DEFERRED** per owner Tier-D selection (2026-09-29).
- [ ] **Surface server recordings in the app** (former Phase 4, Bug #3) – data layer done
      (`ServerRecordingClient.listRecordings()` + `ServerRecordingDto`, server-relative URLs).
      Remaining is architectural: a UI-model abstraction over the Room-only `RecordingEntity` list
      for network thumbnails + remote playback. **Confirm design before building. DEFERRED** per owner
      Tier-D selection (2026-09-29).
      Files: `data/server/ServerRecordingClient.kt`, `ui/recordings/RecordingsViewModel.kt`, `ui/recordings/RecordingsScreen.kt`

# Tier E — Service & data-layer refactors (larger blast radius)
Touch the 24/7 monitoring/recording lifecycle or the capture pipeline.

- [~] **Server-mode stop reconciliation** (former Phase 2) – auto-stop uses a fixed
      `maxDurationSeconds+2` local timer with no reconciliation of the actual server job; the app
      holds no server recording id, so stop/status can drift. Return/persist the server recording
      id and reconcile via `/api/recordings/{id}` (also needs the server to return an id from start).
      File: `video/RtspStreamRecorder.kt:69-79`
- [ ] **Server ↔ app device sync** (former Phase 4) – server `devices` table and app devices are
      independent; the app never registers devices on the server, so `/api/live/{id}/mjpeg` only
      works for server-UI-created devices. New sync API + pairing flow + conflict handling.
      Files: `data/server/ServerRecordingClient.kt`, `server/entry_recorder_server/main.py`
- 🔭 Long-term: **RTSP H.264 passthrough** (demux→re-mux, zero re-encode/CPU) for stream-capable
      cameras — capture-pipeline rewrite, only for future RTSP-only devices.

# Tier F — Heavy / device-unverifiable refactorings (deferred backlog)
Large, risky, or impossible to validate from CI; each needs a real device/PBX pass. Kept deferred
per owner decisions — do NOT start these before Tiers A–E and the Tier G validation are done.

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
      ⚠️ Dropping user CAs breaks self-signed HTTPS on 2N devices; only after the auth work (Tier B)
      makes the server HTTPS-first.
- [~] **Credential leakage in RTSP URLs** (former Phase 6) – `rtsp://user:pass@host` embedded URLs
      get logged/persisted by external libs. No app-side log statement emits this URL today
      (verified); Media3/ffmpeg logging is outside our control — effectively **wont-fix** unless
      auth-via-header on `GenericRtspDevice` is implemented with an RTSP-only device.
- [ ] **Linphone ABI coverage** (former Phase 7, DEFERRED) – only `jni/arm64-v8a/liblinphone.so`
      committed, `jni/` isn't wired into `sourceSets`/`jniLibs.srcDir` so packaging behavior is
      unclear; blind `abiFilters` could exclude devices. Needs a release-bundle ABI inspection first.

---

# Tier G — On-device validation gates (no code, but blocks marking things done)
Compile-green ≠ done; run these on the owner's real hardware before closing the related tiers.

## targetSdk 37 / edge-to-edge 🔄 (needs an Android 15/16 device)
The 34→37 bump forces edge-to-edge (Android 15) and tightens background-activity-launch (Android 16);
none of this is verifiable from the build.

- [x] Compiles & installs with `targetSdk 37` (assembleDebug green).
- [ ] Edge-to-edge layout: status bar no longer overlaps the top of Live / Recordings / Settings; nav bar
      doesn't cover content or the Settings FAB.
- [ ] `RecordingsScreen` custom top bar ("Recordings Archive" + search/filter chips) sits below the status bar,
      not behind the clock.
- [ ] Incoming-call screen: header and call controls (mute/speaker/hangup) clear the status & gesture/nav bars
      on a punch-hole / gesture-nav device; video still renders fullscreen behind the bars.
- [ ] Locked-screen doorbell ring → full-screen intent still fires and shows `IncomingCallActivity` over the keyguard.
- [ ] Motion/noise → high-priority heads-up notification appears (screen may NOT wake directly from the service on
      Android 15/16 due to BAL rules); tapping it opens `IncomingCallActivity`.
- [ ] Reboot → `BootReceiver` autostart of the `connectedDevice` foreground service still succeeds
      (`ForegroundServiceStartNotAllowedException` regression check).

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
  all endpoints incl. media/live (mandatory-key remainder moved to Tier B).
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
