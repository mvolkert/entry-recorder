# Tasks

## 1. Setting

- [ ] 1.1 Add an `autoBootEnabled` column to `AppSettingsEntity` with a Room migration and verify the
  migration upgrades an existing install without data loss
- [ ] 1.2 Surface an "Auto Boot" switch in Settings and verify toggling persists across restart

## 2. Gate the receiver

- [ ] 2.1 Make `BootReceiver` start the monitor service only when `autoBootEnabled` is set and verify a
  reboot with the switch off leaves the service stopped

## 3. Verification

- [ ] 3.1 Run `./gradlew compileDebugKotlin testDebugUnitTest lintDebug` green
- [ ] 3.2 On-device: reboot twice (switch on, switch off) and confirm the FGS autostarts only in the first case

## Workflow follow-up

- Archive after the reboot gate in 3.2 passes.
