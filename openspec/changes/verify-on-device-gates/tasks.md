# Tasks

Each task is an acceptance gate against real hardware. Mark `[x]` only when the described observation
passes; the change stays open until all pass.

## 1. Edge-to-edge / targetSdk (Android 15/16)

- [ ] 1.1 Recordings top band sits below the status bar; incoming-call header/controls clear the bars and a
  landscape frame is letterboxed (not cropped); locked-screen full-screen intent shows over keyguard
- [ ] 1.2 Motion/noise heads-up notification appears and tapping opens the call screen (BAL rules noted)
- [ ] 1.3 Reboot → `BootReceiver` autostart of the monitor FGS still succeeds

## 2. SIP / ring (Verso + Fritz!Box)

- [ ] 2.1 "Test SIP Registration" answers Registered with the real account, Rejected with a wrong password,
  survives 3-4 taps with no `Fatal signal 11`, and a doorbell still rings while monitoring is active
- [ ] 2.2 Editing the SIP password withdraws the old registration (one unregister then one success)
- [ ] 2.3 One doorbell press → exactly one notification; a folded ring during a motion clip → one file with
  both badges; a ring does not truncate a longer recording

## 3. Motion / MJPEG (Verso)

- [ ] 3.1 With camera triggers off: zero motion/noise status traffic and no DEGRADED/OFFLINE camera-event
  report; with motion on/noise off: exactly one endpoint polled
- [ ] 3.2 Read idle + walk-past health lines and pick the Motion sensitivity tier from data
- [ ] 3.3 `MJPEG_STREAM`/AUTO derived `snapshotUrl&fps=N` paints in live view and a motion clip has frames

## 4. Room real-data upgrades

- [ ] 4.1 v14→v15 (`alsoEventTypes`): upgrade without uninstall, existing rows keep one badge
- [ ] 4.2 v12→v13 (`serverDeviceId`) and v8→v9 (accent roles) upgrade over real data intact

## 5. Server (live deployment)

- [ ] 5.1 Start returns a persistable id; in-progress rows stay hidden; `already_recording` carries the id;
  empty capture discards the row; process-restart resume honours the original deadline
- [ ] 5.2 Device sync: register-on-save, start carries no credentials, just-in-time register + local fallback,
  delete + restore clears `serverDeviceId`
- [ ] 5.3 Merged gallery + composite keys, remote thumbnail/playback, pull-to-refresh, mutating server actions
- [ ] 5.4 Server-relayed live view paints with `X-API-Key` header (no key in URL) and falls back on drop
- [ ] 5.5 Firefox web-UI checklist (auth wiring, key change, fMP4 inline, no false warning, legacy MKV
  fallback, live view)

## 6. Cleanup / export (real providers)

- [ ] 6.1 Over-quota phone storage actually triggers the stepped deletion; out-of-range quota shows unusable
- [ ] 6.2 SAF-folder pruning deletes only the app's own files against a real provider (Nextcloud/Drive)
- [ ] 6.3 Player autoplay-after-seek resumes on device; exported H.264 fMP4 plays in VLC

## 7. Theme / splash (Android 12+)

- [ ] 7.1 Forced modes drive the splash; runtime flip recreates cleanly; light-mode readability pass; themed
  monochrome icon + splash layers

## Workflow follow-up

- This change does not modify specs; it is the verification ledger. Close (archive) only when every gate
  above is checked against real hardware.
