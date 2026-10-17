# Tasks

Requirements that must stay satisfied unchanged: `backup-restore` — *Settings and recordings backup round-trip*,
*Normalize restored values*; `storage-cleanup` — *Opt-in SAF export-folder quota*, *Guard external failures from
breaking local cleanup*; `settings-ui` — *Full-screen device editor with connection tests*.

## 1. Backup and restore runner

- [ ] 1.1 Create `data/backup/BackupRestoreRunner.kt` (`@Inject`: repository, backupStore, gson,
      credentialCipher, themeModeStore, `@IoDispatcher`) with `suspend fun export(uri)` / `suspend fun restore(uri)`
      returning sealed `BackupRestoreOutcome`; move the bodies verbatim, including the `serverDeviceId = null`
      reset and the `normalizedForRestore()` ordering; verify: `.\gradlew.bat compileDebugKotlin` passes.
- [ ] 1.2 Rewire `SettingsViewModel.exportBackup` / `restoreBackup` to delegate and map each outcome onto the
      existing `settings_backup_*` strings (no new or reworded resource); verify: `compileDebugKotlin` passes and
      the messages emitted per branch match the current ones in the diff.
- [ ] 1.3 Add device-free `BackupRestoreRunnerTest` over `FakeBackupDocumentStore` + `FakeDaos` covering a
      round trip, an unreadable/`null`-parse file → `Invalid`, and that a restored settings row mirrors the theme
      store before the Room write; verify: `.\gradlew.bat testDebugUnitTest` passes.

## 2. Storage hub runner

- [ ] 2.1 Create `ui/settings/StorageHubRunner.kt` (`@Inject`: exportGateway, storageDirs,
      `@IoDispatcher`) holding the folder-access probe, thumbnail usage, export-folder byte listing with its
      `CancellationException` rethrow, display name, and take/release grant calls; verify: `compileDebugKotlin`
      passes.
- [ ] 2.2 Point the four storage `StateFlow`s at the runner, leaving `combine` / `distinctUntilChanged` /
      `flowOn(ioDispatcher)` / `stateIn(viewModelScope, …)` in the ViewModel; verify: `compileDebugKotlin` passes
      and *Guard external failures from breaking local cleanup* still holds — a throwing provider listing yields
      `null`, never a crash (assert in the existing or a new test).

## 3. Device probe runner

- [ ] 3.1 Create `ui/settings/DeviceProbeRunner.kt` (`@Inject`: deviceFactory, snapshotClient, sipRegistrar,
      `@IoDispatcher`) with the three probes and a cold `fun measuredFps(deviceId: StateFlow<Long?>): Flow<Float>`
      owning `MEASURED_FPS_POLL_MS`; verify: `compileDebugKotlin` passes.
- [ ] 3.2 Collapse the three duplicated in-flight guards in the ViewModel into one private helper and route
      `monitorUsesSip` through the runner; verify: `compileDebugKotlin` passes and the existing probe cases in
      `SettingsViewModelTest` still pass unmodified.

## 4. Constructor and test wind-down

- [ ] 4.1 Drop the now-unused constructor parameters from `SettingsViewModel` (expect 18 → ~11) and update the
      Hilt graph implicitly; verify: `compileDebugKotlin` passes and `SettingsViewModel` is under ~300 lines.
- [ ] 4.2 Trim `SettingsViewModelTest`'s setup so it no longer builds real `OkHttpClient`-backed
      `HttpSnapshotClient` / `IntercomDeviceFactory`; verify: `.\gradlew.bat testDebugUnitTest` passes with no
      net test count loss across the four touched test files.
- [ ] 4.3 Run `.\gradlew.bat lintDebug` plus `ktlintCheck`/`detekt` if configured; fix what this change
      introduced.

## 5. On-device acceptance gate (blocks archive)

- [ ] 5.1 Export a backup from a real phone, clear app data, restore it, and confirm devices reappear with
      passwords working (credential re-encryption) and `serverDeviceId` cleared so devices re-register against
      the currently configured server.
- [ ] 5.2 With a real SAF export folder chosen: revoke the grant in system settings, re-open the storage screen,
      and confirm the access probe reports the failure and the byte count shows "nothing to measure" rather than
      crashing; re-pick the folder and confirm the count returns.
- [ ] 5.3 In the device editor against a live camera and PBX: run the connection test, the SIP registration test,
      and "Get FPS", confirming each leaves the others idle and switching devices resets finished results.
