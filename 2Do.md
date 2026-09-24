# EntryRecorder – Code & Feature Review

Reviewed `README.md`, `server/README.md` and `2Do.md` against the actual codebase
(Android app under `app/`, Python server under `server/`, GitHub Actions under `.github/`).

Legend: `[x]` implemented · `[~]` partially implemented · `[ ]` missing / claimed but not delivered

---

## Feature Matrix (README claims vs. code)

### Android App
- [x] Configurable IP / HTTP port / RTSP port / Digest+Basic auth (`DeviceEntity`, `TwoNDigestAuthenticator`)
- [~] Configurable HTTPS – fields exist (`useHttps`, `httpsPort`) but **no UI** in `DeviceEditDialog`
- [x] Recording mode App-Local vs Python-Server with fallback (`RtspStreamRecorder.startRecording`)
- [x] Local recording container is **MKV** – deliberate, crash-survivable choice (incrementally-flushed clusters; MP4's trailing `moov` atom is unrecoverable on abort). Keep it.
- [~] Local recording only captures MJPEG/snapshot frames – no RTSP/H.264 capture path (see Bug #1)
- [x] Auto-record on Ring (`KeyPressed` / `CallStateChanged`)
- [x] Auto-record on Motion (SSE `MotionDetected` + HTTP polling fallback)
- [x] Auto-record on Noise (SSE `NoiseDetected` + HTTP polling fallback)
- [x] On-device motion analysis from snapshot stream (`OnDeviceMotionAnalyzer`)
- [x] Manual instant recording from Live view (`MainActivity` → `EventType.MANUAL`)
- [x] Lockscreen wake + fullscreen live video (`IncomingCallActivity`, `showWhenLocked`/`turnScreenOn`)
- [x] SIP intercom P2P + PBX registrar, mute & speaker toggle (`SipCallManager` via Linphone)
- [x] Searchable/filterable gallery, protect, single delete, in-app player (`RecordingsScreen`)
- [~] Video export / share – works but **MIME mismatch** (see Bug #2)
- [x] Retention policy (days) + storage-quota purge via WorkManager (`RetentionCleanupWorker`)
- [~] Multi-device extensibility – factory + interface exist, but generic RTSP/ONVIF device cannot be added via UI (see Gap #3)

### Python Server
- [x] FFmpeg RTSP recording + HTTP-snapshot fallback; currently **MP4** output (`recorder.py`) –
      planned to move to MKV (see Bug #2 / long-term MKV-everywhere plan)
- [x] Web UI dashboard + gallery + HTML5 player (`static/index.html`, `/`)
- [x] REST API `/api/status`, `/api/recordings/start`, `/api/recordings/stop`, `/api/recordings`
- [x] Extra endpoints (beyond README): `/api/recordings/{id}`, `/video`, `/thumbnail`, `/protect`, DELETE, `/api/cleanup`, `/api/devices` CRUD, `/api/live/{id}/mjpeg`
- [x] Automatic retention + storage quota (`cleanup_recordings`)

---

## Bugs

> Design intent (confirmed by owner):
> - The **primary target camera supports snapshot pulling only** – the app is intentionally built
>   around snapshot/MJPEG capture. Missing RTSP/H.264 capture is therefore **not a bug for the
>   main use case**; it only matters for future/optional RTSP-only devices.
> - MKV is the deliberate container everywhere (crash resilience via incrementally-flushed
>   clusters; MP4's trailing `moov` atom is unrecoverable on abort).
> - **Long-term: move all output to MKV, drop MP4 entirely** (including the Python server).

### 1. MJPEG-in-MKV cannot be decoded on-device (playback) — ✅ thumbnails fixed, ✅ hybrid playback/export implemented (⚠️ needs on-device validation)
The `V_MJPEG` track (independent of the MKV container) is **not a supported Android video codec**
(supported set is H.263/264/265, MPEG-4, VP8/9, AV1). Confirmed impact:
- **Thumbnails** via `MediaMetadataRetriever` returned null → gallery showed only the fallback icon.
- **In-app playback** (`VideoPlayerModal` / ExoPlayer) cannot decode the track → black screen / unsupported.

**Design decision (why no re-encode at capture):** MKV is only the container; the unplayable part is
the `V_MJPEG` codec track. A snapshot-only camera yields standalone JPEG stills (no H.264 elementary
stream), so there is nothing to bitstream-copy. Re-encoding every captured frame was rejected because
the app records on events for 24/7 monitoring and continuous CPU/battery is unacceptable.

**Done (this change — hybrid):**
- ✅ **Capture stays cheap**: `RtspStreamRecorder` writes JPEG frames straight to a crash-safe
  `V_MJPEG` MKV with **no re-encoding** (reverted from the earlier always-transcode version).
- ✅ **Thumbnails** generated directly from the first captured JPEG frame (`ThumbnailUtil.saveThumbnailFromJpeg`).
- ✅ **In-app playback**: new `JpegFramePlayer` (Compose) decodes/displays stored JPEG frames on demand
  via a new `MjpegMkvReader` (minimal EBML reader with an offset frame index → random access + scrubbing).
  `VideoPlayerModal` routes local `.mkv` recordings to it; ExoPlayer is kept for other containers.
- ✅ **Transcode only on export/share**: `ExportTranscoder` (reusing `H264Encoder` + `MkvStreamMuxer`
  `V_MPEG4/ISO/AVC`/`avcC`) converts the MJPEG MKV to a playable H.264 MKV **on the explicit export
  action**, with a progress dialog. This is the only place CPU is spent.
- ✅ **VLC black-screen fix (re-encoded MKV)**: Matroska stores H.264 in **packetized AVCC mode** — each
  NAL prefixed with a 4-byte big-endian length (matching the `avcC` `lengthSizeMinusOne = 3`) — *not*
  Annex-B start codes. `MkvStreamMuxer.writeH264Frame` now converts each Annex-B access unit to
  length-prefixed NALs before writing the SimpleBlock. Previously strict demuxers (VLC/libavformat) read
  the `00 00 00 01` start code as a length of 1 and misparsed every frame → **black screen** (MXPlayer
  recovered by sniffing Annex-B). ⚠️ Re-export and confirm playback in VLC.
- ✅ **Settings toggle** "Transcode to H.264 on Export" (default on, with a CPU/battery note). Off = share
  raw MKV. Requires a Room migration (DB v4→v5, new `transcodeOnExport` column) — added as a real
  `Migration` because destructive fallback is disabled.
- ✅ Export now reports `video/x-matroska` + real extension instead of MP4 (Bug #2).
  - ⚠️ **Must be validated on a real device** — the in-app player, `MjpegMkvReader` parsing, and the
    export `H264Encoder`/`avcC` framing are device-dependent and can't be verified from CI.
- 🔭 **Future**: RTSP **H.264 passthrough** (demux→re-mux, zero re-encode/CPU) for devices that expose a
  real H.264 stream — no battery cost, only relevant for non-snapshot cameras.

Files: `video/MjpegMkvReader.kt` (new), `video/JpegFramePlayer`→`ui/components/JpegFramePlayer.kt` (new),
`ui/components/VideoPlayerModal.kt`, `video/ExportTranscoder.kt` (new), `video/H264Encoder.kt`,
`video/MkvStreamMuxer.kt`, `video/RtspStreamRecorder.kt`, `data/local/AppDatabase.kt`, `util/ExportHelper.kt`

### 2. Export/metadata hardcoded MP4 after the MKV pivot — ✅ FIXED
`ExportHelper` now derives MIME/extension from the actual file (`mimeFor()`): MKV → `video/x-matroska`,
and the MediaStore display name uses the source file's extension instead of always `.mp4`.
Files: `util/ExportHelper.kt` (fixed)

### 3. Python-Server recordings are invisible in the App
In `PYTHON_SERVER` mode the app only calls `start`/`stop`/`status`; it never queries
`/api/recordings`, `/video`, `/thumbnail` or `/live`. Those recordings therefore **never
appear in the app's Recordings gallery** (which only reads local Room). No live view through
the server either. Files: `video/RtspStreamRecorder.kt`, `data/server/ServerRecordingClient.kt`,
`ui/recordings/RecordingsViewModel.kt`

### 4. Editing a device resets un-shown fields (data loss)
`DeviceEditDialog` rebuilds a full `DeviceEntity` on save but omits `useHttps`, `httpsPort`,
`ringRecordSeconds`, `motionPostRecordSeconds`, `noisePostRecordSeconds`, `isEnabled`, so each
edit silently reverts them to defaults. File: `ui/settings/DeviceEditDialog.kt:392-417`

### 5. Dead settings (UI toggles with no effect)
`wakeOnRing`, `vibrateOnRing`, `soundOnRing` are stored & toggled in `SettingsScreen` but
**never read** by `IntercomMonitorService` (doorbell always wakes/rings regardless).
`maxStorageUsageMb` is used by the worker but has **no UI** despite README "configurable storage quota".
Files: `service/IntercomMonitorService.kt` (onEvent DoorbellRung), `ui/settings/SettingsScreen.kt`

### 6. Foreground-service start is likely blocked on Android 12+/14
`BootReceiver` and background paths start the FGS with
`foregroundServiceType="connectedDevice|phoneCall"`. Starting a `phoneCall`-type FGS from a
BOOT/background broadcast is restricted → `ForegroundServiceStartNotAllowedException`; autostart
after reboot can silently fail. `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permission is declared but
the type is never used. Files: `AndroidManifest.xml:73-88`, `receiver/BootReceiver.kt`,
`service/IntercomMonitorService.kt:314-322`

### 7. Notification-ID collisions
IDs are `1002+deviceId` (doorbell), `1003+deviceId` (motion), `1004+deviceId` (noise). These
overlap across channels for different devices (e.g. doorbell id=2 → 1004 == motion id=1 → 1004),
and `.toInt()` on a `Long` id can wrap. File: `notification/NotificationHelper.kt:142,170,198`

### 8. Export workflow: save exports to disk (SAF folder), separate from Share
Two related pain points reported after the hybrid export landed:
- **Canceled share wastes the re-encode**: `ExportTranscoder` writes the H.264 MKV into
  `cacheDir/export` (a cache dir, so it can be evicted and is invisible to the user). If the share
  sheet is dismissed, nothing is persisted and the whole transcode must be redone next time.
- **Proposal (target design)**: let the user pick a **persistent export folder via SAF**
  (`ACTION_OPEN_DOCUMENT_TREE` + persisted `TakePersistableUriPermission`) in Settings. On export,
  copy **both** the original `...snapshot.mkv` (MJPEG) **and** the re-encoded `..._h264.mkv` into that
  one user-accessible folder so they survive and are browsable/shareable outside the app. The **Share**
  button then becomes a *pure* share intent (FileProvider URI) and is no longer the only way to get the
  file onto disk. "Save to Gallery" would write into the chosen folder (or MediaStore) instead of cache.
  - Keep an **export status/indicator** so a long transcode is visible and can't be silently lost.
  - Related existing request: *"Move recordings to a SAF-pickable folder"* (Feature Requests below).
  Files: `ui/recordings/RecordingsScreen.kt`, `video/ExportTranscoder.kt`, `util/ExportHelper.kt`,
  `data/local/entity/AppSettingsEntity.kt` (new `exportFolderUri` + migration), `ui/settings/SettingsScreen.kt`

---

## Missing / Not delivered (vs README)
- [ ] Generic RTSP/ONVIF device type cannot be selected in the add/edit UI – `deviceType` always
      defaults to `TWO_N_VERSO`, so the second `IntercomDevice` implementation is unreachable.
- [ ] HTTPS / per-event recording durations / storage-quota not editable in the app UI.
- [ ] Bulk / multi-select delete ("Löschen mehrerer Aufnahmen") not implemented – single delete only.
- [ ] Device filter chips and a `MANUAL` filter chip missing in `RecordingsScreen` (ViewModel supports deviceId).
- [ ] No recording list / live view integration with the server in the app (see Bug #3).
- [ ] `GenericRtspDevice.startMonitoring()` emits no events (only "RTSP Ready") → generic cams
      support manual recording only, no motion/ring auto-record. File: `data/device/GenericRtspDevice.kt:36-39`

---

## Code Smells / Issues

### Performance & battery
- [ ] 24/7 `PARTIAL_WAKE_LOCK` (24h) + `WIFI_MODE_FULL_HIGH_PERF` `WifiLock` held continuously by the
      monitor service → heavy battery drain (addresses standing note *"Active writing on disk 24/7?"* –
      it is not disk I/O but the always-held wake/wifi high-perf locks). `service/IntercomMonitorService.kt:265-285`
- [ ] `MjpegStreamReader.readMjpegStream` reads **one byte at a time** and calls `runBlocking` per frame
      inside a coroutine collector → inefficient + can block the dispatcher. `data/network/MjpegStreamReader.kt:119-153`
- [ ] `OnDeviceMotionAnalyzer` polls a snapshot every 500ms per device indefinitely while monitoring.

### Correctness / robustness
- [ ] Global `UncaughtExceptionHandler` swallows Media3 RTSP `NullPointerException`s to keep the process
      alive – masks real crashes and can leave the player in a broken state. `EntryRecorderApp.kt:63-79`
- [ ] Server-mode auto-stop uses a fixed `maxDurationSeconds+2` local timer with no reconciliation of the
      actual server job; app holds no server recording id, so stop/status can drift. `video/RtspStreamRecorder.kt:69-79`
- [ ] `TwoNDigestAuthenticator.parseDigestParams` splits the header on `,` – breaks if `realm`/`nonce`
      contain commas; MD5-only digest; gives up after a single `Authorization` attempt. `data/device/TwoNIPVersoDevice.kt:320-332`
- [ ] SIP is one global Linphone core reconfigured per device in `updateMonitoredDevices` – with multiple
      devices the last one wins; P2P vs PBX cannot coexist. `sip/SipCallManager.kt`
- [ ] Room `fallbackToDestructiveMigration` – DB version bumps wipe the recordings index. `data/local/AppDatabase.kt:43`
- [ ] `AppSettingsEntity.serverBaseUrl` defaults to a hard-coded LAN IP `http://192.168.1.100:8000`.

### Security
- [ ] Device HTTP passwords, SIP passwords and the server API key are stored **plaintext** in Room;
      `network_security_config` permits global cleartext **and trusts user CAs** (MITM risk).
      `res/xml/network_security_config.xml`, `data/local/entity/DeviceEntity.kt`
- [ ] Server exposes device credentials via `GET /api/devices` (returns username/password) and leaves
      `/api/recordings/{id}/video`, `/thumbnail` and `/api/live/{id}/mjpeg` **unauthenticated** (no API-key dependency).
      `server/entry_recorder_server/main.py:196-222,265-267,325-355`
- [ ] Credentials embedded in RTSP URLs (`rtsp://user:pass@host`) are logged/persisted. `DeviceEntity.rtspStreamUrl`

### Docs / CI / config consistency
- [ ] `README.md` (and the `RtspStreamRecorder` class name/comments) still say **MP4**; after the
      deliberate MP4→MKV pivot they should document MKV.
- [ ] **Long-term MKV-everywhere**: switch the Python server (`recorder.py`) from MP4 (`-movflags
      +faststart`, `.mp4`) to MKV too, so the whole stack is MKV with no MP4 anywhere.
- [x] `ExportHelper` MP4 mislabelling → fixed to derive MKV MIME/extension (see Bug #2).
- [x] `VideoPlayerModal` / in-app playback: custom `JpegFramePlayer` (no capture re-encode) + H.264 transcode only on export (see Bug #1); on-device validation still pending.
- [ ] `server/README.md` request example sends `source_mode`, but the app's `StartServerRecordingPayload`
      omits `source_mode`/`note` (server always uses `auto`); app also sends `snapshot_url` not shown in the example.
- [ ] `python-server.yml` `on.push.branches: [none]` effectively disables automatic push builds (only tags/PR/manual);
      README claims "automatically built on GitHub Actions". `.github/workflows/python-server.yml:4-6`
- [ ] `googleplay.yml` builds on `main`, but active development is on `dev`; Play upload runs `track: beta`.
- [ ] `targetSdk = 34` while `compileSdk = 37`; Google Play will require a newer `targetSdk` (35+) for updates.
- [ ] Only `jni/arm64-v8a/liblinphone.so` is committed; no `abiFilters`/packaging config, so non-arm64
      devices/emulators depend entirely on the Maven Linphone artifact providing other ABIs.

---

## Server-side (Python) notes
- [ ] `@app.on_event("startup")` is deprecated – use the lifespan context handler. `main.py:68`
- [ ] Server `devices` table and the app's devices are two independent models; the app never registers
      devices on the server, so `/api/live/{id}/mjpeg` only works for devices created via the server Web UI.
- [ ] `cleanup_recordings` deletes rows then files; if file deletion fails the DB row is already gone (orphan files).

---

## Feature Requests (from original 2Do)
- [ ] Add an **Exit** button in the expanded (foreground) notification to stop monitoring
      (`NotificationHelper.buildServiceNotification` currently has no stop action).
- [ ] Move recordings to a **SAF-pickable folder** (user-selected, easily accessible & shareable)
      instead of the private `filesDir/recordings` location.
- [] Add Backup Function to save all settings
---

## Suggested priorities
1. ✅ Thumbnails + ✅ export MIME + ✅ **hybrid playback (cheap capture, in-app JPEG player, transcode-on-export + toggle)** + ✅ **VLC/AVCC re-encode fix** implemented (Bug #1). ⚠️ Next: validate on a real device (player/reader/encoder framing + confirm VLC plays the re-export). Follow-up #8: SAF export folder so exports persist to disk independent of Share. Long-term also move the server to MKV and drop MP4; add RTSP H.264 passthrough for stream-capable cams.
2. Surface server recordings in the app – Bug #3.
3. Stop FGS start being blocked after reboot – Bug #6.
4. Preserve un-shown fields on device edit; wire or remove dead settings – Bugs #4, #5.
5. Reduce always-on wake/wifi locks – Performance.
6. Tighten credential storage / transport & server endpoint auth – Security.
