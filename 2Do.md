# EntryRecorder – Implementation Phases

Reorganized from the code & feature review, re-verified against the current codebase
(`app/`, `server/`, `.github/`) on 2026-09-27.

Legend: `[x]` implemented · `[~]` partially implemented / needs validation · `[ ]` missing

---

## Design intent (fixed, do not revisit)
- The **primary target camera supports snapshot pulling only** – the app is intentionally built
  around snapshot/MJPEG capture. Missing RTSP/H.264 capture is **not a bug for the main use case**;
  it only matters for future/optional RTSP-only devices.
- **MKV is the deliberate container everywhere** (crash resilience via incrementally-flushed
  clusters; MP4's trailing `moov` atom is unrecoverable on abort).
- **Cheap capture, lazy encoding**: no re-encode at record time (24/7 events → CPU/battery
  unacceptable). H.264 transcode happens only on explicit export/share.
- Capture location stays a forward-only local `FileOutputStream` in `filesDir/recordings`;
  playable output belongs in SAF only via export/transcode-on-demand. **(WON'T DO: SAF live capture)**

---

## Phase 0 — Validate the hybrid playback/export pipeline on a real device ⚠️
Everything already shipped (Bug #1 hybrid + #8 SAF export) is device-dependent and unverifiable from CI.
Do this first — it gates Phase 4 (server MKV) and de-risks everything else.

- [ ] In-app player: `JpegFramePlayer` + `MjpegMkvReader` (EBML parsing, offset index, scrubbing) on a real device.
- [ ] Export: `ExportTranscoder` → `H264Encoder` + `MkvStreamMuxer` `avcC`/AVCC framing;
      **re-export and confirm VLC plays** the previously black-screen recordings (AVCC length-prefix fix).
- [ ] SAF export folder: grant persistence across reboots, overwrite-by-name behavior,
      both `_h264.mkv` + raw MKV appear and play in the chosen folder.
- [ ] Room migrations v4→v5 (`transcodeOnExport`) and v5→v6 (`exportFolderUri`) run cleanly over real data.

**Done so far (freeze, don't rebuild):** capture stays JPEG-in-MKV no re-encode · thumbnails from
first captured JPEG · in-app `JpegFramePlayer` routing in `VideoPlayerModal` · transcode-on-export +
settings toggle · VLC/AVCC fix · export MIME `video/x-matroska` (Bug #2 ✅) · Share/export split with
SAF folder (Bug #8 ✅).

Files: `video/MjpegMkvReader.kt`, `ui/components/JpegFramePlayer.kt`, `ui/components/VideoPlayerModal.kt`,
`video/ExportTranscoder.kt`, `video/MkvStreamMuxer.kt`, `util/ExportHelper.kt`, `data/local/AppDatabase.kt`

---

## Phase 1 — Data-loss & correctness hotfixes (small, isolated)
Silent data loss and dead UI come first — cheap fixes, immediate user impact.

- [x] **Bug #4 – Device edit resets un-shown fields.** `DeviceEditDialog` rebuilds a full `DeviceEntity`
      on save but omits `useHttps`, `httpsPort`, `ringRecordSeconds`, `motionPostRecordSeconds`,
      `noisePostRecordSeconds`, `isEnabled` → each edit silently reverts them. Start from
      `initialDevice` and copy-over only edited fields. File: `ui/settings/DeviceEditDialog.kt:392-417`
- [x] **Bug #5 – Dead settings.** `wakeOnRing`/`vibrateOnRing`/`soundOnRing` are toggled in
      `SettingsScreen` but never read by `IntercomMonitorService` (doorbell always wakes/rings) —
      wire them into the DoorbellRung path or remove the toggles.
      `maxStorageUsageMb` is used by the worker but has **no UI** despite README "configurable storage quota" → add one.
      Files: `service/IntercomMonitorService.kt`, `ui/settings/SettingsScreen.kt`
- [x] **Bug #7 – Notification-ID collisions.** Verified still present: `NOTIFICATION_ID_DOORBELL(1002)
      + device.id`, `MOTION(1003) + id`, `NOISE(1004) + id` overlap across channels for different devices
      (doorbell id=2 → 1004 == motion id=1 → 1004); `.toInt()` on a `Long` id can wrap.
      Use a dedicated ID space per event type. File: `notification/NotificationHelper.kt:142,170,198`
- [~] **Server: unconditional auth.** `verify_api_key` is a no-op when `API_KEY` is empty (`.env` ships none),
      and `/api/recordings/{id}/video`, `/thumbnail`, `/api/live/{id}/mjpeg` have **no auth dependency at all**
      (verified). Require a key (generate default on first run) and protect the media/live endpoints.
      File: `server/entry_recorder_server/main.py:59-65,196-222`
- [x] **Room: remove destructive fallback risk.** Verify `fallbackToDestructiveMigration` no longer active /
      real migrations for every version bump — a v-bump must never wipe the recordings index.
      File: `data/local/AppDatabase.kt`

---

## Phase 2 — Service reliability (foreground service & SIP)
Make the 24/7 monitoring actually survive reboots and multi-device use.

- [x] **Bug #6 – FGS start blocked on Android 12+/14.** `BootReceiver`/background paths start the FGS with
      `connectedDevice|phoneCall`; a `phoneCall`-type FGS from a BOOT broadcast is restricted →
      `ForegroundServiceStartNotAllowedException`, autostart silently fails. `FOREGROUND_SERVICE_MEDIA_PLAYBACK`
      permission is declared but the type unused. Rework the start path (e.g., `connectedDevice` for monitoring,
      proper exceptions/exempted boot handling). Files: `AndroidManifest.xml:73-88`, `receiver/BootReceiver.kt`,
      `service/IntercomMonitorService.kt:314-322`
- [~] **Server-mode stop reconciliation.** Auto-stop uses a fixed `maxDurationSeconds+2` local timer with no
      reconciliation of the actual server job; the app holds no server recording id, so stop/status can drift.
      Return/persist the server recording id and reconcile via `/api/recordings/{id}`. File: `video/RtspStreamRecorder.kt:69-79`
- [~] **SIP per-device cores.** (DEFERRED — owner chose to revisit with real device/PBX; large unverifiable refactor) One global Linphone core reconfigured per device in `updateMonitoredDevices` —
      with multiple devices the last one wins; P2P vs PBX cannot coexist. File: `sip/SipCallManager.kt`
- [~] **Replace global `UncaughtExceptionHandler`** that swallows Media3 RTSP NPEs — masks real crashes and can
      leave the player broken; handle at the player error callback instead. File: `EntryRecorderApp.kt:63-79`
- [x] **Digest auth robustness.** `parseDigestParams` splits on `,` (breaks on commas in `realm`/`nonce`),
      MD5-only, gives up after one `Authorization` attempt. File: `data/device/TwoNIPVersoDevice.kt:320-332`

---

## Phase 3 — Settings & device UI completion
Close the "claimed but not editable" gaps once the data layer is safe (Phase 1 #4).

- [x] **HTTPS fields UI** – `useHttps`/`httpsPort` exist in `DeviceEntity` but no inputs in `DeviceEditDialog`.
- [x] **Per-event recording durations UI** – `ringRecordSeconds`, `motionPostRecordSeconds`,
      `noisePostRecordSeconds` exist but are not editable.
- [x] **Generic RTSP/ONVIF device type unreachable** – `DeviceEditDialog` keeps `deviceType` in state
      (line 40) but renders **no selector**; it always stays `TWO_N_VERSO`, so `GenericRtspDevice` is dead code.
      Add a device-type picker.
- [x] **Generic device events** – `GenericRtspDevice.startMonitoring()` emits no events (only "RTSP Ready") →
      generic cams support manual recording only, no motion/ring auto-record. Decide: implement snapshot-based
      motion events vs document the limitation. File: `data/device/GenericRtspDevice.kt:36-39`
- [~] **Default server URL** – `AppSettingsEntity.serverBaseUrl` hard-codes LAN IP `http://192.168.1.100:8000`;
      default empty with an explicit "Set up server" flow. (SKIPPED: the field is already editable in Settings, and
      changing the entity default alters Room's generated schema and fails v6 schema validation on existing installs
      — needs a full table-rebuild migration for a cosmetic value. Revisit if a settings schema migration is done anyway.)

---

## Phase 4 — Server recordings in the app + MKV-everywhere
The largest feature work; depends on Phase 0 validation (server output format) and Phase 2 (id handling).

- [~] **Bug #3 – Surface server recordings in the app.** Data-layer foundation added: `ServerRecordingClient.listRecordings()`
      + `ServerRecordingDto` (server-relative `/video` & `/thumbnail` URLs). Remaining: a unified/bridged gallery that
      renders network thumbnails + streams remote playback needs a UI-model abstraction over the Room-only
      `RecordingEntity` list (architectural change; confirm design before building).
      Files: `data/server/ServerRecordingClient.kt`, `ui/recordings/RecordingsViewModel.kt`, `ui/recordings/RecordingsScreen.kt`
- [ ] **Live view through the server** (`/api/live/{id}/mjpeg`) in `LiveCamerasScreen`.
- [x] **Server: MP4 → MKV.** `recorder.py` now writes `.mkv` (`-f matroska`, MP4 `+faststart` removed) for both
      FFmpeg RTSP copy and snapshot/libx264 paths; `/video` derives `video/x-matroska`. ⚠️ Verify the web `index.html`
      HTML5 `<video>` plays MKV in target browsers (some browsers won't); may need an in-page transcode/download note.
- [ ] **Server ↔ app device sync.** Server `devices` table and app devices are independent; the app never
      registers devices on the server, so `/api/live/{id}/mjpeg` only works for server-UI-created devices.
- [x] **Server cleanup order.** `cleanup_recordings` now deletes files first and only removes the DB row when file
      deletion succeeded (failed deletions keep the row so nothing is orphaned); same ordering applied to `delete_recording`.
- [x] **Server: lifespan handler** – replaced deprecated `@app.on_event("startup")` with an `@asynccontextmanager` lifespan.
- [~] **Docs/API payload parity** – app `StartServerRecordingPayload` now sends `source_mode` ("auto") and `note`,
      matching the server model. `server/README.md` example update folded into the Docs section.
- 🔭 Long-term: **RTSP H.264 passthrough** (demux→re-mux, zero re-encode/CPU) for stream-capable cameras.

---

## Phase 5 — Gallery UX & feature requests
User-facing extras, independent of the pipeline work.

- [x] **Bulk / multi-select delete** ("Löschen mehrerer Aufnahmen") — single delete only today.
- [x] **Device filter chips + `MANUAL` filter chip** in `RecordingsScreen` (ViewModel already supports `deviceId`;
      only 4 chips exist today).
- [x] **Exit button in the expanded foreground notification** to stop monitoring
      (`NotificationHelper.buildServiceNotification` has no stop action).
- [x] **Backup function** – export/import all settings (devices + app settings) to a user file.

---

## Phase 6 — Battery, performance & security hardening
Ongoing-cost items; heaviest design work, tackle after features are stable.

- [~] **Always-on wake/wifi locks.** Verified: `PARTIAL_WAKE_LOCK` (24h) + `WIFI_MODE_FULL_HIGH_PERF`
      `WifiLock` acquired at service start and held continuously → heavy battery drain (this is the answer to
      the standing "Active writing on disk 24/7?" note — it's the locks, not disk I/O).
      Keep locks only during active recording/ring, not idle monitoring. File: `service/IntercomMonitorService.kt:265-285`
      **(DEFERRED — owner: app runs on a dedicated, detection-first device; battery is secondary to never missing a ring. Revisit as a later optimization.)**
- [x] **MjpegStreamReader efficiency.** Reads one byte at a time and calls `runBlocking` per frame inside a
      coroutine collector → inefficient, can block the dispatcher. Now buffered chunk reads + direct suspend `onFrame`. File: `data/network/MjpegStreamReader.kt:119-153`
- [x] **Motion-analyzer polling.** `OnDeviceMotionAnalyzer` polls a snapshot every 500ms per device indefinitely
      while monitoring — added adaptive idle backoff (500ms→1500ms) that snaps straight back to the fast interval on any pixel change, so detection latency is unchanged.
- [~] **Credential storage.** Device HTTP passwords, SIP passwords, server API key stored **plaintext** in Room;
      migrate to EncryptedSharedPreferences / Keystore-backed encryption.
      **(DEFERRED — owner: Keystore-backed Room TypeConverter + migration is a large, device-unverifiable change; revisit in a dedicated security pass.)**
- [~] **Network transport.** `network_security_config` permits global cleartext **and trusts user CAs** (MITM risk) —
      scope cleartext to local subnet only, drop user-CA trust.
      **(DEFERRED — owner: dropping user-CA trust would break self-signed HTTPS on 2N devices; revisit as a security optimization.)**
- [~] **Credential leakage in RTSP URLs.** `rtsp://user:pass@host` embedded URLs get logged/persisted —
      redact in logs, auth via header where possible. File: `DeviceEntity.rtspStreamUrl`
      **(No app-side log statement emits this URL today; Media3/ffmpeg logging is outside our control — left as-is.)**
- [x] **Server: stop returning credentials.** `GET /api/devices` no longer returns the password; the web edit form
      treats a blank password as "keep existing" and `/api/recordings/start` falls back to server-stored credentials.
      File: `server/entry_recorder_server/main.py:265+`, `static/index.html`

---

## Phase 7 — Build, CI & platform hygiene
Do at a natural break; some items (targetSdk) are hard requirements for Play uploads.

- [ ] **`targetSdk = 34` while `compileSdk = 37`** — Google Play requires targetSdk 35+ for updates;
      plan the 35/36 behavior-change migration (esp. FGS types, photo picker, edge-to-edge). File: `app/build.gradle.kts`
- [ ] **`googleplay.yml` builds on `main`** but active development is on `dev`; Play upload uses `track: beta` —
      align branches/tracks.
- [ ] **`python-server.yml` `on.push.branches: [none]`** effectively disables push builds (only tags/PR/manual);
      README claims "automatically built on GitHub Actions" — fix trigger or docs. `.github/workflows/python-server.yml:4-6`
- [ ] **Linphone ABI coverage.** Only `jni/arm64-v8a/liblinphone.so` committed, no `abiFilters`/packaging config —
      non-arm64 devices/emulators depend entirely on the Maven artifact providing other ABIs.

---

## Docs
- [ ] `README.md` + `RtspStreamRecorder` class name/comments still say **MP4**; after the deliberate MP4→MKV
      pivot they should document MKV (also reflects Bug #1's resolved state).

---

## Resolved log
- ✅ Bug #1 — MJPEG-in-MKV playback: hybrid cheap-capture + `JpegFramePlayer` + transcode-on-export + toggle
      (⚠️ Phase 0 on-device validation pending).
- ✅ Bug #2 — Export MIME/extension derived from actual file (`video/x-matroska`).
- ✅ Bug #8 — SAF export folder + pure Share + "Save to Gallery" kept (⚠️ Phase 0 validation pending).
- ❌ WON'T DO — SAF live recording capture location (crash-resilience + CPU/battery reasons, see design intent).
