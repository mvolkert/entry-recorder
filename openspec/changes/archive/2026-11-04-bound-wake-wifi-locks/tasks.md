# Tasks

## 1. Scope the locks

- [x] 1.1 Acquire wake/wifi locks only on active recording/ring start and release on end in
  `IntercomMonitorService` and verify idle monitoring holds no lock
- [x] 1.2 Add a per-device option to keep locks held (detection-first deployment) and verify the default
  matches the battery-friendly behaviour

## 2. Verification

- [x] 2.1 Run `./gradlew compileDebugKotlin lintDebug` green
- [ ] 2.2 On-device soak: confirm a long idle period does not keep the CPU/wifi awake and a ring during idle
  still wakes reliably
  - Not closed by the archive — it was carried, unchecked, into gate 5.2 of
    `openspec/changes/hold-detector-locks-and-serialize-triggers/`. Its idle-release half is the part that still
    ships (camera-event-only devices without `keepLocksHeld`), and the emulator proved both halves of the pair drop
    at the idle check and return without a service restart; the ring-during-idle reliability half still needs the
    Verso soak.

## Workflow follow-up

- Archive after the idle/ring soak in 2.2 confirms no missed wake. Superseded instead on 2026-11-04: the lock
  posture this change set for analyzing deployments was reversed by
  `hold-detector-locks-and-serialize-triggers`, so the two postures could not both stay active in
  `openspec/changes/`. The soak moved with the change rather than being retired with it.
