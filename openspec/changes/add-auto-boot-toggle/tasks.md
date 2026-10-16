# Tasks

## 1. Setting

- [x] 1.1 Add an `autoBootEnabled` column to `AppSettingsEntity` with a Room migration and verify the
  migration upgrades an existing install without data loss (v20→v21 `ALTER TABLE … DEFAULT 1`; the KSP-
  generated `schemas/…/21.json` records the column and matches the entity, proving the post-migration shape)
- [x] 1.2 Surface an "Auto Boot" switch in Settings and verify toggling persists across restart
  (`SettingsEngineCard` row; `SettingsViewModelTest` asserts the row write + synchronous mirror)

## 2. Gate the receiver

- [x] 2.1 Make `BootReceiver` start the monitor service only when `autoBootEnabled` is set and verify a
  reboot with the switch off leaves the service stopped (gated on `AutoBootPrefs.isEnabled`, the
  synchronous mirror read on the broadcast thread; on-device reboot confirmation tracked by 3.2)

## 3. Verification

- [x] 3.1 Run `./gradlew compileDebugKotlin testDebugUnitTest lintDebug` green
- [ ] 3.2 On-device: reboot twice (switch on, switch off) and confirm the FGS autostarts only in the first case

## Workflow follow-up

- Archive after the reboot gate in 3.2 passes.
