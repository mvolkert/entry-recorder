# On-Device Verification Checklist — `add-auto-transcode-export` (gates 5.2–5.6)

Everything here is the shipped behaviour of commit `505c716`. Check a gate only when **every** listed
observation held; a half pass stays unchecked with the failing line noted. When 5.2–5.6 are all ticked in
`tasks.md`, archive the change (`/opsx:archive add-auto-transcode-export`).

## 0. Setup (once, before the gates)

1. Build and install the debug APK **without clearing app data** (gates 5.4/5.5 need the real state):
   ```powershell
   .\gradlew.bat assembleDebug
   adb install -r app\build\outputs\apk\debug\app-debug.apk
   ```
   Package: `io.github.mvolkert.entryrecorder` (no `.debug` suffix).
2. Open a logcat window filtered to the tags this feature writes through:
   ```powershell
   adb logcat -c
   adb logcat -s RtspStreamRecorder:V ExportTranscoder:V ExportTriggerRunner:V ExportTriggerReceiver:V System.err:W
   ```
   **Tag gotcha:** the capture loop logs under `RtspStreamRecorder` (its `tag` value), not
   `LocalCaptureLoop`. Finalize evidence is therefore in `RtspStreamRecorder`.
3. In the app: Settings -> the storage category -> the **"Export Folder Archive"** section header. All four
   rows this change touches sit there, verbatim:
   - **Export Folder** -> *Choose folder* / *Change folder* / *Grant folder again* / *Remove*
   - **Transcode to H.264 on Export** — the manual switch, must keep its old meaning
   - **Auto-Export Recordings as Raw MKV** — the raw mirror
   - **Auto-Export Recordings as H.264** — the new transcode mirror
   (The "Phone Storage" / retention / quota rows above them are untouched by this change.)
4. Know the two artifact names (device id `1`, event `MANUAL`/`RING`/`MOTION`/`NOISE`):
   - raw mirror: `REC_<deviceId>_<EVENT>_<yyyyMMdd_HHmmss>.mkv`
   - transcode mirror: `REC_<deviceId>_<EVENT>_<yyyyMMdd_HHmmss>_h264.mp4`
5. Optional, on an emulator or a userdebug build — direct state proof:
   ```powershell
   adb shell run-as io.github.mvolkert.entryrecorder sqlite3 databases/entry_recorder_database.db "select autoExportOnFinalize, autoTranscodeOnFinalize from app_settings; pragma user_version;"
   ```
   Many phones ship without `sqlite3`; then rely on the UI + artifact evidence below.
6. Queue state, if you want it visually: Android Studio → **App Inspection → WorkManager** → the unique
   worker `ExportTriggerWorker` named `ExportTriggerWork` (one RUNNING, the rest ENQUEUED is the goal).

## 1. Gate 5.2 — the playable artifact lands while capture keeps running

- [ ] Turn **Auto-Export Recordings as H.264** on (the raw switch flips off by itself — that is gate 5.4 evidence, note it here too). Confirm a folder is set.
- [ ] Live screen → *Start manual recording*, a few seconds, → *Stop manual recording*.
- [ ] Logcat, in this order:
  - `RtspStreamRecorder: Recording saved: id=<N>, duration=<x>s, size=<bytes> bytes`
  - `ExportTranscoder: Transcoding <frames> frames <W>x<H> @ <fps>fps -> REC_..._h264.mp4`
  - `ExportTriggerRunner: Export pass finished (recording=<N>): 1/1 recordings exported`
- [ ] Export folder contains **exactly one** new file for that clip: `..._h264.mp4`, and **no** `.mkv`
      (the raw mirror must be skipped while the transcode mirror is the opted-in one).
