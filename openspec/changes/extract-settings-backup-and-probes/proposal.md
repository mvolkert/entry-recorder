# Proposal

## Why

`ui.settings.SettingsViewModel` (501 lines) injects **eighteen** collaborators and mixes five unrelated
responsibility clusters behind one `SettingsUiState`: device/settings CRUD with server sync, the storage/export
hub (four probe-driven `StateFlow`s plus folder-grant management), the device-form connection probes (HTTP, SIP
registrar, snapshot-FPS burst, and a measured-FPS poll loop), backup/restore over SAF with Gson + credential
re-encryption, and the manual cleanup + server-connection one-offs. Only the first cluster is what a *settings
screen* owns; the rest are separate use cases that happen to share a constructor — `SettingsViewModelTest` has to
supply all eighteen arguments, including two real OkHttp-backed clients (`HttpSnapshotClient`,
`IntercomDeviceFactory`), before it can assert anything. Behaviour does not change, so there is no spec delta.

## What Changes

- New `data.backup.BackupRestoreRunner` (`@Inject`, follows the `ExportRunner` / `CleanupRunner` precedent):
  performs the backup write and the restore read/apply, returning a sealed `BackupRestoreOutcome`
  (`Exported(count)` / `Restored(count)` / `Invalid` / `Failed(cause)`) instead of emitting strings; the
  ViewModel keeps mapping the outcome onto the existing snack-bar messages.
- New `ui.settings.StorageHubRunner` (`@Inject`): the blocking probes behind the storage card — folder-access
  probe, thumbnail/archived-usage measurement, export-folder byte listing with its `CancellationException`
  rethrow guard, folder display name, and the take/release tree-grant pair — each a `suspend` function returning
  plain data.
- New `ui.settings.DeviceProbeRunner` (`@Inject`): the three unsaved-value probes (device HTTP test via
  `IntercomDeviceFactory`, registrar probe via `SipRegistrar`, snapshot-FPS burst via `HttpSnapshotClient`),
  returning `DeviceTestState` / `SipTestState` / `SnapshotProbeState` values and a cold `Flow<Float>` for the
  measured-FPS poll.
- The `StateFlow` pipelines themselves stay in the ViewModel (unidirectional data flow: screen state is owned by
  the ViewModel and `stateIn(viewModelScope, …)` must not move into an injected singleton); each cluster's flow
  body becomes a call into its runner. The VM also keeps `uiState`, `events`, device/settings intents,
  `testServerConnection`, and `triggerCleanupNow`, so composables' call sites are unchanged.
- The constructor drops from 18 injected values to roughly 11: `gson`, `backupStore`, `credentialCipher`,
  `storageDirs`, `exportGateway`, `snapshotClient`, `deviceFactory`, and `sipRegistrar` move onto the runners.
- Out of scope: what any probe *measures* (`HttpSnapshotClient`, `SipRegistrar`, `IntercomDeviceFactory`
  internals), the server-sync policy in `SyncDeviceUseCase`, `SettingsStorageCard`/`DeviceFormComponents`
  composition, which class owns screen state, and any change to message wording or resource ids.

## Capabilities

### New Capabilities

- None — pure refactor, `skip_specs: true`.

### Modified Capabilities

- None — `backup-restore`, `device-connectivity`, `settings-ui` and `storage-cleanup` requirements are unchanged;
  their existing scenarios are the acceptance list.

## Impact

- Three new files (`data/backup/`, two under `ui/settings/`); `SettingsViewModel` loses ~180 lines of bodies
  while keeping its state plumbing.
- `SettingsViewModelTest` splits by cluster; the `backup-restore` and `settings-ui` spec scenarios must stay
  covered with no net loss of cases.
- Layering guard: `util` must not import `ui`, so `BackupRestoreRunner` (non-UI outcome) lives under
  `data.backup`, while the two runners that return UI state types stay in `ui.settings` — the same layer.
- On-device gate: a real backup → wipe → restore round trip and a live export-folder grant revoke/re-check.
- `compileDebugKotlin` + `testDebugUnitTest` + `lintDebug`.
