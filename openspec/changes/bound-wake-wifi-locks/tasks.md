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

## Workflow follow-up

- Archive after the idle/ring soak in 2.2 confirms no missed wake.