- [ ] The `.mp4` plays in the system gallery **and** in VLC (the spec's player gate).
- [ ] The app's own copy is untouched: the clip still opens in the in-app gallery, and
      `files/recordings/REC_...mkv` still exists (`adb shell run-as io.github.mvolkert.entryrecorder ls -l files/recordings`).
- [ ] **Capture is not blocked by the encode**: start a second manual recording *while*
      `ExportTranscoder: Transcoding ...` is still printing. It must record normally, and finish with its own
      `Recording saved: id=` line. Monitoring keeps working too — a ring during the encode must still raise the
      call screen.
- [ ] Failure signatures that mean **fail** (record them instead of the pass):
      `ExportTriggerRunner: No SAF export folder configured; nothing to export. Set one in Settings.`,
      `ExportTriggerRunner: SAF write failed for ...` with no `.mkv` in the folder.

## 2. Gate 5.3 — the queue runs one at a time, in finalize order, and survives a kill

Getting several separate clips: ring the Verso doorbell three times with gaps **longer than the ring clip**
(a trigger that arrives mid-clip folds into the running clip and yields one artifact, not three). Back-to-back
manual recordings work too; shorten *Recording Durations (seconds)* in the device settings if the encodes are
too quick to overlap.

- [ ] Finish clip A, then B, then C — `Recording saved: id=` three times, ids ascending.
- [ ] Only **one encode at a time**: a `ExportTranscoder: Transcoding ...` line never appears before the
      previous clip's `ExportTriggerRunner: Export pass finished (recording=<id>): 1/1 recordings exported`.
      In the WorkManager inspector: `ExportTriggerWork` shows one RUNNING while the others are ENQUEUED.
- [ ] Drain order equals finalize order: the three `Export pass finished (recording=<N>)` lines appear as
      `(<A>)`, `(<B>)`, `(<C>)` — never out of order, never two for the same id.
- [ ] The folder ends with exactly three `..._h264.mp4` files, one per clip, no duplicates.
- [ ] **Process-death part**: finish three clips, then kill the app while at least one export is still
      ENQUEUED:
      ```powershell
      adb shell am force-stop io.github.mvolkert.entryrecorder
      ```
      Wait ~1 minute, reopen the app (WorkManager is Hilt-initialized on demand here, so reopening is the
      reliable resume path; a JobScheduler retry alone may need it). Expect the remaining ids to export in the
      same order, each with its own `Export pass finished (recording=<N>)`, and each still producing exactly
      one file. Nothing may be lost.
- [ ] **A dropped clip must not wedge the chain**: delete one recording's private MKV before its queued export
      runs (or let retention do it). Logcat must show
      `ExportTriggerRunner: Recording <N> file missing, skipping` (or, if the row itself is gone,
      `Recording <N> no longer exists; nothing to export.`) and the **next** clip's export must still run.
- [ ] Optional contention check: while the queue is running, fire the broadcast export and confirm it joins the
      same chain instead of overlapping:
      ```powershell
      adb shell am broadcast -a io.github.mvolkert.entryrecorder.action.EXPORT_RECORDINGS --es scope all
      ```
      Expect `ExportTriggerReceiver: Export trigger received (scope=all); enqueueing worker`, then an
      `Export pass finished (all=true): ...` line that never interleaves with a per-recording pass.

## 3. Gate 5.4 — the two mirrors exclude each other, in persisted state

- [ ] Start: raw ON, H.264 OFF. Tap H.264 ON → raw flips OFF **in the same view**, no second tap needed.
- [ ] Force-stop and reopen the app → Settings shows H.264 ON, raw OFF (persisted, not recomputed UI state).
- [ ] Tap raw ON → H.264 flips OFF. Restart again → raw ON, H.264 OFF.
- [ ] With `sqlite3` available: `0|1` after step 2 and `1|0` after step 3, and never `1|1`.
- [ ] **The manual switch is untouched**: note **Transcode to H.264 on Export**'s position before the whole
      sequence and after it — unchanged. Cross-check behaviour: with the mirrors toggling, do a manual
      *"Export to folder"* on one recording from the gallery; its format must still follow the manual switch
      only, exactly as before this change.
- [ ] Behaviour matches the switch that is on: H.264 mirror → next finished clip yields only
      `..._h264.mp4`; raw mirror → next finished clip yields only `REC_....mkv`, with **no**
      `ExportTranscoder` / `ExportTriggerRunner` line for it (the raw copy is done by the capture loop).
      Never both for one recording.
- [ ] **Restore clamp** (the save path normalizes the pair since the review follow-up): restore a backup file
      whose JSON carries **both** `autoExportOnFinalize` and `autoTranscodeOnFinalize` true. Expect exactly one
      lit switch — *Auto-Export Recordings as H.264* ON, raw mirror OFF — and `sqlite3` reading `0|1`. Then
      restore a raw-only file and confirm it comes back `1|0`: the clamp removes the mirror the file did not
      choose to keep, never the single choice it carries.
- [ ] **Finalize precedence** (needs the row forced, now that restore clamps): set both columns to `1` with
      `sqlite3`, finish a clip, and expect
      `RtspStreamRecorder: Both auto-export mirrors are stored for recording <N>; the H.264 mirror wins`
      plus exactly one `..._h264.mp4`. Tapping either switch afterwards must write the pair back normalized.

## 4. Gate 5.5 — v24 → v25 upgrade over a real database

Prerequisite: a phone already running the **previous** build (schema 24) with raw auto-export ON, a folder set
and a populated archive. Do **not** uninstall or clear data.

- [ ] Note the recording count in the gallery and a couple of file names, so you can prove nothing was reset.
- [ ] `adb install -r` the new APK (step 0.1), open the app.
- [ ] **No crash at first DB access.** A broken migration shows up as an `IllegalStateException` /
      "A migration ... was not detected" / `Room` stack trace in `System.err` the moment a screen reads the
      archive; absence of that is the pass condition.
- [ ] Settings: raw mirror still ON, **Auto-Export Recordings as H.264 present and OFF**, retention period,
      quota, devices and folder selection all unchanged.
- [ ] With `sqlite3`: `PRAGMA user_version` → `25`, `autoTranscodeOnFinalize` → `0`.
- [ ] Trigger one clip → it lands in the folder as `REC_....mkv` (raw mirror still works), with its
      `Recording saved: id=` line and **no** `ExportTranscoder` activity.
- [ ] Old clips still open and play in the in-app gallery.

## 5. Gate 5.6 — failure path: broken SAF grant with auto-transcode on

Read this first: **a failed encode falls back to the raw MKV; a failed folder write has nothing to fall back
to.** The two are surfaced differently, on purpose: a degraded delivery is logged and reported in the run
result but raises **no** notice (the clip is in the folder, so the archive has no hole), while anything that
lands nothing — a refused write, a vanished source file, a thrown run, no folder — goes through
`ExportFailureMonitor` and the private copy stays authoritative. Both halves need checking here.

- [ ] H.264 mirror ON, folder set. Break the grant: delete or rename the export folder on the phone, or revoke
      the app's storage access in system settings. Settings → Export Folder must show
      *"Access to this folder was revoked — grant it again to keep exporting"* or
      *"This folder no longer exists on the device — choose a new one"*.
      **Do not use the *Remove* link as the lever here** — that clears the folder URI, which by design makes
      `finalizeExportFor` return no mirror at all (no export attempt, no stalled notice). That is a separate,
      correct behaviour worth one extra check: with no folder set, a finished clip writes nothing to SAF and
      logs no `ExportTriggerRunner` line.
- [ ] Finish a clip. Expect, in order: `Recording saved: id=<N>`, `ExportTranscoder: Transcoding ...`, then
      **either** `ExportTriggerRunner: SAF write failed for REC_..._h264.mp4` (a refused write, counted as
      0/1 exported) **or** `ExportTriggerRunner: Auto-export of recording <N> failed` plus a stack trace (the
      transient path that asks WorkManager for `Result.retry()`).
- [ ] The failure becomes visible without opening Settings: notification **"Auto-export stalled"** /
      *"Recordings are not reaching your export folder — re-check the folder grant in Settings"*, and the
      Settings hub's storage row says **"export stalled"**.
- [ ] **Nothing delivered is surfaced even when nothing threw**: delete the private MKV of a queued clip
      (`adb shell run-as io.github.mvolkert.entryrecorder rm databases/.../REC_<id>_..._<ts>.mkv`) before its
      export runs. Expect `ExportTriggerRunner: Recording <N> file missing, skipping`, then
      `Export pass finished (recording=N): 0/1 recordings exported` and
      `Auto-export delivered nothing (recording=N)` in the monitor — the stalled notification must appear.
- [ ] **No crash and no stall of monitoring**: `adb logcat -b crash` stays empty for the package; a ring right
      after the failure still records.
- [ ] **The queue keeps draining**: finish a second clip while the folder is still broken → it must get its own
      export attempt (its own `SAF write failed` / retry line) instead of the chain wedging on the first
      failure, and both clips' private MKVs must be intact.
- [ ] **A retrying head does not wedge the chain forever**: the transient path returns `Result.retry()` with
      WorkManager's default backoff (no `setBackoffCriteria` on the auto request, by decision). Watch the
      first failing item through to `FAILED`/`CANCELLED` in App Inspection → WorkManager and confirm the next
      clip's export then runs. If the chain sits on one poisoned item, that is a finding, not a flake.
- [ ] Recover: *Grant folder again* (or point at a new folder), finish a third clip → the file lands, the
      stalled notification is withdrawn by itself, and the hub subtitle stops saying "export stalled".
- [ ] Raw-fallback half: break the **encoder** rather than the folder — the easiest lever is a clip with no
      decodable frames (an empty/half-written MKV) or a device whose MediaCodec refuses the size. Expect
      `ExportTriggerRunner: Transcode failed for <N>, exporting raw`, then
      `Recording <N> reached the folder degraded to raw MKV, no H.264` and
      `Export pass finished (recording=N): 1/1 recordings exported (1 degraded to raw MKV)`. The raw
      `REC_....mkv` must land in the folder instead of an MP4, and **no** "Auto-export stalled" notification
      may appear — if one was showing from an earlier failure, this delivery must withdraw it.

## 6. Sign-off

| Gate | Pass / Fail | Date | Evidence seen (log lines, file names) | Notes |
| --- | --- | --- | --- | --- |
| 5.2 playable artifact | | | | |
| 5.3 queue + drain after kill | | | | |
| 5.4 toggle exclusion + restart | | | | |
| 5.5 v24→v25 over real data | | | | |
| 5.6 broken grant / fallback | | | | |

When all five pass: tick 5.2–5.6 in `tasks.md`, commit the ticked ledger, then archive the change. If a gate
fails, leave it unchecked, write the failing line into this table and open the fix as a follow-up — the commit
body of `505c716` names which seam owns which symptom.
